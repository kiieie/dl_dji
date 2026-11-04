package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.logTelemetry
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.executor.PreflightEvaluator
import com.example.myfirstapp.core.executor.PreflightCheck
import com.example.myfirstapp.core.model.CameraStatus
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.route.Viewpoints
import com.example.myfirstapp.core.model.ObstacleDirection
import com.example.myfirstapp.core.model.ObstacleWarning
import com.example.myfirstapp.core.model.Obstacles
import com.example.myfirstapp.core.executor.ExecutorConfig
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.RouteSummary
import com.example.myfirstapp.ui.procedure.ProcedurePage
import com.example.myfirstapp.data.TelemetrySampleEntity
import com.example.myfirstapp.drone.DjiDroneGateway
import com.example.myfirstapp.drone.djiAircraftConnected
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.executor.Command
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.executor.ShutterRoute
import com.example.myfirstapp.core.executor.shutterRoute
import com.example.myfirstapp.core.model.PhotoModes
import com.example.myfirstapp.core.model.ShootMode
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.executor.ExecutorUiState
import com.example.myfirstapp.core.executor.InspectionExecutor
import com.example.myfirstapp.core.executor.recoveredCaptures
import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.core.port.StorageMonitor
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.LineNumbers
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.SortiePlan
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.RecorderSortie
import com.example.myfirstapp.data.RoomRecorder
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.fakePhotoSource
import com.example.myfirstapp.data.listMbtiles
import com.example.myfirstapp.data.MapBackground
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.pickMapSource
import com.example.myfirstapp.data.mapBackgroundRetrying
import com.example.myfirstapp.data.toEntity
import com.example.myfirstapp.data.toJson
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.detect.LastPhoto
import com.example.myfirstapp.detect.LiveDetection
import com.example.myfirstapp.detect.PreciseDetection
import com.example.myfirstapp.core.port.InspectionRecorder
import com.example.myfirstapp.sound.ToneNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 미션이 날기 시작하면(또는 공중 상태가 되면) 절차 화면을 닫는다(10.5.5). */
private val CLOSES_PROCEDURE = setOf(ExecState.FlyingLeg, ExecState.Paused, ExecState.ReturningHome, ExecState.LinkLost)

/** 모의 기체의 시간 배속. 시연과 스모크 시험이 몇 분 안에 끝나게 한다. 실제 기체에는 해당 없음. */
const val FAKE_TIME_SCALE = 5.0

