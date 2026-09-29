package com.example.myfirstapp.detect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.example.myfirstapp.core.detect.Letterbox
import com.example.myfirstapp.core.detect.YoloDecoder
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.port.Detector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLO TFLite 탐지기(9.1, 9.3). 모델 폴더(`model.tflite`, `labels.txt`, `model.json`)를 읽어 규격을 검사한다.
 * 추론은 단일 워커에서 차례로 실행한다(Interpreter는 스레드에 안전하지 않다).
 */
class TfliteYoloDetector private constructor(
    override val modelVersion: String,
    private val interpreter: Interpreter,
    private val decoder: YoloDecoder,
    private val size: Int,
    private val outSize: Int,
    private val n: Int,
) : Detector, Closeable {
    private val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * outSize).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(size * size)

    override suspend fun detect(image: ByteArray): List<Detection> = withContext(worker) {
        val t0 = System.nanoTime()
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image, 0, image.size)) { "이미지를 읽지 못했습니다" }
        val box = Letterbox(bitmap.width, bitmap.height, size)
        letterbox(bitmap, box)
        val t1 = System.nanoTime()
        output.rewind()
        interpreter.run(input, output)
        val t2 = System.nanoTime()
        val out = FloatArray(outSize).also { output.rewind(); output.asFloatBuffer().get(it) }
        val list = decoder.decode(out, n, box)
        val t3 = System.nanoTime()
        // 9.4 C++ 전환 판단용 단계별 시간
        Log.i(TAG, "pre=${(t1 - t0) / 1_000_000}ms infer=${(t2 - t1) / 1_000_000}ms post=${(t3 - t2) / 1_000_000}ms n=${list.size}")
        list
    }

    /** 회색(114) 바탕에 비율을 지켜 줄인 이미지를 가운데 놓고 RGB 0~1 float로 채운다. */
    private fun letterbox(src: Bitmap, box: Letterbox) {
        val dst = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(dst).apply {
            drawColor(Color.rgb(114, 114, 114))
            val rect = RectF(box.padX, box.padY, box.padX + src.width * box.scale, box.padY + src.height * box.scale)
            drawBitmap(src, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        dst.getPixels(pixels, 0, size, 0, 0, size, size)
        dst.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat((p shr 16 and 0xFF) / 255f)
            input.putFloat((p shr 8 and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
    }

    /** 진행 중인 추론이 끝난 뒤 같은 워커에서 닫는다. */
    override fun close() {
        CoroutineScope(worker).launch { interpreter.close() }
    }

    companion object {
        private const val TAG = "Detector"
        private val worker = Dispatchers.Default.limitedParallelism(1)

        /** 모델 폴더 하나를 읽는다. 규격이 맞지 않으면 이유를 담은 예외를 던진다. */
        fun load(dir: File): TfliteYoloDetector {
            val json = JSONObject(File(dir, "model.json").readText())
            val labels = File(dir, "labels.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val inp = json.getJSONObject("input")
            val size = inp.getInt("width")
            require(size == inp.getInt("height")) { "정사각 입력만 지원합니다" }
            require(inp.optString("format", "RGB") == "RGB" && inp.optString("normalize", "0-1") == "0-1") { "입력 형식은 RGB 0-1이어야 합니다" }
            val th = json.optJSONObject("thresholds") ?: JSONObject()
            val alert = json.optJSONArray("alertLabels")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val pixelBoxes = json.optJSONObject("output")?.optString("boxes") == "pixel"

            val interpreter = Interpreter(File(dir, "model.tflite"), Interpreter.Options().setNumThreads(4))
            try {
                val it = interpreter.getInputTensor(0)
                require(it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(intArrayOf(1, size, size, 3))) {
                    "입력 텐서 ${it.shape().contentToString()} ${it.dataType()} ≠ [1, $size, $size, 3] FLOAT32"
                }
                val ot = interpreter.getOutputTensor(0)
                val shape = ot.shape()
                require(ot.dataType() == DataType.FLOAT32 && shape.size == 3 && shape[0] == 1 && shape[1] == 4 + labels.size) {
                    "출력 텐서 ${shape.contentToString()} ${ot.dataType()} ≠ [1, ${4 + labels.size}, N] FLOAT32 (라벨 ${labels.size}개)"
                }
                val decoder = YoloDecoder(
                    labels, alert, th.optDouble("score", 0.35).toFloat(), th.optDouble("iou", 0.5).toFloat(), pixelBoxes,
                )
                return TfliteYoloDetector(json.getString("version"), interpreter, decoder, size, shape[1] * shape[2], shape[2])
            } catch (e: Throwable) {
                interpreter.close()
                throw e
            }
        }

        /**
         * `models/<version>/` 중 규격 검사를 통과한 폴더에서 이름이 가장 뒤인 것을 쓴다(9.1).
         * 없으면 null이다. 거부한 폴더는 로그에 이유를 남긴다.
         */
        fun loadLatest(root: File): TfliteYoloDetector? =
            root.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }.firstNotNullOfOrNull { dir ->
                runCatching { load(dir) }
                    .onSuccess { Log.i(TAG, "model=${it.modelVersion} dir=${dir.name}") }
                    .onFailure { Log.w(TAG, "모델 거부 ${dir.name}: ${it.message}") }
                    .getOrNull()
            }
    }
}
