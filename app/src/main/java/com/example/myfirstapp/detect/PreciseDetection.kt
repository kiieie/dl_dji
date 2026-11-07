package com.example.myfirstapp.detect

import android.content.Context
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.detect.BackfillProgress
import com.example.myfirstapp.core.detect.ModelPick
import com.example.myfirstapp.core.detect.PreciseBackfill
import com.example.myfirstapp.core.port.Detector
import com.example.myfirstapp.data.DetectionEntity
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.RcBattery
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.watchThermal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 정밀 탐지(9.6). 앱 수명 동안 있다([com.example.myfirstapp.InspectionApp]가 만든다). 판독 화면 [탐지]로 요청한 미션([request]) 사진 중
 * 정밀 모델(`role: full`, 없으면 `live` 모델) 결과가 없는 것을 하나씩 전체 이미지로 탐지해 `DETECTION`에 저장한다(사진마다 `CAPTURE.detectModel`).
 * 다 하면 요청을 지우고 멈춘다. 비행 중에도 막지 않는다(11-07 사용자 결정). 속도 측정 중·발열 SEVERE 이상·조종기 배터리 부족이면 쉬고, 풀리면 이어 한다.
 * 모델이 하나도 없으면 모의 탐지기로 한다(모의 기체 시연에서 판독 화면 확인용).
 */
