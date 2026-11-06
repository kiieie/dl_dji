package com.example.myfirstapp.ui

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.flightParamsFromJson
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.LivePositions
import com.example.myfirstapp.core.model.ParamItem
import com.example.myfirstapp.core.model.items
import com.example.myfirstapp.core.model.moveItem
import com.example.myfirstapp.core.model.step
import com.example.myfirstapp.core.input.FiveDGate
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.PoleOverride
import com.example.myfirstapp.core.model.PoleCodes
import com.example.myfirstapp.core.model.PoleTargets
import com.example.myfirstapp.core.model.Voltage
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.ImportedRoute
import com.example.myfirstapp.core.route.LineNumbers
import com.example.myfirstapp.core.route.RouteImport
import com.example.myfirstapp.core.route.SortiePlan
import com.example.myfirstapp.core.route.Polygon
import com.example.myfirstapp.core.route.RoutePlan
import com.example.myfirstapp.core.route.RoutePlanner
import com.example.myfirstapp.core.route.UnreachablePole
import com.example.myfirstapp.data.MissionEntity
import com.example.myfirstapp.data.PoleEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.SortieDraft
import com.example.myfirstapp.data.listMbtiles
import com.example.myfirstapp.data.MapBackground
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.pickMapSource
import com.example.myfirstapp.data.mapBackgroundRetrying
import com.example.myfirstapp.data.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 지도 도구. AREA는 누를 때마다 꼭짓점을 더하고, HOME은 한 번 눌러 이륙 지점을 정한다. */
enum class Tool { NONE, AREA, HOME }

data class PlanUiState(
    val projectName: String = "",
    /** 아직 읽지 않았으면 null. */
    val poles: List<PoleEntity>? = null,
    val mapFiles: List<File> = emptyList(),
    /** 배경 지도(설정에서 고른 Bing·VWorld, 7.4). 타일 주소를 못 받으면 비어 있고 캐시만 보인다. */
    val background: MapBackground = MapBackground.NONE,
    val selected: String? = null,
    val message: String? = null,
    val tool: Tool = Tool.NONE,
    val vertices: List<GeoPoint> = emptyList(),
    val home: GeoPoint? = null,
    /** 이륙 지점을 어디서 정했나(운용 로그): aircraft, rc, device(조종기 안드로이드 GPS), map. */
    val homeSource: String? = null,
    /** 이륙 지점을 앱이 자동으로 정했다: 더 믿을 만한 위치(기체 > DJI 조종기 > 안드로이드)가 생기면 올린다. 운용자가 정하거나 계획을 저장하면 거짓. */
    val homeAuto: Boolean = false,
    /** 지금 기체 위치·기수(지도 화살표, [기체 위치로])와 조종기 위치([조종기 위치로], 파란 점). 모르면 null(10.5.5). */
    val aircraft: GeoPoint? = null,
    val aircraftHeadingDeg: Double = 0.0,
    val rc: GeoPoint? = null,
    val plan: RoutePlan? = null,
    val planMs: Long = 0,
    val planning: Boolean = false,
    /** 지도가 확대해 보여 줄 점들. */
    val focus: List<GeoPoint> = emptyList(),
    val params: FlightParams = FlightParams(),
    /** 손 선택(F-06): 전주번호 → 참(손으로 넣음)·거짓(손으로 뺌). 전압 대상(`params.voltageTargets`)보다 먼저다(4.5.8). */
    val manual: Map<String, Boolean> = emptyMap(),
    /** 지금 [plan]을 저장한 미션 번호. 계획이 바뀌면 null이 되어 [비행 시작]이 [계획 저장]으로 돌아간다. */
    val savedMissionId: Long? = null,
    /** [수행]으로 저장한 미션. 화면이 받아 비행 화면을 열고 [PlanViewModel.onFlightOpened]로 지운다(한 번만 열리게). */
    val flyMissionId: Long? = null,
    /** 미션을 저장하는 중. 장갑으로 두 번 눌러도 한 번만 저장한다. */
    val saving: Boolean = false,
    /** 기체가 시뮬레이터 모드: 새 계획의 지형 추적 기본을 끈다(시뮬레이터는 지형 추적 미션을 시작하지 않음, 2026-10-30). */
    val simulator: Boolean = false,
    /** 5D로 고른 미션 설정 항목(10.4.3). 처음 ↕를 누르기 전에는 null. */
    val paramFocus: ParamItem? = null,
    /** 경로 역전(5.7)한 소티(전주번호 묶음). 다시 계산해도 같은 전주 묶음이면 뒤집어 둔다. */
    val reversed: Set<Set<String>> = emptySet(),
    /** 가져온 Pilot 2 경로(5.7)의 전주 차례(그대로 나눔). 영역을 새로 그리면 지운다. */
    val importOrder: List<String>? = null,
    /** 가져온 경로에서 전주에 맞지 않은 웨이포인트(지도에 회색 점). */
    val importUnmatched: List<GeoPoint> = emptyList(),
    /** 저장하지 않고 나가려 함: Pilot 2 "임무 저장 안 됨. 종료하기 전에 저장할까요?"(5.7). */
    val leaveAsked: Boolean = false,
    /** 나가도 됨. 화면이 받아 뒤로 간다. */
    val leave: Boolean = false,
) {
    val paramErrors: List<String> get() = params.validate()
    /** 저장할 수 있는 경로가 있음(전주별 설정 오류가 있으면 막음). */
    val canSave get() = plan != null && plan.sorties.isNotEmpty() && !saving && paramErrors.isEmpty()
    /** 저장하지 않은 경로가 있음(나갈 때 묻는다). */
    val unsaved get() = plan != null && plan.sorties.isNotEmpty() && savedMissionId == null
    /** 영역 안 전주. 상태 하나에 한 번만 계산한다(화면·canPlan·경로 계산이 여러 번 읽음, 전주 1,000개 이상). */
    val inArea: List<PoleEntity> by lazy {
        if (vertices.size < 3) emptyList() else Polygon(vertices).let { area -> poles.orEmpty().filter { area.contains(GeoPoint(it.lat, it.lon)) } }
    }
    /** 경로 대상: 영역 안 전주 중 손으로 넣었거나, 손으로 빼지 않고 전압 대상인 것(4.5.8). */
    val targets: List<PoleEntity> by lazy { inArea.filter { isTarget(it) } }
    fun isTarget(p: PoleEntity) = PoleTargets.isTarget(p.poleId, p.voltage, params.voltageTargets, manual)
    /** 전압 칩의 영역 안 전주 수(null은 미상). */
    val voltageCounts: Map<Voltage?, Int> by lazy { inArea.groupingBy { PoleCodes.voltageOf(it.voltage) }.eachCount() }
    val canPlan get() = home != null && targets.isNotEmpty() && paramErrors.isEmpty()
    /** 영역 안에서 경로 대상이 아닌 전주(손으로 뺐거나 전압 대상 밖): 지도에 회색 점. */
    val excludedPoints get() = inArea.filterNot { isTarget(it) }.map { GeoPoint(it.lat, it.lon) }
}

