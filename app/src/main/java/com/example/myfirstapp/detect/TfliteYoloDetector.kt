package com.example.myfirstapp.detect

import com.example.myfirstapp.AppConfig
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.example.myfirstapp.core.detect.Letterbox
import com.example.myfirstapp.core.detect.YoloDecoder
import com.example.myfirstapp.core.detect.fromCenterCrop
import com.example.myfirstapp.core.detect.sampleSize
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.port.Detector
import com.example.myfirstapp.data.OpLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * YOLO TFLite 탐지기(9.1, 9.3). 모델 폴더(`model.tflite`, `labels.txt`, `model.json`)를 읽어 규격을 검사한다.
 * 추론은 전용 스레드 하나에서 차례로 실행한다(Interpreter는 스레드에 안전하지 않고, GPU delegate는 만든 스레드에서 돌려야 한다).
 */
class TfliteYoloDetector private constructor(
    val spec: ModelSpec,
    private val interpreter: Interpreter,
    private val gpu: GpuDelegate?,
    private val decoder: YoloDecoder,
    private val outSize: Int,
    private val n: Int,
) : Detector, Closeable {
    override val modelVersion get() = spec.version
    private val size = spec.inputSize

    /** 실제로 쓰는 쪽(로그·속도 측정): GPU 또는 CPU 스레드 수. */
    val delegate = if (gpu != null) "GPU" else "CPU${AppConfig.Detector.THREADS}"

    private val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * outSize).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(size * size)

    /** 한 번 탐지의 결과와 단계별 시간(ms). [sample]은 축소 디코딩 배율. */
    data class Timed(val detections: List<Detection>, val decodeMs: Double, val preMs: Double, val inferMs: Double, val postMs: Double, val sample: Int) {
        val totalMs get() = decodeMs + preMs + inferMs + postMs
    }

    override suspend fun detect(image: ByteArray): List<Detection> = detectTimed(image).detections

    /** 메모리의 JPEG. [crop]이 1보다 작으면 가운데 그 비율만 잘라 넣고 상자는 원본 좌표로 돌려준다(9.6). */
    suspend fun detectTimed(image: ByteArray, crop: Float = 1f) = run(crop) { BitmapFactory.decodeByteArray(image, 0, image.size, it) }

    /** 저장한 사진 파일(정밀 탐지·속도 측정). */
    suspend fun detectTimed(file: File, crop: Float = 1f) = run(crop) { BitmapFactory.decodeFile(file.path, it) }

    private suspend fun run(crop: Float, decode: (BitmapFactory.Options) -> Bitmap?): Timed = withContext(worker) {
        val t0 = System.nanoTime()
        // 20MP 원본을 다 풀지 않는다: 쓸 영역의 긴 변이 입력 이상으로 남는 만큼만 줄여 디코딩(inSampleSize)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { decode(it) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "이미지를 읽지 못했습니다" }
        val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), crop, size)
        val bitmap = requireNotNull(decode(BitmapFactory.Options().apply { inSampleSize = sample })) { "이미지를 읽지 못했습니다" }
        val t1 = System.nanoTime()
        val cw = (bitmap.width * crop).toInt()
        val ch = (bitmap.height * crop).toInt()
        val src = Rect((bitmap.width - cw) / 2, (bitmap.height - ch) / 2, (bitmap.width - cw) / 2 + cw, (bitmap.height - ch) / 2 + ch)
        val box = Letterbox(cw, ch, size)
        letterbox(bitmap, src, box)
        bitmap.recycle()
        val t2 = System.nanoTime()
        output.rewind()
        interpreter.run(input, output)
        val t3 = System.nanoTime()
        val out = FloatArray(outSize).also { output.rewind(); output.asFloatBuffer().get(it) }
        val list = decoder.decode(out, n, box).let { if (crop < 1f) it.map { d -> d.fromCenterCrop(crop) } else it }
        val t4 = System.nanoTime()
        Timed(list, ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), sample).also {
            // 9.4 C++ 전환 판단용 단계별 시간
            Log.i(TAG, "${spec.version} $delegate crop=$crop sample=$sample decode=${it.decodeMs.toInt()} pre=${it.preMs.toInt()} infer=${it.inferMs.toInt()} post=${it.postMs.toInt()}ms n=${list.size}")
        }
    }

    private fun ms(a: Long, b: Long) = (b - a) / 1e6

    /** 회색(114) 바탕에 [src] 영역을 비율을 지켜 줄여 가운데 놓고 RGB 0~1 float로 채운다. */
    private fun letterbox(bitmap: Bitmap, src: Rect, box: Letterbox) {
        val dst = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(dst).apply {
            drawColor(Color.rgb(114, 114, 114))
            val rect = RectF(box.padX, box.padY, box.padX + src.width() * box.scale, box.padY + src.height() * box.scale)
            drawBitmap(bitmap, src, rect, Paint(Paint.FILTER_BITMAP_FLAG))
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

    /** 진행 중인 추론이 끝난 뒤 같은 스레드에서 닫는다(GPU delegate는 인터프리터 뒤에). */
    override fun close() {
        CoroutineScope(worker).launch {
            interpreter.close()
            gpu?.close()
        }
    }

    companion object {
        private const val TAG = "Detector"

        /** 모든 탐지기가 함께 쓰는 추론 스레드 하나(비행 중 1장·정밀·속도 측정이 차례로). */
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "detector") }.asCoroutineDispatcher()

        /**
         * 모델 폴더 하나를 연다. [gpu]면 GPU delegate를 먼저 해 보고 안 되면 CPU([AppConfig.Detector.THREADS] 스레드)로 간다.
         * 규격이 맞지 않으면 이유를 담은 예외를 던진다. 추론 스레드에서 연다(GPU delegate 규칙).
         */
        suspend fun open(dir: File, gpu: Boolean): TfliteYoloDetector = withContext(worker) { load(dir, gpu) }

        /** 순서대로 열어 보고 처음 열린 모델(9.6 [com.example.myfirstapp.core.detect.ModelPick] 순서). 없으면 null. 거부한 폴더는 로그에 이유를 남긴다. */
        suspend fun openFirst(root: File, order: List<String>, gpu: Boolean): TfliteYoloDetector? =
            order.firstNotNullOfOrNull { name ->
                runCatching { open(File(root, name), gpu) }
                    .onFailure { Log.w(TAG, "모델 거부 $name: ${it.message}") }
                    .getOrNull()
            }

        /** 규격 검사·CPU 열기(설정 화면 검사, 가져오기). 쓰고 나면 닫는다. */
        fun load(dir: File, gpu: Boolean = false): TfliteYoloDetector {
            val spec = ModelSpec.read(dir)
            val labels = File(dir, "labels.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val file = File(dir, "model.tflite")
            var delegate: GpuDelegate? = null
            var gpuError: String? = null
            val interpreter = (if (gpu) {
                runCatching {
                    val d = CompatibilityList().use { c ->
                        check(c.isDelegateSupportedOnThisDevice) { "이 기기는 GPU delegate를 지원하지 않습니다" }
                        GpuDelegate(c.bestOptionsForThisDevice)
                    }
                    delegate = d
                    Interpreter(file, Interpreter.Options().addDelegate(d))
                }.onFailure {
                    // NoClassDefFoundError 등도 잡아 CPU로 간다
                    delegate?.close()
                    delegate = null
                    gpuError = it.message ?: it::class.simpleName
                }.getOrNull()
            } else null) ?: Interpreter(file, Interpreter.Options().setNumThreads(AppConfig.Detector.THREADS))
            try {
                val size = spec.inputSize
                val it = interpreter.getInputTensor(0)
                require(it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(intArrayOf(1, size, size, 3))) {
                    "입력 텐서 ${it.shape().contentToString()} ${it.dataType()} ≠ [1, $size, $size, 3] FLOAT32"
                }
                val ot = interpreter.getOutputTensor(0)
                val shape = ot.shape()
                require(ot.dataType() == DataType.FLOAT32 && shape.size == 3 && shape[0] == 1 && shape[1] == 4 + labels.size) {
                    "출력 텐서 ${shape.contentToString()} ${ot.dataType()} ≠ [1, ${4 + labels.size}, N] FLOAT32 (라벨 ${labels.size}개)"
                }
                val decoder = YoloDecoder(labels, spec.alertLabels, spec.score, spec.iou, spec.pixelBoxes)
                return TfliteYoloDetector(spec, interpreter, delegate, decoder, shape[1] * shape[2], shape[2]).also { d ->
                    if (gpu) OpLog.log("detect", "open", mapOf("model" to spec.version, "dir" to dir.name, "delegate" to d.delegate, "gpuError" to gpuError))
                }
            } catch (e: Throwable) {
                interpreter.close()
                delegate?.close()
                throw e
            }
        }
    }
}
