package com.example.myfirstapp.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.Polygon
import com.example.myfirstapp.core.route.RoutePlan
import com.example.myfirstapp.core.route.RoutePlanner
import com.example.myfirstapp.core.route.UnreachablePole
import com.example.myfirstapp.data.MissionEntity
import com.example.myfirstapp.data.PoleEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.SortieDraft
import com.example.myfirstapp.data.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val selected: String? = null,
    val message: String? = null,
    val tool: Tool = Tool.NONE,
    val vertices: List<GeoPoint> = emptyList(),
    val home: GeoPoint? = null,
    val plan: RoutePlan? = null,
    val planMs: Long = 0,
    val planning: Boolean = false,
    /** 지도가 확대해 보여 줄 점들. */
    val focus: List<GeoPoint> = emptyList(),
    val params: FlightParams = FlightParams(),
    val excluded: Set<String> = emptySet(),
    /** 지금 [plan]을 저장한 미션 번호. 계획이 바뀌면 null이 되어 [비행 시작]이 [계획 저장]으로 돌아간다. */
    val savedMissionId: Long? = null,
) {
    val paramErrors: List<String> get() = params.validate()
    val inArea: List<PoleEntity>
        get() = if (vertices.size < 3) emptyList() else Polygon(vertices).let { area -> poles.orEmpty().filter { area.contains(GeoPoint(it.lat, it.lon)) } }
    val canPlan get() = home != null && inArea.size > excluded.size && paramErrors.isEmpty() && !planning
    val excludedPoints get() = poles.orEmpty().filter { it.poleId in excluded }.map { GeoPoint(it.lat, it.lon) }
}

/**
 * 계획 화면 ③(10장): 영역, 이륙 지점, 파라미터, 전주 제외, 경로 계산과 계획 저장.
 * [replanFrom]이 있으면 그 미션의 남은 전주만 골라 연다(F-20 [남은 전주로 새 계획]). 저장하면 그 미션은 부분 완료로 닫힌다.
 */
