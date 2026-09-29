package com.example.myfirstapp.ui.mission

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

    private fun closeConfirm(nav: (MissionSummary) -> MissionNav) {
        val m = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        _nav.trySend(nav(m))
    }

    companion object {
        fun factory(dao: ProjectDao, projectId: Long) = viewModelFactory { initializer { MissionListViewModel(dao, projectId) } }
    }
}
