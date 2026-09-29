package com.example.myfirstapp.ui.flight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.executor.Command
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.executor.ExecutorUiState
import com.example.myfirstapp.core.executor.InspectionExecutor
import com.example.myfirstapp.core.fake.FakeDetector
import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.StorageMonitor
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.SortiePlan
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.RecorderSortie
import com.example.myfirstapp.data.RoomRecorder
import com.example.myfirstapp.data.fakePhotoSource
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.detect.TfliteYoloDetector
import com.example.myfirstapp.sound.ToneNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 모의 기체의 시간 배속. 시연과 스모크 시험이 몇 분 안에 끝나게 한다. 실제 기체에는 해당 없음. */
const val FAKE_TIME_SCALE = 5.0

/** 조종기 쪽 저장 공간 기준(6.5). SD 여유 기준(6.7)과 같게 1GB로 둔다. */
private const val MIN_FREE_BYTES = 1L shl 30

/** 소티 완료 화면(10장 2인 운용)의 보조자 칸: 방금 끝난 소티의 전주·검출·1차 판독 수. */
data class SortieSummary(val sortieNo: Int, val poles: Int, val detected: Int, val reviewed: Int)

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
    /** 모의 기체면 true: 모의 RC 조작 줄을 보여 준다. */
    val simulated: Boolean = false,
    val modelVersion: String? = null,
    val sortieSummary: SortieSummary? = null,
) {
    val canExit get() = exec.state in setOf(ExecState.Idle, ExecState.SortieDone, ExecState.MissionDone)
    val sortieCount get() = (exec.visits.maxOfOrNull { it.sortieIndex } ?: 0) + 1
}

/**
 * 비행 화면 ④(10장). 저장한 `MISSION`에서 남은(PENDING·IN_PROGRESS) 방문만 읽어 소티를 만들고 실행기를 돌린다.
 * 실행기는 [viewModelScope]에서 돌아 소티 완료 뒤 1차 판독 화면에 다녀와도 이어진다.
 * 기체는 [droneFactory]로 받는다. 기체 연동 전에는 [FakeDroneGateway]이고, 연동하면 `DjiDroneGateway`로 바꾼다.
 */
