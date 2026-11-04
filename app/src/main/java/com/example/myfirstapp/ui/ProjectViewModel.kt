package com.example.myfirstapp.ui

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.data.OpLog
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.csv.CsvImportResult
import com.example.myfirstapp.core.csv.PoleAttrDiff
import com.example.myfirstapp.core.csv.PoleCsvParser
import com.example.myfirstapp.core.csv.diffPoleAttributes
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.route.ImportedRoute
import com.example.myfirstapp.core.route.RouteImport
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProjectUiState(
    val projects: List<ProjectSummary> = emptyList(),
    /** 가져오기 창이 떠 있음. 저장하거나 닫으면 내린다. */
    val importing: Boolean = false,
    /** 고른 CSV를 파싱한 결과. 저장하거나 취소하면 비운다. */
    val result: CsvImportResult? = null,
    val projectName: String = "",
    val message: String? = null,
    /** 삭제 확인 대화상자를 띄울 프로젝트. */
    val deleting: ProjectSummary? = null,
    /** ⋯ 메뉴가 열린 프로젝트 id. */
    val menuFor: Long? = null,
    /** 속성 갱신(4.5.7) 대화상자를 띄울 프로젝트. */
    val attrTarget: ProjectSummary? = null,
    /** 고른 파일로 계산한 속성 갱신 미리보기와 그때 읽은 전주. */
    val attrDiff: PoleAttrDiff? = null,
    val attrPoles: List<Pole> = emptyList(),
    /** 가져오기를 마친 뒤 알릴 말(같은 이름이라 이름을 바꿨음 등). [확인]으로 닫는다. */
    val notice: String? = null,
    val noticeTitle: String = "전주 파일 가져오기",
    /** 가져온 Pilot 2 경로를 열 곳(프로젝트, 파일 주소, 5.7). 화면이 계획 화면을 열고 [ProjectViewModel.onRouteOpened]로 지운다. */
    val routeImport: Pair<Long, String>? = null,
) {
    val canSave get() = (result as? CsvImportResult.Success)?.poles?.isNotEmpty() == true && projectName.isNotBlank()

    /** 메뉴·대화상자가 떠 있음(미션 표 5D를 막는다). */
    val overlayOpen get() = importing || notice != null || deleting != null || menuFor != null || attrTarget != null
}

