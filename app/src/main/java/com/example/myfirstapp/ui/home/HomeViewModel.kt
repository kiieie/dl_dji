package com.example.myfirstapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.drone.AircraftStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 홈에서 [카메라 뷰로 들어가기]·[앨범]이 여는 가장 최근 미션. */
data class LatestMission(val projectId: Long, val missionId: Long, val projectName: String, val done: Int, val total: Int) {
    val remaining get() = total - done
}

data class HomeUiState(val aircraft: AircraftStatus = AircraftStatus(), val latest: LatestMission? = null)

/** 홈 화면(Pilot 2 홈페이지, 10장 ①). 기체 상태는 2초마다 다시 읽는다. */
class HomeViewModel(
    private val dao: ProjectDao,
    private val readAircraft: suspend () -> AircraftStatus,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _state.update { it.copy(aircraft = readAircraft()) }
                delay(2_000)
            }
        }
        refresh()
    }

    /** 다른 화면에서 돌아올 때 최근 미션을 다시 읽는다. */
    fun refresh() {
        viewModelScope.launch {
            val m = dao.latestMission() ?: return@launch _state.update { it.copy(latest = null) }
            val visits = dao.missionVisits(m.id)
            val done = visits.count { it.status != "PENDING" && it.status != "IN_PROGRESS" }
            _state.update { it.copy(latest = LatestMission(m.projectId, m.id, dao.projectName(m.projectId).orEmpty(), done, visits.size)) }
        }
    }

    companion object {
        fun factory(dao: ProjectDao, readAircraft: suspend () -> AircraftStatus) =
            viewModelFactory { initializer { HomeViewModel(dao, readAircraft) } }
    }
}
