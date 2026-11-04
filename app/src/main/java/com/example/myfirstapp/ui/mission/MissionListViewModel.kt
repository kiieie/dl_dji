package com.example.myfirstapp.ui.mission

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.input.FiveDGate
import com.example.myfirstapp.core.model.needsResumeConfirm
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.ExportResult
import com.example.myfirstapp.data.MissionSummary
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.exportMission
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.ui.home.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 상태 거르기(라이브러리 필터, 10.5.8). [FAVORITE]는 즐겨찾기만. */
enum class LibraryFilter(val label: String, val status: String?) {
    ALL("모든 상태", null), PLANNED("계획됨", "PLANNED"), IN_PROGRESS("진행 중", "IN_PROGRESS"), PARTIAL("부분 완료", "PARTIAL"),
    DONE("완료", "DONE"), REPLACED("대체됨", "REPLACED"), FAVORITE("즐겨찾기", null),
}

data class MissionListUiState(
    val loading: Boolean = true,
    /** 모든 프로젝트의 미션(최신 번호가 앞). */
    val missions: List<MissionSummary> = emptyList(),
    /** 미션 → 소티마다 방문 순서의 전주(카드 경로 그림). */
    val routes: Map<Long, List<List<RoutePoint>>> = emptyMap(),
    /** 미션 → 예상 사진 수([com.example.myfirstapp.core.model.FlightParams.expectedPhotos], 홈·미션 칸과 같은 값). */
    val expectedPhotos: Map<Long, Int> = emptyMap(),
    /** 프로젝트(선로) 거르기. null이면 모두. */
    val projectFilter: Long? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    /** 업데이트 시간 내림차순(Pilot 2 기본). */
    val newestFirst: Boolean = true,
    /** 🔍 검색어(프로젝트 이름·미션 번호). null이면 검색 칸을 닫음. */
    val query: String? = null,
    /** 고른 카드(5D·주 버튼). null이면 맨 앞. */
    val selectedId: Long? = null,
    /** 날짜 확인 대화상자를 띄울 미션(F-20). */
    val confirm: MissionSummary? = null,
    /** 삭제 확인을 기다리는 미션들. */
    val deleting: List<MissionSummary> = emptyList(),
    /** ⋯ 메뉴가 열린 미션. */
    val menuFor: Long? = null,
    /** ☑ 다중 선택 중과 고른 미션. */
    val selecting: Boolean = false,
    val checked: Set<Long> = emptySet(),
    val message: String? = null,
    /** 내보내기로 만든 ZIP(저장 폴더를 고르면 복사). */
    val exported: List<ExportResult> = emptyList(),
) {
    /** 거르고 정렬한 카드. */
    val shown: List<MissionSummary>
        get() = missions.filter { m ->
            (projectFilter == null || m.projectId == projectFilter) &&
                when (filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.FAVORITE -> m.favorite
                    else -> m.status == filter.status
                } &&
                (query.isNullOrBlank() || "${m.projectName} #${m.id}".contains(query.trim(), ignoreCase = true))
        }.let { list -> if (newestFirst) list.sortedByDescending { it.updatedAt } else list.sortedBy { it.updatedAt } }

    val selected get() = shown.firstOrNull { it.id == selectedId } ?: shown.firstOrNull()

    /** 열린 대화상자·메뉴가 있으면 5D를 받지 않는다. */
    val overlayOpen get() = confirm != null || deleting.isNotEmpty() || menuFor != null || exported.isNotEmpty()
}

/** 카드의 주 동작: 계획됨은 비행, 남은 전주가 있으면 이어하기, 아니면 결과(사진이 있을 때). */
enum class MissionAction(val label: String) { FLY("▶ 비행"), CONTINUE("이어하기"), RESULT("결과") }

fun MissionSummary.primaryAction(): MissionAction? = when {
    status == "PLANNED" -> MissionAction.FLY
    remainingCount > 0 && status != "REPLACED" -> MissionAction.CONTINUE
    doneCount > 0 -> MissionAction.RESULT
    else -> null
}

/** 라이브러리에서 다른 화면으로 가는 동작. */
sealed interface MissionNav {
    val mission: MissionSummary

    data class Flight(override val mission: MissionSummary) : MissionNav
    /** 남은 전주만 골라 새 계획을 세운다. 저장하면 옛 미션은 대체됨(REPLACED)으로 닫혀 이어하기에서 빠진다. */
    data class Replan(override val mission: MissionSummary) : MissionNav
    data class Result(override val mission: MissionSummary) : MissionNav
    /** [다시 날기](10.5.8): 같은 프로젝트·영역·전주·파라미터로 계획 화면을 연다. 저장하면 새 미션, 옛 미션은 그대로. */
    data class Copy(override val mission: MissionSummary) : MissionNav
}

