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

data class MissionListUiState(
    val loading: Boolean = true,
    val projectName: String = "",
    val missions: List<MissionSummary> = emptyList(),
    /** 날짜 확인 대화상자를 띄울 미션(F-20). */
    val confirm: MissionSummary? = null,
    /** 삭제 확인을 기다리는 미션. */
    val deleting: MissionSummary? = null,
)

/** 미션 목록에서 다른 화면으로 가는 동작. */
sealed interface MissionNav {
    data class Flight(val missionId: Long) : MissionNav
    /** 남은 전주만 골라 새 계획을 세운다. 저장하면 [missionId]는 부분 완료로 닫힌다. */
    data class Replan(val missionId: Long) : MissionNav
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
