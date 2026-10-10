package com.example.myfirstapp.core.fake

import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneEvent
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.LegMission
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.LocalProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * 모의 기체(3.2). 시간에 따라 위치를 옮기고 배터리를 줄이며, 가짜 사진을 돌려준다.
 * 기체 없이 상태머신과 UI를 개발·테스트하는 데 쓴다. `simulate*`/`press*` 함수로 이상 상황을 만든다.
 */
class FakeDroneGateway(
    private val scope: CoroutineScope,
    private val homePoint: GeoPoint,
    private val config: Config = Config(),
) : DroneGateway {

    data class Config(
        /** 시뮬레이션 한 번의 실제 시간. */
        val tickMs: Long = 200,
        /** 실제 1초에 흐르는 모의 시간(초). UI 시연 때 키워서 빨리 돌린다. */
        val timeScale: Double = 1.0,
        val cruisePctPerSec: Double = 100.0 / (25 * 60),
        val hoverPctPerSec: Double = 100.0 / (28 * 60),
        val verticalSpeedMps: Double = 3.0,
        val takeoffMs: Long = 3_000,
        val legStartMs: Long = 2_000,
        val captureMs: Long = 1_000,
        val downloadMs: Long = 1_500,
        /** 다운로드가 돌려줄 사진. 앱은 렌즈·좌표를 그린 JPEG을 넘긴다(10장 ⑤). */
        val image: (CaptureInfo) -> ByteArray = { "FAKE_JPEG:${it.mediaId}".toByteArray() },
        /** 앱 재시작 복구 시연용: 값이 있으면 이 위치 상공에서 호버 중인 상태로 시작한다(실제 기체는 앱이 죽어도 떠 있음). */
        val airborneAt: GeoPoint? = null,
        val airborneAltitudeM: Double = 30.0,
    )

    private val proj = LocalProjection(homePoint)
    private val _telemetry = MutableStateFlow(
        config.airborneAt?.let { Telemetry(it, config.airborneAltitudeM, 0.0, 100.0, FlightMode.MANUAL, 5, true, homePoint) }
            ?: Telemetry(homePoint, 0.0, 0.0, 100.0, FlightMode.ON_GROUND, 5, true, homePoint),
    )

    /** 모의 SD 카드. 테스트에서 앱이 죽기 전에 찍힌 사진을 넣어 둔다. */
    val sdCard = mutableListOf<CaptureInfo>()
    override val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _events = MutableSharedFlow<DroneEvent>(extraBufferCapacity = 64)
    override val events: Flow<DroneEvent> = _events.asSharedFlow()

    /** 비행 전 점검 결과. 테스트에서 바꿔 실패를 만든다. */
    var preflightReport = PreflightReport(
        connected = true, gpsSatellites = 20, gpsLevel = 5, homeRecorded = true, batteryPct = 100.0,
        batteryTempC = 25.0, sdFreeMb = 32_000, ldmEnabled = true, obstacleAvoidanceOn = true,
        rthAltitudeM = 60.0, lostLinkActionIsRth = true, home = homePoint,
    )
    var failNextLegStarts = 0
    var failNextCaptures = 0
    var failNextDownloads = 0

    /** 호출 기록(테스트 확인용). */
    val calls = mutableListOf<String>()

    private var motionJob: Job? = null
    private var missionPaused = false
    private var mediaSeq = 0

    override suspend fun preflight(): PreflightReport {
        calls += "preflight"
        return preflightReport.copy(batteryPct = _telemetry.value.batteryPct)
    }

    override suspend fun takeoff(): Result<Unit> {
        calls += "takeoff"
        if (!connected()) return Result.failure(IllegalStateException("연결 없음"))
        _telemetry.update { it.copy(flightMode = FlightMode.TAKING_OFF) }
        delay(config.takeoffMs)
        _telemetry.update { it.copy(flightMode = FlightMode.MANUAL, altitudeM = 1.2) }
        return Result.success(Unit)
    }

    override suspend fun startLeg(leg: LegMission): Result<Unit> {
        calls += "startLeg"
        if (!connected()) return Result.failure(IllegalStateException("연결 없음"))
        delay(config.legStartMs)
        if (failNextLegStarts > 0) {
            failNextLegStarts--
            return Result.failure(IllegalStateException("모의 업로드 실패"))
        }
        missionPaused = false
        _telemetry.update { it.copy(flightMode = FlightMode.MISSION) }
        motionJob?.cancel()
        motionJob = scope.launch {
            climbTo(leg.transitAltitudeM, leg.speedMps)
            moveTo(proj.toEnu(leg.target), leg.speedMps)
            climbTo(leg.inspectAltitudeM, leg.speedMps)
            _telemetry.update { it.copy(flightMode = FlightMode.MANUAL, speedMps = 0.0) }
            _events.emit(DroneEvent.LegCompleted)
        }
        return Result.success(Unit)
    }

    override suspend fun stopMission(): Result<Unit> {
        calls += "stopMission"
        motionJob?.cancel()
        missionPaused = false
        _telemetry.update { if (it.isFlying) it.copy(flightMode = FlightMode.MANUAL, speedMps = 0.0) else it }
        return Result.success(Unit)
    }

    override suspend fun capture(lens: Lens, gimbalPitchDeg: Double, zoomRatio: Double): Result<CaptureInfo> {
        calls += "capture:$lens"
        delay(config.captureMs)
        if (failNextCaptures > 0) {
            failNextCaptures--
            return Result.failure(IllegalStateException("모의 촬영 실패"))
        }
        val t = _telemetry.value
        val info = CaptureInfo("FAKE_${++mediaSeq}", lens, t.position, t.altitudeM, gimbalPitchDeg, 0.0, System.currentTimeMillis())
        sdCard += info
        return Result.success(info)
    }

    override suspend fun listMedia(sinceMs: Long): Result<List<CaptureInfo>> {
        calls += "listMedia"
        return Result.success(sdCard.filter { it.takenAtMs >= sinceMs })
    }

    override suspend fun download(capture: CaptureInfo, mode: DownloadMode): Result<ByteArray> {
        calls += "download:${capture.mediaId}"
        delay(config.downloadMs)
        if (failNextDownloads > 0) {
            failNextDownloads--
            return Result.failure(IllegalStateException("모의 다운로드 실패"))
        }
        return Result.success(config.image(capture))
    }

    override suspend fun returnHome(): Result<Unit> {
        calls += "returnHome"
        startGoHome()
        return Result.success(Unit)
    }

    // ---------------------------------------------------------------- 시뮬레이션 조작

    /** RC 하드웨어 일시정지 버튼: 미션을 일시정지(종료하지 않음)하고 호버한다. */
    fun pressRcPause() {
        if (_telemetry.value.flightMode == FlightMode.MISSION) {
            missionPaused = true
            _telemetry.update { it.copy(flightMode = FlightMode.MISSION_PAUSED, speedMps = 0.0) }
        }
        _events.tryEmit(DroneEvent.PausedByRc)
    }

    fun pressRcResume() {
        _events.tryEmit(DroneEvent.ResumeByRc)
    }

    fun simulateObstacleAvoidance() {
        missionPaused = true
        _events.tryEmit(DroneEvent.ObstacleAvoidance)
    }

    fun simulateGpsDegraded() {
        _telemetry.update { it.copy(gpsLevel = 2) }
        _events.tryEmit(DroneEvent.GpsDegraded)
    }

    fun simulateLinkLost() {
        _telemetry.update { it.copy(connected = false) }
        _events.tryEmit(DroneEvent.ConnectionLost)
    }

    fun simulateLinkRestored() {
        _telemetry.update { it.copy(connected = true) }
        _events.tryEmit(DroneEvent.ConnectionRestored)
    }

    /** 기체 주도 RTH(저전압, RC RTH 버튼 등). */
    fun simulateAircraftRth() = startGoHome()

    fun setBattery(pct: Double) = _telemetry.update { it.copy(batteryPct = pct) }

    // ---------------------------------------------------------------- 내부

    private fun connected() = _telemetry.value.connected

    private fun startGoHome() {
        motionJob?.cancel()
        _telemetry.update { it.copy(flightMode = FlightMode.GO_HOME) }
        _events.tryEmit(DroneEvent.GoHomeStarted)
        motionJob = scope.launch {
            val speed = 10.0
            climbTo(maxOf(_telemetry.value.altitudeM, preflightReport.rthAltitudeM), speed)
            moveTo(Enu(0.0, 0.0), speed)
            _telemetry.update { it.copy(flightMode = FlightMode.LANDING) }
            climbTo(0.0, speed)
            _telemetry.update { it.copy(flightMode = FlightMode.ON_GROUND, speedMps = 0.0, altitudeM = 0.0) }
            _events.emit(DroneEvent.Landed)
        }
    }

    private val tickSec get() = config.tickMs / 1000.0 * config.timeScale

    private suspend fun climbTo(altitudeM: Double, speedMps: Double) {
        while (abs(_telemetry.value.altitudeM - altitudeM) > 0.01) {
            tick()
            val step = min(config.verticalSpeedMps, speedMps) * tickSec
            _telemetry.update {
                val diff = altitudeM - it.altitudeM
                it.copy(altitudeM = it.altitudeM + diff.coerceIn(-step, step), speedMps = 0.0, batteryPct = drain(it, config.hoverPctPerSec))
            }
        }
    }

    private suspend fun moveTo(target: Enu, speedMps: Double) {
        while (true) {
            tick()
            val t = _telemetry.value
            val cur = proj.toEnu(t.position ?: homePoint)
            val remain = cur.distanceTo(target)
            if (remain < 0.5) break
            val step = min(speedMps * tickSec, remain)
            val next = Enu(cur.x + (target.x - cur.x) / remain * step, cur.y + (target.y - cur.y) / remain * step)
            _telemetry.update { it.copy(position = proj.toGeo(next), speedMps = speedMps, batteryPct = drain(it, config.cruisePctPerSec)) }
        }
    }

    /** 한 틱 기다린다. 미션 일시정지 중에는 제자리 호버하며 배터리만 줄어든다. */
    private suspend fun tick() {
        delay(config.tickMs)
        while (missionPaused) {
            _telemetry.update { it.copy(batteryPct = drain(it, config.hoverPctPerSec)) }
            delay(config.tickMs)
        }
    }

    private fun drain(t: Telemetry, pctPerSec: Double) = (t.batteryPct - pctPerSec * tickSec).coerceAtLeast(0.0)
}
