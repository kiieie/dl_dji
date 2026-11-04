package com.example.myfirstapp.detect

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.detect.LatestOnly
import com.example.myfirstapp.core.detect.ModelPick
import com.example.myfirstapp.core.fake.FakeDetector
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.port.Detector
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** 카메라 뷰의 "마지막 사진"(9.6 실시간 1단계). 상자는 원본 대비 0~1 비율 좌표다. DB에는 남기지 않는다. */
data class LastPhoto(
    val path: String?,
    val detections: List<Detection>,
    /** 탐지한 가운데 영역 비율(1이면 전체). 썸네일에 흐리게 그린다. */
    val crop: Float,
    /** 클래스별 수("LP 2 · SUS 4"). 없으면 "탐지 없음", 실패면 이유. */
    val summary: String,
    /** 라벨 → 화면 이름(model.json `displayNames`). */
    val names: Map<String, String>,
)

/**
 * 비행 중 최근 1장 탐지(9.6): 저장한 사진 중 가장 최근 것만 비행 중 모델(`role: live`, 입력이 가장 작은 것)로 가운데를 잘라 탐지한다.
 * 처리하는 동안 온 사진은 마지막 것만 남긴다([LatestOnly]). [offer]는 기다리지 않아 실행기를 막지 않는다. 신호음은 내지 않는다.
 * 모델이 없으면 모의 탐지기로 한다(모의 기체에서도 썸네일이 보이게).
 */
class LiveDetection(
    scope: CoroutineScope,
    private val modelsDir: File,
    private val settings: SettingsStore,
    private val onResult: (LastPhoto) -> Unit,
) {
    private var detector: Detector? = null
    private val worker = LatestOnly<Pair<ByteArray, String?>>(scope) { (bytes, path) -> onResult(run(bytes, path)) }

    /** 막 저장한 사진(원본 JPEG와 저장 경로). */
    fun offer(bytes: ByteArray, path: String?) = worker.offer(bytes to path)

    private suspend fun run(bytes: ByteArray, path: String?): LastPhoto {
        val det = detector ?: open().also { detector = it }
        val tflite = det as? TfliteYoloDetector
        val names = tflite?.spec?.displayNames.orEmpty()
        val crop = if (tflite != null) AppConfig.Detector.LIVE_CROP_FRACTION else 1f
        return try {
            val timed = tflite?.detectTimed(bytes, crop)
            val list = timed?.detections ?: det.detect(bytes)
            OpLog.log("detect", "live", mapOf("model" to det.modelVersion, "count" to list.size, "ms" to timed?.totalMs?.toInt(), "crop" to crop))
            LastPhoto(path, list, crop, list.groupingBy { it.label }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }.ifEmpty { "탐지 없음" }, names)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 탐지 실패는 비행을 막지 않는다(6.5). OOM도 여기서 잡는다
            OpLog.log("error", "liveDetect", mapOf("message" to (e.message ?: e::class.simpleName)))
            LastPhoto(path, emptyList(), 1f, "탐지 실패", names)
        }
    }

    private suspend fun open(): Detector {
        val specs = withContext(Dispatchers.IO) { readModelSpecs(modelsDir) }
        val order = ModelPick.liveOrder(specs.map { (d, s) -> s.choice(d) }, settings.liveModelDir.first())
        return TfliteYoloDetector.openFirst(modelsDir, order, gpu = true) ?: FakeDetector()
    }

    fun close() {
        (detector as? TfliteYoloDetector)?.close()
    }
}