class PreciseDetection(
    private val context: Context,
    private val dao: ProjectDao,
    private val settings: SettingsStore,
    scope: CoroutineScope,
    rcBattery: StateFlow<RcBattery?>,
) {
    private val modelsDir = File(context.getExternalFilesDir(null), "models")
    private val requested = MutableStateFlow(emptySet<Long>())
    private val cameraViewBy = MutableStateFlow(emptySet<String>())

    /** 모델 속도 측정 중이면 참(측정을 흐리지 않게 쉰다). */
    val benchRunning = MutableStateFlow(false)

    /** 조종기 발열 상태(앱 수명 동안 하나, 9.7 C). */
    val thermal: StateFlow<Int> = watchThermal(context)

    /**
     * GPU 프로그램 캐시 폴더(9.7 O2). `codeCacheDir`는 앱을 고쳐 깔 때마다 시스템이 비워 다음 첫 열기가 수 분 걸렸다(커널 맞추기).
     * `filesDir`는 업데이트 뒤에도 남는다. 옛 캐시는 토큰(런타임·펌웨어·모델 파일)이 바뀔 때 지운다.
     */
    val gpuCache = File(context.filesDir, "gpu-cache")

    /** 쉬는 이유. null이면 요청이 있을 때 돌린다. */
    val pauseReason: StateFlow<String?> = combine(benchRunning, thermal, rcBattery) { bench, thermal, battery ->
        when {
            bench -> "속도 측정 중"
            thermal >= AppConfig.Precise.THERMAL_PAUSE_STATUS -> "발열"
            battery != null && battery.pct < AppConfig.Precise.MIN_RC_BATTERY_PCT -> "조종기 배터리 ${battery.pct}%"
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * GPU 캐시 만들기(멈출 수 없음, 1~7분)가 쉬는 이유: [pauseReason]에 더해 카메라 뷰(자동·수동)가 열려 있으면 쉰다.
     * 카메라 뷰가 열려 있어야 이륙하므로 만들기 도중 이륙하지 않게 한다(9.7).
     */
    val gpuBuildPause: StateFlow<String?> = combine(pauseReason, cameraViewBy) { p, cam -> p ?: if (cam.isNotEmpty()) "카메라 뷰" else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val backfill = PreciseBackfill(
        combine(pauseReason, requested) { pause, req -> pause == null && req.isNotEmpty() }, ::remaining, ::process, onIdle = ::closeModel, idleCloseMs = AppConfig.Precise.IDLE_CLOSE_MS,
    )

    /** 진행(판독 화면 "정밀 탐지 37/120"). */
    val progress: StateFlow<BackfillProgress> = backfill.progress

    init {
        scope.launch { pauseReason.collect { OpLog.log("precise", "gate", mapOf("pause" to it)) } }
        scope.launch {
            // 비행 중에 앱이 다시 켜졌으면(6.4 복구) 비행 화면이 열릴 때까지 GPU 캐시를 만들지 않는다
            if (dao.openSortieMission() != null) setCameraView("auto", true)
            backfill.run()
        }
    }

    /** 비행 화면([source] "auto")·수동 비행 화면("manual")이 열렸는지 알린다(GPU 캐시 만들기만 쉰다, 탐지는 막지 않음). */
    fun setCameraView(source: String, open: Boolean) = cameraViewBy.update { if (open) it + source else it - source }

    /** 판독 화면 [탐지]: [missionId] 사진을 정밀 탐지한다. */
    fun request(missionId: Long) {
        OpLog.log("precise", "request", mapOf("mission" to missionId))
        requested.update { it + missionId }
        backfill.kick()
    }

    private var model: Detector? = null

    /**
     * 앨범 뷰어 [탐지](9.6, 11-07): 사진 1장을 정밀 모델로 전체 탐지한다. 저장하지 않는다(미션 기록이 없는 사진).
     * 누를 때마다 열고 닫는다(캐시가 있으면 열기 약 0.3초). 모델이 없으면 실패.
     */
    suspend fun detectOne(file: File): LastPhoto {
        val specs = withContext(Dispatchers.IO) { readModelSpecs(modelsDir) }
        val order = ModelPick.fullOrder(specs.map { (d, s) -> s.choice(d) }, settings.fullModelDir.first(), settings.liveModelDir.first())
        val m = TfliteYoloDetector.openFirst(modelsDir, order, gpu = true, gpuCache) ?: error("탐지 모델이 없습니다")
        try {
            val t = m.detectTimed(file)
            OpLog.log("precise", "one", mapOf("model" to m.modelVersion, "count" to t.detections.size, "ms" to t.totalMs.toInt()))
            val summary = t.detections.groupingBy { it.label }.eachCount().entries.joinToString(" · ") { "${m.spec.displayNames[it.key] ?: it.key} ${it.value}" }
            return LastPhoto(file.path, t.detections, 1f, summary.ifEmpty { "탐지 없음" }, m.spec.displayNames)
        } finally {
            m.close()
        }
    }

    /** 요청한 미션의 남은 사진. 없으면 요청을 지운다(멈춤). */
    private suspend fun remaining(): List<Long> {
        val ids = requested.value
        return remainingOf(ids.toList()).also { if (it.isEmpty()) requested.update { r -> r - ids } }
    }

    /** 처음 후보 모델의 버전으로 먼저 세어 보고, 있으면 모델을 열어 실제로 연 모델 버전으로 다시 센다. */
    private suspend fun remainingOf(missions: List<Long>): List<Long> {
        if (missions.isEmpty()) return emptyList()
        model?.let { return dao.capturesWithoutDetection(it.modelVersion, missions) }
        val specs = withContext(Dispatchers.IO) { readModelSpecs(modelsDir) }
        val order = ModelPick.fullOrder(specs.map { (d, s) -> s.choice(d) }, settings.fullModelDir.first(), settings.liveModelDir.first())
        // 실제 모델이 없으면 정밀 탐지를 하지 않는다: 모의 탐지기 결과(가짜 검출)가 실제 사진에 저장되면 안 된다
        val first = order.firstOrNull() ?: return emptyList()
        if (dao.capturesWithoutDetection(specs.getValue(first).version, missions).isEmpty()) return emptyList()
        val m = TfliteYoloDetector.openFirst(modelsDir, order, gpu = true, gpuCache) ?: return emptyList()
        model = m
        OpLog.log("precise", "open", mapOf("model" to m.modelVersion, "delegate" to m.delegate, "gpu" to m.preference))
        return dao.capturesWithoutDetection(m.modelVersion, missions)
    }

    private suspend fun process(captureId: Long) {
        val m = model ?: return
        val path = dao.capture(captureId)?.filePath ?: return
        val file = File(path)
        val timed = (m as? TfliteYoloDetector)?.detectTimed(file)
        val list = timed?.detections ?: m.detect(withContext(Dispatchers.IO) { file.readBytes() })
        dao.savePreciseDetections(captureId, m.modelVersion, list.map { DetectionEntity(0, captureId, it.label, it.score, it.x, it.y, it.w, it.h, it.alert) })
        OpLog.log(
            "precise", "photo",
            mapOf("capture" to captureId, "model" to m.modelVersion, "count" to list.size, "alert" to list.count { it.alert }, "ms" to timed?.totalMs?.toInt()),
        )
    }

    /** 쉬거나 다 끝나고 [AppConfig.Precise.IDLE_CLOSE_MS]가 지나면 모델을 닫아 메모리를 돌려준다(다음에 다시 연다, 설정에서 바꾼 모델도 그때 반영). */
    private fun closeModel() {
        (model as? TfliteYoloDetector)?.close()
        model = null
    }
}
