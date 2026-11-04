package com.example.myfirstapp.drone

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.data.OpLog
import android.util.Log
import com.example.myfirstapp.core.executor.MediaClassifier
import com.example.myfirstapp.core.executor.MediaOwner
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneEvent
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.LegMission
import com.example.myfirstapp.core.port.MissionStartRejected
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.Viewpoint
import com.example.myfirstapp.core.route.WpmlWriter
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraStreamSettingsInfo
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.sdk.keyvalue.value.camera.DateTime
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import dji.v5.manager.ldm.LDMManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import kotlin.coroutines.resume
import com.example.myfirstapp.core.model.FlightMode as AppFlightMode
import dji.sdk.keyvalue.value.flightcontroller.FlightMode as DjiFlightMode

/**
 * 실제 기체(M3E) 어댑터. 6.1 방식 A: 소티 KMZ 하나를 올려 계속 날고, 지점·사진 이벤트를 앱에 알린다.
 * 호출은 검증 화면 PoC(`DjiPoc`, 2026-10-30 시뮬레이터 확인)와 같다. 키 구독은 SDK 등록 뒤에 만들어야 한다.
 * [kmzDir]에 올린 KMZ를 남긴다(내보내기 `kmz/`, 4.3).
 */
class DjiDroneGateway(private val scope: CoroutineScope, private val kmzDir: File) : DroneGateway {
    private val keys = KeyManager.getInstance()
    private val missions = WaypointMissionManager.getInstance()
    private val media = MediaDataCenter.getInstance().mediaManager
    private val camera = ComponentIndexType.LEFT_OR_MAIN
    private val holder = Any()

    private val _telemetry = MutableStateFlow(Telemetry.DISCONNECTED)
    override val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()
    private val _events = MutableSharedFlow<DroneEvent>(extraBufferCapacity = 64)
    override val events: Flow<DroneEvent> = _events.asSharedFlow()

    // 미션·사진 콜백이 서로 다른 스레드에서 읽고 쓴다
    @Volatile private var missionName: String? = null
    @Volatile private var waypoint = -1

    /** 올린 KMZ의 웨이포인트(이동 고도 지점 포함, 5.6). 기체가 알리는 KMZ 번호를 촬영 지점 번호로 바꾼다. */
    @Volatile private var kmzWaypoints: List<WpmlWriter.Waypoint> = emptyList()
    /** 새 사진의 주인(화면 촬영·지점·RC 셔터)을 가른다(6.4 RC 셔터 규칙). 시각은 모두 조종기가 알림을 받은 때라 카메라 시계와 무관하다. */
    private val owners = MediaClassifier()
    private var lostJob: Job? = null
    private var lost = false

    /** 다시 붙었지만 비행 모드를 아직 몰라 재연결 알림을 미룬 중(실행기가 모드로 상태를 정하므로, 6.4). */
    private var restorePending = false
    private val newPhotos = MutableSharedFlow<CaptureInfo>(extraBufferCapacity = 16)
    private val mediaLock = Mutex()
    /** 기체가 FINISHED를 마지막 사진보다 먼저 알린다(2026-10-30 확인). 그 뒤 잠깐 들어온 사진도 미션 사진으로 넘긴다. */
    @Volatile private var photoGraceUntil = 0L

    /** 소티 미션의 촬영 렌즈(KMZ `payloadLensIndex` 순서): 한 번 찍으면 렌즈마다 파일이 하나씩 온다(5.6). */
    @Volatile private var sortieLenses: List<Lens> = listOf(Lens.WIDE)
    private var mediaEnabled = false

    /** 기체의 웨이라인 실행 상태(마지막 알림). 앱이 모르는 옛 미션이 남아 있는지 본다. */
    @Volatile private var executeState: WaypointMissionExecuteState? = null

    private val stateListener = WaypointMissionExecuteStateListener { s ->
        executeState = s
        Log.i(TAG, "미션 상태 $s"); OpLog.log("mission", s.name)
        when (s) {
            WaypointMissionExecuteState.INTERRUPTED -> _events.tryEmit(DroneEvent.PausedByRc)
            WaypointMissionExecuteState.FINISHED -> if (missionName != null) {
                missionName = null
                photoGraceUntil = System.currentTimeMillis() + AppConfig.Timeouts.PHOTO_GRACE_MS
                _events.tryEmit(DroneEvent.MissionFinished)
            }
            else -> Unit
        }
    }