class PlanViewModel(
    private val dao: ProjectDao,
    private val projectId: Long,
    private val replanFrom: Long?,
    private val mapsDir: File,
    private val resolver: ContentResolver,
) : ViewModel() {
    private val _state = MutableStateFlow(PlanUiState(mapFiles = listMbtiles(mapsDir)))
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(projectName = dao.projectName(projectId).orEmpty()) } }
        viewModelScope.launch {
            dao.observePoles(projectId).collect { list -> _state.update { it.copy(poles = list) } }
        }
        replanFrom?.let { viewModelScope.launch { seedReplan(it) } }
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

    /** 지도 탭. 도구에 따라 꼭짓점을 더하거나 이륙 지점을 정한다. 계획 대상이 바뀌면 이전 경로는 버린다. */
    fun onMapTap(p: GeoPoint) = _state.update {
        when (it.tool) {
            Tool.NONE -> it
            Tool.AREA -> it.copy(vertices = it.vertices + p, plan = null)
            Tool.HOME -> it.copy(home = p, tool = Tool.NONE, plan = null)
        }
    }

    fun startArea() = _state.update { it.copy(vertices = emptyList(), excluded = emptySet(), plan = null, selected = null, tool = Tool.AREA) }
    fun finishArea() = _state.update { it.copy(tool = Tool.NONE, focus = it.vertices) }
    fun undoVertex() = _state.update { it.copy(vertices = it.vertices.dropLast(1)) }
    fun startHome() = _state.update { it.copy(selected = null, tool = Tool.HOME) }
    fun cancelTool() = _state.update { it.copy(tool = Tool.NONE) }
    fun updateParams(p: FlightParams) = _state.update { it.copy(params = p, plan = null) }
    fun resetPlan() = _state.update { it.copy(plan = null) }

    /** F-06: 영역 안 전주만 개별로 빼거나 다시 넣는다. */
    fun toggleExcluded(poleId: String) = _state.update {
        it.copy(excluded = if (poleId in it.excluded) it.excluded - poleId else it.excluded + poleId, plan = null)
    }

    fun computePlan() {
        val s = _state.value
        val start = s.home ?: return
        if (!s.canPlan) return
        _state.update { it.copy(planning = true) }
        viewModelScope.launch {
            val t0 = System.nanoTime()
            val inArea = s.inArea
            val targets = inArea.filter { it.poleId !in s.excluded }.map { it.toPole() }
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
        }
    }

    fun savePlan() {
        val s = _state.value
        val p = s.plan ?: return
        viewModelScope.launch {
            val rowIds = s.poles.orEmpty().associate { it.poleId to it.id }
            val mission = MissionEntity(
                projectId = projectId, createdAt = System.currentTimeMillis(), areaGeoJson = areaPolygon(s.vertices).toJson(),
                homeLat = p.home.lat, homeLon = p.home.lon, flightParams = s.params.toJson(), modelVersion = null, status = "PLANNED",
            )
            val sorties = p.sorties.map { so -> SortieDraft(so.estDistanceM, so.estBatteryPct, so.poles.map { rowIds.getValue(it.poleId) }) }
            runCatching { dao.saveMission(mission, sorties) }
                .onSuccess { id ->
                    replanFrom?.let { old -> dao.setMissionStatus(old, "PARTIAL") }
                    // 저장하는 사이 계획이 바뀌었으면 [비행 시작]을 켜지 않는다
                    _state.update {
                        it.copy(
                            savedMissionId = id.takeIf { _ -> it.plan === p },
                            message = "계획을 저장했습니다: 미션 #$id (소티 ${sorties.size}개, 전주 ${sorties.sumOf { so -> so.poleRowIds.size }}개)",
                        )
                    }
                    Log.i("RoutePlan", "saved mission=$id")
                }
                .onFailure { e -> _state.update { it.copy(message = "계획을 저장하지 못했습니다: ${e.message}") } }
        }
    }

    fun onMapFilePicked(uri: Uri) {
        _state.update { it.copy(message = "지도 파일을 복사하는 중입니다") }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { copyMbtiles(uri) } }
                .onSuccess { name -> _state.update { it.copy(mapFiles = listMbtiles(mapsDir), message = "지도를 추가했습니다: $name") } }
                .onFailure { e -> _state.update { it.copy(message = "지도를 추가하지 못했습니다: ${e.message}") } }
        }
    }

    /** SAF로 고른 MBTiles를 앱 전용 폴더(4.3 `maps/`)에 복사한다. SQLite 파일인지 머리글로 확인한다. */
    private fun copyMbtiles(uri: Uri): String {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        } ?: "map.mbtiles"
        require(name.endsWith(".mbtiles", ignoreCase = true)) { ".mbtiles 파일만 추가할 수 있습니다" }
        // 파일 이름은 스타일 JSON에 들어가므로 글자·숫자·`.`·`-`·`_`만 남긴다.
        val target = File(mapsDir, name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_"))
        val part = File(mapsDir, target.name + ".part")
        resolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it) } } ?: error("파일을 열 수 없습니다")
        val header = part.inputStream().use { String(it.readNBytesCompat(15), Charsets.US_ASCII) }
        if (header != "SQLite format 3") {
            part.delete()
            error("MBTiles(SQLite) 형식이 아닙니다")
        }
        check(part.renameTo(target)) { "파일 이름을 바꾸지 못했습니다" }
        return target.name
    }

    companion object {
        fun factory(dao: ProjectDao, projectId: Long, replanFrom: Long?, mapsDir: File, resolver: ContentResolver) =
            viewModelFactory { initializer { PlanViewModel(dao, projectId, replanFrom, mapsDir, resolver) } }
    }
}

private fun listMbtiles(dir: File) = dir.listFiles { f -> f.extension == "mbtiles" }.orEmpty().sortedBy { it.name }

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(buf, read, n - read)
        if (r < 0) break
        read += r
    }
    return buf.copyOf(read)
}
