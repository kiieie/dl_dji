package com.example.myfirstapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.drone.AircraftSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 기체 설정 탭 화면 상태. [values]는 항목 이름 → 읽은 값(못 읽으면 없음). [confirming]은 확인을 기다리는 동작. */
data class AircraftSettingsUiState(
    val tab: Int = 0,
    val values: Map<String, Any?> = emptyMap(),
    val loading: Boolean = false,
    val message: String? = null,
    val confirming: AircraftSetting? = null,
)

/** Pilot 2식 기체 설정(10장 ⑥, 매뉴얼 57쪽). 탭을 열 때 그 탭의 값을 기체에서 읽고, 바꾸면 쓰고 다시 읽는다. */
class AircraftSettingsViewModel(val tabs: List<Pair<String, List<AircraftSetting>>>) : ViewModel() {
    private val _state = MutableStateFlow(AircraftSettingsUiState())
    val state: StateFlow<AircraftSettingsUiState> = _state.asStateFlow()

    fun openTab(index: Int) {
        _state.update { it.copy(tab = index, values = emptyMap(), message = null) }
        reload()
    }

    fun reload() {
        val items = tabs.getOrNull(_state.value.tab)?.second ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val values = items.associate { it.label to it.load() }
            _state.update { it.copy(values = values, loading = false) }
        }
    }

    /** 값 바꾸기. 확인이 필요한 동작은 [confirm]을 거친다. */
    fun change(setting: AircraftSetting, value: Any) {
        viewModelScope.launch {
            val result = runCatching { setting.save(value) }
            _state.update { it.copy(message = result.fold({ "${setting.label}: 적용했습니다" }, { e -> "${setting.label} 실패: ${e.message}" })) }
            reload()
        }
    }

    fun ask(setting: AircraftSetting) {
        val kind = setting.kind
        if (kind is AircraftSetting.Kind.Action && kind.confirm != null) _state.update { it.copy(confirming = setting) } else change(setting, Unit)
    }

    fun confirm() {
        val s = _state.value.confirming ?: return
        _state.update { it.copy(confirming = null) }
        change(s, Unit)
    }

    fun dismiss() = _state.update { it.copy(confirming = null) }

    companion object {
        fun factory(tabs: () -> List<Pair<String, List<AircraftSetting>>>) = viewModelFactory { initializer { AircraftSettingsViewModel(tabs()) } }
    }
}