    private val waylineListener = object : WaylineExecutingInfoListener {
        override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo) {
            if (missionName != null && info.currentWaypointIndex != waypoint) {
                waypoint = info.currentWaypointIndex
                // 이동 고도 지점은 알리지 않는다. 실행기는 촬영 지점 번호만 안다
                kmzWaypoints.getOrNull(waypoint)?.shot?.let {
                    owners.shotReached(it, System.currentTimeMillis())
                    _events.tryEmit(DroneEvent.WaypointReached(it))
                }
            }
        }

        override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
            if (error != null) Log.w(TAG, "미션 중단 이유: ${error.errorCode()} ${error.description()}")
        }
    }

    init {
        fun <T> on(info: DJIKeyInfo<T>, index: ComponentIndexType? = null, block: (T?) -> Unit) {
            val key = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
            keys.listen(key, holder) { _, v -> block(v) }
            block(keys.getValue(key))
        }
        on(FlightControllerKey.KeyConnection) { onConnection(it == true) }
        on(FlightControllerKey.KeyAircraftLocation3D) { p ->
            _telemetry.update { t -> if (p == null || !validGeo(p.latitude, p.longitude)) t else t.copy(position = GeoPoint(p.latitude, p.longitude), altitudeM = p.altitude) }
        }
        on(FlightControllerKey.KeyAircraftVelocity) { v ->
            // 속도는 북·동·아래(NED) 성분이라 수직 속도는 부호를 바꾼다
            _telemetry.update { it.copy(speedMps = v?.let { Math.hypot(v.x, v.y) } ?: 0.0, verticalSpeedMps = -(v?.z ?: 0.0)) }
        }
        on(FlightControllerKey.KeyGPSSatelliteCount) { n -> _telemetry.update { it.copy(satellites = n ?: 0) } }
        on(AirLinkKey.KeySignalQuality) { q -> _telemetry.update { it.copy(signalPct = q) } }
        on(BatteryKey.KeyChargeRemainingInPercent) { b -> _telemetry.update { it.copy(batteryPct = (b ?: 0).toDouble()) } }
        on(FlightControllerKey.KeyGPSSignalLevel) { g -> _telemetry.update { it.copy(gpsLevel = gpsLevel(g)) } }
        on(FlightControllerKey.KeyHomeLocation) { h ->
            _telemetry.update { it.copy(home = h?.takeIf { validGeo(h.latitude, h.longitude) }?.let { GeoPoint(h.latitude, h.longitude) }) }
        }
        on(FlightControllerKey.KeyFlightMode) { m -> onFlightMode(m) }
        on(FlightControllerKey.KeyIsLandingConfirmationNeeded) { n -> _telemetry.update { it.copy(landingConfirmNeeded = n == true) } }
        on(FlightControllerKey.KeyIsFlying) { flying ->
            val was = _telemetry.value.isFlying
            // 비행 모드는 이륙 판정과 함께 정한다(어느 쪽이 먼저 와도 다시 계산)
            if (flying != null) onFlightMode(keys.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode)))
            if (was && flying == false) _events.tryEmit(DroneEvent.Landed)
        }
        on(GimbalKey.KeyGimbalAttitude) { g -> _telemetry.update { it.copy(gimbalPitchDeg = g?.pitch ?: 0.0) } }
        on(FlightControllerKey.KeyCompassHeading) { h -> _telemetry.update { it.copy(headingDeg = ((h ?: 0.0) + 360) % 360) } }
        // 조종기 위치(지도 파란 점, 10.5.4)
        on(RemoteControllerKey.KeyRcGPSInfo) { g ->
            _telemetry.update { it.copy(rcPosition = g?.takeIf { g.isValid == true && validGeo(g.location?.latitude, g.location?.longitude) }?.let { GeoPoint(g.location.latitude, g.location.longitude) }) }
        }
        keys.listen(KeyTools.createKey(CameraKey.KeyNewlyGeneratedMediaFile, camera), holder) { _, info ->
            if (info == null) return@listen
            // 녹화한 영상은 SD에만 둔다(10.4.3). 지점·수동 사진으로 넘기지 않는다
            if (info.type == MediaFileType.MOV || info.type == MediaFileType.MP4) return@listen
            val t = _telemetry.value
            val now = System.currentTimeMillis()
            val capture = CaptureInfo(
                mediaId = info.index.toString(), lens = Lens.WIDE, position = t.position, altitudeM = t.altitudeM,
                gimbalPitchDeg = t.gimbalPitchDeg, headingDeg = t.headingDeg,
                takenAtMs = now,
            )
            val inMission = missionName != null || now < photoGraceUntil
            val shot = if (inMission) WpmlWriter.shotIndex(kmzWaypoints, waypoint) else null
            val (owner, file) = owners.classifyFile(now, shot)
            Log.i(TAG, "사진 #${info.index} 지점 $waypoint → $owner")
            OpLog.log("media", owner.name, mapOf("media" to info.index, "waypoint" to waypoint, "shot" to shot, "file" to file?.toString()))
            when (owner) {
                MediaOwner.SCREEN -> newPhotos.tryEmit(capture)
                // 같은 지점의 n번째 파일 = KMZ 렌즈 순서의 n번째(광각 → 망원). 실제 렌즈는 받을 때 파일 이름(_W/_Z)으로 다시 본다
                // 같은 동작 안 n번째 파일 = n번째 렌즈, 둘째 동작부터는 설비 조준 사진(시간 간격으로 가름, 2026-10-31 시뮬레이터는 동작마다 파일 1개)
                MediaOwner.WAYPOINT -> file?.let { f ->
                    _events.tryEmit(DroneEvent.PhotoTaken(f.shot, capture.copy(lens = sortieLenses[f.lensSlot % sortieLenses.size], equipment = f.action >= 1)))
                }
                MediaOwner.SHUTTER -> _events.tryEmit(DroneEvent.ShutterPhoto(capture))
                MediaOwner.NONE -> Unit
            }
        }
        keys.listen(KeyTools.createKey(RemoteControllerKey.KeyShutterButtonDown), holder) { _, down ->
            if (down == true) owners.shutterPressed(System.currentTimeMillis())
        }
        missions.addWaypointMissionExecuteStateListener(stateListener)
        missions.addWaylineExecutingInfoListener(waylineListener)
        media.setMediaFileDataSource(MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).setIndexType(camera).build())
    }

    /** 화면을 닫을 때 구독을 푼다. */
    fun close() {
        keys.cancelListen(holder)
        missions.removeWaypointMissionExecuteStateListener(stateListener)
        missions.removeWaylineExecutingInfoListener(waylineListener)
    }

    private fun onConnection(connected: Boolean) {
        val was = _telemetry.value.connected
        _telemetry.update { it.copy(connected = connected) }
        if (connected) {
            lostJob?.cancel()
            // 끊김 판정(3초) 전에 다시 붙어도, 처음부터 연결 없이 시작했다 붙어도 알린다(실행기는 연결 끊김 상태일 때만 받음).
            // 비행 모드 값이 온 뒤에 알린다
            if (lost || !was) {
                lost = false
                restorePending = true
                maybeRestored()
            }
        } else if (lostJob?.isActive != true) {
            // 3초 넘게 끊겨야 연결 끊김으로 본다(6.4)
            lostJob = scope.launch {
                delay(AppConfig.Timeouts.LINK_LOST_MS)
                lost = true
                _events.tryEmit(DroneEvent.ConnectionLost)
            }
        }
    }

    private fun onFlightMode(m: DjiFlightMode?) {
        val mode = when (m) {
            null, DjiFlightMode.UNKNOWN -> AppFlightMode.UNKNOWN
            DjiFlightMode.WAYPOINT -> AppFlightMode.MISSION
            DjiFlightMode.GO_HOME -> AppFlightMode.GO_HOME
            DjiFlightMode.AUTO_TAKE_OFF -> AppFlightMode.TAKING_OFF
            DjiFlightMode.AUTO_LANDING, DjiFlightMode.FORCE_LANDING, DjiFlightMode.ATTI_LANDING -> AppFlightMode.LANDING
            // 앱을 막 다시 켜면 SDK 값이 아직 없다: 이륙 여부를 모르면 땅으로 보지 않고 UNKNOWN(2026-10-31 시뮬레이터, 떠 있는데 소티를 닫음)
            else -> when (keys.getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying))) {
                true -> AppFlightMode.MANUAL
                false -> AppFlightMode.ON_GROUND
                null -> AppFlightMode.UNKNOWN
            }
        }
        val before = _telemetry.value.flightMode
        _telemetry.update { it.copy(flightMode = mode) }
        if (mode == AppFlightMode.GO_HOME && before != AppFlightMode.GO_HOME) _events.tryEmit(DroneEvent.GoHomeStarted)
        maybeRestored()
    }

    private fun maybeRestored() {
        val t = _telemetry.value
        if (!restorePending || !t.connected || t.flightMode == AppFlightMode.UNKNOWN) return
        restorePending = false
        _events.tryEmit(DroneEvent.ConnectionRestored)
    }

    private fun gpsLevel(g: GPSSignalLevel?) = when (g) {
        GPSSignalLevel.LEVEL_1 -> 1
        GPSSignalLevel.LEVEL_2 -> 2
        GPSSignalLevel.LEVEL_3 -> 3
        GPSSignalLevel.LEVEL_4 -> 4
        GPSSignalLevel.LEVEL_5, GPSSignalLevel.LEVEL_10 -> 5
        else -> 0
    }

    private suspend fun <T> read(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = withTimeoutOrNull(AppConfig.Timeouts.GATEWAY_KEY_READ_MS) {
        val key = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        runCatching { djiValue<T> { keys.getValue(key, it) } }.getOrNull()
    }

    override suspend fun preflight(): PreflightReport {
        val t = _telemetry.value
        val home = read(FlightControllerKey.KeyHomeLocation)?.takeIf { validGeo(it.latitude, it.longitude) }
        return PreflightReport(
            connected = t.connected,
            gpsSatellites = read(FlightControllerKey.KeyGPSSatelliteCount) ?: 0,
            gpsLevel = t.gpsLevel,
            homeRecorded = read(FlightControllerKey.KeyIsHomeLocationSet) == true,
            batteryPct = t.batteryPct,
            batteryTempC = read(BatteryKey.KeyBatteryTemperature) ?: 0.0,
            sdFreeMb = (read(CameraKey.KeySDCardRemainSpace, camera) ?: 0).toLong(),
            ldmEnabled = LDMManager.getInstance().isLDMEnabled,
            obstacleAvoidanceOn = runCatching { djiValue<Boolean> { PerceptionManager.getInstance().getOverallObstacleAvoidanceEnabled(it) } }.getOrDefault(false),
            rthAltitudeM = (read(FlightControllerKey.KeyGoHomeHeight) ?: 0).toDouble(),
            lostLinkActionIsRth = read(FlightControllerKey.KeyFailsafeAction) == FailsafeAction.GOHOME,
            home = home?.let { GeoPoint(it.latitude, it.longitude) },
        )
    }

    // ponytail: 방식 A는 KMZ의 이륙 안전 고도(takeOffSecurityHeight)로 미션이 직접 이륙한다. 방식 B를 붙일 때 KeyStartTakeoff로 바꾼다.
    override suspend fun takeoff(): Result<Unit> = Result.success(Unit)

    override suspend fun startLeg(leg: LegMission): Result<Unit> = Result.failure(UnsupportedOperationException("방식 B 미구현(6.1)"))

    override suspend fun startSortie(points: List<Viewpoint>, params: FlightParams): Result<Unit> = runCatching {
        kmzDir.mkdirs()
        val previousKmz = lastKmzName() // 새 KMZ를 쓰기 전에(옛 미션을 끝낼 때 쓸 이름)
        val name = "sortie_${System.currentTimeMillis()}"
        val kmz = File(kmzDir, "$name.kmz")
        // 시작 위치: 떠 있으면 지금 자리, 땅이면 이륙 지점. 복귀는 홈 지점(5.6 이동 고도 구간)
        val t = _telemetry.value
        val wps = kmz.outputStream().use { WpmlWriter.write(points, params, it, start = t.position ?: t.home, home = t.home ?: t.position) }
        // DJI 검사기로 먼저 본다. 고도 모드(지형 추적) 등이 맞지 않으면 올리지 않고 이유를 알린다
        checkKmz(kmz.path).filterNot { it.startsWith("검사 불가") }.takeIf { it.isNotEmpty() }?.let { error("KMZ 검사 실패: ${it.joinToString()}") }
        // 기체에 앱이 모르는 옛 미션이 중단·실행 상태로 남아 있으면(앱 재시작, GPS 약함으로 중단 등) 새로 올리기가 "기기 상태 오류"로 거절된다(2026-10-31 시뮬레이터)
        val stale = executeState
        if (missionName == null && stale in STALE_STATES) previousKmz?.let { old ->
            val r = runCatching { djiDone { missions.stopMission(old, it) } }
            OpLog.log("mission", "stopStale", mapOf("name" to old, "state" to stale?.name, "ok" to r.isSuccess, "error" to r.exceptionOrNull()?.message))
        }
        suspendCancellableCoroutine { c ->
            missions.pushKMZFileToAircraft(kmz.path, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                override fun onProgressUpdate(progress: Double) {}
                override fun onSuccess() = c.resume(Unit)
                override fun onFailure(error: IDJIError) = c.resumeWith(Result.failure(DjiException(error)))
            })
        }
        waypoint = -1
        kmzWaypoints = wps
        sortieLenses = params.lenses.distinct().ifEmpty { listOf(Lens.WIDE) }
        // 망원 사진 배율(기본 7배, 10-30 사용자 결정). 실패해도 미션은 간다(망원은 지금 배율로 찍힘)
        if (Lens.ZOOM in sortieLenses) setZoom(params.zoomRatio).onFailure { OpLog.log("error", "sortieZoom", mapOf("message" to it.message)) }
        setCaptureLenses(sortieLenses)
        // 지점마다 오는 파일 수: 렌즈 수, 설비 조준 사진이 있으면 두 번(5.6)
        val lensCount = sortieLenses.size
        owners.sortieStarted { shot -> lensCount * (if (points.getOrNull(shot)?.equipmentPitchDeg != null) 2 else 1) }
        missionName = name
        // 올라간 뒤 시작만 거절되면 같은 KMZ로 다시 해도 같다(지형 추적, 2026-10-30 시뮬레이터 "Unknown error")
        runCatching { djiDone { missions.startMission(name, it) } }.onFailure { throw MissionStartRejected("미션 시작 거절: ${it.message}", it) }
        Log.i(TAG, "소티 미션 시작 $name 촬영 지점 ${points.size}개, 웨이포인트 ${wps.size}개")
        Unit
    }.onFailure { missionName = null; Log.w(TAG, "소티 미션 실패: ${it.message}"); OpLog.log("error", "startSortie", mapOf("message" to it.message)) }

    /** 앱 재시작으로 이름을 잃은 채 멈춘 옛 미션(마지막 KMZ 이름). 다음 [stopMission]이 기체 모드와 상관없이 이 이름으로 끝낸다. */
    @Volatile private var recoveredMission: String? = null

    override suspend fun pauseMission(): Result<Unit> = runCatching {
        if (missionName == null) recoveredMission = lastKmzName()
        djiDone { missions.pauseMission(it) }
    }

    override suspend fun resumeMission(): Result<Unit> = runCatching {
        check(missionName != null) { "올린 미션 없음" }
        djiDone { missions.resumeMission(it) }
    }

    override suspend fun stopMission(): Result<Unit> {
        // 앱이 다시 시작되면 이름을 잃는다: 기체가 미션 중이면 마지막으로 올린 KMZ 이름으로 멈춘다(6.4 복구)
        val name = missionName ?: recoveredMission ?: lastKmzName()?.takeIf { _telemetry.value.flightMode == AppFlightMode.MISSION }
            ?: return Result.success(Unit)
        missionName = null
        recoveredMission = null
        // 멈추기 직전 지점에서 찍힌 사진이 늦게 와도 지점 사진으로 받는다(RTH·재업로드 때, 미션 완료와 같은 5초)
        photoGraceUntil = System.currentTimeMillis() + AppConfig.Timeouts.PHOTO_GRACE_MS
        return runCatching { djiDone { missions.stopMission(name, it) } }
    }

    /** 화면 수동 촬영: 찍고 새 사진 알림을 기다린다. 그 동안 첫 파일은 이 촬영 것이다(지점·셔터로 넘기지 않음). */
    override suspend fun capture(lens: Lens, gimbalPitchDeg: Double, zoomRatio: Double): Result<CaptureInfo> = runCatching {
        owners.screenStarted()
        try {
            djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, camera), it) }
            withTimeoutOrNull(AppConfig.Timeouts.NEW_PHOTO_MS) { newPhotos.first() } ?: error("사진 알림 없음")
        } finally {
            owners.screenEnded(System.currentTimeMillis())
        }
    }

    /** SD 최신 목록에서 사진 번호([CaptureInfo.mediaId])로 찾아 받는다. 막 찍힌 사진은 목록에 늦게 올라오므로 몇 번 다시 본다. */
    override suspend fun download(capture: CaptureInfo, mode: DownloadMode): Result<ByteArray> = runCatching {
        mediaLock.withLock {
            val index = capture.mediaId.toInt()
            var file: MediaFile? = null
            repeat(AppConfig.Polling.DOWNLOAD_TRIES) {
                if (file == null) {
                    pullRecent(AppConfig.Polling.DOWNLOAD_RECENT_COUNT)
                    file = media.mediaFileListData.data.firstOrNull { it.fileIndex == index }
                    if (file == null) delay(AppConfig.Polling.DOWNLOAD_RETRY_MS)
                }
            }
            // 같은 번호에 렌즈별 파일이 여럿이면 파일 이름 끝(_W/_Z/_T)으로 고른다
            val same = media.mediaFileListData.data.filter { it.fileIndex == index }
            val f = same.firstOrNull { lensOfName(it.fileName) == capture.lens } ?: file ?: error("SD에서 사진 #$index 못 찾음")
            val named = lensOfName(f.fileName)
            OpLog.log("media", "download", mapOf("media" to index, "name" to f.fileName, "lens" to capture.lens.name, "same" to same.size))
            if (named != null && named != capture.lens) Log.w(TAG, "렌즈 불일치 #$index ${f.fileName}: 기록 ${capture.lens}")
            if (mode == DownloadMode.PREVIEW) {
                val bmp = djiValue<android.graphics.Bitmap> { f.pullPreviewFromCamera(it) }
                ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, AppConfig.Polling.PREVIEW_JPEG_QUALITY, it) }.toByteArray()
            } else {
                val out = ByteArrayOutputStream()
                suspendCancellableCoroutine { c ->
                    f.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
                        override fun onStart() {}
                        override fun onProgress(total: Long, current: Long) {}
                        override fun onRealtimeDataUpdate(data: ByteArray, position: Long) = out.write(data)
                        override fun onFinish() = c.resume(Unit)
                        override fun onFailure(error: IDJIError) = c.resumeWith(Result.failure(DjiException(error)))
                    })
                }
                out.toByteArray()
            }
        }
    }.onFailure { Log.w(TAG, "다운로드 실패 #${capture.mediaId}: ${it.message}"); OpLog.log("error", "download", mapOf("media" to capture.mediaId, "message" to it.message)) }

    override suspend fun listMedia(sinceMs: Long): Result<List<CaptureInfo>> = runCatching {
        mediaLock.withLock {
            pullRecent(AppConfig.Polling.LIST_MEDIA_COUNT)
            media.mediaFileListData.data.map { f ->
                CaptureInfo(f.fileIndex.toString(), Lens.WIDE, null, 0.0, 0.0, 0.0, millis(f.date))
            }.filter { it.takenAtMs >= sinceMs }
        }
    }

    override suspend fun returnHome(): Result<Unit> = runCatching {
        djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(FlightControllerKey.KeyStartGoHome), it) }
        Unit
    }

    /**
     * SD 최신 목록을 받는다. 비행 중에는 미디어 모드 없이 응답이 오지 않을 수 있어(2026-10-30 시뮬레이터), 10초 안에 답이 없으면
     * 미디어 모드를 켜고 다시 받는다. 어느 쪽으로 됐는지 로그로 남긴다.
     */
    private suspend fun pullRecent(count: Int) {
        val param = PullMediaFileListParam.Builder().mediaFileIndex(-1).count(count).build()
        if (!mediaEnabled) {
            val ok = withTimeoutOrNull(AppConfig.Timeouts.SD_LIST_MS) { djiDone { media.pullMediaFileListFromCamera(param, it) } } != null
            if (ok) return Log.i(TAG, "SD 목록(미디어 모드 없이) ${media.mediaFileListData.data.size}개").let {}
            Log.w(TAG, "SD 목록 응답 없음 → 미디어 모드 켬")
            runCatching { djiDone { media.enable(it) } }.onFailure { Log.w(TAG, "미디어 모드 실패: ${it.message}") }
            mediaEnabled = true
        }
        withTimeoutOrNull(AppConfig.Timeouts.SD_LIST_MEDIA_MODE_MS) { djiDone { media.pullMediaFileListFromCamera(param, it) } } ?: error("SD 목록 응답 없음")
        Log.i(TAG, "SD 목록(미디어 모드) ${media.mediaFileListData.data.size}개")
    }

    override suspend fun setVideoLens(lens: Lens): Result<Unit> = runCatching {
        val source = if (lens == Lens.ZOOM) CameraVideoStreamSourceType.ZOOM_CAMERA else CameraVideoStreamSourceType.WIDE_CAMERA
        djiDone { keys.setValue(KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, camera), source, it) }
    }

    override suspend fun toggleGimbal(): Result<Unit> = runCatching {
        djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(GimbalKey.KeyGimbalReset), GimbalResetType.PITCH_UP_OR_DOWN, it) }
        Unit
    }

    /** 망원 렌즈 줌(M3E 망원은 같은 카메라 [camera]의 ZOOM 렌즈). */
    override suspend fun setZoom(ratio: Double): Result<Unit> = runCatching {
        djiDone { keys.setValue(KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios, camera, CameraLensType.CAMERA_LENS_ZOOM), ratio, it) }
    }

    override suspend fun setVideoMode(on: Boolean): Result<Unit> = runCatching {
        djiDone { keys.setValue(KeyTools.createKey(CameraKey.KeyCameraMode, camera), if (on) CameraMode.VIDEO_NORMAL else CameraMode.PHOTO_NORMAL, it) }
    }

    override suspend fun setRecording(on: Boolean): Result<Unit> = runCatching {
        djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(if (on) CameraKey.KeyStartRecord else CameraKey.KeyStopRecord, camera), it) }
        Unit
    }

    override suspend fun confirmLanding(): Result<Unit> = runCatching {
        djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(FlightControllerKey.KeyConfirmLanding), it) }
        Unit
    }

    private fun millis(d: DateTime?): Long = if (d == null) 0 else Calendar.getInstance().apply {
        set(d.year, d.month - 1, d.day, d.hour, d.minute, d.second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * 사진을 저장할 렌즈(Pilot 2 "사진 저장" 설정, `KeyCaptureCameraStreamSettings`). 2026-10-31 시뮬레이터에서 KMZ `wide,zoom`만으로는
     * 지점마다 파일 1개(`_V`)만 생겨 카메라 쪽 설정도 맞춘다. 실패해도 미션은 간다(그때는 카메라 설정대로 찍힘). 앞뒤 값을 로그에 남긴다.
     */
    private suspend fun setCaptureLenses(lenses: List<Lens>) {
        val key = KeyTools.createKey(CameraKey.KeyCaptureCameraStreamSettings, camera)
        val before = runCatching { keys.getValue(key)?.toJson()?.toString() }.getOrNull()
        val sources = lenses.mapNotNull {
            when (it) {
                Lens.WIDE -> CameraVideoStreamSourceType.WIDE_CAMERA
                Lens.ZOOM -> CameraVideoStreamSourceType.ZOOM_CAMERA
                Lens.THERMAL -> CameraVideoStreamSourceType.INFRARED_CAMERA
            }
        }
        val result = runCatching { djiDone { keys.setValue(key, CameraStreamSettingsInfo(false, sources), it) } }
        OpLog.log("camera", "captureLenses", mapOf("before" to before, "set" to sources.joinToString { it.name }, "ok" to result.isSuccess, "error" to result.exceptionOrNull()?.message))
    }

    /** 마지막으로 만든 소티 KMZ의 미션 이름(`sortie_<시각>`). */
    private fun lastKmzName(): String? = kmzDir.listFiles { f -> f.name.startsWith("sortie_") && f.extension == "kmz" }?.maxByOrNull { it.lastModified() }?.nameWithoutExtension

    /**
     * DJI 파일 이름의 렌즈 글자(`DJI_..._0001_W.JPG`, 웨이라인 `fileSuffix`가 붙으면 `..._0001_W_<접미>.JPG`일 수 있음). 모르면 null.
     * ponytail: 실기체 SD 이름으로 확인 전. 렌즈 글자 뒤 접미가 W/Z/T 한 글자로 시작하는 조각이면 잘못 읽을 수 있다.
     */
    private fun lensOfName(name: String?): Lens? = when (name?.let { Regex("_([WZT])(?=[_.])", RegexOption.IGNORE_CASE).find(it) }?.groupValues?.get(1)?.uppercase()) {
        "W" -> Lens.WIDE
        "Z" -> Lens.ZOOM
        // M3T는 _T가 열화상, M3E는 망원이 _T일 수 있다(현장 확인 전): 열화상을 찍는 소티가 아니면 망원으로 본다
        "T" -> if (Lens.THERMAL in sortieLenses) Lens.THERMAL else Lens.ZOOM
        else -> null
    }

    /**
     * 충돌 경고(13번): `PerceptionManager` 장애물 데이터. 거리는 mm, 수평은 기수부터 시계 방향 [ObstacleData.getHorizontalAngleInterval]°마다.
     * 모으는 동안만 구독한다. 다른 작업과 겹치지 않게 import 없이 전체 이름으로 쓴다.
     */
    override val obstacles: Flow<com.example.myfirstapp.core.model.Obstacles?> = kotlinx.coroutines.flow.callbackFlow {
        val pm = PerceptionManager.getInstance()
        val l = dji.v5.manager.aircraft.perception.listener.ObstacleDataListener { d ->
            // 시뮬레이터에서는 실제 기체가 책상 위라 센서가 주변 물건을 0.5m로 계속 본다(2026-10-31): 경고하지 않는다
            if (djiSimulatorOn()) return@ObstacleDataListener run { trySend(null) }
            fun m(mm: Int?) = mm?.takeIf { it > 0 }?.div(1000.0)
            trySend(com.example.myfirstapp.core.model.Obstacles.fromHorizontal(
                d.horizontalObstacleDistance.orEmpty().map { m(it) ?: 0.0 }, d.horizontalAngleInterval,
                m(d.upwardObstacleDistance), m(d.downwardObstacleDistance),
            ))
        }
        pm.addObstacleDataListener(l)
        try { kotlinx.coroutines.awaitCancellation() } finally { pm.removeObstacleDataListener(l) }
    }

    companion object {
        const val TAG = "DjiDrone"

        /** 새 미션을 올리기 전에 끝내야 하는 웨이라인 상태. */
        private val STALE_STATES = setOf(
            WaypointMissionExecuteState.INTERRUPTED, WaypointMissionExecuteState.EXECUTING, WaypointMissionExecuteState.RECOVERING,
            WaypointMissionExecuteState.ENTER_WAYLINE, WaypointMissionExecuteState.PREPARING,
        )
    }
}