class FlightViewModel(
    private val dao: ProjectDao,
    private val projectId: Long,
    private val missionId: Long,
    private val appFilesDir: File?,
    private val droneFactory: (CoroutineScope, GeoPoint, File?) -> DroneGateway = ::fakeDrone,
) : ViewModel() {
    private val _state = MutableStateFlow(FlightUiState())
    val state: StateFlow<FlightUiState> = _state.asStateFlow()

    private val notifier = ToneNotifier()
    private var model: TfliteYoloDetector? = null
    private var executor: InspectionExecutor? = null
    private var drone: DroneGateway? = null
    private var energy: EnergyModel? = null
    /** 실행기 소티 순번 → 그 소티의 방문 id. */
    private var sortieVisits: List<List<Long>> = emptyList()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val mission = dao.mission(missionId) ?: return _state.update { it.copy(loading = false, error = "미션을 찾을 수 없습니다") }
        val params = flightParamsFromJson(mission.flightParams)
        val energy = EnergyModel(EnergyParams(), params).also { energy = it }
        val home = GeoPoint(mission.homeLat, mission.homeLon)
        val rows = dao.missionVisits(missionId).filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }
        if (rows.isEmpty()) return _state.update { it.copy(loading = false, error = "남은 전주가 없습니다(미션 완료)") }
        val groups = rows.groupBy { it.sortieId }.values.toList()
        sortieVisits = groups.map { g -> g.map { it.visitId } }
        val sorties = groups.map { g ->
            val poles = g.map { Pole(it.poleId, GeoPoint(it.lat, it.lon), it.lineName, it.note) }
            val route = listOf(home) + poles.map { it.position } + home
            val dist = route.zipWithNext { a, b -> LocalProjection.distanceM(a, b) }.sum()
            SortiePlan(poles, dist, energy.sortieCost(dist, poles.size), energy.sortieDurationSec(dist, poles.size))
        }
        val recorder = RoomRecorder(
            dao, missionId,
            groups.map { g -> RecorderSortie(g.first().sortieId, g.map { it.visitId to it.poleId }) },
            File(appFilesDir, "projects/$projectId/missions/$missionId/images"),
            params.downloadMode,
        )
        val drone = droneFactory(viewModelScope, home, appFilesDir).also { drone = it }
        val storage = StorageMonitor { (appFilesDir?.usableSpace ?: 0L) >= MIN_FREE_BYTES }
        // 앱 폴더 models/에 규격에 맞는 모델이 있으면 TFLite 탐지기, 없으면 모의 탐지기(9.1)
        model = withContext(Dispatchers.IO) { TfliteYoloDetector.loadLatest(File(appFilesDir, "models")) }
        val detector = model ?: FakeDetector()
        val executor = InspectionExecutor(viewModelScope, drone, recorder, notifier, storage, energy, detector, sorties)
            .also { executor = it }
        executor.start()
        _state.update {
            it.copy(
                loading = false, home = home, poles = sorties.flatMap { s -> s.poles },
                simulated = drone is FakeDroneGateway, modelVersion = detector.modelVersion,
            )
        }
        combine(executor.uiState, drone.telemetry) { ui, t -> ui to t }.collect { (ui, t) ->
            val need = energy.move(t.position?.let { LocalProjection.distanceM(it, home) } ?: 0.0).toInt()
            val entered = ui.state == ExecState.SortieDone && _state.value.exec.state != ExecState.SortieDone
            _state.update { it.copy(exec = ui, telemetry = t, returnNeedPct = need) }
            if (entered) refreshSortieSummary()
        }
    }

    /** 소티 완료 화면의 검출·판독 수를 DB에서 다시 센다. 1차 판독 화면에서 돌아올 때도 부른다. */
    fun refreshSortieSummary() {
        val s = _state.value.exec
        if (s.state != ExecState.SortieDone) return
        val ids = sortieVisits.getOrNull(s.sortieIndex)?.toSet() ?: return
        viewModelScope.launch {
            // 이 소티 계획에서 이미 처리한(대기 아닌) 전주만 센다. 배터리 부족으로 돌아오면 일부만 처리된다
            val rows = dao.resultRows(missionId).filter { it.visitId in ids && it.status != "PENDING" }
            val reviewed = dao.latestReviews(missionId).count { it.visitId in ids }
            _state.update { it.copy(sortieSummary = SortieSummary(s.sortieIndex + 1, rows.size, rows.count { r -> r.detectCount > 0 }, reviewed)) }
        }
    }

    fun start() = send(Command.Start)

    /** 배터리를 교체했다고 보고 다음 소티를 시작한다(모의 기체는 배터리를 100%로 채운다). */
    fun nextSortie() {
        (drone as? FakeDroneGateway)?.setBattery(100.0)
        send(Command.Start)
    }

    fun endMission() = send(Command.EndMission)
    fun resume() = send(Command.Resume)
    fun manualCapture() = send(Command.ManualCapture)
    fun skip() = send(Command.Skip())
    fun markDone() = send(Command.MarkDone)
    fun returnHome() = send(Command.ReturnHome)

    // RC 하드웨어 버튼을 대신하는 모의 조작(기체 연동 전 전용)
    fun rcPause() = (drone as? FakeDroneGateway)?.pressRcPause()
    fun rcResume() = (drone as? FakeDroneGateway)?.pressRcResume()
    fun dropBattery() = (drone as? FakeDroneGateway)?.let { it.setBattery((it.telemetry.value.batteryPct - 30).coerceAtLeast(0.0)) }

    private fun send(c: Command) = executor?.send(c)

    override fun onCleared() {
        notifier.release()
        model?.close()
    }

    companion object {
        fun factory(dao: ProjectDao, projectId: Long, missionId: Long, appFilesDir: File?) =
            viewModelFactory { initializer { FlightViewModel(dao, projectId, missionId, appFilesDir) } }
    }
}

private fun fakeDrone(scope: CoroutineScope, home: GeoPoint, appFilesDir: File?): DroneGateway =
    FakeDroneGateway(scope, home, FakeDroneGateway.Config(timeScale = FAKE_TIME_SCALE, image = fakePhotoSource(appFilesDir)))