data class FlightUiState(
    val loading: Boolean = true,
    /** 미션을 열 수 없는 이유(없는 미션, 남은 전주 없음). */
    val error: String? = null,
    val exec: ExecutorUiState = ExecutorUiState(),
    val telemetry: Telemetry? = null,
    /** 지금 위치에서 이륙 지점까지 돌아가는 데 필요한 배터리(%). */
    val returnNeedPct: Int = 0,
    val home: GeoPoint? = null,
    val poles: List<Pole> = emptyList(),
    /** 지도 배경: 앱 폴더의 MBTiles와 설정에서 고른 배경(Bing·VWorld, 7.4). 계획 화면과 같다. */
    val mapFiles: List<File> = emptyList(),
    val background: MapBackground = MapBackground.NONE,
    /** 모의 기체면 true: 모의 RC 조작 줄을 보여 준다. */
    val simulated: Boolean = false,
    /** 왼쪽 아래 "마지막 사진"(9.6 실시간 1단계): 가장 최근에 저장한 사진과 설비 상자. 아직 없으면 null. */
    val lastPhoto: LastPhoto? = null,
    /** FPV 렌즈(Pilot 2 카메라 뷰 전환). */
    val lens: Lens = Lens.WIDE,
    /** 망원 줌 배율(10.4.3 줌 +/−). 광각이면 1.0X로 보인다. */
    val zoomRatio: Double = FlightParams().zoomRatio,
    /** 사진/영상 전환에서 영상(셔터 = 녹화). */
    val videoMode: Boolean = false,
    /** 녹화를 시작한 시각. 녹화 중이 아니면 null. */
    val recordingSinceMs: Long? = null,
    /** 열린 절차 화면(10.5.5): 비행 전 점검 → 웨이포인트 경로 체크리스트. 닫혀 있으면 null. */
    val page: ProcedurePage? = null,
    /** 미션 파라미터(촬영 방식 포함). 미니맵에 실제 촬영 지점 경로를 그린다. */
    val params: FlightParams? = null,
    /** 충돌 경고(13번): 방향별 등급·거리. 내비 원판 부채꼴과 상단 상태 칸에 보인다. */
    val obstacles: ObstacleWarning = ObstacleWarning(),
    /** 지도 비행 궤적(10.5.4 주황 선): 떠 있는 동안의 기체 위치, 개수 상한([withTrack]). 지도 지우개로 비운다. */
    val track: List<GeoPoint> = emptyList(),
    /** 노출 띠(10.5.2): 기체 카메라 노출·초점·남은 저장. */
    val camera: CameraStatus = CameraStatus(),
    /** 큰 화면이 지도(10.5.4 미니맵을 누르거나 RC C2). */
    val mapMain: Boolean = false,
    /** 내비 원판 왼쪽 위 다음 웨이포인트(10.5.3). 미션 비행 중에만. */
    val nextWaypoint: NextWaypoint? = null,
    /** 기체·홈·조종기 위치를 모를 때(실내 수동 비행) 지도를 맞출 곳: 지난 비행 마지막 위치 또는 최근 미션 이륙 지점. */
    val mapFallback: GeoPoint? = null,
    /**
     * 미션 칸 수(10.5.2, 홈·라이브러리와 같은 정의): 미션의 모든 방문 전주 수, 이 화면을 열 때 이미 끝난(완료·건너뜀·실패) 전주 수,
     * 이미 찍은 사진 수(CAPTURE 행), 예상 사진 수(전주마다 [FlightParams.photosPerPole] 합). 실행기는 남은 전주만 가지므로 여기에 더해 보인다.
     */
    val missionPoles: Int = 0,
    val finishedBefore: Int = 0,
    val photosBefore: Int = 0,
    val expectedPhotos: Int = 0,
    /**
     * 소티 번호(10.5.11 "소티 지금/전체", 홈·라이브러리와 같은 정의): 실행기 소티 순번마다 미션 안 소티 번호(1부터)와 미션의 모든 소티 수.
     * 실행기는 남은 전주가 있는 소티만 가지므로 순번과 번호가 다를 수 있다.
     */
    val sortieNumbers: List<Int> = emptyList(),
    val sortieTotal: Int = 0,
    /** 경로 이름(Pilot 2 미션 칸 이름): "<프로젝트> #<미션>". */
    val routeName: String = "",
    /** 촬영 모드 메뉴(10.5.7): 열림, 고른 사진 모드, 타이머 간격(초), 타이머 촬영 중, 기체가 받는 모드(메뉴를 처음 열 때 읽음). */
    val shootMenu: Boolean = false,
    val shootMode: ShootMode = ShootMode.SINGLE,
    val timerSec: Double = AppConfig.CameraView.TIMER_DEFAULT_SEC,
    val timerRunning: Boolean = false,
    val photoModes: PhotoModes = PhotoModes.BASIC,
    /** 앱이 한 장 찍는 중(사진 알림 전, SMART는 몇 초): 다른 카메라 조작은 "촬영 중" 안내문. */
    val capturing: Boolean = false,
    /** Pilot 2 안내문(10.5.7). */
    val notice: CameraNotice? = null,
    /** 땅 위·타이머 수동 사진(수동 사진 경로)을 저장한 곳 또는 실패(미션 화면 안내 줄). */
    val shotMessage: String? = null,
) {
    /** 화면을 떠나도 되는 상태. 연결 끊김도 포함(기체 없이 복구로 열렸을 때 갇히지 않게, 다시 열면 복구가 이어짐). */
    val canExit get() = exec.state in setOf(ExecState.Idle, ExecState.Uploaded, ExecState.SortieDone, ExecState.MissionDone, ExecState.LinkLost)
    /** 지금 소티의 미션 안 번호(1부터). */
    val sortieNumber get() = sortieNumbers.getOrElse(exec.sortieIndex) { exec.sortieIndex + 1 }

    /** 줌·사진/영상·초점을 바꿔도 되는 때: 일시정지 중, 또는 자동 비행 구간이 아닐 때(땅·RTH, Pilot 2처럼 언제든). 지점 사진 중에는 막는다. */
    val cameraFree get() = exec.state == ExecState.Paused || !exec.state.airborne

    /** 화면 셔터를 누를 수 있는 때: 자동 비행 중이 아닐 때(일시정지·땅·RTH, [shutterRoute]), 녹화·타이머 중(멈추기). */
    val shutterFree get() = recordingSinceMs != null || timerRunning || shutterRoute(exec.state, telemetry?.isFlying == true) != ShutterRoute.BLOCKED
}

/**
 * 비행 화면 ④(10장). 저장한 `MISSION`에서 남은(PENDING·IN_PROGRESS) 방문만 읽어 소티를 만들고 실행기를 돌린다.
 * 실행기는 [viewModelScope]에서 돌아 설정 화면에 다녀와도 이어진다. 판독은 미션이 끝난 뒤 결과 화면에서 한다(10장).
 * 기체는 [droneFactory]로 받는다. 기체 연동 전에는 [FakeDroneGateway]이고, 연동하면 `DjiDroneGateway`로 바꾼다.
 */
