package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.executor.shutterLinkTarget
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.logTelemetry
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.RecorderSortie
import com.example.myfirstapp.data.RoomRecorder
import com.example.myfirstapp.ui.home.LatestMission
import com.example.myfirstapp.ui.home.loadLatestMission
import com.example.myfirstapp.data.listMbtiles
import com.example.myfirstapp.data.MapBackground
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.pickMapSource
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.mapBackgroundRetrying
import com.example.myfirstapp.drone.DjiDroneGateway
import com.example.myfirstapp.drone.djiAircraftConnected
import com.example.myfirstapp.sound.ToneNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 수동 비행 카메라 뷰 상태. 지도·상단 바는 비행 화면과 같은 [FlightUiState]를 쓴다. */
data class ManualFlightUiState(
    val flight: FlightUiState = FlightUiState(loading = false),
    val connected: Boolean = false,
    val shooting: Boolean = false,
    val shots: Int = 0,
    val message: String? = null,
    /** 왼쪽 위 경로 알약(Pilot 2 55쪽)이 이어서 열 최근 미션. 없으면 알약을 숨긴다. */
    val latest: LatestMission? = null,
)

/**
 * 수동 비행 카메라 뷰(Pilot 2 카메라 뷰, 10.0): 미션 없이 기체 영상·계기·지도를 보고 조종사가 스틱으로 난다.
 * 셔터(화면 버튼·RC 셔터)는 언제든 찍고 원본을 받는다. 남은 전주가 있는 최근 미션의 전주가 [Tuning.Photo.SHUTTER_POLE_LINK_M] 안이면 그 전주의 수동 사진으로,
 * 아니면 `manual/<날짜>/`에 둔다(6.4 RC 셔터). 자동 점검 비행은 미션의 [비행 시작]으로 한다.
 */