/**
 * 라이브러리(10.5.8, Pilot 2 비행 경로 라이브러리): 모든 프로젝트의 미션을 경로 카드로. 프로젝트·상태·즐겨찾기로 거르고 업데이트 시간으로 정렬,
 * 다중 선택으로 즐겨찾기·삭제·내보내기. 이어하기(F-20)는 마지막 비행이 오늘이 아니면 먼저 묻는다. 목록은 Room Flow라 비행·판독 뒤 저절로 바뀐다.
 */
class MissionListViewModel(
    private val dao: ProjectDao,
    initialProject: Long? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** 미션의 사진·KMZ 폴더(지울 때 함께 지움, 내보내기 ZIP 자리). */
    private val missionDir: (projectId: Long, missionId: Long) -> File? = { _, _ -> null },
    /** 고른 폴더(SAF 트리)에 ZIP을 복사한다. 복사한 개수. */
    private val copyToFolder: suspend (folder: Uri, files: List<File>) -> Int = { _, _ -> 0 },
) : ViewModel() {
    private val _state = MutableStateFlow(MissionListUiState(projectFilter = initialProject))
    val state: StateFlow<MissionListUiState> = _state.asStateFlow()
    private val _nav = Channel<MissionNav>(Channel.BUFFERED)
    val nav = _nav.receiveAsFlow()

    init {
        viewModelScope.launch {
            dao.observeLibrary().collect { list ->
                // 카드 그림은 미션 목록이 바뀔 때만 다시 읽는다(전주 수천 개도 한 번의 질의)
                val points = dao.libraryPoints().groupBy { it.missionId }
                val routes = points.mapValues { (_, pts) ->
                    pts.groupBy { it.sortieId }.values.map { s -> s.map { RoutePoint(it.lat, it.lon, it.status != "PENDING" && it.status != "IN_PROGRESS") } }
                }
                val params = list.associate { it.id to flightParamsFromJson(it.flightParams) }
                val expected = points.mapValues { (id, pts) ->
                    params[id]?.expectedPhotos(pts.map { Pole("", GeoPoint(it.lat, it.lon), equipment = it.equipment) }) ?: 0
                }
                _state.update {
                    it.copy(loading = false, missions = list, routes = routes, expectedPhotos = expected, checked = it.checked.filter { id -> list.any { m -> m.id == id } }.toSet())
                }
            }
        }
    }

    fun select(m: MissionSummary) = _state.update { it.copy(selectedId = m.id) }
    fun setMenu(m: MissionSummary?) = _state.update { it.copy(menuFor = m?.id) }
    fun setProject(id: Long?) = _state.update { it.copy(projectFilter = id, selectedId = null) }
    fun setFilter(f: LibraryFilter) = _state.update { it.copy(filter = f, selectedId = null) }
    fun setNewestFirst(on: Boolean) = _state.update { it.copy(newestFirst = on) }
    fun setQuery(q: String?) = _state.update { it.copy(query = q, selectedId = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** 카드의 주 동작 실행(버튼 또는 5D 가운데). */
    fun run(m: MissionSummary) {
        select(m)
        when (m.primaryAction()) {
            MissionAction.FLY, MissionAction.CONTINUE -> fly(m)
            MissionAction.RESULT -> _nav.trySend(MissionNav.Result(m))
            null -> Unit
        }
    }

    fun result(m: MissionSummary) = _nav.trySend(MissionNav.Result(m)).let { setMenu(null) }

    /** [다시 날기]: 계획 화면에서 같은 경로로 새 미션을 만든다(옛 미션은 그대로). */
    fun copy(m: MissionSummary) {
        setMenu(null)
        OpLog.log("library", "copy", mapOf("mission" to m.id))
        _nav.trySend(MissionNav.Copy(m))
    }

    /** 5D 입력 정리(결과 화면과 같은 [FiveDGate]). */
    private val gate = FiveDGate()

    /** RC 5D(10.4.4): ↕ 카드 고르기(2열이라 위아래 한 줄), ● 주 동작(다중 선택 중이면 체크). ← 뒤로는 MainActivity 몫. */
    fun on5d(b: RcButton) {
        if (!b.name.startsWith("FIVE_D_")) return
        apply5d(gate.press(b, now()))
        viewModelScope.launch {
            delay(gate.settleMs)
            apply5d(gate.tick(now()))
        }
    }

    private fun apply5d(r: FiveDGate.Result) = r.actions.forEach { b ->
        val s = _state.value
        if (s.overlayOpen) return@forEach
        val list = s.shown
        val i = list.indexOf(s.selected)
        when (b) {
            RcButton.FIVE_D_UP -> list.getOrNull(i - COLUMNS)?.let(::select)
            RcButton.FIVE_D_DOWN -> list.getOrNull(i + COLUMNS)?.let(::select)
            RcButton.FIVE_D_RIGHT -> list.getOrNull(i + 1)?.let(::select)
            RcButton.FIVE_D_CENTER -> s.selected?.let { if (s.selecting) toggleCheck(it) else run(it) }
            else -> Unit
        }
    }

    /** [비행]·[이어하기]. 마지막 비행이 오늘이 아니면 먼저 묻는다. */
    fun fly(m: MissionSummary) {
        if (needsResumeConfirm(m.lastFlightAt, now())) _state.update { it.copy(confirm = m) }
        else _nav.trySend(MissionNav.Flight(m))
    }

    fun confirmContinue() = closeConfirm { MissionNav.Flight(it) }
    fun confirmReplan() = closeConfirm { MissionNav.Replan(it) }
    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    // ---------------------------------------------------------------- ☑ 다중 선택(10.5.8)

    fun setSelecting(on: Boolean) = _state.update { it.copy(selecting = on, checked = emptySet()) }
    fun toggleCheck(m: MissionSummary) = _state.update { it.copy(checked = if (m.id in it.checked) it.checked - m.id else it.checked + m.id) }

    /** 전체 선택: 보이는 카드가 다 골라져 있으면 모두 풀기. */
    fun checkAll() = _state.update { s ->
        val ids = s.shown.map { it.id }.toSet()
        s.copy(checked = if (s.checked.containsAll(ids)) emptySet() else ids)
    }

    private val checkedMissions get() = _state.value.let { s -> s.missions.filter { it.id in s.checked } }

    /** 즐겨찾기: 고른 것 중 하나라도 아니면 모두 즐겨찾기, 모두 즐겨찾기면 해제. */
    fun favoriteChecked() {
        val list = checkedMissions.ifEmpty { return }
        val on = list.any { !it.favorite }
        viewModelScope.launch { dao.setFavorite(list.map { it.id }, on) }
    }

    fun toggleFavorite(m: MissionSummary) {
        setMenu(null)
        viewModelScope.launch { dao.setFavorite(listOf(m.id), !m.favorite) }
    }

    fun askDelete(m: MissionSummary) = _state.update { it.copy(deleting = listOf(m), menuFor = null) }
    fun askDeleteChecked() = _state.update { it.copy(deleting = checkedMissions) }
    fun dismissDelete() = _state.update { it.copy(deleting = emptyList()) }

    /** 미션 삭제: DB 기록과 미션 폴더(사진·KMZ). 되돌릴 수 없다. */
    fun confirmDelete() {
        val list = _state.value.deleting.ifEmpty { return }
        _state.update { it.copy(deleting = emptyList(), checked = it.checked - list.map { m -> m.id }.toSet()) }
        viewModelScope.launch {
            list.forEach { m ->
                OpLog.log("mission", "delete", mapOf("mission" to m.id, "project" to m.projectId))
                dao.deleteMission(m.id)
                withContext(Dispatchers.IO) { missionDir(m.projectId, m.id)?.deleteRecursively() }
            }
        }
    }

    /** 내보내기(Pilot 2 다중 선택 내보내기): 고른 미션마다 결과 ZIP(4.3)을 만들고 저장할 폴더를 묻는다. */
    fun exportChecked() {
        val list = checkedMissions.ifEmpty { return }
        OpLog.log("library", "export", mapOf("missions" to list.map { it.id }))
        viewModelScope.launch {
            val results = list.mapNotNull { m ->
                val zip = missionDir(m.projectId, m.id)?.let { File(it, "export/${m.id}.zip") } ?: return@mapNotNull null
                runCatching { exportMission(dao, m.id, zip) }.getOrNull()?.let { r ->
                    // 저장 폴더에서 알아볼 이름: <프로젝트>_mission<번호>.zip
                    val named = File(zip.parentFile, "${m.projectName}_mission${m.id}.zip")
                    if (zip.renameTo(named)) r.copy(zip = named) else r
                }
            }
            _state.update { it.copy(exported = results, message = if (results.size < list.size) "ZIP ${list.size - results.size}개를 만들지 못했습니다" else null) }
        }
    }

    /** 고른 폴더로 ZIP을 복사한다. 취소하면 ZIP은 앱 폴더(미션 폴더 export/)에 남는다. */
    fun onExportFolder(folder: Uri?) {
        val files = _state.value.exported.map { it.zip }
        _state.update { it.copy(exported = emptyList()) }
        if (folder == null) return _state.update { it.copy(message = "저장을 취소했습니다. ZIP은 앱 폴더에 남아 있습니다.") }
        viewModelScope.launch {
            val n = runCatching { copyToFolder(folder, files) }.getOrDefault(0)
            _state.update { it.copy(message = "내보냈습니다: 미션 ${n}개", selecting = false, checked = emptySet()) }
        }
    }

    private fun closeConfirm(nav: (MissionSummary) -> MissionNav) {
        val m = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        _nav.trySend(nav(m))
    }

    companion object {
        /** 카드 열 수(Pilot 2 2열). */
        const val COLUMNS = 2

        fun factory(
            dao: ProjectDao, initialProject: Long?, missionDir: (Long, Long) -> File?, copyToFolder: suspend (Uri, List<File>) -> Int,
        ) = viewModelFactory { initializer { MissionListViewModel(dao, initialProject, missionDir = missionDir, copyToFolder = copyToFolder) } }
    }
}
