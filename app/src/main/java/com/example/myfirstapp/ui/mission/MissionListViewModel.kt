package com.example.myfirstapp.ui.mission

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import com.example.myfirstapp.data.OpLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.model.needsResumeConfirm
import com.example.myfirstapp.data.MissionSummary
import com.example.myfirstapp.data.ProjectDao
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.myfirstapp.core.input.FiveDGate
import com.example.myfirstapp.core.port.RcButton

data class MissionListUiState(
    val loading: Boolean = true,
    val projectName: String = "",
    val missions: List<MissionSummary> = emptyList(),
    /** 고른 줄(10.4.3: 고른 줄에만 채운 버튼). null이면 맨 위. */
    val selectedId: Long? = null,
    /** 날짜 확인 대화상자를 띄울 미션(F-20). */
    val confirm: MissionSummary? = null,
    /** 삭제 확인을 기다리는 미션. */
    val deleting: MissionSummary? = null,
    /** ⋯ 메뉴가 열린 미션. */
    val menuFor: Long? = null,
) {
    val selected get() = missions.firstOrNull { it.id == selectedId } ?: missions.firstOrNull()
}

/** 줄의 주 동작: 계획됨은 비행, 남은 전주가 있으면 이어하기, 아니면 결과(사진이 있을 때). */
enum class MissionAction(val label: String) { FLY("▶ 비행"), CONTINUE("이어하기"), RESULT("결과") }

fun MissionSummary.primaryAction(): MissionAction? = when {
    status == "PLANNED" -> MissionAction.FLY
    remainingCount > 0 && status != "PARTIAL" -> MissionAction.CONTINUE
    doneCount > 0 -> MissionAction.RESULT
    else -> null
}

/** 미션 목록에서 다른 화면으로 가는 동작. */
sealed interface MissionNav {
    data class Flight(val missionId: Long) : MissionNav
    /** 남은 전주만 골라 새 계획을 세운다. 저장하면 [missionId]는 부분 완료로 닫힌다. */
    data class Replan(val missionId: Long) : MissionNav
    data class Result(val missionId: Long) : MissionNav
}

/** 미션 목록 ①-1과 이어하기(F-20). 목록은 Room Flow라 비행·판독 뒤 돌아오면 저절로 바뀐다. */
class MissionListViewModel(
    private val dao: ProjectDao,
    private val projectId: Long,
    private val now: () -> Long = System::currentTimeMillis,
    /** 미션의 사진·KMZ 폴더(지울 때 함께 지움). */
    private val missionDir: (Long) -> File? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(MissionListUiState())
    val state: StateFlow<MissionListUiState> = _state.asStateFlow()
    private val _nav = Channel<MissionNav>(Channel.BUFFERED)
    val nav = _nav.receiveAsFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(projectName = dao.projectName(projectId).orEmpty()) } }
        viewModelScope.launch { dao.observeMissions(projectId).collect { list -> _state.update { it.copy(loading = false, missions = list) } } }
    }

    fun select(m: MissionSummary) = _state.update { it.copy(selectedId = m.id) }
    fun setMenu(m: MissionSummary?) = _state.update { it.copy(menuFor = m?.id) }

    /** 줄의 주 동작 실행(버튼 또는 5D 가운데). */
    fun run(m: MissionSummary) {
        select(m)
        when (m.primaryAction()) {
            MissionAction.FLY, MissionAction.CONTINUE -> fly(m)
            MissionAction.RESULT -> _nav.trySend(MissionNav.Result(m.id))
            null -> Unit
        }
    }

    /** 5D 입력 정리(결과 화면과 같은 [FiveDGate]). */
    private val gate = FiveDGate()

    /** RC 5D(10.4.4): ↕ 줄 고르기, ● 주 동작. 대화상자·⋯ 메뉴가 떠 있으면 받지 않는다(← 뒤로는 MainActivity 몫). */
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
        if (s.confirm != null || s.deleting != null || s.menuFor != null) return@forEach
        val i = s.missions.indexOf(s.selected)
        when (b) {
            RcButton.FIVE_D_UP -> s.missions.getOrNull(i - 1)?.let(::select)
            RcButton.FIVE_D_DOWN -> s.missions.getOrNull(i + 1)?.let(::select)
            RcButton.FIVE_D_CENTER -> s.selected?.let(::run)
            else -> Unit
        }
    }

    /** [비행 시작]·[이어하기]. 마지막 비행이 오늘이 아니면 먼저 묻는다. */
    fun fly(m: MissionSummary) {
        if (needsResumeConfirm(m.lastFlightAt, now())) _state.update { it.copy(confirm = m) }
        else _nav.trySend(MissionNav.Flight(m.id))
    }

    fun confirmContinue() = closeConfirm { MissionNav.Flight(it.id) }
    fun confirmReplan() = closeConfirm { MissionNav.Replan(it.id) }
    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun askDelete(m: MissionSummary) = _state.update { it.copy(deleting = m) }
    fun dismissDelete() = _state.update { it.copy(deleting = null) }

    /** 미션 삭제: DB 기록과 미션 폴더(사진·KMZ). 되돌릴 수 없다. */
    fun confirmDelete() {
        val m = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }
        OpLog.log("mission", "delete", mapOf("mission" to m.id, "project" to projectId))
        viewModelScope.launch {
            dao.deleteMission(m.id)
            withContext(Dispatchers.IO) { missionDir(m.id)?.deleteRecursively() }
        }
    }

    private fun closeConfirm(nav: (MissionSummary) -> MissionNav) {
        val m = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        _nav.trySend(nav(m))
    }

    companion object {
        fun factory(dao: ProjectDao, projectId: Long, missionDir: (Long) -> File? = { null }) =
            viewModelFactory { initializer { MissionListViewModel(dao, projectId, missionDir = missionDir) } }
    }
}