class ManualFlightViewModel(
    private val dao: ProjectDao,
    private val appFilesDir: File?,
    rcButtons: Flow<RcButton> = emptyFlow(),
    /** 배경 지도 선택(7.4). 없으면 Bing. */
    private val settings: SettingsStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ManualFlightUiState())
    val state: StateFlow<ManualFlightUiState> = _state.asStateFlow()
    private val drone: DroneGateway? = if (djiAircraftConnected()) DjiDroneGateway(viewModelScope, File(appFilesDir, "manual/kmz")) else null
    private val camera = CameraControl(viewModelScope, { drone }, { _state.value.flight }, { f -> _state.update { it.copy(flight = f(it.flight)) } }, "manual")
    private val tone = ToneNotifier()

    init {
        OpLog.log("manual", "open", mapOf("connected" to (drone != null)))
        _state.update { it.copy(connected = drone != null, message = if (drone == null) "기체가 연결되지 않았습니다" else null) }
        drone?.let { d -> viewModelScope.launch { d.telemetry.collect { t -> _state.update { it.copy(flight = it.flight.copy(telemetry = t, home = t.home).withTrack(t)) } } } }
        // 수동 비행 궤적은 운용 로그에(떠 있는 동안 1초마다, 4.4)
        drone?.let { d -> viewModelScope.launch { while (true) { delay(AppConfig.Polling.TRACK_MS); d.telemetry.value.takeIf { it.isFlying }?.let { logTelemetry("manual", it) } } } }
        drone?.let { d -> viewModelScope.watchObstacles(d, tone) { w -> _state.update { it.copy(flight = it.flight.copy(obstacles = w)) } } }
        viewModelScope.launch {
            val files = appFilesDir?.let { listMbtiles(File(it, "maps")) }.orEmpty()
            _state.update { it.copy(flight = it.flight.copy(mapFiles = files)) }
            val bg = settings?.let { mapBackgroundRetrying(it) } ?: MapBackground.NONE
            _state.update { it.copy(flight = it.flight.copy(background = bg)) }
        }
        viewModelScope.launch { loadLatestMission(dao)?.let { m -> _state.update { it.copy(latest = m) } } }
        // RC: C2는 짐벌 전환(비행 화면과 같음), 셔터·녹화는 앱이 찍고 녹화한다(6.4, 2026-11-04 고침). 이 화면 위에 설정 등이 열려 있을 때([active] 거짓)는 받지 않는다
        viewModelScope.launch { rcButtons.collect {
            if (!active) return@collect
            when (it) {
                RcButton.C2 -> toggleGimbal()
                RcButton.FIVE_D_CENTER, RcButton.SHUTTER -> shutter() // ○(C3)도 5D 누름으로 온다
                RcButton.RECORD -> camera.recordButton()
                else -> {}
            }
        } }
    }

    /** 화면이 맨 위에 보이는 동안만 참(화면이 [LifecycleResumeEffect]로 바꾼다). */
    var active = false

    /** 화면 셔터: 영상 모드면 녹화 시작·정지, 사진이면 [shoot]. */
    fun shutter() = if (_state.value.flight.videoMode) toggleRecording() else shoot()

    /** 사진 셔터: 찍고 원본을 받는다. */
    fun shoot() {
        val d = drone ?: return
        if (_state.value.shooting) return
        val lens = _state.value.flight.lens
        keep(d, lens) { d.capture(lens, 0.0, 1.0).getOrThrow() }
    }

    private fun keep(d: DroneGateway, lens: Lens, shot: suspend () -> CaptureInfo) {
        _state.update { it.copy(shooting = true) }
        viewModelScope.launch {
            val result = runCatching {
                val info = shot()
                store(info, d.download(info, DownloadMode.ORIGINAL).getOrThrow(), lens)
            }
            OpLog.log("manual", "shoot", mapOf("ok" to result.isSuccess, "saved" to result.getOrNull(), "error" to result.exceptionOrNull()?.message))
            _state.update {
                it.copy(
                    shooting = false, shots = it.shots + if (result.isSuccess) 1 else 0,
                    message = result.fold({ s -> "저장했습니다: $s" }, { e -> "촬영 실패: ${e.message}" }),
                )
            }
        }
    }

    /** 최근 미션 전주가 가까우면 그 방문의 수동 사진(4.3 미션 `images/`), 아니면 `manual/<날짜>/`. 저장한 곳을 돌려준다. */
    private suspend fun store(info: CaptureInfo, bytes: ByteArray, lens: Lens): String {
        val m = dao.latestOpenMission()
        val v = if (m == null) null else shutterLinkTarget(info.position, dao.missionVisits(m.id)) { GeoPoint(it.lat, it.lon) }
        if (m != null && v != null) {
            val rec = RoomRecorder(
                dao, m.id, listOf(RecorderSortie(v.sortieId, listOf(v.visitId to v.poleId))),
                File(appFilesDir, "projects/${m.projectId}/missions/${m.id}/images"), DownloadMode.ORIGINAL,
            )
            rec.saveImage(rec.saveCapture(0, 0, info.copy(lens = lens, viewpoint = null), CaptureSource.MANUAL), bytes)
            return "전주 ${v.poleId}"
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val file = File(appFilesDir, "manual/$day/manual_${info.takenAtMs}_${info.mediaId}_${lens}.jpg")
        withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
        return file.name
    }


    /** 지도 지우개(10.5.4): 비행 궤적을 지운다. */
    fun clearTrack() = _state.update { it.copy(flight = it.flight.copy(track = emptyList())) }

    /** 지도 레이어(10.5.7): 배경을 바꾸고 설정에도 남긴다. */
    fun setMapSource(source: MapSource) {
        val st = settings ?: return
        viewModelScope.launch { st.pickMapSource(source).let { bg -> _state.update { it.copy(flight = it.flight.copy(background = bg)) } } }
    }

    fun setLens(lens: Lens) = camera.setLens(lens)
    fun zoom(up: Boolean) = camera.zoom(up)
    fun setVideoMode(on: Boolean) = camera.setVideoMode(on)
    fun toggleRecording() = camera.toggleRecording()

    fun toggleGimbal() {
        OpLog.log("manual", "gimbal")
        drone?.let { d -> viewModelScope.launch { d.toggleGimbal() } }
    }

    fun returnHome() {
        OpLog.log("manual", "rth")
        drone?.let { d -> viewModelScope.launch { d.returnHome().onFailure { e -> _state.update { it.copy(message = "RTH 실패: ${e.message}") } } } }
    }

    fun confirmLanding() {
        OpLog.log("manual", "confirmLanding")
        drone?.let { d -> viewModelScope.launch { d.confirmLanding().onFailure { e -> Log.w("Manual", "착륙 확인 실패: ${e.message}") } } }
    }

    override fun onCleared() {
        (drone as? DjiDroneGateway)?.close()
        tone.release()
    }

    companion object {
        fun factory(dao: ProjectDao, appFilesDir: File?, rcButtons: Flow<RcButton>, settings: SettingsStore) =
            viewModelFactory { initializer { ManualFlightViewModel(dao, appFilesDir, rcButtons, settings) } }
    }
}
