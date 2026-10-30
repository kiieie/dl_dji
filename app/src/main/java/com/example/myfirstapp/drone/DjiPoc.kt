package com.example.myfirstapp.drone

import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import com.example.myfirstapp.core.route.Viewpoints
import com.example.myfirstapp.core.route.WpmlWriter
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.flightParamsFromJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 기체 PoC(13장 1단계) 시험 호출. 연동 검증 화면 전용이며, 결과는 로그 태그 `DjiPoc`에도 남긴다.
 * 실제 비행 연동은 PoC 결과를 본 뒤 `DjiDroneGateway`로 만든다.
 */
object DjiPoc {
    private val camera = ComponentIndexType.LEFT_OR_MAIN

    private fun log(text: String) = text.also { Log.i("DjiPoc", it) }

    private suspend fun done(call: (CommonCallbacks.CompletionCallback) -> Unit) = djiDone(call)
    private suspend fun <T> value(call: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit) = djiValue(call)

    private inline fun <T> step(name: String, call: () -> T): T =
        try { call() } catch (e: DjiException) { throw Exception("$name: ${e.message}") }

    /** 기체 연결 여부와 기종. */
    fun aircraft(): String {
        val keys = KeyManager.getInstance()
        val connected = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false)
        val type = keys.getValue(KeyTools.createKey(ProductKey.KeyProductType))
        val gps = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount))
        val gpsLevel = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel))
        val mode = keys.getValue(KeyTools.createKey(CameraKey.KeyCameraMode, camera))
        val photoMode = keys.getValue(KeyTools.createKey(CameraKey.KeyShootPhotoMode, camera))
        val sd = keys.getValue(KeyTools.createKey(CameraKey.KeyCameraSDCardState, camera))
        val sdFree = keys.getValue(KeyTools.createKey(CameraKey.KeySDCardRemainSpace, camera))
        return log(
            "기체: ${if (connected) "연결됨" else "연결 안 됨"}, 기종 $type, 시뮬레이터 ${SimulatorManager.getInstance().isSimulatorEnabled}, " +
                "GPS ${gps}개 $gpsLevel, 카메라 $mode/$photoMode, SD $sd(남은 ${sdFree}MB)",
        )
    }

    /** 시뮬레이터는 화면이 없으므로 위치·고도·속도·비행 모드를 글로 보여 준다(1초마다 읽기). */
    private val telemetryKeys by lazy {
        // getValue는 캐시만 읽으므로 한 번 구독해 두어야 값이 갱신된다
        listOf(
            FlightControllerKey.KeyAircraftLocation3D, FlightControllerKey.KeyAircraftVelocity, FlightControllerKey.KeyCompassHeading,
            FlightControllerKey.KeyFlightMode, FlightControllerKey.KeyAreMotorsOn,
        ).forEach { KeyManager.getInstance().listen(KeyTools.createKey(it), this) { _, _ -> } }
    }

    fun telemetry(): String {
        telemetryKeys
        val keys = KeyManager.getInstance()
        val p = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D))
        val v = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))
        val speed = v?.let { Math.hypot(it.x, it.y) } ?: 0.0
        val heading = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyCompassHeading)) ?: 0.0
        val mode = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode))
        val motors = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn), false)
        return if (p == null) "위치: 없음"
        else "위치 %.6f, %.6f · 고도 %.1fm · 속도 %.1fm/s · 기수 %.0f° · %s · 모터 %s".format(p.latitude, p.longitude, p.altitude, speed, heading, mode, if (motors) "켜짐" else "꺼짐")
    }

    /** PoC ③: 촬영 → SD 최신 사진 → 미리보기·원본 다운로드 시간. 원본은 [dir]에 저장한다. */
    suspend fun shootAndDownload(dir: File): String {
        val t0 = System.currentTimeMillis()
        step("촬영") { value<EmptyMsg> { KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, camera), it) } }
        val tShot = System.currentTimeMillis()
        delay(1500) // 사진이 SD에 써질 시간
        val media = MediaDataCenter.getInstance().mediaManager
        media.setMediaFileDataSource(MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).setIndexType(camera).build())
        try {
            media.enable(null)
            step("목록") { done { media.pullMediaFileListFromCamera(PullMediaFileListParam.Builder().mediaFileIndex(-1).count(5).build(), it) } }
            val tList = System.currentTimeMillis()
            val file: MediaFile = media.mediaFileListData.data.maxByOrNull { it.fileIndex } ?: error("SD에 사진 없음")
            step("미리보기") { value { file.pullPreviewFromCamera(it) } }
            val tPreview = System.currentTimeMillis()
            dir.mkdirs()
            val out = File(dir, file.fileName)
            FileOutputStream(out).use { stream ->
                suspendCancellableCoroutine { c ->
                    file.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
                        override fun onStart() {}
                        override fun onProgress(total: Long, current: Long) {}
                        override fun onRealtimeDataUpdate(data: ByteArray, position: Long) = stream.write(data)
                        override fun onFinish() = c.resume(Unit)
                        override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
                    })
                }
            }
            val tOriginal = System.currentTimeMillis()
            return log(
                "촬영 ${tShot - t0}ms, 목록 ${tList - tShot - 1500}ms(+대기 1.5초), 미리보기 ${tPreview - tList}ms, " +
                    "원본 ${file.fileName} ${out.length() / 1024}KB ${tOriginal - tPreview}ms",
            )
        } finally {
            media.disable(null)
        }
    }

    /** PoC ⑥ 준비: 시뮬레이터 켜기(프로펠러 제거 상태). 위치는 현장 KMZ M1(대평선 137L-A)의 첫 웨이포인트 근처. */
    suspend fun simulator(on: Boolean): String {
        val sim = SimulatorManager.getInstance()
        if (on) done { sim.enableSimulator(InitializationSettings.createInstance(LocationCoordinate2D(36.48010, 127.33090), 15), it) }
        else done { sim.disableSimulator(it) }
        return log("시뮬레이터 ${if (sim.isSimulatorEnabled) "켜짐" else "꺼짐"}")
    }

    private val missions = WaypointMissionManager.getInstance()
    private var missionT0 = 0L
    private var lastWaypoint = -1

    /** 미션 상태 변화와 현재 웨이포인트를 시작 명령 기준 경과 시간과 함께 로그로 남긴다(한 번만 등록). */
    private val stateLog by lazy {
        missions.addWaypointMissionExecuteStateListener(WaypointMissionExecuteStateListener { log("미션 상태 $it +${System.currentTimeMillis() - missionT0}ms") })
        missions.addWaylineExecutingInfoListener(object : WaylineExecutingInfoListener {
            override fun onWaylineExecutingInfoUpdate(info: WaylineExecutingInfo) {
                if (info.currentWaypointIndex != lastWaypoint) {
                    lastWaypoint = info.currentWaypointIndex
                    log("웨이포인트 ${info.currentWaypointIndex} +${System.currentTimeMillis() - missionT0}ms")
                }
            }

            override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
                if (error != null) log("미션 중단 이유: ${error.errorCode()} ${error.description()}")
            }
        })
        // 사진이 생길 때마다 그 순간의 위치·기수·짐벌을 남긴다(촬영 지점 대조용, 5.6)
        val keys = KeyManager.getInstance()
        keys.listen(KeyTools.createKey(GimbalKey.KeyGimbalAttitude), this) { _, _ -> }
        keys.listen(KeyTools.createKey(CameraKey.KeyNewlyGeneratedMediaFile, camera), this) { _, info ->
            if (info == null) return@listen
            val p = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D))
            val heading = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyCompassHeading))
            val gimbal = keys.getValue(KeyTools.createKey(GimbalKey.KeyGimbalAttitude))
            log(
                "사진 #${info.index} 웨이포인트 $lastWaypoint 위치 %.6f, %.6f 고도 %.1f 기수 %.0f 짐벌 %.1f".format(
                    p?.latitude ?: 0.0, p?.longitude ?: 0.0, p?.altitude ?: 0.0, heading ?: 0.0, gimbal?.pitch ?: 0.0,
                ),
            )
        }
    }

    /** PoC ①: KMZ 업로드 시간. */
    suspend fun upload(kmz: File): String {
        stateLog
        require(kmz.exists()) { "KMZ 없음: ${kmz.path}" }
        val t0 = System.currentTimeMillis()
        suspendCancellableCoroutine { c ->
            missions.pushKMZFileToAircraft(kmz.path, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
                override fun onProgressUpdate(progress: Double) {}
                override fun onSuccess() = c.resume(Unit)
                override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
            })
        }
        return log("업로드 ${kmz.name} ${kmz.length() / 1024}KB ${System.currentTimeMillis() - t0}ms, 웨이라인 ${missions.getAvailableWaylineIDs(kmz.path)}")
    }

    /** PoC ①: 시작 명령. 실제 출발까지의 지연은 상태 로그(ENTER_WAYLINE, 웨이포인트 0)로 본다. */
    suspend fun start(kmz: File): String {
        stateLog
        missionT0 = System.currentTimeMillis()
        lastWaypoint = -1
        done { missions.startMission(kmz.nameWithoutExtension, it) }
        return log("시작 명령 성공 +${System.currentTimeMillis() - missionT0}ms")
    }

    suspend fun pause() = done { missions.pauseMission(it) }.let { log("일시정지 성공") }
    suspend fun resume() = done { missions.resumeMission(it) }.let { log("재개 성공") }
    suspend fun stop(kmz: File) = done { missions.stopMission(kmz.nameWithoutExtension, it) }.let { log("정지 성공") }

    /**
     * 가장 최근 미션의 첫 소티를 앱이 직접 KMZ로 만든다(5.6, 6.2). [mode]로 촬영 방식을 바꿔 볼 수 있다.
     * 만든 파일 경로와 요약을 돌려준다.
     */
    suspend fun planKmz(dao: ProjectDao, dir: File, mode: ShotMode): Pair<File, String> {
        val mission = dao.latestMission() ?: error("저장한 미션이 없습니다")
        val rows = dao.missionVisits(mission.id)
        val seq = rows.minOf { it.seq }
        val poles = rows.filter { it.seq == seq }.map { it.toPole() }
        val params = flightParamsFromJson(mission.flightParams).copy(shotMode = mode)
        params.validate().firstOrNull()?.let { error(it) }
        val points = Viewpoints.expand(poles, params, GeoPoint(mission.homeLat, mission.homeLon))
        dir.mkdirs()
        val kmz = File(dir, "plan_${mission.id}_$seq.kmz")
        val home = GeoPoint(mission.homeLat, mission.homeLon)
        kmz.outputStream().use { WpmlWriter.write(points, params, it, start = home) }
        return kmz to log("KMZ 생성 미션 #${mission.id} 소티 $seq: 전주 ${poles.size}개 → 촬영 지점 ${points.size}개($mode), ${kmz.length() / 1024}KB")
    }
}
