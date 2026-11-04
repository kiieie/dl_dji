package com.example.myfirstapp.detect

import com.example.myfirstapp.AppConfig
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import com.example.myfirstapp.core.detect.Letterbox
import com.example.myfirstapp.core.detect.Region
import com.example.myfirstapp.core.detect.YoloDecoder
import com.example.myfirstapp.core.detect.centerRegion
import com.example.myfirstapp.core.detect.fromRegion
import com.example.myfirstapp.core.detect.isProgressiveJpeg
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
import org.tensorflow.lite.gpu.GpuDelegateFactory
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

    // 전처리·후처리 버퍼는 한 번만 만든다(9.7 O7: 1280이면 비트맵·픽셀만 6.5MB씩). 추론 스레드 하나만 쓴다
    private val pixels = IntArray(size * size)
    private val row = FloatArray(size * 3)
    private val out = FloatArray(outSize)
    private val dst = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(dst)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 한 번 탐지의 결과와 단계별 시간(ms). [sample]은 축소 디코딩 배율, [region]이면 가운데만 풀었다(9.7 O1). */
    data class Timed(
        val detections: List<Detection>, val decodeMs: Double, val preMs: Double, val inferMs: Double, val postMs: Double,
        val sample: Int, val region: Boolean,
    ) {
        val totalMs get() = decodeMs + preMs + inferMs + postMs
    }

    override suspend fun detect(image: ByteArray): List<Detection> = detectTimed(image).detections

    /**
     * 메모리의 JPEG(비행 중 1장). [crop]이 1보다 작으면 가운데 그 비율만 넣고 상자는 원본 좌표로 돌려준다(9.6).
     * 그때 [region]이면 가운데만 푼다(9.7 O1). 점진 JPEG이거나 영역 디코딩이 안 되면 전체를 축소해 풀어 자른다.
     */
    suspend fun detectTimed(image: ByteArray, crop: Float = 1f, region: Boolean = true): Timed = withContext(worker) {
        val t0 = System.nanoTime()
        val d = (if (region && crop < 1f && !isProgressiveJpeg(image)) decodeCenter(image, crop) else null)
            ?: decodeSampled(crop) { BitmapFactory.decodeByteArray(image, 0, image.size, it) }
        infer(t0, d, crop)
    }

    /** 저장한 사진 파일(정밀 탐지·속도 측정). */
    suspend fun detectTimed(file: File, crop: Float = 1f): Timed = withContext(worker) {
        val t0 = System.nanoTime()
        infer(t0, decodeSampled(crop) { BitmapFactory.decodeFile(file.path, it) }, crop)
    }

    /** 회색 입력으로 한 번 돌린다(9.7 A). GPU 프로그램 준비·메모리 잡기를 땅 위에서 끝내 비행 중 첫 사진이 늦지 않게. 걸린 시간(ms). */
    suspend fun warmUp(): Double = withContext(worker) {
        val t0 = System.nanoTime()
        row.fill(PAD_GREY * INV255)
        input.rewind()
        input.asFloatBuffer().let { fb -> repeat(size) { fb.put(row) } }
        output.rewind()
        interpreter.run(input, output)
        ms(t0, System.nanoTime())
    }

    /** 푼 비트맵, 그 안에서 넣을 영역 [src], 그 영역이 [imageW]×[imageH] 이미지에서 차지하는 자리 [region](상자 옮기기). */
    private class Decoded(
        val bitmap: Bitmap, val src: Rect, val region: Region, val imageW: Int, val imageH: Int, val sample: Int, val regionDecoded: Boolean,
    )

    /** 20MP 원본을 다 풀지 않는다: 쓸 영역의 긴 변이 입력 이상으로 남는 만큼만 줄여 전체를 풀고(inSampleSize) 가운데를 고른다. */
    private fun decodeSampled(crop: Float, decode: (BitmapFactory.Options) -> Bitmap?): Decoded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { decode(it) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "이미지를 읽지 못했습니다" }
        val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), crop, size)
        val bitmap = requireNotNull(decode(BitmapFactory.Options().apply { inSampleSize = sample })) { "이미지를 읽지 못했습니다" }
        val r = centerRegion(bitmap.width, bitmap.height, crop)
        return Decoded(bitmap, r.toRect(), r, bitmap.width, bitmap.height, sample, regionDecoded = false)
    }

    /** 원본 좌표로 가운데 [crop] 영역만 줄여 푼다(9.7 O1). 안 되면 null(전체 디코딩으로). */
    @Suppress("DEPRECATION") // newInstance(byte[], int, int)는 API 31부터라 minSdk 24에서는 옛 것을 쓴다
    private fun decodeCenter(image: ByteArray, crop: Float): Decoded? = runCatching {
        val dec = BitmapRegionDecoder.newInstance(image, 0, image.size, false)
        try {
            val r = centerRegion(dec.width, dec.height, crop)
            val sample = sampleSize(maxOf(dec.width, dec.height), crop, size)
            val bitmap = requireNotNull(dec.decodeRegion(r.toRect(), BitmapFactory.Options().apply { inSampleSize = sample }))
            Decoded(bitmap, Rect(0, 0, bitmap.width, bitmap.height), r, dec.width, dec.height, sample, regionDecoded = true)
        } finally {
            dec.recycle()
        }
    }.onFailure { Log.w(TAG, "가운데 영역 디코딩 실패, 전체 디코딩으로: ${it.message}") }.getOrNull()

    private fun infer(t0: Long, d: Decoded, crop: Float): Timed {
        val t1 = System.nanoTime()
        val box = Letterbox(d.src.width(), d.src.height(), size)
        letterbox(d.bitmap, d.src, box)
        d.bitmap.recycle()
        val t2 = System.nanoTime()
        output.rewind()
        interpreter.run(input, output)
        val t3 = System.nanoTime()
        output.rewind()
        output.asFloatBuffer().get(out)
        val list = decoder.decode(out, n, box).let { l -> if (crop < 1f) l.map { it.fromRegion(d.region, d.imageW, d.imageH) } else l }
        val t4 = System.nanoTime()
        return Timed(list, ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), d.sample, d.regionDecoded).also {
            // 9.4 C++ 전환 판단용 단계별 시간
            Log.i(
                TAG,
                "${spec.version} $delegate crop=$crop region=${d.regionDecoded} sample=${d.sample} decode=${it.decodeMs.toInt()} " +
                    "pre=${it.preMs.toInt()} infer=${it.inferMs.toInt()} post=${it.postMs.toInt()}ms n=${list.size}",
            )
        }
    }

    private fun ms(a: Long, b: Long) = (b - a) / 1e6

    /** 회색(114) 바탕에 [src] 영역을 비율을 지켜 줄여 가운데 놓고 RGB 0~1 float로 채운다(한 줄씩 한꺼번에 넣는다, 9.7 O7). */
    private fun letterbox(bitmap: Bitmap, src: Rect, box: Letterbox) {
        canvas.drawColor(Color.rgb(PAD_GREY, PAD_GREY, PAD_GREY))
        val rect = RectF(box.padX, box.padY, box.padX + src.width() * box.scale, box.padY + src.height() * box.scale)
        canvas.drawBitmap(bitmap, src, rect, paint)
        dst.getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        val fb = input.asFloatBuffer()
        var p = 0
        repeat(size) {
            var j = 0
            repeat(size) {
                val c = pixels[p++]
                row[j++] = (c shr 16 and 0xFF) * INV255
                row[j++] = (c shr 8 and 0xFF) * INV255
                row[j++] = (c and 0xFF) * INV255
            }
            fb.put(row)
        }
    }

    /** 진행 중인 추론이 끝난 뒤 같은 스레드에서 닫는다(GPU delegate는 인터프리터 뒤에). */
    override fun close() {
        CoroutineScope(worker).launch {
            interpreter.close()
            gpu?.close()
            dst.recycle()
        }
    }

    companion object {
        private const val TAG = "Detector"

        /** 레터박스 바탕 회색(YOLO 학습과 같게). */
        private const val PAD_GREY = 114
        private const val INV255 = 1f / 255

        /** GPU를 같은 모델로 되풀이해 돌린다(비행 중 1장·정밀 모두, 9.7 O3). 열기는 느려지고 추론이 고르다. 캐시(O2)가 열기 시간을 메운다. */
        private const val GPU_PREFERENCE = GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED

        /** GPU 직렬화 캐시 이름(9.7 O2). 모델 파일·입력·선호·기기 펌웨어가 바뀌면 다른 이름이 되어 옛 캐시를 쓰지 않는다. */
        private fun gpuToken(spec: ModelSpec, file: File) =
            "%08x".format("${spec.version}|${file.length()}|${file.lastModified()}|${spec.inputSize}|$GPU_PREFERENCE|${Build.FINGERPRINT}".hashCode())

        /** 모든 탐지기가 함께 쓰는 추론 스레드 하나(비행 중 1장·정밀·속도 측정이 차례로). */
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "detector") }.asCoroutineDispatcher()

        /**
         * 모델 폴더 하나를 연다. [gpu]면 GPU delegate를 먼저 해 보고 안 되면 CPU([AppConfig.Detector.THREADS] 스레드)로 간다.
         * [gpuCache]가 있으면 GPU 프로그램을 그 폴더에 저장해 두 번째부터 빨리 연다(9.7 O2, OpenCL만).
         * 규격이 맞지 않으면 이유를 담은 예외를 던진다. 추론 스레드에서 연다(GPU delegate 규칙).
         */
        suspend fun open(dir: File, gpu: Boolean, gpuCache: File? = null): TfliteYoloDetector = withContext(worker) { load(dir, gpu, gpuCache) }

        /** 순서대로 열어 보고 처음 열린 모델(9.6 [com.example.myfirstapp.core.detect.ModelPick] 순서). 없으면 null. 거부한 폴더는 로그에 이유를 남긴다. */
        suspend fun openFirst(root: File, order: List<String>, gpu: Boolean, gpuCache: File? = null): TfliteYoloDetector? =
            order.firstNotNullOfOrNull { name ->
                runCatching { open(File(root, name), gpu, gpuCache) }
                    .onFailure { Log.w(TAG, "모델 거부 $name: ${it.message}") }
                    .getOrNull()
            }

        /** 규격 검사·CPU 열기(설정 화면 검사, 가져오기). 쓰고 나면 닫는다. */
        fun load(dir: File, gpu: Boolean = false, gpuCache: File? = null): TfliteYoloDetector {
            val spec = ModelSpec.read(dir)
            val labels = File(dir, "labels.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val file = File(dir, "model.tflite")
            var delegate: GpuDelegate? = null
            var gpuError: String? = null
            val interpreter = (if (gpu) {
                runCatching {
                    val d = CompatibilityList().use { c ->
                        check(c.isDelegateSupportedOnThisDevice) { "이 기기는 GPU delegate를 지원하지 않습니다" }
                        GpuDelegate(
                            c.bestOptionsForThisDevice.apply {
                                setInferencePreference(GPU_PREFERENCE)
                                gpuCache?.let { setSerializationParams(it.apply { mkdirs() }.path, gpuToken(spec, file)) }
                            },
                        )
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

private fun Region.toRect() = Rect(left, top, left + width, top + height)
