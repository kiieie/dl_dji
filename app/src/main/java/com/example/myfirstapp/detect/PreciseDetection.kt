package com.example.myfirstapp.detect

import android.content.Context
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.detect.BackfillProgress
import com.example.myfirstapp.core.detect.ModelPick
import com.example.myfirstapp.core.detect.PreciseBackfill
import com.example.myfirstapp.core.fake.FakeDetector
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
 * 착륙 뒤 정밀 탐지(9.6). 앱 수명 동안 돈다([com.example.myfirstapp.InspectionApp]가 만든다). 최근 미션 사진 중 정밀 모델(`role: full`,
 * 없으면 비행 중 모델) 결과가 없는 것을 하나씩 전체 이미지로 탐지해 `DETECTION`에 저장한다(사진마다 `CAPTURE.detectModel`).
 * 비행 중(소티·수동 비행)·속도 측정 중·발열 SEVERE 이상·조종기 배터리 부족이면 쉬고, 풀리면 남은 것부터 이어 한다.
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
    private val flyingBy = MutableStateFlow(emptySet<String>())

    /** 모델 속도 측정 중이면 참(측정을 흐리지 않게 쉰다). */
    val benchRunning = MutableStateFlow(false)

    /** 쉬는 이유. null이면 돌린다. */
    val pauseReason: StateFlow<String?> = combine(flyingBy, benchRunning, watchThermal(context), rcBattery) { flying, bench, thermal, battery ->
        when {
            flying.isNotEmpty() -> "비행 중"
            bench -> "속도 측정 중"
            thermal >= AppConfig.Precise.THERMAL_PAUSE_STATUS -> "발열"
            battery != null && battery.pct < AppConfig.Precise.MIN_RC_BATTERY_PCT -> "조종기 배터리 ${battery.pct}%"
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val backfill = PreciseBackfill(pauseReason.map { it == null }, ::remaining, ::process, onIdle = ::closeModel)

    /** 진행(판독 화면 "정밀 탐지 37/120"). */
    val progress: StateFlow<BackfillProgress> = backfill.progress

    init {
        scope.launch { pauseReason.collect { OpLog.log("precise", "gate", mapOf("pause" to it)) } }
        scope.launch { backfill.run() }
    }

    /** 비행 화면([source] "auto")·수동 비행 화면("manual")이 기체가 떠 있는지 알린다. 하나라도 떠 있으면 쉰다. */
    fun setFlying(source: String, flying: Boolean) = flyingBy.update { if (flying) it + source else it - source }

    /** 새 사진을 저장했다(착륙 뒤 받은 사진도 이어서 처리). */
    fun kick() = backfill.kick()

    private var model: Detector? = null

    /** 남은 사진. 처음 후보 모델의 버전으로 먼저 세어 보고, 있으면 모델을 열어 실제로 연 모델 버전으로 다시 센다. */
    private suspend fun remaining(): List<Long> {
        val since = System.currentTimeMillis() - AppConfig.Precise.RECENT_DAYS * 24L * 3600 * 1000
        model?.let { return dao.capturesWithoutDetection(it.modelVersion, since) }
        val specs = withContext(Dispatchers.IO) { readModelSpecs(modelsDir) }
        val order = ModelPick.fullOrder(specs.map { (d, s) -> s.choice(d) }, settings.fullModelDir.first(), settings.liveModelDir.first())
        val guess = order.firstOrNull()?.let { specs.getValue(it).version } ?: FAKE_VERSION
        if (dao.capturesWithoutDetection(guess, since).isEmpty()) return emptyList()
        val m = (if (order.isEmpty()) FakeDetector() else TfliteYoloDetector.openFirst(modelsDir, order, gpu = true)) ?: return emptyList()
        model = m
        OpLog.log("precise", "open", mapOf("model" to m.modelVersion, "delegate" to (m as? TfliteYoloDetector)?.delegate))
        return dao.capturesWithoutDetection(m.modelVersion, since)
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

    /** 쉬거나 다 끝나면 모델을 닫아 메모리를 돌려준다(다음에 다시 연다, 설정에서 바꾼 모델도 그때 반영). */
    private fun closeModel() {
        (model as? TfliteYoloDetector)?.close()
        model = null
    }

    private companion object {
        val FAKE_VERSION = FakeDetector().modelVersion
    }
}
