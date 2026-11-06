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
import com.example.myfirstapp.core.model.currentSortie
import com.example.myfirstapp.data.MissionVisitRow
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.drone.AircraftStatus
import com.example.myfirstapp.core.model.GeoSummary
import com.example.myfirstapp.core.model.HmsIssue
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.myfirstapp.data.MapThumbs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 홈 카드 경로 미리보기의 전주 한 점. [label]은 선로번호(없으면 전산화번호 끝 4자리). */
data class RoutePoint(val lat: Double, val lon: Double, val done: Boolean, val label: String = "")

/** 경로 그림이 맞추는 점(모든 전주 + 이륙 지점). 지도 바탕(10.4.10)도 같은 점으로 맞춰야 선과 바탕이 겹친다. */
fun thumbPoints(route: List<List<RoutePoint>>, home: GeoPoint?): List<GeoPoint> =
    route.flatten().map { GeoPoint(it.lat, it.lon) } + listOfNotNull(home)

/** 홈 [미션 비행 시작]·[앨범]과 수동 비행 화면의 경로 알약이 여는 가장 최근 미션. [route]는 소티마다 방문 순서. */
data class LatestMission(
    val projectId: Long,
    val missionId: Long,
    val projectName: String,
    val done: Int,
    val total: Int,
    val status: String = "PLANNED",
    val sorties: Int = 0,
    /** 사진 찍은/예상(10.5 숫자 규칙): 예상은 [FlightParams.expectedPhotos](모든 방문 전주). */
    val photosTaken: Int = 0,
    val photos: Int = 0,
    val shotMode: ShotMode = ShotMode.OVERHEAD,
    val home: GeoPoint? = null,
    val route: List<List<RoutePoint>> = emptyList(),
) {
    val remaining get() = total - done

    /** 지금 소티(10.5.11 "소티 지금/전체"): 남은 전주가 있는 첫 소티, 다 끝났으면 전체 수. 전체는 [sorties]. */
    val sortieNow get() = currentSortie(route.map { s -> s.count { !it.done } })
}

/**
 * 날 미션(남은 전주가 있는 가장 최근 것, 없으면 가장 최근 미션)과 진행 수. 미션이 없으면 null.
 * 숫자 규칙(10.5, 모든 화면 같음): 전주 = 끝난(완료·건너뜀·실패)/모든 방문, 사진 = 찍은 지점(자동) 사진/예상([FlightParams.expectedPhotos]), 수동 사진은 세지 않음(11-06).
 */
suspend fun loadLatestMission(dao: ProjectDao): LatestMission? {
    val m = dao.latestOpenMission() ?: dao.latestMission() ?: return null
    val visits = dao.missionVisits(m.id)
    val isDone = { v: MissionVisitRow -> v.status != "PENDING" && v.status != "IN_PROGRESS" }
    val params = flightParamsFromJson(m.flightParams)
    val route = visits.groupBy { it.sortieId }.values.map { s -> s.map { RoutePoint(it.lat, it.lon, isDone(it), it.lineNo?.takeIf(String::isNotBlank) ?: it.poleId.takeLast(4)) } }
    return LatestMission(
        m.projectId, m.id, dao.projectName(m.projectId).orEmpty(), visits.count(isDone), visits.size,
        status = m.status, sorties = route.size, photosTaken = dao.missionCaptureCount(m.id), photos = params.expectedPhotos(visits.map { it.toPole() }),
        shotMode = params.shotMode,
        home = GeoPoint(m.homeLat, m.homeLon), route = route,
    )
}

/**
 * 홈 앨범 타일 바탕(10.5.5, Pilot 2 앨범 카드): 가장 최근 사진 파일. 전주에 붙은 사진(DB 최근 [dbPaths] 중 파일이 남은 첫 것)과
 * 붙지 않은 사진(`manual/<날짜>/`: 수동 비행·기체 앨범에서 받은 것, 앨범과 같은 자리) 중 파일 시각이 늦은 것. 없으면 null.
 */
internal fun newestPhoto(dbPaths: List<String>, appFilesDir: File?): File? {
    val db = dbPaths.asSequence().map(::File).firstOrNull { it.isFile }
    val manual = appFilesDir?.let { File(it, "manual") }?.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty()
        .sortedByDescending { it.name }
        .firstNotNullOfOrNull { d -> d.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }?.maxByOrNull { it.lastModified() } }
    return listOfNotNull(db, manual).maxByOrNull { it.lastModified() }
}

/** [latestPhoto]: 가장 최근 사진 파일(앨범 타일 바탕, [newestPhoto]). [hms]는 기체 판 HMS 칸, [geo]는 GEO 구역 카드(없으면 "로컬 데이터 없음"). */
data class HomeUiState(
    val aircraft: AircraftStatus = AircraftStatus(),
    val latest: LatestMission? = null,
    val message: String? = null,
    val latestPhoto: String? = null,
    val hms: List<HmsIssue> = emptyList(),
    val geo: GeoSummary? = null,
)

/** 홈 화면(Pilot 2 홈페이지, 10장 ①). 기체 상태는 화면이 보이는 동안([active]) 2초마다 다시 읽는다. */
class HomeViewModel(
    private val dao: ProjectDao,
    private val readAircraft: suspend () -> AircraftStatus,
    /** 데이터 보호 [캐시·로그 삭제]: 지운 파일 수. */
    private val clearCaches: suspend () -> Int = { 0 },
    private val readHms: () -> List<HmsIssue> = { emptyList() },
    private val readGeo: suspend () -> GeoSummary? = { null },
    private val mapThumbs: MapThumbs? = null,
    /** 앱 폴더: 앨범 타일이 전주에 붙지 않은 사진(`manual/`)도 본다. */
    private val appFilesDir: File? = null,
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
                    val aircraft = readAircraft()
                    val geo = readGeo()
                    _state.update { it.copy(aircraft = aircraft, hms = readHms(), geo = geo) }
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
            val paths = dao.recentPhotoPaths(AppConfig.Album.HOME_RECENT_DB_PHOTOS)
            val photo = withContext(Dispatchers.IO) { newestPhoto(paths, appFilesDir)?.path }
            _state.update { it.copy(latest = latest, latestPhoto = photo) }
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

    /** 최근 미션 경로 그림의 지도 바탕(10.4.10): [w]×[h] 픽셀, 둘레 [pad] 픽셀. 미리 받은 타일이 없으면 null. */
    fun mapThumb(w: Int, h: Int, pad: Int): Flow<ImageBitmap?> {
        val m = _state.value.latest ?: return flowOf(null)
        return mapThumbs?.flow(m.missionId, thumbPoints(m.route, m.home), w, h, pad)?.map { it?.asImageBitmap() } ?: flowOf(null)
    }

    companion object {
        fun factory(
            dao: ProjectDao, readAircraft: suspend () -> AircraftStatus, readHms: () -> List<HmsIssue>, readGeo: suspend () -> GeoSummary?,
            mapThumbs: MapThumbs?, appFilesDir: File?, clearCaches: suspend () -> Int,
        ) = viewModelFactory { initializer { HomeViewModel(dao, readAircraft, clearCaches, readHms, readGeo, mapThumbs, appFilesDir) } }
    }
}
