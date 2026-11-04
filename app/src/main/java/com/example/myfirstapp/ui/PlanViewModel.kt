package com.example.myfirstapp.ui

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.data.OpLog
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
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.LineNumbers
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
    val excluded: Set<String> = emptySet(),
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
) {
    val paramErrors: List<String> get() = params.validate()
    /** 영역 안 전주. 상태 하나에 한 번만 계산한다(화면·canPlan·경로 계산이 여러 번 읽음, 전주 1,000개 이상). */
    val inArea: List<PoleEntity> by lazy {
        if (vertices.size < 3) emptyList() else Polygon(vertices).let { area -> poles.orEmpty().filter { area.contains(GeoPoint(it.lat, it.lon)) } }
    }
    val canPlan get() = home != null && inArea.size > excluded.size && paramErrors.isEmpty()
    val excludedPoints get() = poles.orEmpty().filter { it.poleId in excluded }.map { GeoPoint(it.lat, it.lon) }
}

/**
 * 계획 화면 ③(10장): 영역, 이륙 지점, 파라미터, 전주 제외, 경로 계산과 계획 저장.
 * [replanFrom]이 있으면 그 미션의 남은 전주만 골라 연다(F-20 [남은 전주로 새 계획]). 저장하면 그 미션은 대체됨(REPLACED)으로 닫힌다.
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
        }
        viewModelScope.launch {
            dao.observePoles(projectId).collect { list -> _state.update { it.copy(poles = list) } }
        }
        replanFrom?.let { viewModelScope.launch { seedReplan(it) } }
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
        viewModelScope.launch {
            _state.filter { it.tool == Tool.NONE }
                .map { PlanInput(it.poles, it.vertices, it.home, it.params, it.excluded) }
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
        val poles: List<PoleEntity>?, val vertices: List<GeoPoint>, val home: GeoPoint?, val params: FlightParams, val excluded: Set<String>,
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

    /** 남은 전주를 감싸는 사각형을 영역으로 두고, 그 안의 다른 전주는 제외한다(F-06 제외와 같은 방식). */
    private suspend fun seedReplan(old: Long) {
        val all = dao.observePoles(projectId).first()
        val remaining = dao.missionVisits(old).filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }.map { it.poleId }.toSet()
        val targets = all.filter { it.poleId in remaining }
        if (targets.isEmpty()) return
        val pad = 0.0003 // 약 30m
        val (s, n) = targets.minOf { it.lat } - pad to targets.maxOf { it.lat } + pad
        val (w, e) = targets.minOf { it.lon } - pad to targets.maxOf { it.lon } + pad
        val vertices = listOf(GeoPoint(s, w), GeoPoint(s, e), GeoPoint(n, e), GeoPoint(n, w))
        _state.update {
            it.copy(
                vertices = vertices, focus = vertices,
                excluded = all.filter { p -> p.lat in s..n && p.lon in w..e && p.poleId !in remaining }.map { p -> p.poleId }.toSet(),
                message = "미션 #$old 의 남은 전주 ${targets.size}개를 골랐습니다. 이륙 지점을 정하세요.",
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

    fun startArea() = OpLog.log("plan", "startArea").let { _state.update { it.copy(vertices = emptyList(), excluded = emptySet(), plan = null, selected = null, tool = Tool.AREA) } }
    /** 영역을 다 그리면 그 안의 지선주는 기본으로 뺀다(4.5). 전주를 눌러 다시 넣을 수 있다. */
    fun finishArea() = _state.update { s ->
        s.copy(tool = Tool.NONE, focus = s.vertices).let { it.copy(excluded = it.excluded + it.inArea.filter { p -> p.guy }.map { p -> p.poleId }) }
    }.also { OpLog.log("plan", "finishArea", mapOf("vertices" to _state.value.vertices.size)) }
    fun undoVertex() = _state.update { it.copy(vertices = it.vertices.dropLast(1), plan = null) }
    /** 영역과 제외를 모두 지운다(Pilot 2 웨이포인트 지우기). */
    fun clearArea() = OpLog.log("plan", "clearArea").let { _state.update { it.copy(vertices = emptyList(), excluded = emptySet(), plan = null, tool = Tool.NONE) } }
    fun startHome() = OpLog.log("plan", "startHome").let { _state.update { it.copy(selected = null, tool = Tool.HOME) } }
    fun cancelTool() = _state.update { it.copy(tool = Tool.NONE) }
    fun updateParams(p: FlightParams) = _state.update { it.copy(params = p, plan = null) }.also { OpLog.log("plan", "params", mapOf("params" to p.toJson())) }

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

    /** F-06: 영역 안 전주만 개별로 빼거나 다시 넣는다. */
    fun toggleExcluded(poleId: String) = _state.update {
        it.copy(excluded = if (poleId in it.excluded) it.excluded - poleId else it.excluded + poleId, plan = null)
    }

    private suspend fun computePlan() {
        val s = _state.value
        val start = s.home
        if (start == null || !s.canPlan) return _state.update { it.copy(plan = null, planning = false) }
        OpLog.log("plan", "compute", mapOf("project" to projectId, "home" to start.toString(), "homeSource" to s.homeSource, "inArea" to s.inArea.size))
        _state.update { it.copy(planning = true) }
        try {
            val t0 = System.nanoTime()
            val inArea = s.inArea
            // 선로 방향(4방향 사선)은 영역 밖 이웃까지 포함한 전체 전주의 선로번호로 정한다(4.5.6)
            val targets = LineNumbers.withBearings(inArea.filter { it.poleId !in s.excluded }.map { it.toPole() }, s.poles.orEmpty().map { it.toPole() })
            // ponytail: 에너지 모델은 기본값(EnergyParams)이다. 보정값 설정은 ⑥ 설정 화면에서 붙인다.
            val result = withContext(Dispatchers.Default) { RoutePlanner(EnergyModel(EnergyParams(), s.params)).plan(start, targets) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            _state.update { it.copy(plan = result, planMs = ms, planning = false, savedMissionId = null) }
            // 증빙 스크립트가 같은 입력으로 영역 판정과 가시권 판정을 다시 계산해 대조한다.
            val p = s.params
            Log.i(
                "RoutePlan",
                "vertices=${s.vertices.joinToString(";") { "${it.lat},${it.lon}" }} home=${start.lat},${start.lon} " +
                    "inArea=${inArea.size} excluded=${s.excluded.sorted().joinToString(";")} " +
                    "params=${p.transitAltitudeM},${p.inspectAltitudeM},${p.speedMps},${p.batteryReservePct} sorties=${result.sorties.map { it.poles.size }} " +
                    "vlos=${result.unreachable.count { it.reason == UnreachablePole.Reason.VLOS_EXCEEDED }} " +
                    "battery=${result.unreachable.count { it.reason == UnreachablePole.Reason.BATTERY_EXCEEDED }} ms=$ms",
            )
        } finally {
            // 입력이 바뀌어 계산이 취소돼도 "계산 중"이 남지 않게
            _state.update { it.copy(planning = false) }
        }
    }

    /** [fly]면 저장 뒤 비행 화면을 연다(Pilot 2 [수행]). 이미 저장한 계획이면 저장 없이 연다. */
    fun savePlan(fly: Boolean = false) {
        _state.value.savedMissionId?.let { id -> if (fly) _state.update { it.copy(flyMissionId = id) }; return }
        val s = _state.value
        if (s.saving) return
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
            prefetchArea: (List<GeoPoint>, String, MapBackground) -> Unit,
        ) = viewModelFactory { initializer { PlanViewModel(dao, projectId, replanFrom, mapsDir, settings, positions, deviceLocation, simulatorOn, prefetchArea) } }
    }
}
