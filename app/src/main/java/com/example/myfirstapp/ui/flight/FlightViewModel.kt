package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.executor.PreflightEvaluator
import com.example.myfirstapp.core.executor.PreflightCheck
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.executor.ExecutorConfig
import com.example.myfirstapp.data.TelemetrySampleEntity
import com.example.myfirstapp.drone.DjiDroneGateway
import com.example.myfirstapp.drone.djiAircraftConnected
import kotlinx.coroutines.delay
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.executor.Command
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.executor.ExecutorUiState
import com.example.myfirstapp.core.executor.InspectionExecutor
import com.example.myfirstapp.core.executor.recoveredCaptures
import com.example.myfirstapp.core.fake.FakeDetector
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
import com.example.myfirstapp.data.onlineSatelliteTilesRetrying
import com.example.myfirstapp.data.toEntity
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.detect.TfliteYoloDetector
import com.example.myfirstapp.sound.ToneNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 모의 기체의 시간 배속. 시연과 스모크 시험이 몇 분 안에 끝나게 한다. 실제 기체에는 해당 없음. */
const val FAKE_TIME_SCALE = 5.0

/** 조종기 쪽 저장 공간 기준(6.5). SD 여유 기준(6.7)과 같게 1GB로 둔다. */
private const val MIN_FREE_BYTES = 1L shl 30

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
    /** 지도 배경: 앱 폴더의 MBTiles와 온라인 위성 타일(7.4 임시). 계획 화면과 같다. */
    val mapFiles: List<File> = emptyList(),
    val onlineTiles: List<String> = emptyList(),
    /** 모의 기체면 true: 모의 RC 조작 줄을 보여 준다. */
    val simulated: Boolean = false,
    val modelVersion: String? = null,
    /** FPV 렌즈(Pilot 2 카메라 뷰 전환). */
    val lens: Lens = Lens.WIDE,
    /** 망원 줌 배율(10.4.3 줌 +/−). 광각이면 1.0X로 보인다. */
    val zoomRatio: Double = FlightParams().zoomRatio,
    /** 사진/영상 전환에서 영상(셔터 = 녹화). */
    val videoMode: Boolean = false,
    /** 녹화를 시작한 시각. 녹화 중이 아니면 null. */
    val recordingSinceMs: Long? = null,
    /** 비행 전 점검 체크리스트(Pilot 2): [비행 시작]을 누르면 채워지고, 확인하면 비운다. */
    val preflight: List<PreflightCheck>? = null,
    /** 미션 파라미터(촬영 방식 포함). 미니맵에 실제 촬영 지점 경로를 그린다. */
    val params: FlightParams? = null,
) {
    val canExit get() = exec.state in setOf(ExecState.Idle, ExecState.SortieDone, ExecState.MissionDone)
    val sortieCount get() = (exec.visits.maxOfOrNull { it.sortieIndex } ?: 0) + 1
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
    private val droneFactory: (CoroutineScope, GeoPoint, File?, GeoPoint?) -> DroneGateway = ::fakeDrone,
    /** RC 하드웨어 버튼 누름. 화면 버튼과 같은 동작에 매핑한다(10장). */
    private val rcButtons: Flow<RcButton> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(FlightUiState())
    val state: StateFlow<FlightUiState> = _state.asStateFlow()
    private val camera = CameraControl(viewModelScope, { drone }, { _state.value }, { f -> _state.update(f) }, "flight")

    private val notifier = ToneNotifier()
    private var model: TfliteYoloDetector? = null
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
            val tiles = onlineSatelliteTilesRetrying()
            _state.update { it.copy(onlineTiles = tiles) }
        }
    }

    /** 일시정지 버튼: 실제 기체는 펌웨어가 멈추고 어댑터가 알리므로 모의 기체만 흉내 낸다. C1: 화면 [재개]와 같다. */
    private fun onRcButton(b: RcButton) {
        Log.i("RcKey", "$b state=${_state.value.exec.state}")
        when (b) {
            RcButton.PAUSE -> rcPause()
            RcButton.C1 -> resume()
            RcButton.C2 -> toggleGimbal()
            RcButton.RTH -> if (drone is FakeDroneGateway) returnHome() // 실제 기체는 펌웨어 RTH
            RcButton.SHUTTER -> {
                // 실제 기체는 스스로 찍는다. 모의 기체는 SD에 사진을 넣어 흉내 낸다
                val at = System.currentTimeMillis()
                (drone as? FakeDroneGateway)?.pressRcShutter()
                send(Command.HardwareShutter(at))
            }
            else -> Unit // 5D는 비행 명령에 쓰지 않는다(10장)
        }
    }

    private suspend fun load() {
        val mission = dao.mission(missionId) ?: return _state.update { it.copy(loading = false, error = "미션을 찾을 수 없습니다") }
        val params = flightParamsFromJson(mission.flightParams)
        val energy = EnergyModel(EnergyParams(), params).also { energy = it }
        val home = GeoPoint(mission.homeLat, mission.homeLon)
        val dbSorties = dao.sorties(missionId)
        // 시작했지만 끝나지 않은 소티가 있으면 앱이 비행 중에 죽은 것이다(6.4 복구)
        val open = dbSorties.firstOrNull { it.startedAt != null && it.endedAt == null }
        val lastTarget = dao.missionVisits(missionId).firstOrNull { it.sortieId == open?.id && it.status == "IN_PROGRESS" }
        val drone = droneFactory(viewModelScope, home, appFilesDir, open?.let { lastTarget?.let { GeoPoint(it.lat, it.lon) } ?: home })
            .also { drone = it }
        if (open != null) recoverTarget(open.id, drone)

        val rows = dao.missionVisits(missionId).filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }
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
            val route = listOf(home) + poles.map { it.position } + home
            val dist = route.zipWithNext { a, b -> LocalProjection.distanceM(a, b) }.sum()
            SortiePlan(poles, dist, energy.sortieCost(dist, poles.size), energy.sortieDurationSec(dist, poles.size))
        }
        plans = sorties
        val recorder = RoomRecorder(
            dao, missionId,
            groups.map { (id, g) -> RecorderSortie(id, g.map { it.visitId to it.poleId }) },
            File(appFilesDir, "projects/$projectId/missions/$missionId/images"),
            params.downloadMode,
        )
        val storage = StorageMonitor { (appFilesDir?.usableSpace ?: 0L) >= MIN_FREE_BYTES }
        // 앱 폴더 models/에 규격에 맞는 모델이 있으면 TFLite 탐지기(⑥ 설정에서 고른 모델 우선), 없으면 모의 탐지기(9.1)
        val preferred = settings.modelDir.first()
        model = withContext(Dispatchers.IO) { TfliteYoloDetector.loadPreferred(File(appFilesDir, "models"), preferred) }
        val detector = model ?: FakeDetector()
        val executor = InspectionExecutor(
            viewModelScope, drone, recorder, notifier, storage, energy, detector, sorties,
            // 6.1 방식 A: 소티 미션 하나로 계속 비행(모의 기체도 같은 흐름)
            config = ExecutorConfig(sortieMission = true),
            recoverSortie = open?.let { o -> groups.indexOfFirst { it.first == o.id } },
        ).also { executor = it }
        executor.start()
        // 비행 궤적(TELEMETRY_SAMPLE, 4.2): 떠 있는 동안 1초마다 그 소티에 남긴다
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val t = drone.telemetry.value
                val sortieId = groups.getOrNull(executor.uiState.value.sortieIndex)?.first
                if (sortieId != null && t.isFlying) dao.insertTelemetry(
                    TelemetrySampleEntity(
                        sortieId = sortieId, at = System.currentTimeMillis(), lat = t.position?.lat, lon = t.position?.lon,
                        altRel = t.altitudeM, speed = t.speedMps, heading = t.headingDeg, gimbalPitch = t.gimbalPitchDeg,
                        batteryPct = t.batteryPct.toInt(), flightMode = t.flightMode.name,
                    ),
                )
            }
        }
        _state.update {
            it.copy(
                loading = false, home = home, poles = sorties.flatMap { s -> s.poles }, params = params, zoomRatio = params.zoomRatio,
                simulated = drone is FakeDroneGateway, modelVersion = detector.modelVersion,
            )
        }
        combine(executor.uiState, drone.telemetry) { ui, t -> ui to t }.collect { (ui, t) ->
            val need = energy.move(t.position?.let { LocalProjection.distanceM(it, home) } ?: 0.0).toInt()
            val wasPaused = paused
            _state.update { it.copy(exec = ui, telemetry = t, returnNeedPct = need) }
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

    fun start() = send(Command.Start)

    /** [비행 시작]: Pilot 2처럼 먼저 비행 전 점검 체크리스트를 보여 준다(판정은 실행기도 다시 한다, 6.7). */
    fun openPreflight() {
        val d = drone ?: return
        val p = flightParams ?: return
        viewModelScope.launch {
            val ui = _state.value.exec
            val index = plans.indices.firstOrNull { i -> ui.visits.any { it.sortieIndex == i && it.status == VisitStatus.PENDING } } ?: ui.sortieIndex
            val plan = plans.getOrNull(index)
            val first = ui.visits.firstOrNull { it.sortieIndex == index && it.status == VisitStatus.PENDING }?.pole?.position
            val list = PreflightEvaluator.checklist(d.preflight(), p, plan?.estBatteryPct ?: 0.0, first)
            OpLog.log("preflight", "checklist", list.associate { it.label to "${if (it.ok) "OK" else "WARN"} ${it.value}" })
            _state.update { it.copy(preflight = list) }
        }
    }

    fun closePreflight() = _state.update { it.copy(preflight = null) }

    /** 체크리스트 확인 → 업로드·시작. [ignoreWarnings]면 ▲ 항목을 운용자가 확인하고 그대로 시작한다(기체 연결은 필요). */
    fun confirmPreflight(ignoreWarnings: Boolean = false) {
        closePreflight()
        send(if (ignoreWarnings) Command.StartIgnoringWarnings else Command.Start)
    }

    /** 배터리를 교체했다고 보고 다음 소티를 시작한다(모의 기체는 배터리를 100%로 채운다). */
    fun nextSortie() {
        (drone as? FakeDroneGateway)?.setBattery(100.0)
        send(Command.Start)
    }

    fun endMission() = send(Command.EndMission)
    /** 재개(화면 ▶·RC C1): 영상 모드였으면 사진 모드로 돌린 뒤 재개한다(지점 사진을 놓치지 않게). */
    fun resume() {
        viewModelScope.launch {
            camera.backToPhotoAndWait()
            send(Command.Resume)
        }
    }

    /** 화면 [Ⅱ 수동 비행](Pilot 2 55쪽 경로 알약): RC 일시정지 버튼과 같다. 기체가 멈춰 호버링하고 스틱으로 조종한다. */
    fun pause() = send(Command.Pause)
    fun manualCapture() = send(Command.ManualCapture)

    /** 화면 셔터: 영상 모드면 녹화 시작·정지, 사진이면 수동 촬영(일시정지 중). */
    fun shutter() { if (_state.value.videoMode) toggleRecording() else manualCapture() }
    fun skip() = send(Command.Skip())
    fun markDone() = send(Command.MarkDone)
    /**
     * [RTH 길게]: 미션 중이면 실행기가 처리한다(방문 정리 포함). 실행기는 대기인데 기체가 떠 있으면
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

    /** FPV 렌즈 전환(Pilot 2 카메라 뷰 전환). */
    fun setLens(lens: Lens) = camera.setLens(lens)

    private val paused get() = _state.value.exec.state == ExecState.Paused

    /** 줌 +/−·사진/영상·녹화는 일시정지 중에만(10.4.3). 지점 사진(광각)은 건드리지 않는다. */
    fun zoom(up: Boolean) { if (paused) camera.zoom(up) }
    fun setVideoMode(on: Boolean) { if (paused) camera.setVideoMode(on) }
    fun toggleRecording() { if (paused || _state.value.recordingSinceMs != null) camera.toggleRecording() }

    /** 짐벌 중앙 ↔ 아래(Pilot 2 짐벌 모드, RC C2). */
    fun toggleGimbal() {
        OpLog.log("flight", "gimbal")
        drone?.let { d -> viewModelScope.launch { d.toggleGimbal().onFailure { e -> Log.w("Flight", "짐벌 전환 실패: ${e.message}") } } }
    }

    /** 착륙 보호 확인(기체가 지면 가까이에서 기다릴 때). 실행기 상태와 무관한 기체 명령이다. */
    fun confirmLanding() {
        OpLog.log("flight", "confirmLanding")
        val d = drone ?: return
        viewModelScope.launch { d.confirmLanding().onFailure { e -> Log.w("Flight", "착륙 확인 실패: ${e.message}") } }
    }

    // RC 하드웨어 버튼을 대신하는 모의 조작(기체 연동 전 전용)
    fun rcPause() = (drone as? FakeDroneGateway)?.pressRcPause()
    fun rcResume() = (drone as? FakeDroneGateway)?.pressRcResume()
    fun dropBattery() = (drone as? FakeDroneGateway)?.let { it.setBattery((it.telemetry.value.batteryPct - 30).coerceAtLeast(0.0)) }

    private fun send(c: Command) = executor?.send(c).also { OpLog.log("flight", c.toString(), mapOf("mission" to missionId)) }

    override fun onCleared() {
        (drone as? DjiDroneGateway)?.close()
        notifier.release()
        model?.close()
    }

    companion object {
        /** 기체가 연결돼 있으면 실제 기체([DjiDroneGateway]), 아니면 모의 기체. 올린 KMZ는 미션 폴더 `kmz/`에 남는다(4.3). */
        fun factory(dao: ProjectDao, projectId: Long, missionId: Long, appFilesDir: File?, settings: SettingsStore, rcButtons: Flow<RcButton>) =
            viewModelFactory {
                initializer {
                    FlightViewModel(
                        dao, projectId, missionId, appFilesDir, settings, rcButtons = rcButtons,
                        droneFactory = { scope, home, dir, airborneAt ->
                            if (djiAircraftConnected()) DjiDroneGateway(scope, File(dir, "projects/$projectId/missions/$missionId/kmz"))
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
