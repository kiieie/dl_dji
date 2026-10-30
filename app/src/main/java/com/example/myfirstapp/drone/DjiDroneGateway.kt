package com.example.myfirstapp.drone

import com.example.myfirstapp.data.OpLog
import android.util.Log
import com.example.myfirstapp.core.executor.SHUTTER_SKEW_MS
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneEvent
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.LegMission
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

    private var missionName: String? = null
    private var waypoint = -1

    /** 올린 KMZ의 웨이포인트(이동 고도 지점 포함, 5.6). 기체가 알리는 KMZ 번호를 촬영 지점 번호로 바꾼다. */
    private var kmzWaypoints: List<WpmlWriter.Waypoint> = emptyList()
    private var manualShot = false
    /** RC 셔터를 누른 직후 새로 생긴 사진은 지점 사진이 아니다(실행기가 SD에서 찾아 수동 사진으로 붙인다, 6.4 RC 셔터). */
    private var rcShotUntil = 0L
    private var lostJob: Job? = null
    private var lost = false
    private val newPhotos = MutableSharedFlow<CaptureInfo>(extraBufferCapacity = 16)
    private val mediaLock = Mutex()
    /** 기체가 FINISHED를 마지막 사진보다 먼저 알린다(2026-10-30 확인). 그 뒤 잠깐 들어온 사진도 미션 사진으로 넘긴다. */
    private var photoGraceUntil = 0L
    private var mediaEnabled = false

    private val stateListener = WaypointMissionExecuteStateListener { s ->
        Log.i(TAG, "미션 상태 $s"); OpLog.log("mission", s.name)
        when (s) {
            WaypointMissionExecuteState.INTERRUPTED -> _events.tryEmit(DroneEvent.PausedByRc)
            WaypointMissionExecuteState.FINISHED -> if (missionName != null) {
                missionName = null
                photoGraceUntil = System.currentTimeMillis() + 5_000
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
                kmzWaypoints.getOrNull(waypoint)?.shot?.let { _events.tryEmit(DroneEvent.WaypointReached(it)) }
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
            if (flying == false) _telemetry.update { it.copy(flightMode = AppFlightMode.ON_GROUND) }
            if (was && flying == false) _events.tryEmit(DroneEvent.Landed)
        }
        on(GimbalKey.KeyGimbalAttitude) { g -> _telemetry.update { it.copy(gimbalPitchDeg = g?.pitch ?: 0.0) } }
        on(FlightControllerKey.KeyCompassHeading) { h -> _telemetry.update { it.copy(headingDeg = ((h ?: 0.0) + 360) % 360) } }
        keys.listen(KeyTools.createKey(CameraKey.KeyNewlyGeneratedMediaFile, camera), holder) { _, info ->
            if (info == null) return@listen
            // 녹화한 영상은 SD에만 둔다(10.4.3). 지점·수동 사진으로 넘기지 않는다
            if (info.type == MediaFileType.MOV || info.type == MediaFileType.MP4) return@listen
            val t = _telemetry.value
            val capture = CaptureInfo(
                mediaId = info.index.toString(), lens = Lens.WIDE, position = t.position, altitudeM = t.altitudeM,
                gimbalPitchDeg = t.gimbalPitchDeg, headingDeg = t.headingDeg,
                takenAtMs = System.currentTimeMillis(),
            )
            Log.i(TAG, "사진 #${info.index} 지점 $waypoint")
            newPhotos.tryEmit(capture)
            val inMission = missionName != null || System.currentTimeMillis() < photoGraceUntil
            // ponytail: 셔터를 누른 3초 안에 지점 사진이 먼저 생기면 그 사진을 셔터 사진으로 본다(둘 다 기록되고 자동·수동 표시만 바뀜). 정확히 가르려면 PoC에서 셔터 사진의 SD 번호를 확인한다
            val rcShot = System.currentTimeMillis() < rcShotUntil
            if (rcShot) rcShotUntil = 0
            val shot = WpmlWriter.shotIndex(kmzWaypoints, waypoint)
            if (!manualShot && !rcShot && inMission && shot != null) _events.tryEmit(DroneEvent.PhotoTaken(shot, capture))
        }
        keys.listen(KeyTools.createKey(RemoteControllerKey.KeyShutterButtonDown), holder) { _, down ->
            if (down == true) rcShotUntil = System.currentTimeMillis() + SHUTTER_SKEW_MS
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
        _telemetry.update { it.copy(connected = connected) }
        if (connected) {
            lostJob?.cancel()
            if (lost) {
                lost = false
                _events.tryEmit(DroneEvent.ConnectionRestored)
            }
        } else if (lostJob?.isActive != true) {
            // 3초 넘게 끊겨야 연결 끊김으로 본다(6.4)
            lostJob = scope.launch {
                delay(3_000)
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
            else -> if (keys.getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying), false)) AppFlightMode.MANUAL else AppFlightMode.ON_GROUND
        }
        val before = _telemetry.value.flightMode
        _telemetry.update { it.copy(flightMode = mode) }
        if (mode == AppFlightMode.GO_HOME && before != AppFlightMode.GO_HOME) _events.tryEmit(DroneEvent.GoHomeStarted)
    }

    private fun gpsLevel(g: GPSSignalLevel?) = when (g) {
        GPSSignalLevel.LEVEL_1 -> 1
        GPSSignalLevel.LEVEL_2 -> 2
        GPSSignalLevel.LEVEL_3 -> 3
        GPSSignalLevel.LEVEL_4 -> 4
        GPSSignalLevel.LEVEL_5, GPSSignalLevel.LEVEL_10 -> 5
        else -> 0
    }

    private suspend fun <T> read(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = withTimeoutOrNull(3_000) {
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
        val name = "sortie_${System.currentTimeMillis()}"
        val kmz = File(kmzDir, "$name.kmz")
        // 시작 위치: 떠 있으면 지금 자리, 땅이면 이륙 지점. 복귀는 홈 지점(5.6 이동 고도 구간)
        val t = _telemetry.value
        val wps = kmz.outputStream().use { WpmlWriter.write(points, params, it, start = t.position ?: t.home, home = t.home ?: t.position) }
        suspendCancellableCoroutine { c ->
            missions.pushKMZFileToAircraft(kmz.path, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                override fun onProgressUpdate(progress: Double) {}
                override fun onSuccess() = c.resume(Unit)
                override fun onFailure(error: IDJIError) = c.resumeWith(Result.failure(DjiException(error)))
            })
        }
        waypoint = -1
        kmzWaypoints = wps
        missionName = name
        djiDone { missions.startMission(name, it) }
        Log.i(TAG, "소티 미션 시작 $name 촬영 지점 ${points.size}개, 웨이포인트 ${wps.size}개")
        Unit
    }.onFailure { missionName = null; Log.w(TAG, "소티 미션 실패: ${it.message}"); OpLog.log("error", "startSortie", mapOf("message" to it.message)) }

    override suspend fun pauseMission(): Result<Unit> = runCatching { djiDone { missions.pauseMission(it) } }

    override suspend fun resumeMission(): Result<Unit> = runCatching {
        check(missionName != null) { "올린 미션 없음" }
        djiDone { missions.resumeMission(it) }
    }

    override suspend fun stopMission(): Result<Unit> {
        val name = missionName ?: return Result.success(Unit)
        missionName = null
        return runCatching { djiDone { missions.stopMission(name, it) } }
    }

    /** 수동 촬영(일시정지 중): 찍고 새 사진 알림을 기다린다. 미션 사진으로 잘못 넘기지 않게 표시해 둔다. */
    override suspend fun capture(lens: Lens, gimbalPitchDeg: Double, zoomRatio: Double): Result<CaptureInfo> = runCatching {
        manualShot = true
        try {
            djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, camera), it) }
            withTimeoutOrNull(5_000) { newPhotos.first() } ?: error("사진 알림 없음")
        } finally {
            manualShot = false
        }
    }

    /** SD 최신 목록에서 사진 번호([CaptureInfo.mediaId])로 찾아 받는다. 막 찍힌 사진은 목록에 늦게 올라오므로 몇 번 다시 본다. */
    override suspend fun download(capture: CaptureInfo, mode: DownloadMode): Result<ByteArray> = runCatching {
        mediaLock.withLock {
            val index = capture.mediaId.toInt()
            var file: MediaFile? = null
            repeat(5) {
                if (file == null) {
                    pullRecent(10)
                    file = media.mediaFileListData.data.firstOrNull { it.fileIndex == index }
                    if (file == null) delay(1_000)
                }
            }
            val f = file ?: error("SD에서 사진 #$index 못 찾음")
            if (mode == DownloadMode.PREVIEW) {
                val bmp = djiValue<android.graphics.Bitmap> { f.pullPreviewFromCamera(it) }
                ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
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
            pullRecent(100)
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
            val ok = withTimeoutOrNull(10_000) { djiDone { media.pullMediaFileListFromCamera(param, it) } } != null
            if (ok) return Log.i(TAG, "SD 목록(미디어 모드 없이) ${media.mediaFileListData.data.size}개").let {}
            Log.w(TAG, "SD 목록 응답 없음 → 미디어 모드 켬")
            runCatching { djiDone { media.enable(it) } }.onFailure { Log.w(TAG, "미디어 모드 실패: ${it.message}") }
            mediaEnabled = true
        }
        withTimeoutOrNull(15_000) { djiDone { media.pullMediaFileListFromCamera(param, it) } } ?: error("SD 목록 응답 없음")
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

    companion object {
        const val TAG = "DjiDrone"
    }
}
