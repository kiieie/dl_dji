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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 카메라 뷰의 "마지막 사진"(9.6). 상자는 원본 대비 0~1 비율 좌표다. DB에는 남기지 않는다. */
data class LastPhoto(
    val path: String?,
    val detections: List<Detection>,
    /** 탐지한 가운데 영역 비율(1이면 전체). 썸네일에 흐리게 그린다. */
    val crop: Float,
    /** 클래스별 수("LP 2 · SUS 4"). 없으면 "탐지 없음", 실패면 이유. */
    val summary: String,
    /** 라벨 → 화면 이름(model.json `displayNames`). */
    val names: Map<String, String>,
    /** 아직 탐지하지 않음(누르면 탐지). */
    val pending: Boolean = false,
)

/**
 * 마지막 사진 탐지(9.6, 11-07 누를 때만): [offer]는 썸네일만 바꾸고, 사용자가 썸네일을 누르면([detect]) 그 1장만 비행 중 모델
 * (`role: live`, 입력이 가장 작은 것)로 가운데를 잘라 탐지한다. 비행 중 자동 탐지는 하지 않는다(비행 안전, 사용자 결정 11-07).
 * 모델은 처음 누를 때 연다. 탐지하는 동안 새 사진이 오면 옛 결과는 버린다. 모델이 없으면 모의 탐지기로 한다. 조종기가 뜨거우면([thermal]) 쉰다(9.7 C).
 */
class LiveDetection(
    scope: CoroutineScope,
    private val modelsDir: File,
    private val settings: SettingsStore,
    /** GPU 프로그램 캐시 폴더(9.7 O2). */
    private val gpuCache: File?,
    /** 조종기 발열 상태(`PowerManager.THERMAL_STATUS_*`). null이면 보지 않는다. */
    private val thermal: StateFlow<Int>?,
    private val onResult: (LastPhoto) -> Unit,
) {
    /** 화면 수명과 따로 끝까지 연다: 여는 도중 화면을 닫아도 GPU 자원이 새지 않게 [close]가 받아 닫는다. */
    private val loader = CoroutineScope(Dispatchers.IO)
    private var model: Deferred<Detector>? = null
    private val worker = LatestOnly<String>(scope) { path -> run(path).takeIf { path == last }?.let(onResult) }
    @Volatile private var last: String? = null

    /** 발열로 쉬는 중(바뀔 때만 로그). */
    private var hot = false

    /** 막 저장한 사진의 경로. 탐지는 하지 않는다. */
    fun offer(path: String?) {
        last = path
        onResult(LastPhoto(path, emptyList(), 1f, "누르면 탐지", emptyMap(), pending = true))
    }

    /** 썸네일을 눌렀다: 마지막 사진 1장을 탐지한다. */
    fun detect() {
        val path = last ?: return
        onResult(LastPhoto(path, emptyList(), 1f, "탐지 중…", emptyMap(), pending = true))
        worker.offer(path)
    }

    private suspend fun run(path: String): LastPhoto {
        val status = thermal?.value ?: 0
        if ((status >= AppConfig.Precise.THERMAL_PAUSE_STATUS) != hot) {
            hot = !hot
            OpLog.log("detect", "liveThermal", mapOf("pause" to hot, "status" to status))
        }
        if (hot) return LastPhoto(path, emptyList(), 1f, "발열로 탐지 쉼", emptyMap())
        var names = emptyMap<String, String>()
        return try {
            val det = (model ?: loader.async { open() }.also { model = it }).await()
            val bytes = withContext(Dispatchers.IO) { File(path).readBytes() }
            val tflite = det as? TfliteYoloDetector
            names = tflite?.spec?.displayNames.orEmpty()
            val crop = if (tflite != null) AppConfig.Detector.LIVE_CROP_FRACTION else 1f
            val timed = tflite?.detectTimed(bytes, crop)
            val list = timed?.detections ?: det.detect(bytes)
            OpLog.log(
                "detect", "live",
                mapOf("model" to det.modelVersion, "count" to list.size, "ms" to timed?.totalMs?.toInt(), "crop" to crop, "region" to timed?.region),
            )
            LastPhoto(path, list, crop, list.groupingBy { it.label }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }.ifEmpty { "탐지 없음" }, names)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 탐지 실패는 비행을 막지 않는다(6.5). OOM도 여기서 잡는다
            OpLog.log("error", "liveDetect", mapOf("message" to (e.message ?: e::class.simpleName)))
            LastPhoto(path, emptyList(), 1f, "탐지 실패", names)
        }
    }

    /** 모델을 열고 회색 입력으로 한 번 돌린다(처음 누를 때). */
    private suspend fun open(): Detector {
        val t0 = System.nanoTime()
        val specs = readModelSpecs(modelsDir)
        val order = ModelPick.liveOrder(specs.map { (d, s) -> s.choice(d) }, settings.liveModelDir.first())
        val det = TfliteYoloDetector.openFirst(modelsDir, order, gpu = true, gpuCache) ?: return FakeDetector()
        val openMs = (System.nanoTime() - t0) / 1e6
        val warmMs = runCatching { det.warmUp() }.getOrNull()
        // gpu: SUSTAINED(캐시 있음) 또는 FAST(캐시 없음, 뒤에서 만듦 9.7)
        OpLog.log(
            "detect", "livePreload",
            mapOf("model" to det.spec.version, "delegate" to det.delegate, "gpu" to det.preference, "openMs" to openMs.toInt(), "warmMs" to warmMs?.toInt()),
        )
        return det
    }

    fun close() {
        val m = model ?: return
        loader.launch { (runCatching { m.await() }.getOrNull() as? TfliteYoloDetector)?.close() }
    }
}