/** 프로젝트 목록 ①과 CSV 가져오기 ②(10장). 파일 읽기·파싱·저장·삭제를 맡는다. */
class ProjectViewModel(private val dao: ProjectDao, private val resolver: ContentResolver) : ViewModel() {
    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { dao.observeProjects().collect { list -> _state.update { it.copy(projects = list) } } }
    }

    fun onCsvPicked(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(result = null, message = null) }
        runCatching { withContext(Dispatchers.IO) { readCsv(uri) } }
            .onSuccess { (fileName, parsed) -> _state.update { it.copy(projectName = fileName.substringBeforeLast('.'), result = parsed) } }
            .onFailure { e -> _state.update { it.copy(message = "파일을 읽지 못했습니다: ${e.message}") } }
    }

    fun setProjectName(name: String) = _state.update { it.copy(projectName = name) }
    fun openImport() = _state.update { it.copy(importing = true) }
    fun cancelImport() = _state.update { it.copy(importing = false, result = null, message = null) }

    fun save() {
        OpLog.log("project", "import", mapOf("name" to _state.value.projectName))
        val s = _state.value
        val poles = (s.result as? CsvImportResult.Success)?.poles ?: return
        if (!s.canSave) return
        val asked = s.projectName.trim()
        // 같은 이름이 있으면 날짜(와 번호)를 붙인다(11-03 현장: 같은 이름 프로젝트 2개)
        val name = uniqueProjectName(asked, s.projects.map { it.name }.toSet(), SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date()))
        viewModelScope.launch {
            runCatching { dao.importProject(name, poles) }
                .onSuccess {
                    cancelImport()
                    if (name != asked) _state.update { it.copy(notice = "같은 이름 프로젝트가 있어 '$name'(으)로 저장했습니다.") }
                }
                .onFailure { e -> _state.update { it.copy(message = "저장하지 못했습니다: ${e.message}") } }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null, noticeTitle = ProjectUiState().noticeTitle) }

    /**
     * 라이브러리 `+` 경로 가져오기(Pilot 2 KMZ/KML, 5.7): 웨이포인트가 가장 많이 맞는 프로젝트의 계획 화면을 연다.
     * 맞는 전주가 없으면(그 선로 전주 파일을 아직 안 가져옴) 알린다.
     */
    fun onRouteFilePicked(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val route = readRouteFile(resolver, uri)
                require(route.waypoints.isNotEmpty()) { "웨이포인트가 없습니다" }
                route to dao.allPoles().groupBy { it.projectId }
                    .mapValues { (_, list) -> RouteImport.match(route, list.map { it.toPole() }, Tuning.Route.IMPORT_POLE_LINK_M).poles.size }
                    .maxByOrNull { it.value }
            }
        }.onSuccess { (route, best) ->
            OpLog.log("project", "importRoute", mapOf("waypoints" to route.waypoints.size, "project" to best?.key, "poles" to best?.value))
            if (best == null || best.value == 0) {
                val m = Tuning.Route.IMPORT_POLE_LINK_M.toInt()
                _state.update { it.copy(noticeTitle = "경로 가져오기", notice = "웨이포인트 ${route.waypoints.size}개의 ${m}m 안에 전주가 없습니다. 먼저 그 선로의 전주 파일(엑셀·CSV)을 가져오세요.") }
            } else _state.update { it.copy(routeImport = best.key to uri.toString()) }
        }.onFailure { e -> _state.update { it.copy(noticeTitle = "경로 가져오기", notice = "경로 파일을 읽지 못했습니다: ${e.message}") } }
    }

    fun onRouteOpened() = _state.update { it.copy(routeImport = null) }

    fun askDelete(p: ProjectSummary) = _state.update { it.copy(deleting = p) }
    fun dismissDelete() = _state.update { it.copy(deleting = null) }

    fun confirmDelete() {
        OpLog.log("project", "delete", mapOf("project" to _state.value.deleting?.id))
        val p = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }
        viewModelScope.launch { dao.deleteProject(p.id) }
    }

    fun openMenu(id: Long?) = _state.update { it.copy(menuFor = id) }

    fun openAttrUpdate(p: ProjectSummary) = _state.update { it.copy(menuFor = null, attrTarget = p, attrDiff = null, attrPoles = emptyList(), message = null) }
    fun closeAttrUpdate() = _state.update { it.copy(attrTarget = null, attrDiff = null, attrPoles = emptyList(), message = null) }

    /** 속성 갱신 파일: 파싱하고 기존 전주와 전산화번호로 맞춰 미리보기를 만든다. */
    fun onAttrFilePicked(uri: Uri) = viewModelScope.launch {
        val p = _state.value.attrTarget ?: return@launch
        _state.update { it.copy(attrDiff = null, attrPoles = emptyList(), message = null) }
        runCatching {
            withContext(Dispatchers.IO) {
                when (val r = readCsv(uri).second) {
                    is CsvImportResult.HeaderError -> error("필수 열을 찾지 못했습니다(${r.missing.joinToString()})")
                    is CsvImportResult.Success -> r.poles to diffPoleAttributes(dao.polesOf(p.id).map { it.toPole() }, r.poles)
                }
            }
        }
            .onSuccess { (poles, diff) -> _state.update { it.copy(attrPoles = poles, attrDiff = diff) } }
            .onFailure { e -> _state.update { it.copy(message = "파일을 읽지 못했습니다: ${e.message}") } }
    }

    fun confirmAttrUpdate() {
        val s = _state.value
        val p = s.attrTarget ?: return
        if (s.attrDiff?.changed.isNullOrEmpty()) return
        OpLog.log("project", "attrUpdate", mapOf("project" to p.id, "changed" to s.attrDiff?.changed?.size))
        viewModelScope.launch {
            runCatching { dao.updatePoleAttributes(p.id, s.attrPoles) }
                .onSuccess { closeAttrUpdate() }
                .onFailure { e -> _state.update { it.copy(message = "갱신하지 못했습니다: ${e.message}") } }
        }
    }

    /** SAF로 고른 파일을 읽어 파싱한다. 파일 이름은 프로젝트 이름 기본값으로 쓴다. */
    private fun readCsv(uri: Uri): Pair<String, CsvImportResult> = readPicked(resolver, uri).let { (name, bytes) -> name to PoleCsvParser.parse(bytes) }

    companion object {
        fun factory(dao: ProjectDao, resolver: ContentResolver) = viewModelFactory { initializer { ProjectViewModel(dao, resolver) } }
    }
}

/** SAF로 고른 파일의 (이름, 내용). 10MB([AppConfig.Storage.IMPORT_MAX_BYTES])를 넘으면 거절한다. */
internal fun readPicked(resolver: ContentResolver, uri: Uri): Pair<String, ByteArray> {
    var name = "가져온 전주"
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) {
            if (!it.isNull(0)) name = it.getString(0)
            if (!it.isNull(1)) size = it.getLong(1)
        }
    }
    require((size ?: 0) <= AppConfig.Storage.IMPORT_MAX_BYTES) { "10MB를 넘는 파일입니다" }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("파일을 열 수 없습니다")
    return name to bytes
}

/** 고른 Pilot 2 경로 파일(KMZ/KML, 5.7)을 읽는다. KMZ 안 파일도 같은 크기 상한. */
internal fun readRouteFile(resolver: ContentResolver, uri: Uri): ImportedRoute = RouteImport.read(readPicked(resolver, uri).second, AppConfig.Storage.IMPORT_MAX_BYTES)

/** [name]이 [existing]에 없으면 그대로, 있으면 `-날짜`, 그것도 있으면 `-날짜-2`, `-날짜-3` …. */
internal fun uniqueProjectName(name: String, existing: Set<String>, yyyymmdd: String): String {
    if (name !in existing) return name
    val dated = "$name-$yyyymmdd"
    if (dated !in existing) return dated
    return generateSequence(2) { it + 1 }.map { "$dated-$it" }.first { it !in existing }
}
