package com.example.myfirstapp.core.fake

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.CameraStatus
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FocusMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.FinishAction
import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Obstacles
import com.example.myfirstapp.core.model.PhotoModes
import com.example.myfirstapp.core.model.ShootMode
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.port.CameraNotReady
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneEvent
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.LegMission
import com.example.myfirstapp.core.port.MissionStartRejected
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Viewpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val cruisePctPerSec: Double = Tuning.Energy.CRUISE_PCT_PER_SEC,
        val hoverPctPerSec: Double = Tuning.Energy.HOVER_PCT_PER_SEC,
        val verticalSpeedMps: Double = Tuning.Energy.VERTICAL_SPEED_MPS,
        val takeoffMs: Long = 3_000,
        val legStartMs: Long = 2_000,
        val captureMs: Long = 1_000,
        val downloadMs: Long = 1_500,
        /** 다운로드가 돌려줄 사진. 앱은 렌즈·좌표를 그린 JPEG을 넘긴다(10장 ⑤). */
        val image: (CaptureInfo) -> ByteArray = { "FAKE_JPEG:${it.mediaId}".toByteArray() },
        /** 앱 재시작 복구 시연용: 값이 있으면 이 위치 상공에서 호버 중인 상태로 시작한다(실제 기체는 앱이 죽어도 떠 있음). */
        val airborneAt: GeoPoint? = null,
        val airborneAltitudeM: Double = 30.0,
        /** 떠 있는 채 시작할 때의 비행 모드(앱만 죽고 기체는 미션을 계속 나는 경우 MISSION). */
        val airborneMode: FlightMode = FlightMode.MANUAL,
        /** 모의 배터리 전압(V, 상단 바·비행 전 점검 상태 줄). */
        val batteryVoltageV: Double = 16.9,
    )

    private val proj = LocalProjection(homePoint)
    private val _telemetry = MutableStateFlow(
        (config.airborneAt?.let { Telemetry(it, config.airborneAltitudeM, 0.0, 100.0, config.airborneMode, 5, true, homePoint) }
            ?: Telemetry(homePoint, 0.0, 0.0, 100.0, FlightMode.ON_GROUND, 5, true, homePoint))
            // 상단 바·배터리 팝업에 보일 그럴듯한 값(10.5.1). 바람·해발 고도는 모름("--")
            .copy(satellites = 20, batteryVoltageV = config.batteryVoltageV, batteryTempC = 25.0),
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

    /** 지형 추적 미션 시작을 거절한다(2026-10-30 시뮬레이터에서 본 동작). */
    var rejectTerrainStarts = false

    /** 실제 기체처럼 이륙을 KMZ에 맡긴다(takeoff는 바로 성공하고 뜨지 않음, 방식 A). */
    var kmzTakesOff = false
    var failNextCaptures = 0

    /** 값이 있으면 카메라를 단일 사진으로 맞추지 못한 것처럼 올리기·시작·재개를 [CameraNotReady]로 거절한다(실제 기체 어댑터, 6.4). */
    var cameraNotReady: String? = null

    /** 다음 지점 사진을 이만큼 늦게 알린다(다음 지점에 도착한 뒤 오는 사진, 6.4 사진 순서). */
    var lateNextPhotoMs = 0L
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
        if (kmzTakesOff) return Result.success(Unit)
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

    /** [uploadSortie]로 올린 소티(지점, 파라미터). */
    private var uploaded: Pair<List<Viewpoint>, FlightParams>? = null

    /** 방식 A: 지점을 차례로 날며 도착·촬영을 알리고, 끝나면 KMZ 완료 동작(기본 RTH, 그 밖은 제자리 호버). */
    override suspend fun startSortie(points: List<Viewpoint>, params: FlightParams): Result<Unit> =
        uploadSortie(points, params).mapCatching { runUploaded().getOrThrow() }

    override suspend fun uploadSortie(points: List<Viewpoint>, params: FlightParams): Result<Unit> {
        calls += "startSortie:${points.size}"
        if (!connected()) return Result.failure(IllegalStateException("연결 없음"))
        cameraNotReady?.let { return Result.failure(CameraNotReady(it)) }
        delay(config.legStartMs)
        if (failNextLegStarts > 0) {
            failNextLegStarts--
            return Result.failure(IllegalStateException("모의 업로드 실패"))
        }
        calls += "terrain:${params.terrainFollow}"
        uploaded = points to params
        return Result.success(Unit)
    }

    override suspend fun startUploaded(): Result<Unit> {
        calls += "startUploaded"
        cameraNotReady?.let { return Result.failure(CameraNotReady(it)) }
        return runUploaded()
    }

    private fun runUploaded(): Result<Unit> {
        val (points, params) = uploaded ?: return Result.failure(IllegalStateException("올린 미션 없음"))
        if (rejectTerrainStarts && params.terrainFollow) return Result.failure(MissionStartRejected("모의 지형 추적 시작 거절"))
        uploaded = null
        missionPaused = false
        _telemetry.update { it.copy(flightMode = FlightMode.MISSION) }
        motionJob?.cancel()
        motionJob = scope.launch {
            points.forEachIndexed { i, p ->
                climbTo(p.altitudeM, params.speedMps)
                moveTo(proj.toEnu(p.position), params.speedMps)
                _events.emit(DroneEvent.WaypointReached(i))
                tick()
                if (failNextCaptures > 0) {
                    failNextCaptures--
                } else {
                    // 한 번 찍으면 렌즈마다 파일 하나(광각+망원 동시, 5.6)
                    val t = _telemetry.value
                    // 설치기기 전주는 설비 쪽으로 한 번 더(5.6)
                    val aims = listOfNotNull(false to p.gimbalPitchDeg, p.equipmentPitchDeg?.let { true to it })
                    val infos = aims.flatMap { (equipment, pitch) ->
                        params.lenses.map { lens ->
                            CaptureInfo("FAKE_${++mediaSeq}", lens, t.position, t.altitudeM, pitch, p.headingDeg, System.currentTimeMillis(), equipment = equipment)
                                .also { sdCard += it }
                        }
                    }
                    val late = lateNextPhotoMs.also { lateNextPhotoMs = 0 }
                    if (late > 0) scope.launch { delay(late); infos.forEach { _events.emit(DroneEvent.PhotoTaken(i, it)) } }
                    else infos.forEach { _events.emit(DroneEvent.PhotoTaken(i, it)) }
                }
            }
            _events.emit(DroneEvent.MissionFinished)
            // ponytail: 착륙·첫 지점으로 같은 다른 완료 동작은 제자리 호버로만 흉내 낸다
            if (params.finishAction == FinishAction.GO_HOME) startGoHome()
            else _telemetry.update { it.copy(flightMode = FlightMode.MANUAL, speedMps = 0.0) }
        }
        return Result.success(Unit)
    }

    override suspend fun pauseMission(): Result<Unit> {
        calls += "pauseMission"
        if (_telemetry.value.flightMode == FlightMode.MISSION) {
            missionPaused = true
            _telemetry.update { it.copy(flightMode = FlightMode.MISSION_PAUSED, speedMps = 0.0) }
        }
        return Result.success(Unit)
    }

    override suspend fun resumeMission(): Result<Unit> {
        calls += "resumeMission"
        cameraNotReady?.let { return Result.failure(CameraNotReady(it)) }
        if (!missionPaused || motionJob?.isActive != true) return Result.failure(IllegalStateException("멈춘 미션 없음"))
        missionPaused = false
        _telemetry.update { it.copy(flightMode = FlightMode.MISSION) }
        return Result.success(Unit)
    }

    override suspend fun stopMission(): Result<Unit> {
        calls += "stopMission"
        motionJob?.cancel()
        missionPaused = false
        _telemetry.update { if (it.isFlying) it.copy(flightMode = FlightMode.MANUAL, speedMps = 0.0) else it }
        return Result.success(Unit)
    }

    /** 앱 촬영은 하나씩(실제 기체 어댑터와 같음: 겹치면 같은 새 사진 알림을 나눠 받음). */
    private val captureLock = Mutex()

    override suspend fun capture(lens: Lens, gimbalPitchDeg: Double, zoomRatio: Double): Result<CaptureInfo> = captureLock.withLock {
        calls += "capture:$lens"
        delay(config.captureMs)
        if (failNextCaptures > 0) {
            failNextCaptures--
            return Result.failure(IllegalStateException("모의 촬영 실패"))
        }
        Result.success(newShot(lens, gimbalPitchDeg))
    }

    private fun newShot(lens: Lens, gimbalPitchDeg: Double): CaptureInfo {
        val t = _telemetry.value
        return CaptureInfo("FAKE_${++mediaSeq}", lens, t.position, t.altitudeM, gimbalPitchDeg, 0.0, System.currentTimeMillis()).also { sdCard += it }
    }

    // 모의 촬영 모드(10.5.7): 단일·타이머·SMART(파노라마 없음). 타이머는 간격(실제 시간, 배속 없음)마다 모의 사진을 내보낸다
    override suspend fun photoModes(): PhotoModes = PhotoModes(setOf(ShootMode.SINGLE, ShootMode.TIMER, ShootMode.SMART))
    override suspend fun setShootMode(mode: ShootMode): Result<Unit> = Result.success(Unit).also { calls += "shootMode:$mode" }
    private var intervalStop: CompletableDeferred<Unit>? = null

    override fun intervalShots(intervalSec: Double): Flow<CaptureInfo> {
        val stop = CompletableDeferred<Unit>().also { intervalStop = it }
        return flow {
            captureLock.withLock {
                calls += "interval:$intervalSec"
                while (withTimeoutOrNull((intervalSec * 1000).toLong()) { stop.await() } == null) emit(newShot(Lens.WIDE, _telemetry.value.gimbalPitchDeg))
            }
        }
    }

    override suspend fun stopInterval(): Result<Unit> {
        intervalStop?.complete(Unit)
        return Result.success(Unit)
    }

    override suspend fun setExposureCompensation(ev: Double): Result<Unit> = Result.success(Unit).also {
        calls += "ev:$ev"
        _camera.update { it.copy(ev = if (abs(ev) < 0.05) "0" else "%+.1f".format(ev)) }
    }

    // 모의 카메라: 노출 띠 값은 그럴듯한 고정값, 초점 모드만 바뀐다(10.5.2)
    private val _camera = MutableStateFlow(CameraStatus("100", "1/640", "2.8", "0", false, FocusMode.AFS, 21_121, 5_400))
    override val cameraStatus: StateFlow<CameraStatus> = _camera.asStateFlow()
    override suspend fun setFocusMode(mode: FocusMode): Result<Unit> = Result.success(Unit).also { calls += "focusMode:$mode"; _camera.update { it.copy(focus = mode) } }
    override suspend fun focusAt(x: Double, y: Double): Result<Unit> = Result.success(Unit).also { calls += "focusAt" }
    override suspend fun setGimbal(down: Boolean): Result<Unit> = Result.success(Unit).also {
        calls += "gimbal:$down"
        _telemetry.update { it.copy(gimbalPitchDeg = if (down) -90.0 else 0.0) }
    }

    // 모의 카메라: 줌·영상 모드·녹화는 받기만 한다(영상 파일은 만들지 않음)
    override suspend fun setVideoLens(lens: Lens): Result<Unit> = Result.success(Unit).also { calls += "lens:$lens" }
    override suspend fun setZoom(ratio: Double): Result<Unit> = Result.success(Unit).also { calls += "zoom:$ratio" }
    override suspend fun setVideoMode(on: Boolean): Result<Unit> = Result.success(Unit).also { calls += "videoMode:$on" }
    override suspend fun setRecording(on: Boolean): Result<Unit> = Result.success(Unit).also { calls += "record:$on" }

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

    /** 홈포인트를 기체(또는 조종기) 지금 위치로. 위치를 모르면 실패. 그 뒤 RTH는 바뀐 텔레메트리 홈으로 간다. */
    override suspend fun setHome(toRc: Boolean): Result<Unit> {
        val t = _telemetry.value
        val at = (if (toRc) t.rcPosition else t.position) ?: return Result.failure(IllegalStateException(if (toRc) "조종기 위치 모름" else "기체 위치 모름"))
        calls += "setHome:${if (toRc) "rc" else "aircraft"}"
        _telemetry.update { it.copy(home = at) }
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

    /** 앱 재시작 직후처럼 비행 모드 값이 늦게 온다(재연결 알림 없이). */
    fun simulateFlightModeArrives(mode: FlightMode) = _telemetry.update { it.copy(flightMode = mode) }

    fun simulateLinkRestored() {
        _telemetry.update { it.copy(connected = true) }
        _events.tryEmit(DroneEvent.ConnectionRestored)
    }

    /** 기체 주도 RTH(저전압, RC RTH 버튼 등). */
    fun simulateAircraftRth() = startGoHome()

    fun setBattery(pct: Double) = _telemetry.update { it.copy(batteryPct = pct) }

    /** 장애물 주입(시뮬레이터에는 장애물이 없다). null이면 감지 값 없음(내비 "NA"). 처음은 감지 켬·장애물 없음. */
    fun setObstacles(o: Obstacles?) { _obstacles.value = o }
    private val _obstacles = MutableStateFlow<Obstacles?>(Obstacles(emptyMap()))
    override val obstacles: StateFlow<Obstacles?> = _obstacles.asStateFlow()

    // ---------------------------------------------------------------- 내부

    private fun connected() = _telemetry.value.connected

    private fun startGoHome() {
        motionJob?.cancel()
        _telemetry.update { it.copy(flightMode = FlightMode.GO_HOME) }
        _events.tryEmit(DroneEvent.GoHomeStarted)
        motionJob = scope.launch {
            val speed = 10.0
            climbTo(maxOf(_telemetry.value.altitudeM, preflightReport.rthAltitudeM), speed)
            moveTo(proj.toEnu(_telemetry.value.home ?: homePoint), speed)
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
