package com.example.myfirstapp.drone

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.data.OpLog
import android.util.Log
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.executor.MediaClassifier
import com.example.myfirstapp.core.executor.MediaOwner
import com.example.myfirstapp.core.model.CameraStatus
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FocusMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.flightModeLetter
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.PhotoModes
import com.example.myfirstapp.core.model.ShootMode
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
import dji.sdk.keyvalue.value.camera.CameraExposureCompensation
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraShootPhotoMode
import dji.sdk.keyvalue.value.camera.CameraStreamSettingsInfo
import dji.sdk.keyvalue.value.camera.PhotoIntervalShootSettings
import dji.sdk.keyvalue.value.camera.DCFCameraType
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.camera.CameraFocusMode
import dji.sdk.keyvalue.value.common.DoublePoint2D
import dji.sdk.keyvalue.value.flightcontroller.WindDirection
import dji.sdk.keyvalue.value.camera.DateTime
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
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
    private val _camera = MutableStateFlow(CameraStatus())
    override val cameraStatus: StateFlow<CameraStatus> = _camera.asStateFlow()

    // 미션·사진 콜백이 서로 다른 스레드에서 읽고 쓴다
    @Volatile private var missionName: String? = null
    @Volatile private var waypoint = -1

    /** 올린 KMZ의 웨이포인트(이동 고도 지점 포함, 5.6). 기체가 알리는 KMZ 번호를 촬영 지점 번호로 바꾼다. */
    @Volatile private var kmzWaypoints: List<WpmlWriter.Waypoint> = emptyList()
    /** 새 사진의 주인(앱 촬영·지점)을 가른다(6.4 사진 주인 가르기). 시각은 모두 조종기가 알림을 받은 때라 카메라 시계와 무관하다. */
    private val owners = MediaClassifier()
    private var lostJob: Job? = null
    private var lost = false

    /** 다시 붙었지만 비행 모드를 아직 몰라 재연결 알림을 미룬 중(실행기가 모드로 상태를 정하므로, 6.4). */
    private var restorePending = false
    private val newPhotos = MutableSharedFlow<CaptureInfo>(extraBufferCapacity = 16)
    private val mediaLock = Mutex()
    /** 앱 촬영은 하나씩(화면 셔터와 RC 셔터가 겹치면 둘 다 같은 첫 새 사진 알림을 받아 하나는 잃음). */
    private val captureLock = Mutex()
    /** 기체가 FINISHED를 마지막 사진보다 먼저 알린다(2026-10-30 확인). 그 뒤 잠깐 들어온 사진도 미션 사진으로 넘긴다. */
    @Volatile private var photoGraceUntil = 0L

    /** 소티 미션의 촬영 렌즈(KMZ 순서). 파일 렌즈 글자 `_T`를 열화상·망원 중 무엇으로 볼지 정할 때 쓴다. */
    @Volatile private var sortieLenses: List<Lens> = listOf(Lens.WIDE)
    private var mediaEnabled = false

    /** 지금 카메라 모드(`KeyCameraMode` 알림, init에서 처음 값을 넣으므로 init보다 앞에 둔다). */
    @Volatile private var cameraMode: CameraMode? = null

    /** 앱이 고른 사진 모드(단일·SMART, 10.5.7). SMART면 사진 알림을 오래 기다린다. */
    @Volatile private var shootMode = ShootMode.SINGLE

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
            if (error != null) {
                Log.w(TAG, "미션 중단 이유: ${error.errorCode()} ${error.description()}")
                OpLog.log("mission", "interruptReason", mapOf("error" to DjiException(error).detail))
            }
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
        on(FlightControllerKey.KeyGPSSignalLevel) { g -> _telemetry.update { it.copy(gpsLevel = gpsLevelOf(g)) } }
        on(FlightControllerKey.KeyHomeLocation) { h ->
            _telemetry.update { it.copy(home = h?.takeIf { validGeo(h.latitude, h.longitude) }?.let { GeoPoint(h.latitude, h.longitude) }) }
        }
        on(FlightControllerKey.KeyFlightMode) { m -> onFlightMode(m) }
        on(FlightControllerKey.KeyAreMotorsOn) { m -> _telemetry.update { it.copy(motorsOn = m == true) } }
        on(FlightControllerKey.KeyIsLandingConfirmationNeeded) { n -> _telemetry.update { it.copy(landingConfirmNeeded = n == true) } }
        on(FlightControllerKey.KeyIsFlying) { flying ->
            val was = _telemetry.value.isFlying
            // 비행 모드는 이륙 판정과 함께 정한다(어느 쪽이 먼저 와도 다시 계산)
            if (flying != null) onFlightMode(keys.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode)))
            if (was && flying == false) _events.tryEmit(DroneEvent.Landed)
        }
        on(GimbalKey.KeyGimbalAttitude) { g -> _telemetry.update { it.copy(gimbalPitchDeg = g?.pitch ?: 0.0) } }
        // AR 투영(10.5.2)의 카메라 방위 = 기수 + 이 값. ponytail: 기수 기준 키라 절대 요(KeyGimbalAttitude.yaw)보다 안전하다고 봄, 부호·값은 실기 확인
        on(GimbalKey.KeyYawRelativeToAircraftHeading) { y -> _telemetry.update { it.copy(gimbalYawRelDeg = y ?: 0.0) } }
        // Pilot 2 상단 바·배터리 띠·내비(10.5.1, 10.5.3)
        on(BatteryKey.KeyVoltage) { v -> _telemetry.update { it.copy(batteryVoltageV = v?.div(1000.0)) } }
        on(BatteryKey.KeyBatteryTemperature) { c -> _telemetry.update { it.copy(batteryTempC = c) } }
        on(FlightControllerKey.KeyLowBatteryWarningThreshold) { p -> _telemetry.update { it.copy(lowBatteryPct = p) } }
        on(FlightControllerKey.KeySeriousLowBatteryWarningThreshold) { p -> _telemetry.update { it.copy(criticalBatteryPct = p) } }
        on(FlightControllerKey.KeyGoHomeAssessment) { a ->
            _telemetry.update { it.copy(rthBatteryPct = a?.batteryPercentNeededToGoHome, landBatteryPct = a?.batteryPercentNeededToLand, remainingFlightSec = a?.remainingFlightTime) }
        }
        // ponytail: 풍속 단위를 0.1 m/s로 본다(MSDK 문서에 단위 없음). 실기에서 Pilot 2 WS와 대조한다
        on(FlightControllerKey.KeyWindSpeed) { w -> _telemetry.update { it.copy(windSpeedMps = w?.div(10.0)) } }
        on(FlightControllerKey.KeyWindDirection) { d -> _telemetry.update { it.copy(windDirDeg = windDeg(d)) } }
        on(FlightControllerKey.KeyHeightAboveSeaLevel) { h -> _telemetry.update { it.copy(altitudeAslM = h?.height) } }
        // 상단 바 비행 모드(10.5.10): 스위치 F는 다중 비행 모드·T/N/S·A/N/S 설정에 따라 T 또는 A
        var rcSwitch: Char? = null
        var multiMode: Boolean? = null
        var fMode: Char? = null
        fun rcMode() = _telemetry.update { it.copy(rcMode = flightModeLetter(rcSwitch, multiMode, fMode)) }
        on(FlightControllerKey.KeyRemoteControllerFlightMode) { m -> rcSwitch = m.letter(); rcMode() }
        on(FlightControllerKey.KeyMultipleFlightModeEnabled) { m -> multiMode = m; rcMode() }
        on(FlightControllerKey.KeyCustomFunctionMode) { m -> fMode = m.fLetter(); rcMode() }
        // 노출 띠(10.5.2). ponytail: 렌즈를 정하지 않은 키(기본 렌즈)로 읽는다. 망원 보기에서 값이 광각 것이면 렌즈별 키로 바꾼다(실기 확인)
        on(CameraKey.KeyExposureSettings, camera) { e ->
            _camera.update {
                it.copy(iso = e?.iso?.toString(), shutter = shutterLabel(e?.shutterSpeed?.name), aperture = apertureLabel(e?.aperture?.name), ev = evLabel(e?.exposureCompensation?.name))
            }
        }
        on(CameraKey.KeyAELockEnabled, camera) { l -> _camera.update { it.copy(aeLocked = l == true) } }
        on(CameraKey.KeyCameraFocusMode, camera) { m -> _camera.update { it.copy(focus = focusOf(m)) } }
        on(CameraKey.KeySDCardAvailablePhotoCount, camera) { n -> _camera.update { it.copy(photosLeft = n) } }
        on(CameraKey.KeySDCardAvailableVideoDuration, camera) { n -> _camera.update { it.copy(recordSecLeft = n) } }
        on(CameraKey.KeyCameraMode, camera) { m -> cameraMode = m }
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
            // 파일 종류(_W 광각, _Z 망원, _T 열화상). _V(가시광, 렌즈 모름)는 null → 동작 순서로 본다
            val lens = when (info.dcf_type) {
                DCFCameraType.WIDE -> Lens.WIDE
                DCFCameraType.ZOOM -> Lens.ZOOM
                DCFCameraType.INFRARED -> Lens.THERMAL
                else -> null
            }
            val (owner, file) = owners.classifyFile(now, shot, lens)
            Log.i(TAG, "사진 #${info.index} 지점 $waypoint → $owner")
            OpLog.log("media", owner.name, mapOf("media" to info.index, "dcf" to info.dcf_type?.name, "waypoint" to waypoint, "shot" to shot, "file" to file?.toString()))
            when (owner) {
                MediaOwner.SCREEN -> newPhotos.tryEmit(capture)
                // 같은 지점의 n번째 파일 = n번째 촬영 동작(광각 → 망원 → 설비). 실제 렌즈는 받을 때 파일 이름(_W/_Z)으로 다시 본다
                MediaOwner.WAYPOINT -> file?.let { f -> _events.tryEmit(DroneEvent.PhotoTaken(f.shot, capture.copy(lens = f.lens, equipment = f.equipment))) }
                MediaOwner.NONE -> Unit
            }
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

    override suspend fun preflight(): PreflightReport = djiPreflightReport()

    // ponytail: 방식 A는 KMZ의 이륙 안전 고도(takeOffSecurityHeight)로 미션이 직접 이륙한다. 방식 B를 붙일 때 KeyStartTakeoff로 바꾼다.
    override suspend fun takeoff(): Result<Unit> = Result.success(Unit)

    override suspend fun startLeg(leg: LegMission): Result<Unit> = Result.failure(UnsupportedOperationException("방식 B 미구현(6.1)"))

    /** 방식 A 한 번에: 올리고 바로 시작(재개·복구·이륙 기준 다시 올리기). 체크리스트는 [uploadSortie] → [startUploaded] 두 단계(10.5.5). */
    override suspend fun startSortie(points: List<Viewpoint>, params: FlightParams): Result<Unit> =
        uploadSortie(points, params).mapCatching { startUploaded().getOrThrow() }

    /** [uploadSortie]로 올리고 아직 시작하지 않은 미션 이름과 그 고도 방식(시작 실패 로그용). */
    @Volatile private var uploadedName: String? = null
    @Volatile private var uploadedTerrain = false

    override suspend fun uploadSortie(points: List<Viewpoint>, params: FlightParams): Result<Unit> = runCatching {
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
        if (Lens.ZOOM in sortieLenses) setZoom(params.zoomRatio).onFailure { OpLog.log("error", "sortieZoom", mapOf("message" to it.djiDetail())) }
        // 렌즈별 동작이면 동작마다 파일 1장: 카메라 렌즈 동시 저장이 켜져 있으면 끄고, 못 끄면 동작마다 그 렌즈들 파일을 기다린다.
        // 한 동작에 렌즈를 묶는 옛 방식만 카메라 렌즈 동시 저장을 맞추고, 거절되면 동작당 1장으로 본다(11-03 현장)
        val perLens = Tuning.Photo.ACTION_PER_LENS
        val stuck = if (perLens) singleCaptureLens() else null
        val multiFile = !perLens && sortieLenses.size > 1 && setCaptureLenses(sortieLenses)
        owners.sortieStarted { shot ->
            points.getOrNull(shot)?.let { v ->
                WpmlWriter.photoActions(v, params, perLens).map {
                    when {
                        stuck != null -> it.copy(lenses = stuck)
                        perLens || multiFile -> it
                        else -> it.copy(lenses = it.lenses.take(1))
                    }
                }
            }.orEmpty()
        }
        OpLog.log("mission", "photoActions", mapOf("perLens" to perLens, "multiFile" to multiFile, "stuck" to stuck?.joinToString(), "first" to points.firstOrNull()?.let { WpmlWriter.photoActions(it, params, perLens).size }))
        params.photoFolder?.takeIf { it.isNotBlank() }?.let { setPhotoFolder(it) }
        uploadedName = name
        uploadedTerrain = params.terrainFollow
        Log.i(TAG, "소티 미션 올림 $name 촬영 지점 ${points.size}개, 웨이포인트 ${wps.size}개")
        Unit
    }.onFailure { e ->
        uploadedName = null
        logMissionFailure("uploadSortie", e, params.terrainFollow)
    }

    override suspend fun startUploaded(): Result<Unit> = runCatching {
        val name = uploadedName ?: error("올린 미션 없음")
        missionName = name
        // 올라간 뒤 시작만 거절되면 같은 KMZ로 다시 해도 같다(지형 추적, 2026-10-30 시뮬레이터 "Unknown error")
        runCatching { djiDone { missions.startMission(name, it) } }.onFailure { throw MissionStartRejected("미션 시작 거절: ${it.message}", it) }
        uploadedName = null
        Log.i(TAG, "소티 미션 시작 $name")
        Unit
    }.onFailure { e ->
        missionName = null
        logMissionFailure("startSortie", e, uploadedTerrain)
    }

    /** 거절 이유를 알 수 있게 SDK 오류 코드 전체와 올린 KMZ의 DJI 검사 결과를 남긴다(11-03 현장 "Unknown error", 4.4). */
    private fun logMissionFailure(what: String, e: Throwable, terrainFollow: Boolean) {
        Log.w(TAG, "소티 미션 실패($what): ${e.message}")
        val kmz = lastKmzName()?.let { File(kmzDir, "$it.kmz") }
        OpLog.log(
            "error", what,
            mapOf(
                "message" to e.message, "sdk" to (e.cause ?: e).djiDetail(), "kmz" to kmz?.name,
                "kmzCheck" to kmz?.let { checkKmz(it.path).ifEmpty { listOf("NoError") }.joinToString() },
                "terrainFollow" to terrainFollow,
            ),
        )
    }

    /**
     * 체크리스트 폴더 만들기(10.5.5): SD 사진 폴더 이름 뒤에 붙는 사용자 이름(DJI 맞춤 폴더 이름). 실패해도 미션은 간다.
     * ponytail: priority 값의 뜻과 한글 이름이 그대로 쓰이는지는 실기 확인 전(Pilot 2는 한글 폴더 "경천1"을 만든다).
     */
    private suspend fun setPhotoFolder(folder: String) {
        val r = runCatching {
            djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) {
                keys.setValue(
                    KeyTools.createKey(CameraKey.KeyCustomExpandDirectoryNameSettings, camera),
                    dji.sdk.keyvalue.value.camera.CustomExpandNameSettings(
                        dji.sdk.keyvalue.value.common.EnCodingType.UTF8, dji.sdk.keyvalue.value.common.RelativePosition.POSITION_END, folder, 0, true,
                    ),
                    it,
                )
            }
        }
        OpLog.log("camera", "photoFolder", mapOf("name" to folder, "ok" to r.isSuccess, "error" to r.exceptionOrNull()?.djiDetail()))
    }

    /** 앱 재시작으로 이름을 잃은 채 멈춘 옛 미션(마지막 KMZ 이름). 다음 [stopMission]이 기체 모드와 상관없이 이 이름으로 끝낸다. */
    @Volatile private var recoveredMission: String? = null

    override suspend fun pauseMission(): Result<Unit> = runCatching {
        if (missionName == null) recoveredMission = lastKmzName()
        djiDone { missions.pauseMission(it) }
    }

    override suspend fun resumeMission(): Result<Unit> = runCatching {
        check(missionName != null) { "올린 미션 없음" }
        djiDone { missions.resumeMission(it) }
        owners.missionResumed()
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

    /**
     * 앱 촬영(화면·RC 셔터): 찍고 새 사진 알림을 기다린다. 그 동안 첫 파일은 이 촬영 것이다(지점 사진으로 넘기지 않음).
     * 알림은 촬영 명령을 보내기 전에 구독한다(명령 응답보다 알림이 먼저 올 수 있음). 시간 초과 뒤 늦게 온 파일도 지점 사진으로 넘기지 않는다.
     */
    override suspend fun capture(lens: Lens, gimbalPitchDeg: Double, zoomRatio: Double): Result<CaptureInfo> = runCatching {
        captureLock.withLock {
            // 인터벌·파노라마 모드(타이머가 남겼거나 Pilot 2가 남김)에서 셔터를 누르면 여러 장이 찍힌다: 한 장 촬영 전에 사진 모드를 맞춘다
            if (cameraMode == CameraMode.PHOTO_INTERVAL || cameraMode == CameraMode.PHOTO_PANORAMA) {
                runCatching { setCameraMode(photoModeOf(shootMode)) }.onFailure { OpLog.log("camera", "modeFix", mapOf("from" to cameraMode?.name, "error" to it.djiDetail())) }
            }
            owners.screenStarted()
            var refused = false
            try {
                coroutineScope {
                    val photo = async(start = CoroutineStart.UNDISPATCHED) { newPhotos.first() }
                    runCatching { djiValue<EmptyMsg>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, camera), it) } }
                        .onFailure { refused = it is DjiException }.getOrThrow()
                    val wait = if (shootMode == ShootMode.SMART) AppConfig.CameraView.SMART_PHOTO_MS else AppConfig.Timeouts.NEW_PHOTO_MS
                    withTimeoutOrNull(wait) { photo.await() } ?: error("사진 알림 없음")
                }
            } finally {
                owners.screenEnded(System.currentTimeMillis(), owed = !refused)
            }
        }
    }

    private fun photoModeOf(m: ShootMode) = if (m == ShootMode.SMART) CameraMode.PHOTO_HYPER_LIGHT else CameraMode.PHOTO_NORMAL

    private suspend fun setCameraMode(m: CameraMode) = djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createKey(CameraKey.KeyCameraMode, camera), m, it) }

    /**
     * 촬영 모드 메뉴(10.5.7): `KeyCameraModeRange`의 사진 모드(인터벌 → 타이머, 파노라마, 하이퍼라이트 → SMART)와 `KeyIntervalModeParamRange` 간격.
     * 원래 값을 운용 로그에 남긴다. ponytail: Pilot 2 "SMART"를 저조도(`PHOTO_HYPER_LIGHT`)로 본다(실기 확인).
     */
    override suspend fun photoModes(): PhotoModes? = runCatching {
        val range = djiValue<List<CameraMode>>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.getValue(KeyTools.createKey(CameraKey.KeyCameraModeRange, camera), it) }
        val intervals = runCatching {
            djiValue<List<Double>>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.getValue(KeyTools.createKey(CameraKey.KeyIntervalModeParamRange, camera), it) }
        }.getOrNull().orEmpty()
        OpLog.log("camera", "photoModes", mapOf("range" to range.joinToString { it.name }, "intervals" to intervals.joinToString()))
        val modes = range.mapNotNull {
            when (it) {
                CameraMode.PHOTO_INTERVAL -> ShootMode.TIMER
                CameraMode.PHOTO_PANORAMA -> ShootMode.PANORAMA
                CameraMode.PHOTO_HYPER_LIGHT -> ShootMode.SMART
                else -> null
            }
        }
        PhotoModes(modes.toSet() + ShootMode.SINGLE, intervals.filter { it > 0 }.distinct().sorted())
    }.onFailure { OpLog.log("camera", "photoModes", mapOf("error" to it.djiDetail())) }.getOrNull()

    override suspend fun setShootMode(mode: ShootMode): Result<Unit> = runCatching {
        setCameraMode(photoModeOf(mode))
        shootMode = mode
    }

    /** [intervalShots]를 멈추라는 신호(화면 셔터를 다시 누름). */
    @Volatile private var intervalStop: CompletableDeferred<Unit>? = null

    /**
     * 타이머 촬영(10.5.7): 카메라를 인터벌(장수 [AppConfig.CameraView.INTERVAL_COUNT_CONTINUOUS])로 바꾸고 촬영을 시작한다. 그 동안 새 파일은 모두 앱 촬영 것([MediaClassifier] 연속).
     * 멈추면 `KeyStopShootPhoto` 뒤 [Tuning.Photo.MULTI_FILE_MS] 동안 늦은 파일까지 넘기고 사진 모드(단일·SMART)로 돌린다.
     * ponytail: M3E가 `KeyCameraMode` PHOTO_INTERVAL을 받는지 실기 확인 전이라, 거절되면 `KeyShootPhotoMode` INTERVAL로 한 번 더 해 본다(어느 쪽인지 로그).
     */
    override fun intervalShots(intervalSec: Double): Flow<CaptureInfo> {
        // 멈춤 신호는 부를 때 만든다: 모으기 시작 전에 누른 멈춤도 잃지 않게
        val stop = CompletableDeferred<Unit>().also { intervalStop = it }
        return channelFlow {
            captureLock.withLock {
                // 사진 알림은 시작 명령 전에 구독한다(capture와 같음)
                val photos = launch(start = CoroutineStart.UNDISPATCHED) { newPhotos.collect { send(it) } }
                owners.screenStarted(continuous = true)
                try {
                    runCatching { setCameraMode(CameraMode.PHOTO_INTERVAL) }.onFailure { e ->
                        OpLog.log("camera", "intervalMode", mapOf("cameraMode" to e.djiDetail()))
                        djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createKey(CameraKey.KeyShootPhotoMode, camera), CameraShootPhotoMode.INTERVAL, it) }
                    }
                    val settings = PhotoIntervalShootSettings(AppConfig.CameraView.INTERVAL_COUNT_CONTINUOUS, intervalSec)
                    djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createKey(CameraKey.KeyPhotoIntervalShootSettings, camera), settings, it) }
                    djiValue<EmptyMsg>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, camera), it) }
                    OpLog.log("camera", "intervalStart", mapOf("sec" to intervalSec))
                    stop.await()
                } finally {
                    withContext(NonCancellable) {
                        val stopped = runCatching { djiValue<EmptyMsg>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.performAction(KeyTools.createKey(CameraKey.KeyStopShootPhoto, camera), it) } }
                        owners.screenEnded(System.currentTimeMillis())
                        delay(Tuning.Photo.MULTI_FILE_MS)
                        photos.cancel()
                        val back = runCatching { setCameraMode(photoModeOf(shootMode)) }
                        OpLog.log("camera", "intervalStop", mapOf("stop" to stopped.exceptionOrNull()?.djiDetail(), "mode" to back.exceptionOrNull()?.djiDetail()))
                    }
                }
            }
        }
    }

    override suspend fun stopInterval() {
        intervalStop?.complete(Unit)
    }

    /** ☀ 노출 손잡이(10.5.7): 기체 범위(`KeyExposureCompensationRange`, 처음 한 번)에서 [ev]에 가장 가까운 단계. 수동 노출이면 기체가 거절한다. */
    override suspend fun setExposureCompensation(ev: Double): Result<Unit> = runCatching {
        val range = evRange ?: runCatching {
            djiValue<List<CameraExposureCompensation>>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.getValue(KeyTools.createKey(CameraKey.KeyExposureCompensationRange, camera), it) }
        }.getOrNull().orEmpty().ifEmpty { CameraExposureCompensation.entries }.also { evRange = it }
        val pick = range.mapNotNull { e -> evLabel(e.name)?.toDoubleOrNull()?.let { e to it } }.minByOrNull { abs(it.second - ev) }?.first ?: error("EV 단계 없음")
        djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createKey(CameraKey.KeyExposureCompensation, camera), pick, it) }
    }

    @Volatile private var evRange: List<CameraExposureCompensation>? = null

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
        djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, camera), source, it) }
    }

    /** 기체에 물어 지금 FPV 렌즈를 읽는다(렌즈 전환 확인). */
    override suspend fun videoLens(): Lens? = runCatching {
        lensOf(djiValue<CameraVideoStreamSourceType>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.getValue(KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, camera), it) })
    }.getOrNull()

    private fun lensOf(s: CameraVideoStreamSourceType?): Lens? = when (s) {
        CameraVideoStreamSourceType.ZOOM_CAMERA -> Lens.ZOOM
        CameraVideoStreamSourceType.WIDE_CAMERA -> Lens.WIDE
        CameraVideoStreamSourceType.INFRARED_CAMERA -> Lens.THERMAL
        else -> null
    }

    /** 짐벌 메뉴(10.5.7): 중앙은 짐벌 리셋(RECENTER), 아래는 피치 -90° 절대 각도. */
    override suspend fun setGimbal(down: Boolean): Result<Unit> = runCatching {
        djiValue<EmptyMsg>(AppConfig.Timeouts.CAMERA_CMD_MS) {
            if (!down) {
                keys.performAction(KeyTools.createKey(GimbalKey.KeyGimbalReset), GimbalResetType.RECENTER, it)
            } else {
                val down90 = GimbalAngleRotation().apply {
                    mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
                    pitch = -90.0
                    pitchIgnored = false
                    rollIgnored = true
                    yawIgnored = true
                    duration = 1.0
                }
                keys.performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle), down90, it)
            }
        }
        Unit
    }

    override suspend fun setFocusMode(mode: FocusMode): Result<Unit> = runCatching {
        val m = when (mode) {
            FocusMode.AFS -> CameraFocusMode.AF
            FocusMode.AFC -> CameraFocusMode.AFC
            FocusMode.MF -> CameraFocusMode.MANUAL
        }
        djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createKey(CameraKey.KeyCameraFocusMode, camera), m, it) }
    }

    /** 탭 초점(10.5.2): MF면 AFS로 바꾼 뒤 초점 지점(0~1)을 보낸다. ponytail: 측광 지점(`KeySpotMeteringPoint`)은 같이 옮기지 않는다(실기 확인 뒤). */
    override suspend fun focusAt(x: Double, y: Double): Result<Unit> = runCatching {
        if (_camera.value.focus == FocusMode.MF) setFocusMode(FocusMode.AFS).getOrThrow()
        djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) {
            keys.setValue(KeyTools.createKey(CameraKey.KeyCameraFocusTarget, camera), DoublePoint2D(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)), it)
        }
    }

    /** 망원 렌즈 줌(M3E 망원은 같은 카메라 [camera]의 ZOOM 렌즈). 기체 범위([zoomRange]) 안으로 맞춰 보낸다. */
    override suspend fun setZoom(ratio: Double): Result<Unit> = runCatching {
        val r = zoomRange()?.let { ratio.coerceIn(it) } ?: ratio
        djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios, camera, CameraLensType.CAMERA_LENS_ZOOM), r, it) }
    }

    /** 읽은 줌 범위. 못 읽었어도 이 연결 동안 다시 묻지 않는다(재개 때마다 5초씩 기다리지 않게). */
    @Volatile private var zoomRangeRead: Result<ClosedFloatingPointRange<Double>?>? = null

    /** 망원 줌 범위(`KeyCameraZoomRatiosRange`의 gears 최소~최대). 처음 한 번 읽고 운용 로그에 원래 값을 남긴다. */
    override suspend fun zoomRange(): ClosedFloatingPointRange<Double>? = (zoomRangeRead ?: runCatching {
        val r = djiValue(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.getValue(KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatiosRange, camera, CameraLensType.CAMERA_LENS_ZOOM), it) }
        val gears = r.gears?.takeIf { it.isNotEmpty() }
        OpLog.log("camera", "zoomRange", mapOf("continuous" to r.isContinuous, "gears" to gears?.joinToString()))
        gears?.let { it.min().toDouble()..it.max().toDouble() }
    }.onFailure { OpLog.log("camera", "zoomRange", mapOf("error" to it.djiDetail())) }.also { zoomRangeRead = it }).getOrNull()

    /** 기체에 물어 지금 망원 줌 배율을 읽는다. */
    override suspend fun zoomRatio(): Double? = runCatching {
        djiValue<Double>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.getValue(KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios, camera, CameraLensType.CAMERA_LENS_ZOOM), it) }
    }.getOrNull()

    override suspend fun setVideoMode(on: Boolean): Result<Unit> = runCatching {
        djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(KeyTools.createKey(CameraKey.KeyCameraMode, camera), if (on) CameraMode.VIDEO_NORMAL else CameraMode.PHOTO_NORMAL, it) }
    }

    override suspend fun setRecording(on: Boolean): Result<Unit> = runCatching {
        djiValue<EmptyMsg>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.performAction(KeyTools.createKey(if (on) CameraKey.KeyStartRecord else CameraKey.KeyStopRecord, camera), it) }
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
    private suspend fun setCaptureLenses(lenses: List<Lens>): Boolean {
        val key = KeyTools.createKey(CameraKey.KeyCaptureCameraStreamSettings, camera)
        val before = runCatching { keys.getValue(key)?.toJson()?.toString() }.getOrNull()
        val sources = lenses.mapNotNull {
            when (it) {
                Lens.WIDE -> CameraVideoStreamSourceType.WIDE_CAMERA
                Lens.ZOOM -> CameraVideoStreamSourceType.ZOOM_CAMERA
                Lens.THERMAL -> CameraVideoStreamSourceType.INFRARED_CAMERA
            }
        }
        val result = runCatching { djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(key, CameraStreamSettingsInfo(false, sources), it) } }
        OpLog.log("camera", "captureLenses", mapOf("before" to before, "set" to sources.joinToString { it.name }, "ok" to result.isSuccess, "error" to result.exceptionOrNull()?.djiDetail()))
        return result.isSuccess
    }

    /**
     * 렌즈별 동작(5.6) 전에 카메라 "사진 저장"(`KeyCaptureCameraStreamSettings`)을 본다. Pilot 2에서 광각+망원을 켜 두었으면 동작마다 파일이 여럿 생겨
     * 사진 주인이 밀린다(6.4): 지금 값을 로그에 남기고 한 렌즈(광각)로 바꾼다. 못 바꾸면 동작마다 지금 설정의 렌즈 파일이 온다고 보고 그 렌즈들을 돌려준다.
     * 한 렌즈였거나 바꿨거나 읽지 못하면 null(동작마다 1장). 실패해도 미션은 간다.
     * ponytail: 한 렌즈 저장에서 망원 동작이 망원으로 찍히는지는 실내 기체 시험 전(13.0). 광각으로 찍히면 지금(11-03)과 같다.
     */
    private suspend fun singleCaptureLens(): List<Lens>? {
        val key = KeyTools.createKey(CameraKey.KeyCaptureCameraStreamSettings, camera)
        val now = runCatching { djiValue<CameraStreamSettingsInfo>(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.getValue(key, it) } }
        val lenses = now.getOrNull()?.let { s -> s.cameraVideoStreamSources.orEmpty().mapNotNull(::lensOf) + listOfNotNull(Lens.WIDE.takeIf { s.requestCurrentScreen }) }.orEmpty()
        val set = if (lenses.size > 1) {
            runCatching { djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { keys.setValue(key, CameraStreamSettingsInfo(false, listOf(CameraVideoStreamSourceType.WIDE_CAMERA)), it) } }
        } else {
            null
        }
        OpLog.log(
            "camera", "captureSingle",
            mapOf(
                "before" to now.getOrNull()?.toJson()?.toString(), "readError" to now.exceptionOrNull()?.djiDetail(),
                "set" to set?.isSuccess, "error" to set?.exceptionOrNull()?.djiDetail(),
            ),
        )
        return lenses.takeIf { set?.isFailure == true }
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
        // 감지 켬·경고·제동 거리(내비 OFF·부채꼴 색, 10.5.3). 바뀌면 마지막 거리로 다시 보낸다
        var info: dji.v5.manager.aircraft.perception.data.PerceptionInfo? = null
        var last: com.example.myfirstapp.core.model.Obstacles? = null
        fun send(o: com.example.myfirstapp.core.model.Obstacles?) {
            last = o
            val i = info
            trySend(
                if (o == null || i == null) o
                else o.copy(sensingOn = i.isHorizontalObstacleAvoidanceEnabled, warningM = i.horizontalObstacleAvoidanceWarningDistance, brakingM = i.horizontalObstacleAvoidanceBrakingDistance),
            )
        }
        val l = dji.v5.manager.aircraft.perception.listener.ObstacleDataListener { d ->
            // 시뮬레이터에서는 실제 기체가 책상 위라 센서가 주변 물건을 0.5m로 계속 본다(2026-10-31): 경고하지 않는다
            if (djiSimulatorOn()) return@ObstacleDataListener run { send(null) }
            fun m(mm: Int?) = mm?.takeIf { it > 0 }?.div(1000.0)
            send(com.example.myfirstapp.core.model.Obstacles.fromHorizontal(
                d.horizontalObstacleDistance.orEmpty().map { m(it) ?: 0.0 }, d.horizontalAngleInterval,
                m(d.upwardObstacleDistance), m(d.downwardObstacleDistance),
            ))
        }
        val p = dji.v5.manager.aircraft.perception.listener.PerceptionInformationListener { i ->
            info = i
            if (!djiSimulatorOn()) send(last ?: com.example.myfirstapp.core.model.Obstacles(emptyMap()))
        }
        pm.addObstacleDataListener(l)
        pm.addPerceptionInformationListener(p)
        try { kotlinx.coroutines.awaitCancellation() } finally {
            pm.removeObstacleDataListener(l)
            pm.removePerceptionInformationListener(p)
        }
    }

    private fun focusOf(m: CameraFocusMode?): FocusMode? = when (m) {
        CameraFocusMode.AF -> FocusMode.AFS
        CameraFocusMode.AFC -> FocusMode.AFC
        CameraFocusMode.MANUAL -> FocusMode.MF
        else -> null
    }

    /** 풍향(8방위)을 각도로. 바람 없음·모름은 null. ponytail: 바람이 오는 쪽인지 가는 쪽인지 실기에서 Pilot 2 화살표와 대조한다. */
    private fun windDeg(d: WindDirection?): Double? = when (d) {
        WindDirection.NORTH -> 0.0
        WindDirection.NORTH_EAST -> 45.0
        WindDirection.EAST -> 90.0
        WindDirection.SOUTH_EAST -> 135.0
        WindDirection.SOUTH -> 180.0
        WindDirection.SOUTH_WEST -> 225.0
        WindDirection.WEST -> 270.0
        WindDirection.NORTH_WEST -> 315.0
        else -> null
    }

    companion object {
        const val TAG = "DjiDrone"

        /** 셔터 이름(`SHUTTER_SPEED1_640`, `SHUTTER_SPEED1_12DOT5`, `SHUTTER_SPEED2`)을 Pilot 2 글자(1/640, 1/12.5, 2")로. */
        internal fun shutterLabel(name: String?): String? = when (name) {
            null, "UNKNOWN" -> null
            "SHUTTER_SPEED_AUTO" -> "Auto"
            else -> name.removePrefix("SHUTTER_SPEED").replace("DOT", ".").let { if (it.startsWith("1_")) "1/" + it.drop(2) else "$it\"" }
        }

        /** 조리개 이름(`F2_8`)을 "2.8"로. */
        internal fun apertureLabel(name: String?): String? = when (name) {
            null, "UNKNOWN" -> null
            "F_AUTO" -> "Auto"
            else -> name.removePrefix("F").replace('_', '.')
        }

        /** EV 이름(`NEG_0P3EV`, `POS_1P0EV`, `NEG_0EV`)을 "-0.3", "+1.0", "0"으로. */
        internal fun evLabel(name: String?): String? {
            if (name == null || name == "UNKNOWN" || name == "FIXED") return null
            val v = name.substringAfter('_').removeSuffix("EV").replace('P', '.')
            return if (v == "0") "0" else (if (name.startsWith("NEG")) "-" else "+") + v
        }

        /** 새 미션을 올리기 전에 끝내야 하는 웨이라인 상태. */
        private val STALE_STATES = setOf(
            WaypointMissionExecuteState.INTERRUPTED, WaypointMissionExecuteState.EXECUTING, WaypointMissionExecuteState.RECOVERING,
            WaypointMissionExecuteState.ENTER_WAYLINE, WaypointMissionExecuteState.PREPARING,
        )
    }
}