class FlightViewModel(
    private val dao: ProjectDao,
    private val projectId: Long,
    private val missionId: Long,
    private val appFilesDir: File?,
    private val settings: SettingsStore,
    /** (scope, 이륙 지점, 앱 폴더, 복구 위치). 복구 위치는 모의 기체가 떠 있는 상태로 시작할 곳이고 실제 기체는 쓰지 않는다. */
    private val droneFactory: suspend (CoroutineScope, GeoPoint, File?, GeoPoint?) -> DroneGateway = { s, h, d, a -> fakeDrone(s, h, d, a) },
    /** RC 하드웨어 버튼 누름. 화면 버튼과 같은 동작에 매핑한다(10장). */
    private val rcButtons: Flow<RcButton> = emptyFlow(),
    /** 착륙 뒤 정밀 탐지(9.6): 떠 있는 동안 쉬게 알리고, 사진을 저장하면 깨운다. */
    private val precise: PreciseDetection? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(FlightUiState())
    val state: StateFlow<FlightUiState> = _state.asStateFlow()
    private val camera = CameraControl(
        viewModelScope, { drone }, { _state.value }, { f -> _state.update(f) }, "flight",
        onTimerPhoto = { info -> drone?.let { d -> manualSaved(info, photos.keep(d, info, _state.value.lens)) } },
    )

    private val notifier = ToneNotifier()
    /** 비행 중 최근 1장 탐지(9.6). 실행기는 사진을 저장만 하고, 저장한 사진을 여기에 넘긴다. */
    private val live = LiveDetection(viewModelScope, File(appFilesDir, "models"), settings, precise?.gpuCache, precise?.thermal) { p ->
        _state.update { it.copy(lastPhoto = p) }
    }
    /** 땅 위 셔터·타이머 사진(수동 사진 경로, 10.4.3): 이 미션의 전주가 20m 안이면 그 전주, 아니면 기타. 실행기를 거치지 않는다. */
    private val photos = ManualPhotos(dao, appFilesDir, { dao.mission(missionId) }, live, precise)
    private var executor: InspectionExecutor? = null
    private var drone: DroneGateway? = null
    private var energy: EnergyModel? = null
    private var plans: List<SortiePlan> = emptyList()
    private var flightParams: FlightParams? = null
    /** 실행기 소티 순번 → 그 소티의 방문 id. */
    private var sortieVisits: List<List<Long>> = emptyList()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { rcButtons.collect(::onRcButton) }
        viewModelScope.launch {
            val files = appFilesDir?.let { listMbtiles(File(it, "maps")) }.orEmpty()
            _state.update { it.copy(mapFiles = files) }
            val bg = mapBackgroundRetrying(settings)
            _state.update { it.copy(background = bg) }
        }
    }

    /**
     * RC 버튼(10.5.10, Pilot 2 기본값): 일시정지는 실제 기체면 펌웨어가 멈추고 어댑터가 알리므로 모의 기체만 흉내 낸다.
     * C1 광각/줌, C2 지도/카메라, 일시정지 중 5D 누름 = 미션 칸 ▶ 재개. 셔터·녹화는 화면 버튼과 같다.
     */
    private fun onRcButton(b: RcButton) {
        Log.i("RcKey", "$b state=${_state.value.exec.state}")
        when (b) {
            RcButton.PAUSE -> rcPause()
            RcButton.C1 -> if (active) toggleLens() // 판독 화면 C1(다음 사진)과 겹치지 않게 맨 위일 때만
            RcButton.C2 -> if (active) swapView()
            // 5D·○ 누름은 다른 화면(앨범·설정)에서도 오므로 이 화면이 맨 위이고 절차 화면이 없을 때만 재개로 받는다
            RcButton.FIVE_D_CENTER -> if (paused && active && _state.value.page == null) resume()
            RcButton.RTH -> if (drone is FakeDroneGateway) returnHome() // 실제 기체는 펌웨어 RTH
            // RC 셔터·녹화는 앱이 찍고 녹화한다(6.4, 2026-11-04). 화면 셔터와 같은 자리로 간다(자동 비행 중만 막음, [shutterRoute])
            RcButton.SHUTTER -> if (active) shutter(rc = true)
            RcButton.RECORD -> if (paused || _state.value.recordingSinceMs != null) camera.recordButton()
            else -> Unit // 5D 방향은 비행 명령에 쓰지 않는다(10장)
        }
    }

    private suspend fun load() {
        val mission = dao.mission(missionId) ?: return _state.update { it.copy(loading = false, error = "미션을 찾을 수 없습니다") }
        val params = flightParamsFromJson(mission.flightParams)
        projectName = dao.projectName(projectId).orEmpty()
        val energy = EnergyModel(EnergyParams(), params).also { energy = it }
        val home = GeoPoint(mission.homeLat, mission.homeLon)
        val dbSorties = dao.sorties(missionId)
        // 시작했지만 끝나지 않은 소티가 있으면 앱이 비행 중에 죽은 것이다(6.4 복구)
        val open = dbSorties.firstOrNull { it.startedAt != null && it.endedAt == null }
        val lastTarget = dao.missionVisits(missionId).firstOrNull { it.sortieId == open?.id && it.status == "IN_PROGRESS" }
        val drone = droneFactory(viewModelScope, home, appFilesDir, open?.let { lastTarget?.let { GeoPoint(it.lat, it.lon) } ?: home })
            .also { drone = it }
        // SD 대조는 기체가 붙어 있을 때만(없으면 진행 중 전주는 그대로 두고 남은 전주로 다시 감)
        if (open != null && drone.telemetry.value.connected) recoverTarget(open.id, drone)

        val all = dao.missionVisits(missionId)
        val rows = all.filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }
        if (rows.isEmpty() && open == null) return _state.update { it.copy(loading = false, error = "남은 전주가 없습니다(미션 완료)") }
        val bySortie = rows.groupBy { it.sortieId }
        // 복구할 소티는 남은 전주가 없어도 넣는다(착륙·소티 종료를 그 소티에 기록해야 한다)
        val groups = dbSorties.filter { it.id in bySortie || it.id == open?.id }.map { it.id to bySortie[it.id].orEmpty() }
        sortieVisits = groups.map { (_, g) -> g.map { it.visitId } }
        flightParams = params
        // 4방향 사선의 선로 방향은 프로젝트 전체 전주의 선로번호로 정한다(4.5.6)
        val bearings = LineNumbers.bearings(dao.observePoles(projectId).first().map { it.toPole() })
        val sorties = groups.map { (_, g) ->
            val poles = g.map { it.toPole().copy(lineBearingDeg = bearings[it.poleId]) }
            // 계획(RoutePlanner)과 같은 셈: 이륙 지점 왕복 + 전주 사이 + 사선 전주 둘레(5.6), 이동 고도 오르내림
            val legs = poles.zipWithNext { a, b -> LocalProjection.distanceM(a.position, b.position) }
            val homeLegs = if (poles.isEmpty()) 0.0 else LocalProjection.distanceM(home, poles.first().position) + LocalProjection.distanceM(poles.last().position, home)
            val dist = energy.pathM(homeLegs, legs.sum(), poles.size)
            val jumps = energy.longJumps(legs)
            val extra = energy.equipmentShots(poles)
            SortiePlan(poles, dist, energy.sortieCost(dist, poles.size, jumps, extra), energy.sortieDurationSec(dist, poles.size, jumps, extra))
        }
        plans = sorties
        val room = RoomRecorder(
            dao, missionId,
            groups.map { (id, g) -> RecorderSortie(id, g.map { it.visitId to it.poleId }) },
            File(appFilesDir, "projects/$projectId/missions/$missionId/images"),
            params.downloadMode,
        )
        // 저장한 사진은 비행 중 최근 1장 탐지와 착륙 뒤 정밀 탐지로 넘긴다(9.6). 실행기를 기다리게 하지 않는다
        val recorder = object : InspectionRecorder by room {
            override suspend fun saveImage(captureId: Long, bytes: ByteArray) {
                room.saveImage(captureId, bytes)
                viewModelScope.launch { live.offer(bytes, dao.capture(captureId)?.filePath) }
                precise?.kick()
            }
        }
        val storage = StorageMonitor { (appFilesDir?.usableSpace ?: 0L) >= AppConfig.Storage.MIN_FREE_BYTES }
        // 탐지는 실행기 밖에서 한다(9.6 두 단계 탐지): 실행기는 사진을 받아 저장만 한다
        val executor = InspectionExecutor(
            viewModelScope, drone, recorder, notifier, storage, energy, null, sorties,
            // 6.1 방식 A: 소티 미션 하나로 계속 비행(모의 기체도 같은 흐름)
            config = ExecutorConfig(sortieMission = true),
            recoverSortie = open?.let { o -> groups.indexOfFirst { it.first == o.id } },
        ).also { executor = it }
        executor.start()
        // 비행 궤적(TELEMETRY_SAMPLE, 4.2): 떠 있는 동안 1초마다 그 소티에 남긴다
        viewModelScope.launch {
            while (true) {
                delay(AppConfig.Polling.TRACK_MS)
                val t = drone.telemetry.value
                val sortieId = groups.getOrNull(executor.uiState.value.sortieIndex)?.first
                if (t.isFlying) logTelemetry("auto", t)
                if (sortieId != null && t.isFlying) dao.insertTelemetry(
                    TelemetrySampleEntity(
                        sortieId = sortieId, at = System.currentTimeMillis(), lat = t.position?.lat, lon = t.position?.lon,
                        altRel = t.altitudeM, speed = t.speedMps, heading = t.headingDeg, gimbalPitch = t.gimbalPitchDeg,
                        batteryPct = t.batteryPct.toInt(), flightMode = t.flightMode.name,
                    ),
                )
            }
        }
        viewModelScope.watchObstacles(drone, notifier) { w -> _state.update { it.copy(obstacles = w) } }
        viewModelScope.launch { drone.cameraStatus.collect { c -> _state.update { it.copy(camera = c) } } }
        _state.update {
            it.copy(
                loading = false, home = home, poles = sorties.flatMap { s -> s.poles }, params = params, zoomRatio = params.zoomRatio,
                simulated = drone is FakeDroneGateway,
                missionPoles = all.size, finishedBefore = all.size - rows.size, photosBefore = dao.missionCaptures(missionId).size,
                expectedPhotos = params.expectedPhotos(all.map { r -> r.toPole() }),
                sortieNumbers = groups.map { (id, _) -> dbSorties.indexOfFirst { d -> d.id == id } + 1 }, sortieTotal = dbSorties.size,
                routeName = "$projectName #$missionId",
            )
        }
        combine(executor.uiState, drone.telemetry) { ui, t -> ui to t }.collect { (ui, t) ->
            val need = if (!t.isFlying) 0 else energy.returnPct(t.position?.let { LocalProjection.distanceM(it, home) } ?: 0.0).toInt()
            val wasPaused = paused
            precise?.setFlying("auto", ui.state.airborne || t.isFlying)
            _state.update {
                it.copy(exec = ui, telemetry = t, returnNeedPct = need, nextWaypoint = nextWaypoint(ui, t)).withTrack(t)
                    .let { s -> if (ui.state in CLOSES_PROCEDURE) s.copy(page = null) else s }
            }
            // RC 재개 등으로 일시정지를 벗어나면 녹화를 멈추고 사진 모드로(10.4.3)
            if (wasPaused && ui.state != ExecState.Paused) camera.backToPhoto()
        }
    }

    /**
     * 6.4 SD 대조: 죽을 때의 목표 전주에 이미 찍은 사진(DB 기록 또는 DB에 없는 SD 사진)이 있으면 연결하고 완료로 둔다.
     * 사진이 없으면 미점검으로 되돌려 재개할 때 다시 간다. SD 사진의 이미지는 비행 후 원본 다운로드로 받는다.
     */
    private suspend fun recoverTarget(sortieId: Long, drone: DroneGateway) {
        val v = dao.visits(sortieId).firstOrNull { it.status == "IN_PROGRESS" } ?: return
        val db = dao.missionCaptures(missionId)
        val sd = v.arrivedAt?.let { drone.listMedia(it).getOrNull() }.orEmpty()
        val fresh = recoveredCaptures(v.arrivedAt, sd, db.map { it.mediaId }.toSet())
        fresh.forEach { dao.insertCapture(it.toEntity(v.id, CaptureSource.AUTO)) }
        val done = fresh.isNotEmpty() || db.any { it.visitId == v.id }
        dao.setVisitStatus(v.id, if (done) "DONE" else "PENDING", null)
    }

    fun start() = afterPhotoReady { send(Command.Start) }

    /** [비행 시작]·상단 바 비행 상태 글자: Pilot 2처럼 비행 전 점검 화면을 연다(판정은 실행기도 다시 한다, 6.7). */
    fun openPreflight() = _state.update { it.copy(page = ProcedurePage.PREFLIGHT) }

    fun closeProcedure() = _state.update { it.copy(page = null) }

    /** 비행 전 점검 화면이 읽는 기체 값: 실행기와 같은 기체(모의 기체면 모의 값). */
    suspend fun preflightReport(): PreflightReport? = drone?.preflight()

    /** 비행 전 점검 판정(6.7): 다음 소티의 배터리 예상·첫 전주까지 거리 포함. */
    fun checksFor(report: PreflightReport): List<PreflightCheck> {
        val p = flightParams ?: return PreflightEvaluator.manual(report)
        val ui = _state.value.exec
        val index = nextSortieIndex()
        val first = ui.visits.firstOrNull { it.sortieIndex == index && it.status == VisitStatus.PENDING }?.pole?.position
        return PreflightEvaluator.checklist(report, p, plans.getOrNull(index)?.estBatteryPct ?: 0.0, first)
    }

    /**
     * 비행 전 점검 [다음] → 웨이포인트 경로 체크리스트(10.5.5). [ignoreWarnings]면 경고 항목을 운용자가 보고 넘어간 것이다
     * (업로드 때 실행기에 넘긴다, 기체 연결·LDM은 여전히 필요). 폴더 이름이 없으면 "<프로젝트>-#<미션>".
     */
    fun confirmPreflight(ignoreWarnings: Boolean = false) {
        OpLog.log("preflight", "next", mapOf("warned" to ignoreWarnings))
        warned = ignoreWarnings
        flightParams?.takeIf { it.photoFolder == null }?.let { updateChecklist(it.copy(photoFolder = "$projectName-#$missionId")) }
        _state.update { it.copy(page = ProcedurePage.CHECKLIST) }
    }

    /** 체크리스트 [뒤로] → 비행 전 점검. */
    fun backToPreflight() = _state.update { it.copy(page = ProcedurePage.PREFLIGHT) }

    /** 체크리스트 값(안전한 이륙 고도·시작 위치로 상승·속도·완료 동작·신호 끊김·폴더)을 고친다. 미션 JSON에 남긴다. 올린 뒤에는 바꾸지 않는다. */
    fun updateChecklist(p: FlightParams) {
        if (_state.value.exec.state !in setOf(ExecState.Idle, ExecState.SortieDone)) return
        flightParams = p
        _state.update { it.copy(params = p) }
        viewModelScope.launch { dao.setMissionParams(missionId, p.toJson()) }
    }

    /** 체크리스트 [비행 임무 업로드]: 점검 → 업로드까지(실행기 [ExecState.Uploaded]). 땅에서 고른 타이머·SMART·영상은 먼저 단일 사진으로(KMZ 촬영 동작). */
    fun upload() = afterPhotoReady { send(Command.Upload(flightParams, warned)) }

    /** 체크리스트 [시작]: 올린 미션을 이륙·시작. */
    fun startUploaded() = afterPhotoReady { send(Command.Start) }

    /** 체크리스트 [업로드 취소]: 시작하지 않고 대기로(값을 다시 고칠 수 있음). */
    fun cancelUpload() = send(Command.CancelUpload)

    /** 체크리스트 요약(거리·예상 시간·웨이포인트·사진): 다음 소티. */
    fun checklistSummary(): RouteSummary? = flightParams?.let { p -> plans.getOrNull(nextSortieIndex())?.let { RouteSummary.of(listOf(it), p) } }

    private fun nextSortieIndex(): Int {
        val ui = _state.value.exec
        return plans.indices.firstOrNull { i -> ui.visits.any { it.sortieIndex == i && it.status == VisitStatus.PENDING } } ?: ui.sortieIndex
    }

    /** 비행 전 점검에서 경고를 보고 넘어왔는지. */
    private var warned = false
    private var projectName = ""

    /** 배터리를 교체했다고 보고 다음 소티: 비행 전 점검 → 체크리스트(10.5.5). 모의 기체는 배터리를 100%로 채운다. */
    fun nextSortie() {
        (drone as? FakeDroneGateway)?.setBattery(100.0)
        openPreflight()
    }

    fun endMission() = send(Command.EndMission)
    private var resuming: Job? = null

    /**
     * 재개(미션 칸 ▶·일시정지 중 RC 5D 누름): 영상 모드였으면 사진 모드로 돌린 뒤 재개한다(지점 사진을 놓치지 않게).
     * 카메라가 답하지 않아도 [AppConfig.Timeouts.RESUME_CAMERA_MS] 뒤에는 재개한다. 기다리는 동안 다시 누른 것은 무시한다.
     */
    fun resume() = afterPhotoReady(restoreZoom = true) { send(Command.Resume) }

    /**
     * 카메라를 미션 사진 상태(타이머·녹화 정지, 사진, 단일, 재개면 미션 망원 배율)로 돌린 뒤 [then]. 카메라가 답하지 않아도
     * [AppConfig.Timeouts.RESUME_CAMERA_MS] 뒤에는 [then]. 기다리는 동안 다시 누른 것은 무시한다.
     */
    private fun afterPhotoReady(restoreZoom: Boolean = false, then: () -> Unit) {
        if (resuming?.isActive == true) return
        resuming = viewModelScope.launch {
            withTimeoutOrNull(AppConfig.Timeouts.RESUME_CAMERA_MS) {
                camera.backToPhotoAndWait()
                if (restoreZoom) flightParams?.takeIf { Lens.ZOOM in it.lenses }?.let { camera.restoreZoom(it.zoomRatio) }
                true
            } ?: OpLog.log("flight", "resumeCameraTimeout")
            then()
        }
    }

    /** 화면 [Ⅱ 수동 비행](Pilot 2 55쪽 경로 알약): RC 일시정지 버튼과 같다. 기체가 멈춰 호버링하고 스틱으로 조종한다. */
    fun pause() = send(Command.Pause)
    fun manualCapture() = send(Command.ManualCapture)

    /** 화면 셔터. */
    fun shutter() = shutter(rc = false)

    /**
     * 셔터(10.4.3, Pilot 2처럼 자동 비행 중이 아니면 언제나): 영상 모드면 녹화, 타이머면 시작·멈춤(수동 사진 경로), 사진이면 [shutterRoute]대로 —
     * 일시정지 중 화면 셔터는 수동 촬영(목표 전주 완료), RC 셔터와 떠 있는 대기는 실행기 셔터 사진, 땅 위·RTH 중은 수동 사진 경로([manualShot]).
     */
    private fun shutter(rc: Boolean) {
        val st = _state.value
        if (!st.shutterFree) return
        when {
            st.videoMode -> toggleRecording()
            st.shootMode == ShootMode.TIMER -> camera.toggleTimer()
            else -> when (shutterRoute(st.exec.state, st.telemetry?.isFlying == true)) {
                ShutterRoute.EXECUTOR -> if (paused && !rc) manualCapture() else send(Command.RcShutter(st.lens))
                ShutterRoute.MANUAL -> manualShot()
                ShutterRoute.BLOCKED -> Unit
            }
        }
    }

    /** 수동 사진 한 장(땅 위·RTH 중): 찍고 받아 가까운 전주 또는 기타로. 실행기의 지점 사진 가르기에 넣지 않는다. */
    private fun manualShot() {
        val d = drone ?: return
        if (_state.value.capturing) return
        val lens = _state.value.lens
        OpLog.log("flight", "manualShot", mapOf("lens" to lens.name, "state" to _state.value.exec.state.name))
        _state.update { it.copy(capturing = true) }
        viewModelScope.launch {
            val shot = d.capture(lens, 0.0, 1.0)
            _state.update { it.copy(capturing = false) }
            val info = shot.getOrNull()
            manualSaved(info, if (info == null) Result.failure(shot.exceptionOrNull() ?: IllegalStateException("촬영 실패")) else photos.keep(d, info, lens))
        }
    }

    /** 수동 사진 경로 결과: 안내 줄, 위치가 없으면 Pilot 2 "위치 레코딩 실패". */
    private fun manualSaved(info: CaptureInfo?, result: Result<String>) {
        OpLog.log("flight", "manualSaved", mapOf("ok" to result.isSuccess, "saved" to result.getOrNull(), "error" to result.exceptionOrNull()?.message))
        if (info != null && info.position == null) camera.noPosition()
        _state.update { it.copy(shotMessage = result.fold({ w -> "저장했습니다: $w" }, { e -> "촬영 실패: ${e.message}" })) }
    }
    fun skip() = send(Command.Skip())
    /** 지형 추적 시작 거절 창(5.6): [이륙 기준으로 시작] / [취소]. */
    fun flyTakeoffHeight() = send(Command.FlyTakeoffHeight)
    fun dismissTerrainFallback() = send(Command.DismissTerrainFallback)
    fun markDone() = send(Command.MarkDone)
    /**
     * RTH(RC RTH 버튼은 모의 기체만, 모의 RC 줄 [RTH]): 미션 중이면 실행기가 처리한다(방문 정리 포함). 실행기는 대기인데 기체가 떠 있으면
     * (수동 비행에서 넘어옴, 소티·미션 완료, 재시작 복구) 기체에 바로 RTH를 보낸다.
     */
    fun returnHome() {
        if (_state.value.exec.state.airborne) {
            send(Command.ReturnHome)
            return
        }
        val d = drone ?: return
        OpLog.log("flight", "rthDirect")
        viewModelScope.launch {
            d.returnHome().onFailure { e -> _state.update { it.copy(exec = it.exec.copy(message = "RTH 실패: ${e.message}")) } }
        }
    }

    /** 지도 지우개(10.5.4): 비행 궤적을 지운다(기록 TELEMETRY_SAMPLE은 그대로). */
    fun clearTrack() = _state.update { it.copy(track = emptyList()) }

    /** 지도 레이어(10.5.7): 배경을 바꾸고 설정에도 남긴다. */
    fun setMapSource(source: MapSource) { viewModelScope.launch { settings.pickMapSource(source).let { bg -> _state.update { it.copy(background = bg) } } } }

    /** FPV 렌즈 전환(Pilot 2 카메라 뷰 전환). */
    fun setLens(lens: Lens) = camera.setLens(lens)

    private val paused get() = _state.value.exec.state == ExecState.Paused

    /** 비행 화면이 맨 위에 보이는 동안만 참(화면이 [androidx.lifecycle.compose.LifecycleResumeEffect]로 바꾼다). */
    var active = false

    /** 줌 +/−·사진/영상·녹화는 자동 비행 구간이 아닐 때만([FlightUiState.cameraFree], 10.4.3). 지점 사진(광각)은 건드리지 않는다. */
    fun zoom(up: Boolean) { if (_state.value.cameraFree) camera.zoom(up) }
    fun setVideoMode(on: Boolean) { if (_state.value.cameraFree) camera.setVideoMode(on) }
    fun toggleRecording() { if (_state.value.cameraFree || _state.value.recordingSinceMs != null) camera.toggleRecording() }

    /** 촬영 모드 메뉴(10.5.7): 다른 카메라 조작처럼 자동 비행 구간이 아닐 때만. */
    fun openShootMenu() { if (_state.value.cameraFree) camera.openShootMenu() }
    fun closeShootMenu() = camera.closeShootMenu()
    fun setShootMode(mode: ShootMode) { if (_state.value.cameraFree) camera.setShootMode(mode) }
    fun setTimer(sec: Double) { if (_state.value.cameraFree) camera.setTimer(sec) }

    /** ☀ 노출 손잡이(탭 초점 옆, 10.5.7): 탭 초점과 같은 때만. */
    fun setEv(ev: Double) { if (_state.value.cameraFree) camera.setEv(ev) }

    /** 짐벌 메뉴(10.5.7): 짐벌 중앙 / 아래(-90°). */
    fun setGimbal(down: Boolean) {
        OpLog.log("flight", "gimbal", mapOf("down" to down))
        drone?.let { d -> viewModelScope.launch { d.setGimbal(down).onFailure { e -> Log.w("Flight", "짐벌 실패: ${e.message}") } } }
    }

    /** RC C1·왼쪽 열 ZOOM/WIDE(10.5.10): 광각 ↔ 망원 보기. */
    fun toggleLens() = setLens(if (_state.value.lens == Lens.WIDE) Lens.ZOOM else Lens.WIDE)

    /** RC C2·미니맵 누름(10.5.4): 큰 화면 지도 ↔ 카메라. */
    fun swapView() = _state.update { it.copy(mapMain = !it.mapMain) }

    /** 노출 띠 AFS·탭 초점(10.5.2): 자동 비행 구간이 아닐 때만(지점 사진 초점을 건드리지 않게). */
    fun cycleFocus() { if (_state.value.cameraFree) camera.cycleFocus(_state.value.camera.focus) }
    fun focusAt(x: Double, y: Double) { if (_state.value.cameraFree) camera.focusAt(x, y) }

    /** 소티마다 펼친 촬영 지점 번호(전주 id → 그 전주 첫 지점 번호, 지도 웨이포인트 번호와 같음). */
    private val waypointNumbers = mutableMapOf<Int, Map<String, Int>>()

    /**
     * 내비 왼쪽 위 다음 웨이포인트(10.5.3): 미션 비행 중 목표 전주의 첫 촬영 지점 번호와 기체에서 그 전주까지 수평 거리.
     * ponytail: 4방향 사선은 전주의 첫 지점 번호만 보인다. 지점마다 바꾸려면 어댑터의 지금 웨이포인트 번호를 상태로 내보낸다.
     */
    private fun nextWaypoint(ui: ExecutorUiState, t: Telemetry): NextWaypoint? {
        val target = ui.target?.takeIf { ui.state.airborne && ui.state != ExecState.ReturningHome } ?: return null
        val p = flightParams ?: return null
        val home = _state.value.home ?: return null
        val pos = t.position ?: return null
        val numbers = waypointNumbers.getOrPut(ui.sortieIndex) {
            val poles = ui.visits.filter { it.sortieIndex == ui.sortieIndex }.sortedBy { it.order }.map { it.pole }
            Viewpoints.expand(poles, p, home).withIndex().groupBy({ it.value.pole.poleId }, { it.index + 1 }).mapValues { it.value.first() }
        }
        return numbers[target.pole.poleId]?.let { NextWaypoint(it, LocalProjection.distanceM(pos, target.pole.position), target.pole.position) }
    }

    /** 착륙 보호 확인(기체가 지면 가까이에서 기다릴 때). 실행기 상태와 무관한 기체 명령이다. */
    fun confirmLanding() {
        OpLog.log("flight", "confirmLanding")
        val d = drone ?: return
        viewModelScope.launch { d.confirmLanding().onFailure { e -> Log.w("Flight", "착륙 확인 실패: ${e.message}") } }
    }

    // RC 하드웨어 버튼을 대신하는 모의 조작(기체 연동 전 전용)
    fun rcPause() = (drone as? FakeDroneGateway)?.pressRcPause()
    fun dropBattery() = (drone as? FakeDroneGateway)?.let { it.setBattery((it.telemetry.value.batteryPct - 30).coerceAtLeast(0.0)) }

    /** 모의 장애물: 누를 때마다 앞 장애물 없음 → 8m → 4m → 없음. */
    fun cycleObstacle() = (drone as? FakeDroneGateway)?.let { d ->
        val front = when (d.obstacles.value?.distancesM?.get(ObstacleDirection.FRONT)) { null -> 8.0; 8.0 -> 4.0; else -> null }
        d.setObstacles(front?.let { Obstacles(mapOf(ObstacleDirection.FRONT to it)) })
    }

    private fun send(c: Command) = executor?.send(c).also { OpLog.log("flight", c.toString(), mapOf("mission" to missionId)) }

    override fun onCleared() {
        (drone as? DjiDroneGateway)?.close()
        notifier.release()
        live.close()
        precise?.setFlying("auto", false)
    }

    companion object {
        /** 기체가 연결돼 있으면 실제 기체([DjiDroneGateway]), 아니면 모의 기체. 올린 KMZ는 미션 폴더 `kmz/`에 남는다(4.3). */
        fun factory(
            dao: ProjectDao, projectId: Long, missionId: Long, appFilesDir: File?, settings: SettingsStore, rcButtons: Flow<RcButton>,
            sdkReady: StateFlow<Boolean>, sdkInitialized: StateFlow<Boolean>, precise: PreciseDetection,
        ) =
            viewModelFactory {
                initializer {
                    FlightViewModel(
                        dao, projectId, missionId, appFilesDir, settings, rcButtons = rcButtons, precise = precise,
                        droneFactory = { scope, home, dir, airborneAt ->
                            // 앱 재시작 복구(열린 소티, [airborneAt] 있음): 앱이 막 켜져 SDK 등록·기체 연결 전일 수 있으니 기다린다(6.4).
                            // 실제 RC(SDK 초기화됨, 등록 실패여도)에서는 연결이 안 돼도 모의 기체로 가지 않는다 — 실행기가 연결 끊김으로 두고 붙으면 맞춘다
                            if (airborneAt != null) withTimeoutOrNull(AppConfig.Timeouts.RECOVER_CONNECT_MS) { while (!(sdkReady.value && djiAircraftConnected())) delay(500) }
                            if (djiAircraftConnected() || (airborneAt != null && sdkInitialized.value)) DjiDroneGateway(scope, File(dir, "projects/$projectId/missions/$missionId/kmz"))
                            else fakeDrone(scope, home, dir, airborneAt)
                        },
                    )
                }
            }
    }
}

private fun fakeDrone(scope: CoroutineScope, home: GeoPoint, appFilesDir: File?, airborneAt: GeoPoint?): DroneGateway =
    FakeDroneGateway(
        scope, home,
        FakeDroneGateway.Config(timeScale = FAKE_TIME_SCALE, image = fakePhotoSource(appFilesDir), airborneAt = airborneAt),
    )
