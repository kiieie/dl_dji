package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.ShootMode
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.logTelemetry
import com.example.myfirstapp.data.ManualSessionEntity
import com.example.myfirstapp.data.ManualTrackEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.home.LatestMission
import com.example.myfirstapp.ui.home.loadLatestMission
import com.example.myfirstapp.data.listMbtiles
import com.example.myfirstapp.data.MapBackground
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.pickMapSource
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.mapBackgroundRetrying
import com.example.myfirstapp.drone.DjiDroneGateway
import com.example.myfirstapp.detect.LiveDetection
import com.example.myfirstapp.detect.PreciseDetection
import com.example.myfirstapp.drone.djiAircraftConnected
import com.example.myfirstapp.sound.ToneNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
    /** 착륙 뒤 정밀 탐지(9.6): 떠 있는 동안 쉬게 알린다. */
    private val precise: PreciseDetection? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ManualFlightUiState())
    val state: StateFlow<ManualFlightUiState> = _state.asStateFlow()
    private val drone: DroneGateway? = if (djiAircraftConnected()) DjiDroneGateway(viewModelScope, File(appFilesDir, "manual/kmz")) else null
    private val camera = CameraControl(
        viewModelScope, { drone }, { _state.value.flight }, { f -> _state.update { it.copy(flight = f(it.flight)) } }, "manual",
        onTimerPhoto = { info -> drone?.let { d -> saved(info, photos.keep(d, info, _state.value.flight.lens)) } },
    )
    private val tone = ToneNotifier()
    /** 셔터 사진의 마지막 사진 썸네일(9.6). 모델 설정이 없으면(시험) 만들지 않는다. */
    private val live = settings?.let {
        LiveDetection(viewModelScope, File(appFilesDir, "models"), it, precise?.gpuCache, precise?.thermal) { p ->
            _state.update { s -> s.copy(flight = s.flight.copy(lastPhoto = p)) }
        }
    }
    /** 셔터·타이머 사진: 남은 전주가 있는 최근 미션의 전주가 20m 안이면 그 전주, 아니면 기타(6.4). */
    private val photos = ManualPhotos(dao, appFilesDir, { dao.latestOpenMission() }, live, precise)

    init {
        OpLog.log("manual", "open", mapOf("connected" to (drone != null)))
        _state.update { it.copy(connected = drone != null, message = if (drone == null) "기체가 연결되지 않았습니다" else null) }
        drone?.let { d ->
            viewModelScope.launch {
                d.telemetry.collect { t ->
                    precise?.setFlying("manual", t.isFlying)
                    _state.update { it.copy(flight = it.flight.copy(telemetry = t, home = t.home).withTrack(t)) }
                }
            }
        }
        // 수동 비행 궤적(떠 있는 동안 1초마다): 뜬 때부터 내릴 때까지 한 번의 수동 비행(MANUAL_SESSION, 10.4.6 다시 보기)과 운용 로그(4.4)
        drone?.let { d ->
            viewModelScope.launch {
                while (true) {
                    delay(AppConfig.Polling.TRACK_MS)
                    val t = d.telemetry.value
                    val now = System.currentTimeMillis()
                    if (!t.isFlying) {
                        session?.let { dao.endManualSession(it, now) }
                        session = null
                        continue
                    }
                    logTelemetry("manual", t)
                    val id = session ?: dao.insertManualSession(ManualSessionEntity(startedAt = now, projectId = _state.value.latest?.projectId)).also { session = it }
                    dao.insertManualTrack(
                        ManualTrackEntity(
                            sessionId = id, at = now, lat = t.position?.lat, lon = t.position?.lon, altRel = t.altitudeM, speed = t.speedMps,
                            heading = t.headingDeg, gimbalPitch = t.gimbalPitchDeg, batteryPct = t.batteryPct.toInt(), flightMode = t.flightMode.name,
                        ),
                    )
                }
            }
        }
        drone?.let { d -> viewModelScope.watchObstacles(d, tone) { w -> _state.update { it.copy(flight = it.flight.copy(obstacles = w)) } } }
        drone?.let { d -> viewModelScope.launch { d.cameraStatus.collect { c -> _state.update { it.copy(flight = it.flight.copy(camera = c)) } } } }
        viewModelScope.launch {
            val files = appFilesDir?.let { listMbtiles(File(it, "maps")) }.orEmpty()
            _state.update { it.copy(flight = it.flight.copy(mapFiles = files)) }
            val bg = settings?.let { mapBackgroundRetrying(it) } ?: MapBackground.NONE
            _state.update { it.copy(flight = it.flight.copy(background = bg)) }
        }
        // 미니맵이 맞출 곳(실내 등 기체·홈·조종기 위치를 모를 때): 지난 비행 마지막 위치, 없으면 최근 미션 이륙 지점(11-04 RC: 흰 칸)
        viewModelScope.launch {
            val m = loadLatestMission(dao)
            val last = dao.lastTelemetryPosition()?.let { r -> r.lat?.let { la -> r.lon?.let { lo -> GeoPoint(la, lo) } } }
            _state.update { it.copy(latest = m, flight = it.flight.copy(mapFallback = last ?: m?.home)) }
        }
        // RC(10.5.10, Pilot 2 기본값): C1 광각/줌, C2 지도/카메라, 셔터·녹화는 앱이 찍고 녹화한다(6.4, 2026-11-04 고침). 이 화면 위에 설정 등이 열려 있을 때([active] 거짓)는 받지 않는다
        viewModelScope.launch { rcButtons.collect {
            if (!active) return@collect
            when (it) {
                RcButton.C1 -> toggleLens()
                RcButton.C2 -> swapView()
                RcButton.FIVE_D_CENTER, RcButton.SHUTTER -> shutter() // ○(C3)도 5D 누름으로 온다
                RcButton.RECORD -> camera.recordButton()
                else -> {}
            }
        } }
    }

    /** 화면이 맨 위에 보이는 동안만 참(화면이 [LifecycleResumeEffect]로 바꾼다). */
    var active = false

    /** 기록 중인 수동 비행(MANUAL_SESSION). 떠 있지 않으면 null. */
    private var session: Long? = null

    /** 화면 셔터: 영상 모드면 녹화 시작·정지, 타이머면 시작·멈춤(10.5.7), 아니면 [shoot](단일·SMART). */
    fun shutter() {
        val f = _state.value.flight
        when {
            f.videoMode -> toggleRecording()
            f.shootMode == ShootMode.TIMER -> camera.toggleTimer()
            else -> shoot()
        }
    }

    /** 사진 한 장(단일·SMART): 찍고 원본을 받는다. 찍는 동안([FlightUiState.capturing]) 다른 카메라 조작은 "촬영 중" 안내문. */
    fun shoot() {
        val d = drone ?: return
        if (_state.value.shooting) return
        val lens = _state.value.flight.lens
        _state.update { it.copy(shooting = true, flight = it.flight.copy(capturing = true)) }
        viewModelScope.launch {
            val shot = d.capture(lens, 0.0, 1.0)
            _state.update { it.copy(flight = it.flight.copy(capturing = false)) }
            val info = shot.getOrNull()
            saved(info, if (info == null) Result.failure(shot.exceptionOrNull() ?: IllegalStateException("촬영 실패")) else photos.keep(d, info, lens))
            _state.update { it.copy(shooting = false) }
        }
    }

    /** 사진 하나의 결과(셔터·타이머): 장수·안내 줄, 위치가 없으면 Pilot 2 "위치 레코딩 실패". */
    private fun saved(info: CaptureInfo?, result: Result<String>) {
        OpLog.log("manual", "shoot", mapOf("ok" to result.isSuccess, "saved" to result.getOrNull(), "error" to result.exceptionOrNull()?.message))
        if (info != null && info.position == null) camera.noPosition()
        _state.update {
            it.copy(
                shots = it.shots + if (result.isSuccess) 1 else 0,
                message = result.fold({ s -> "저장했습니다: $s" }, { e -> "촬영 실패: ${e.message}" }),
            )
        }
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

    /** 촬영 모드 메뉴(10.5.7)·☀ 노출(탭 초점 옆). */
    fun openShootMenu() = camera.openShootMenu()
    fun closeShootMenu() = camera.closeShootMenu()
    fun setShootMode(mode: ShootMode) { camera.setShootMode(mode) }
    fun setTimer(sec: Double) = camera.setTimer(sec)
    fun setEv(ev: Double) = camera.setEv(ev)

    /** 짐벌 메뉴(10.5.7): 짐벌 중앙 / 아래(-90°). */
    fun setGimbal(down: Boolean) {
        OpLog.log("manual", "gimbal", mapOf("down" to down))
        drone?.let { d -> viewModelScope.launch { d.setGimbal(down).onFailure { e -> _state.update { it.copy(message = "짐벌 실패: ${e.message}") } } } }
    }

    /** RC C1·왼쪽 열 ZOOM/WIDE: 광각 ↔ 망원 보기. */
    fun toggleLens() = setLens(if (_state.value.flight.lens == Lens.WIDE) Lens.ZOOM else Lens.WIDE)

    /** RC C2·미니맵 누름: 큰 화면 지도 ↔ 카메라. */
    fun swapView() = _state.update { it.copy(flight = it.flight.copy(mapMain = !it.flight.mapMain)) }

    /** 노출 띠 AFS(초점 모드 차례로)·탭 초점(10.5.2). */
    fun cycleFocus() = camera.cycleFocus(_state.value.flight.camera.focus)
    fun focusAt(x: Double, y: Double) = camera.focusAt(x, y)

    fun confirmLanding() {
        OpLog.log("manual", "confirmLanding")
        drone?.let { d -> viewModelScope.launch { d.confirmLanding().onFailure { e -> Log.w("Manual", "착륙 확인 실패: ${e.message}") } } }
    }

    override fun onCleared() {
        // 떠 있는 채로 화면을 떠나면 그 수동 비행을 닫는다(viewModelScope는 이미 끝나 따로 쓴다)
        session?.let { id -> CoroutineScope(Dispatchers.IO).launch { dao.endManualSession(id, System.currentTimeMillis()) } }
        (drone as? DjiDroneGateway)?.close()
        tone.release()
        live?.close()
        precise?.setFlying("manual", false)
    }

    companion object {
        fun factory(dao: ProjectDao, appFilesDir: File?, rcButtons: Flow<RcButton>, settings: SettingsStore, precise: PreciseDetection) =
            viewModelFactory { initializer { ManualFlightViewModel(dao, appFilesDir, rcButtons, settings, precise) } }
    }
}
