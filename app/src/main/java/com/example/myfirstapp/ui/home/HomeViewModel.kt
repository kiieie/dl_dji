package com.example.myfirstapp.ui.home

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.data.OpLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.ShotMode
import com.example.myfirstapp.data.MissionVisitRow
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.drone.AircraftStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 홈 카드 경로 미리보기의 전주 한 점. [label]은 선로번호(없으면 전산화번호 끝 4자리). */
data class RoutePoint(val lat: Double, val lon: Double, val done: Boolean, val label: String = "")

/** 홈 [미션 비행 시작]·[앨범]과 수동 비행 화면의 경로 알약이 여는 가장 최근 미션. [route]는 소티마다 방문 순서. */
data class LatestMission(
    val projectId: Long,
    val missionId: Long,
    val projectName: String,
    val done: Int,
    val total: Int,
    val status: String = "PLANNED",
    val sorties: Int = 0,
    val photos: Int = 0,
    val shotMode: ShotMode = ShotMode.OVERHEAD,
    val home: GeoPoint? = null,
    val route: List<List<RoutePoint>> = emptyList(),
) {
    val remaining get() = total - done
}

/** 날 미션(남은 전주가 있는 가장 최근 것, 없으면 가장 최근 미션)과 진행 수. 미션이 없으면 null. */
suspend fun loadLatestMission(dao: ProjectDao): LatestMission? {
    val m = dao.latestOpenMission() ?: dao.latestMission() ?: return null
    val visits = dao.missionVisits(m.id)
    val isDone = { v: MissionVisitRow -> v.status != "PENDING" && v.status != "IN_PROGRESS" }
    val params = flightParamsFromJson(m.flightParams)
    val route = visits.groupBy { it.sortieId }.values.map { s -> s.map { RoutePoint(it.lat, it.lon, isDone(it), it.lineNo?.takeIf(String::isNotBlank) ?: it.poleId.takeLast(4)) } }
    return LatestMission(
        m.projectId, m.id, dao.projectName(m.projectId).orEmpty(), visits.count(isDone), visits.size,
        status = m.status, sorties = route.size, photos = visits.sumOf { params.photosPerPole(it.toPole()) }, shotMode = params.shotMode,
        home = GeoPoint(m.homeLat, m.homeLon), route = route,
    )
}

data class HomeUiState(val aircraft: AircraftStatus = AircraftStatus(), val latest: LatestMission? = null, val message: String? = null)

/** 홈 화면(Pilot 2 홈페이지, 10장 ①). 기체 상태는 화면이 보이는 동안([active]) 2초마다 다시 읽는다. */
class HomeViewModel(
    private val dao: ProjectDao,
    private val readAircraft: suspend () -> AircraftStatus,
    /** 데이터 보호 [캐시·로그 삭제]: 지운 파일 수. */
    private val clearCaches: suspend () -> Int = { 0 },
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** 홈이 보이는 동안만 기체를 읽는다(다른 화면 뒤에서 SDK 키를 계속 읽지 않게). */
    private val active = MutableStateFlow(true)
    fun setActive(on: Boolean) { active.value = on }

    init {
        viewModelScope.launch {
            active.collectLatest { on ->
                while (on) {
                    _state.update { it.copy(aircraft = readAircraft()) }
                    delay(AppConfig.Polling.HOME_AIRCRAFT_MS)
                }
            }
        }
        refresh()
    }

    /** 다른 화면에서 돌아올 때 최근 미션을 다시 읽는다. */
    fun refresh() {
        viewModelScope.launch {
            val latest = loadLatestMission(dao)
            _state.update { it.copy(latest = latest) }
        }
    }

    /** 데이터 보호(10.5.5): 지도 캐시·임시 파일·운용 로그를 지운다. 미션·사진·지도 파일은 둔다. */
    fun clearCacheAndLogs() {
        OpLog.log("home", "clearCacheAndLogs")
        viewModelScope.launch {
            val n = runCatching { clearCaches() }
            _state.update { it.copy(message = n.fold({ c -> "캐시·로그를 지웠습니다(파일 ${c}개, 지도 캐시)." }, { e -> "지우지 못했습니다: ${e.message}" })) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(dao: ProjectDao, readAircraft: suspend () -> AircraftStatus, clearCaches: suspend () -> Int) =
            viewModelFactory { initializer { HomeViewModel(dao, readAircraft, clearCaches) } }
    }
}