/**
 * 계획 화면 ③(10장): 영역, 이륙 지점, 파라미터, 전주 제외, 경로 계산과 계획 저장.
 * [replanFrom]이 있으면 그 미션의 남은 전주만 골라 연다(F-20 [남은 전주로 새 계획]). 저장하면 그 미션은 대체됨(REPLACED)으로 닫힌다.
 * [copyFrom]이 있으면 그 미션의 영역·이륙 지점·전주·파라미터로 연다(라이브러리 [다시 날기], 10.5.8). 저장하면 새 미션이고 옛 미션은 그대로다.
 * [importUri]가 있으면 그 Pilot 2 경로(KMZ/KML)의 웨이포인트를 이 프로젝트 전주에 맞춰 그 차례로 연다(라이브러리 경로 가져오기, 5.7).
 * 경로 편집(5.7): 경로 역전, 전주별 고도·짐벌 피치, 저장하지 않고 나가면 저장 확인.
 */
class PlanViewModel(
    private val dao: ProjectDao,
    private val projectId: Long,
    private val replanFrom: Long?,
    private val mapsDir: File,
    private val settings: SettingsStore,
    /** 지금 위치(기체 GPS·기수, DJI 조종기 GPS). 새 계획의 이륙 지점과 지도 초기 위치가 된다. */
    private val positions: suspend () -> LivePositions = { LivePositions() },
    /** 조종기 안드로이드 GPS(셋째 대안). true면 마지막 위치가 없을 때 새 위치를 기다린다. */
    private val deviceLocation: suspend (fresh: Boolean) -> GeoPoint? = { null },
    /** 기체가 시뮬레이터 모드인지(DJI SimulatorManager). */
    private val simulatorOn: () -> Boolean = { false },
    /** 계획을 저장하면 미션 영역의 위성 타일을 미리 받는다(7.4, 현장은 인터넷 없음). 점들과 이름을 넘긴다. */
    private val prefetchArea: (List<GeoPoint>, String, MapBackground) -> Unit = { _, _, _ -> },
    private val copyFrom: Long? = null,
    private val importUri: String? = null,
    /** 고른 경로 파일(SAF 주소)을 읽는다. */
    private val readRoute: suspend (String) -> ImportedRoute = { error("경로 파일을 읽을 수 없습니다") },
) : ViewModel() {
    private val _state = MutableStateFlow(PlanUiState(mapFiles = listMbtiles(mapsDir)))
    val state: StateFlow<PlanUiState> = _state.asStateFlow()
    /** 화면이 맨 위에 보이는 동안만 참(화면이 `LifecycleResumeEffect`로 바꾼다). 비행 화면이 덮으면 위치를 읽지 않는다. */
    private val active = MutableStateFlow(false)
    fun setActive(on: Boolean) { active.value = on }

    init {
        viewModelScope.launch { _state.update { it.copy(projectName = dao.projectName(projectId).orEmpty()) } }
        // 새 계획의 파라미터 초기값은 ⑥ 설정의 비행 기본값이다
        // 시뮬레이터 모드면 지형 추적을 끄고 화면에 알린다(운용자가 다시 켤 수 있음)
        viewModelScope.launch {
            val sim = simulatorOn()
            settings.flightDefaults.first().let { p -> _state.update { it.copy(params = if (sim) p.copy(terrainFollow = false) else p, simulator = sim) } }
            // 기본값을 넣은 뒤에 복사한 미션의 파라미터로 덮는다. 남은 전주·가져오기도 기본값의 전압 대상을 알고 손 선택을 만든다(4.5.8)
            copyFrom?.let { seedCopy(it) }
            replanFrom?.let { seedReplan(it) }
            importUri?.let { seedImport(it) }
        }
        viewModelScope.launch {
            dao.observePoles(projectId).collect { list -> _state.update { it.copy(poles = list) } }
        }
        // 화면이 보이는 동안 지금 위치를 계속 읽는다(10.5.5): 기체 화살표·버튼을 갱신하고, 이륙 지점이 없으면 기체 → DJI 조종기 → 안드로이드 GPS 순으로 정한다.
        // 한 번 읽고 끝내지 않는다(11-03 현장: 화면을 열 때 GPS가 없으면 손으로 찍어야 했음). 자동으로 정한 지점은 더 믿을 만한 위치가 생기면 올린다
        viewModelScope.launch {
            active.collectLatest { on ->
                while (on) {
                    val p = positions()
                    val noHome = _state.value.home == null
                    val rc = p.rc ?: deviceLocation(noHome && p.aircraft == null)
                    _state.update { it.copy(aircraft = p.aircraft, aircraftHeadingDeg = p.headingDeg, rc = rc) }
                    p.homeCandidate(device = rc)?.let { (at, src) -> seedHome(at, src) }
                    delay(AppConfig.Polling.PLAN_LOCATION_MS)
                }
            }
        }
        viewModelScope.launch { mapBackgroundRetrying(settings).let { bg -> _state.update { it.copy(background = bg) } } }
        // Pilot 2처럼 영역·이륙 지점·설정·제외가 바뀌면 경로를 바로 다시 계산한다. 연달아 바꾸면 마지막 것만 계산한다.
        // 영역·이륙 지점을 찍는 동안은 계산하지 않고, 도구만 바꾼 것(이륙 → 취소)은 입력이 같아 저장한 계획을 그대로 둔다.
        // 전주별 설정(5.7)은 촬영 지점의 고도·피치만 바꾸므로 다시 계산하지 않는다(역전한 순서가 그대로 남게).
        viewModelScope.launch {
            _state.filter { it.tool == Tool.NONE }
                .map { PlanInput(it.poles, it.vertices, it.home, it.params.copy(poleOverrides = emptyMap()), it.manual, it.importOrder) }
                .distinctUntilChanged()
                .collectLatest {
                    // 기다리는 동안 안내가 "전주가 없습니다"로 깜박이지 않게 먼저 "계산 중"으로 둔다
                    _state.update { s -> s.copy(planning = s.canPlan) }
                    delay(AppConfig.Polling.REPLAN_DEBOUNCE_MS)
                    computePlan()
                }
        }
    }

    private data class PlanInput(
        val poles: List<PoleEntity>?, val vertices: List<GeoPoint>, val home: GeoPoint?, val params: FlightParams, val manual: Map<String, Boolean>, val order: List<String>?,
    )

    /**
     * 이륙 지점이 아직 없으면 지금 위치로 두고, 그 둘레 약 500m로 지도를 옮긴다.
     * 자동으로 정한 지점은 더 믿을 만한 출처([LivePositions.upgradesHome])가 생기면 그 위치로 올린다(운용자가 정하기 전까지).
     */
    private fun seedHome(p: GeoPoint, source: String) {
        fun PlanUiState.takes() = home == null || homeAuto && LivePositions.upgradesHome(homeSource, source)
        val first = _state.value.home == null
        if (!_state.value.takes()) return
        _state.update {
            if (!it.takes()) return@update it
            val d = 0.005
            it.copy(
                home = p, homeSource = source, homeAuto = true,
                focus = if (it.focus.isEmpty()) listOf(GeoPoint(p.lat - d, p.lon - d), GeoPoint(p.lat + d, p.lon + d)) else it.focus,
            )
        }
        OpLog.log("plan", if (first) "homeAuto" else "homeUpgrade", mapOf("source" to source, "home" to p.toString()))
    }

    /** Pilot 2 [기체 위치로]·[조종기 위치로](10.5.5): 지금 위치를 이륙 지점으로. 모르면 아무것도 하지 않는다(버튼이 꺼져 있음). */
    fun homeFromAircraft() = setHomeFrom(_state.value.aircraft, "aircraft")
    fun homeFromRc() = setHomeFrom(_state.value.rc, "rc")
    private fun setHomeFrom(p: GeoPoint?, source: String) {
        p ?: return
        OpLog.log("plan", "homeFrom", mapOf("source" to source, "home" to p.toString()))
        _state.update { it.copy(home = p, homeSource = source, homeAuto = false, tool = Tool.NONE, plan = null) }
    }

    /** 남은 전주를 감싸는 사각형을 영역으로 두고, 남은 전주만 대상이 되게 손 선택을 만든다(F-06, 4.5.8). */
    private suspend fun seedReplan(old: Long) {
        val all = dao.observePoles(projectId).first()
        val remaining = dao.missionVisits(old).filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }.map { it.poleId }.toSet()
        if (remaining.isEmpty()) return
        val (vertices, manual) = boxAround(remaining, all)
        _state.update {
            it.copy(vertices = vertices, focus = vertices, manual = manual, message = "미션 #$old 의 남은 전주 ${all.count { p -> p.poleId in remaining }}개를 골랐습니다. 이륙 지점을 정하세요.")
        }
    }

    /** [ids] 전주를 감싸는 사각형(약 30m 여유)과, 그 안에서 [ids]만 대상이 되는 손 선택(지금 전압 대상 기준). */
    private fun boxAround(ids: Set<String>, all: List<PoleEntity>): Pair<List<GeoPoint>, Map<String, Boolean>> {
        val targets = all.filter { it.poleId in ids }
        val pad = 0.0003 // 약 30m
        val (s, n) = targets.minOf { it.lat } - pad to targets.maxOf { it.lat } + pad
        val (w, e) = targets.minOf { it.lon } - pad to targets.maxOf { it.lon } + pad
        val inBox = all.filter { p -> p.lat in s..n && p.lon in w..e }.map { it.toPole() }
        return listOf(GeoPoint(s, w), GeoPoint(s, e), GeoPoint(n, e), GeoPoint(n, w)) to
            PoleTargets.manualFor(inBox, ids, _state.value.params.voltageTargets)
    }

    /**
     * 가져온 Pilot 2 경로(5.7): 웨이포인트를 이 프로젝트 전주에 맞추고, 맞은 전주를 감싸는 영역·그 차례로 연다. 맞지 않은 웨이포인트는 회색 점.
     * 파일에 이륙 기준점(`takeOffRefPoint`)이 있으면 이륙 지점으로 둔다. 높이·짐벌·동작은 버리고 우리 촬영 방식(5.6)으로 다시 만든다.
     */
    private suspend fun seedImport(uri: String) {
        val all = dao.observePoles(projectId).first()
        val route = runCatching { readRoute(uri) }.getOrElse { e -> return _state.update { it.copy(message = "경로 파일을 읽지 못했습니다: ${e.message}") } }
        val m = RouteImport.match(route, all.map { it.toPole() }, Tuning.Route.IMPORT_POLE_LINK_M)
        if (m.poles.isEmpty()) return _state.update { it.copy(message = "가져온 경로가 이 프로젝트의 전주와 맞지 않습니다.") }
        val ids = m.poles.map { it.poleId }
        val (vertices, manual) = boxAround(ids.toSet(), all)
        OpLog.log("plan", "importRoute", mapOf("waypoints" to route.waypoints.size, "used" to m.used, "poles" to ids.size, "unmatched" to m.unmatched.size, "takeoff" to (route.takeoff != null)))
        _state.update {
            it.copy(
                vertices = vertices, focus = vertices, manual = manual, importOrder = ids, importUnmatched = m.unmatched.map { w -> w.position },
                home = route.takeoff ?: it.home, homeSource = if (route.takeoff != null) "kmz" else it.homeSource, homeAuto = route.takeoff == null && it.homeAuto,
                message = "웨이포인트 ${m.used}개 → 전주 ${ids.size}개(파일 차례). 맞지 않은 ${m.unmatched.size}개(회색)는 뺐고, 높이·짐벌·동작은 촬영 방식대로 다시 만듭니다.",
            )
        }
    }

    /** [다시 날기]: 미션의 영역·이륙 지점·파라미터(전압 대상 포함) 그대로, 그 미션의 전주만 대상이 되게 손 선택을 만든다(사진 폴더 이름은 새 미션 것으로). */
    private suspend fun seedCopy(old: Long) {
        val m = dao.mission(old) ?: return
        val vertices = runCatching {
            org.maplibre.geojson.Polygon.fromJson(m.areaGeoJson).coordinates()[0].dropLast(1).map { GeoPoint(it.latitude(), it.longitude()) }
        }.getOrDefault(emptyList())
        if (vertices.size < 3) return _state.update { it.copy(message = "미션 #$old 의 영역을 읽지 못했습니다. 영역을 다시 그리세요.") }
        val mine = dao.missionVisits(old).map { it.poleId }.toSet()
        val area = Polygon(vertices)
        val all = dao.observePoles(projectId).first()
        val params = flightParamsFromJson(m.flightParams).copy(photoFolder = null)
        OpLog.log("plan", "copy", mapOf("from" to old, "poles" to mine.size))
        _state.update {
            it.copy(
                vertices = vertices, focus = vertices, home = GeoPoint(m.homeLat, m.homeLon), homeSource = "copy", homeAuto = false,
                params = params,
                manual = PoleTargets.manualFor(all.filter { p -> area.contains(GeoPoint(p.lat, p.lon)) }.map { p -> p.toPole() }, mine, params.voltageTargets),
                message = "미션 #$old 경로를 불러왔습니다. 저장하면 새 미션이 됩니다(옛 미션은 그대로).",
            )
        }
    }

    fun selectPole(id: String?) = _state.update { it.copy(selected = id) }

    /** 지도 검색·과녁(10.5.7): 그 점 둘레로 지도를 옮긴다. [poleId]가 있으면(검색) 그 전주를 고른다. */
    fun focusOn(p: GeoPoint, poleId: String? = null) = _state.update {
        val d = AppConfig.Map.FOCUS_PAD_DEG
        it.copy(focus = listOf(GeoPoint(p.lat - d, p.lon - d), GeoPoint(p.lat + d, p.lon + d)), selected = poleId ?: it.selected)
    }

    /** 지도 레이어(10.5.7): 배경을 바꾸고 설정에도 남긴다(계획을 저장하면 고른 배경으로 미리 받음). */
    fun setMapSource(source: MapSource) { viewModelScope.launch { settings.pickMapSource(source).let { bg -> _state.update { it.copy(background = bg) } } } }

    /** 지도 탭. 도구에 따라 꼭짓점을 더하거나 이륙 지점을 정한다. 계획 대상이 바뀌면 이전 경로는 버린다. */
    fun onMapTap(p: GeoPoint) = _state.update {
        when (it.tool) {
            Tool.NONE -> it
            Tool.AREA -> it.copy(vertices = Polygon.untangle(it.vertices + p), plan = null)
            Tool.HOME -> it.copy(home = p, homeSource = "map", homeAuto = false, tool = Tool.NONE, plan = null)
        }
    }

    fun startArea() = OpLog.log("plan", "startArea").let {
        _state.update { it.copy(vertices = emptyList(), manual = emptyMap(), plan = null, selected = null, tool = Tool.AREA, importOrder = null, importUnmatched = emptyList()) }
    }
    /** 영역을 다 그리면 그 안의 지선주는 손으로 뺀 것으로 둔다(4.5, 전압 대상과 상관없이). 전주를 눌러 다시 넣을 수 있다. */
    fun finishArea() = _state.update { s ->
        s.copy(tool = Tool.NONE, focus = s.vertices).let { it.copy(manual = it.manual + it.inArea.filter { p -> p.guy }.map { p -> p.poleId to false }) }
    }.also { OpLog.log("plan", "finishArea", mapOf("vertices" to _state.value.vertices.size)) }
    fun undoVertex() = _state.update { it.copy(vertices = it.vertices.dropLast(1), plan = null) }
    /** 영역과 제외를 모두 지운다(Pilot 2 웨이포인트 지우기). */
    fun clearArea() = OpLog.log("plan", "clearArea").let {
        _state.update { it.copy(vertices = emptyList(), manual = emptyMap(), plan = null, tool = Tool.NONE, importOrder = null, importUnmatched = emptyList()) }
    }
    fun startHome() = OpLog.log("plan", "startHome").let { _state.update { it.copy(selected = null, tool = Tool.HOME) } }
    fun cancelTool() = _state.update { it.copy(tool = Tool.NONE) }
    fun updateParams(p: FlightParams) = _state.update { it.copy(params = p, plan = null) }.also { OpLog.log("plan", "params", mapOf("params" to p.toJson())) }

    /** 전압 대상 칩(4.5.8): [v]를 넣거나 빼고, 다음 새 계획도 같은 선택으로 열리게 설정의 비행 기본값에 남긴다. */
    fun toggleVoltage(v: Voltage?) {
        val targets = _state.value.params.voltageTargets.let { if (v in it) it - v else it + v }
        updateParams(_state.value.params.copy(voltageTargets = targets))
        viewModelScope.launch { settings.setFlightDefaults(settings.flightDefaults.first().copy(voltageTargets = targets)) }
    }

    /** RC 5D(10.4.3 계획 패널): ↕ 항목 고르기, ↔ 값 바꾸기. 영역·이륙 지점을 찍는 중이거나 저장 중이면 받지 않는다. */
    fun on5d(r: FiveDGate.Result) {
        if (r.conflict) _state.update { it.copy(message = "5D 입력이 겹쳐 무시했습니다. 다시 누르세요") }
        r.actions.forEach { b ->
            val s = _state.value
            if (s.tool != Tool.NONE || s.saving) return@forEach
            when (b) {
                RcButton.FIVE_D_UP, RcButton.FIVE_D_DOWN ->
                    _state.update { it.copy(paramFocus = it.params.moveItem(it.paramFocus, if (b == RcButton.FIVE_D_UP) -1 else 1)) }
                RcButton.FIVE_D_LEFT, RcButton.FIVE_D_RIGHT -> {
                    val item = s.paramFocus?.takeIf { it in s.params.items() } ?: return@forEach
                    updateParams(s.params.step(item, if (b == RcButton.FIVE_D_LEFT) -1 else 1))
                }
                else -> Unit
            }
        }
    }

    /** F-06: 영역 안 전주만 개별로 빼거나 넣는다. 손 선택은 전압 대상보다 먼저고 칩을 바꿔도 남는다(4.5.8). */
    fun togglePole(poleId: String) = _state.update { s ->
        val p = s.inArea.firstOrNull { it.poleId == poleId } ?: return@update s
        s.copy(manual = s.manual + (poleId to !s.isTarget(p)), plan = null)
    }

    /** 전주별 설정(5.7, Pilot 2 웨이포인트 ALT·짐벌 피치). 경로는 그대로 두고 다시 저장하게 한다. */
    fun setOverride(poleId: String, o: PoleOverride) {
        OpLog.log("plan", "override", mapOf("pole" to poleId, "alt" to o.altitudeM, "pitch" to o.gimbalPitchDeg))
        _state.update { it.copy(params = it.params.withOverride(poleId, o), savedMissionId = null) }
    }

    /** 경로 위 전주를 차례로 고른다(Pilot 2 "< 웨이포인트 n >"). [dir]는 −1 또는 +1. */
    fun stepPole(dir: Int) = _state.update { s ->
        val route = s.plan?.sorties?.flatMap { it.poles }.orEmpty()
        val i = route.indexOfFirst { it.poleId == s.selected }
        route.getOrNull(i + dir)?.let { s.copy(selected = it.poleId) } ?: s
    }

    /** 경로 역전(Pilot 2 S⇄E, 5.7): 고른 전주가 있는 소티, 고른 것이 없으면 모든 소티의 방문 순서를 뒤집는다. */
    fun reverseRoute() = _state.update { s ->
        val plan = s.plan ?: return@update s
        val energy = EnergyModel(EnergyParams(), s.params)
        val which = plan.sorties.indexOfFirst { so -> so.poles.any { it.poleId == s.selected } }.let { if (it >= 0) listOf(it) else plan.sorties.indices.toList() }
        OpLog.log("plan", "reverse", mapOf("sorties" to which.map { it + 1 }))
        s.copy(
            plan = which.fold(plan) { r, i -> r.reverse(i, energy) },
            reversed = which.map { sortieKey(plan.sorties[it]) }.fold(s.reversed) { acc, k -> if (k in acc) acc.minusElement(k) else acc.plusElement(k) },
            savedMissionId = null,
            message = if (which.size == 1 && plan.sorties.size > 1) "소티 ${which[0] + 1} 경로를 뒤집었습니다" else "경로를 뒤집었습니다",
        )
    }

    private fun sortieKey(s: SortiePlan) = s.poles.map { it.poleId }.toSet()

    /** 화면 나가기(뒤로·라이브러리): 저장하지 않은 경로가 있으면 Pilot 2처럼 묻는다(5.7). */
    fun requestLeave() = _state.update { if (it.unsaved) it.copy(leaveAsked = true) else it.copy(leave = true) }

    /** 저장 확인 창 답: [save]면 저장한 뒤 나가고(못 하면 남아 사유를 보임), 아니면 저장하지 않고 나간다. */
    fun answerLeave(save: Boolean) {
        _state.update { it.copy(leaveAsked = false, leave = !save) }
        if (save) savePlan(leave = true)
    }

    fun dismissLeave() = _state.update { it.copy(leaveAsked = false) }

    private suspend fun computePlan() {
        val s = _state.value
        val start = s.home
        if (start == null || !s.canPlan) return _state.update { it.copy(plan = null, planning = false) }
        OpLog.log("plan", "compute", mapOf("project" to projectId, "home" to start.toString(), "homeSource" to s.homeSource, "inArea" to s.inArea.size))
        _state.update { it.copy(planning = true) }
        try {
            val t0 = System.nanoTime()
            val inArea = s.inArea
            // 가져온 경로(5.7)는 파일 차례로 놓는다(나중에 다시 넣은 전주는 뒤에)
            val rank = s.importOrder?.withIndex()?.associate { it.value to it.index }
            val chosen = s.targets.let { l -> rank?.let { r -> l.sortedBy { r[it.poleId] ?: Int.MAX_VALUE } } ?: l }
            // 선로 방향(4방향 사선)은 영역 밖 이웃까지 포함한 전체 전주의 선로번호로 정한다(4.5.6)
            val targets = LineNumbers.withBearings(chosen.map { it.toPole() }, s.poles.orEmpty().map { it.toPole() })
            // ponytail: 에너지 모델은 기본값(EnergyParams)이다. 보정값 설정은 ⑥ 설정 화면에서 붙인다.
            val energy = EnergyModel(EnergyParams(), s.params)
            // 역전해 둔 소티(같은 전주 묶음)는 다시 계산해도 뒤집어 둔다
            val result = withContext(Dispatchers.Default) { RoutePlanner(energy).plan(start, targets, keepOrder = rank != null) }
                .let { r -> r.sorties.indices.filter { sortieKey(r.sorties[it]) in s.reversed }.fold(r) { acc, i -> acc.reverse(i, energy) } }
            val ms = (System.nanoTime() - t0) / 1_000_000
            _state.update { it.copy(plan = result, planMs = ms, planning = false, savedMissionId = null) }
            // 증빙 스크립트가 같은 입력으로 영역 판정과 가시권 판정을 다시 계산해 대조한다.
            val p = s.params
            Log.i(
                "RoutePlan",
                "vertices=${s.vertices.joinToString(";") { "${it.lat},${it.lon}" }} home=${start.lat},${start.lon} " +
                    "inArea=${inArea.size} excluded=${inArea.filterNot { s.isTarget(it) }.map { it.poleId }.sorted().joinToString(";")} " +
                    "params=${p.transitAltitudeM},${p.inspectAltitudeM},${p.speedMps},${p.batteryReservePct} sorties=${result.sorties.map { it.poles.size }} " +
                    "vlos=${result.unreachable.count { it.reason == UnreachablePole.Reason.VLOS_EXCEEDED }} " +
                    "battery=${result.unreachable.count { it.reason == UnreachablePole.Reason.BATTERY_EXCEEDED }} ms=$ms",
            )
        } finally {
            // 입력이 바뀌어 계산이 취소돼도 "계산 중"이 남지 않게
            _state.update { it.copy(planning = false) }
        }
    }

    /** [fly]면 저장 뒤 비행 화면을 연다(Pilot 2 [수행]), [leave]면 저장 뒤 화면을 닫는다(저장 확인). 이미 저장한 계획이면 저장 없이 연다. */
    fun savePlan(fly: Boolean = false, leave: Boolean = false) {
        _state.value.savedMissionId?.let { id -> _state.update { it.copy(flyMissionId = if (fly) id else it.flyMissionId, leave = leave || it.leave) }; return }
        val s = _state.value
        if (s.saving) return
        // 전주별 설정이 범위를 벗어나면(이동 고도를 낮춤 등) 저장하지 않는다
        if (!s.canSave) return _state.update { it.copy(message = it.paramErrors.firstOrNull() ?: it.message) }
        val p = s.plan ?: return
        OpLog.log("plan", "save", mapOf("project" to projectId, "sorties" to p.sorties.size, "homeSource" to s.homeSource))
        // 저장한 계획의 이륙 지점은 더 바꾸지 않는다(자동 올리기 끝)
        _state.update { it.copy(saving = true, homeAuto = false) }
        viewModelScope.launch {
            val rowIds = s.poles.orEmpty().associate { it.poleId to it.id }
            val mission = MissionEntity(
                projectId = projectId, createdAt = System.currentTimeMillis(), areaGeoJson = areaPolygon(s.vertices).toJson(),
                homeLat = p.home.lat, homeLon = p.home.lon, flightParams = s.params.toJson(), modelVersion = null, status = "PLANNED",
            )
            val sorties = p.sorties.map { so -> SortieDraft(so.estDistanceM, so.estBatteryPct, so.poles.map { rowIds.getValue(it.poleId) }) }
            runCatching { dao.saveMission(mission, sorties).also { id -> replanFrom?.let { old -> dao.setMissionStatus(old, "REPLACED") } } }
                .onSuccess { id ->
                    // 저장하는 사이 계획이 바뀌었으면 [비행 시작]을 켜지 않는다
                    _state.update {
                        it.copy(
                            savedMissionId = id.takeIf { _ -> it.plan === p },
                            flyMissionId = id.takeIf { _ -> fly && it.plan === p },
                            leave = leave,
                            message = "계획을 저장했습니다: 미션 #$id (소티 ${sorties.size}개, 전주 ${sorties.sumOf { so -> so.poleRowIds.size }}개)",
                        )
                    }
                    Log.i("RoutePlan", "saved mission=$id")
                    prefetchArea(s.vertices + p.home, "mission-$id", _state.value.background)
                }
                .onFailure { e -> _state.update { it.copy(message = "계획을 저장하지 못했습니다: ${e.message}") } }
            _state.update { it.copy(saving = false) }
        }
    }

    fun onFlightOpened() = _state.update { it.copy(flyMissionId = null) }

    companion object {
        fun factory(
            dao: ProjectDao, projectId: Long, replanFrom: Long?, mapsDir: File, settings: SettingsStore,
            positions: suspend () -> LivePositions, deviceLocation: suspend (Boolean) -> GeoPoint?, simulatorOn: () -> Boolean,
            prefetchArea: (List<GeoPoint>, String, MapBackground) -> Unit, copyFrom: Long? = null,
            importUri: String? = null, readRoute: suspend (String) -> ImportedRoute,
        ) = viewModelFactory {
            initializer { PlanViewModel(dao, projectId, replanFrom, mapsDir, settings, positions, deviceLocation, simulatorOn, prefetchArea, copyFrom, importUri, readRoute) }
        }
    }
}
