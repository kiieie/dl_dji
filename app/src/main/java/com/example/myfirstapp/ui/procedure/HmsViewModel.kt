package com.example.myfirstapp.ui.procedure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.HMS_MODULES
import com.example.myfirstapp.core.model.HmsIssue
import com.example.myfirstapp.core.model.hmsModule
import com.example.myfirstapp.drone.HmsDevice
import com.example.myfirstapp.drone.djiAircraftConnected
import com.example.myfirstapp.drone.djiHmsDevice
import com.example.myfirstapp.drone.djiHmsIssues
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** HMS 목록 화면 상태(10.5.5). [modules]는 [HMS_MODULES] 순서로 모듈 → 그 모듈 항목(없으면 정상). */
data class HmsUiState(
    val issues: List<HmsIssue> = emptyList(),
    val device: HmsDevice = HmsDevice(),
    val connected: Boolean = false,
) {
    val modules: List<Pair<String, List<HmsIssue>>> get() = HMS_MODULES.map { m -> m to issues.filter { hmsModule(it.code) == m }.sortedByDescending { it.level } }
}

/** HMS 목록: 항목은 [AppConfig.Polling.HOME_AIRCRAFT_MS]마다 SDK 목록을 다시 읽고(메모리 값, 키 요청 없음), 펌웨어·정비 기록은 열 때 한 번. */
class HmsViewModel(private val readIssues: () -> List<HmsIssue>, private val readDevice: suspend () -> HmsDevice) : ViewModel() {
    private val _state = MutableStateFlow(HmsUiState())
    val state: StateFlow<HmsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(device = readDevice()) } }
        viewModelScope.launch {
            while (true) {
                _state.update { it.copy(issues = readIssues(), connected = djiAircraftConnected()) }
                delay(AppConfig.Polling.HOME_AIRCRAFT_MS)
            }
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                HmsViewModel({ djiHmsIssues(app) }, ::djiHmsDevice)
            }
        }
    }
}
