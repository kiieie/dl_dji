package com.example.myfirstapp.ui.settings

import com.example.myfirstapp.data.OpLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.drone.AircraftCheck
import com.example.myfirstapp.drone.AircraftSetting
import com.example.myfirstapp.drone.loadRanges
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 기체 설정 탭 화면 상태. [values]는 항목 `id` → 읽은 값(못 읽으면 없음). [confirming]은 확인을 기다리는 동작. */
data class AircraftSettingsUiState(
    val tab: Int = 0,
    val values: Map<String, Any?> = emptyMap(),
    /** 숫자 항목 범위(`id` → 범위): 기체 범위 키(RTH·최고 고도·최대 거리), 모의 기체·실패는 앱 범위. */
    val ranges: Map<String, IntRange> = emptyMap(),
    val loading: Boolean = false,
    val message: String? = null,
    val confirming: AircraftSetting? = null,
    /** 상태 점검 탭(10.4.3 ⑧): 마지막 점검 결과와 시각. */
    val check: AircraftCheck? = null,
    val checkedAt: Long? = null,
    val checking: Boolean = false,
)

/** Pilot 2식 ⋯ 설정(10.5.6, 매뉴얼 57쪽). 탭을 열 때 그 탭의 값을 기체에서 읽고, 바꾸면 쓰고 다시 읽는다. */
class AircraftSettingsViewModel(
    val tabs: List<Pair<String, List<AircraftSetting>>>,
    private val readCheck: suspend () -> AircraftCheck = { AircraftCheck() },
) : ViewModel() {
    private val _state = MutableStateFlow(AircraftSettingsUiState())
    val state: StateFlow<AircraftSettingsUiState> = _state.asStateFlow()

    init { recheck() }

    /** 상태 점검을 다시 읽는다([다시 점검]). */
    fun recheck() {
        if (_state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true) }
            val c = readCheck()
            OpLog.log("aircraftCheck", "read", mapOf("connected" to c.connected, "issues" to c.issues))
            _state.update { it.copy(check = c, checkedAt = System.currentTimeMillis(), checking = false) }
        }
    }

    fun openTab(index: Int) {
        _state.update { it.copy(tab = index, values = emptyMap(), message = null) }
        reload()
    }

    fun reload() {
        val items = tabs.getOrNull(_state.value.tab)?.second ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // 함께 읽는다: 기체가 모르는 키는 시간 제한까지 기다리므로 차례로 읽으면 탭이 늦게 뜬다
            val values = items.map { async { it.id to it.load() } }.awaitAll().toMap()
            val ranges = items.loadRanges()
            _state.update { it.copy(values = values, ranges = ranges, loading = false) }
        }
    }

    /** 값 바꾸기. 확인이 필요한 동작은 [confirm]을 거친다. */
    fun change(setting: AircraftSetting, value: Any) {
        viewModelScope.launch {
            val result = runCatching { setting.save(value) }
            OpLog.log("aircraftSetting", setting.label, mapOf("value" to value.toString(), "ok" to result.isSuccess, "error" to result.exceptionOrNull()?.message))
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
        fun factory(tabs: () -> List<Pair<String, List<AircraftSetting>>>, readCheck: suspend () -> AircraftCheck) =
            viewModelFactory { initializer { AircraftSettingsViewModel(tabs(), readCheck) } }
    }
}

/**
 * ⋯ 일반 탭 앞의 앱 항목(10.5.6, Pilot 2 일반 지도 전환 · 현재 비행경로 표시 · GPS 표시 · 단위 설정). 지도 전환은 배경 지도 설정(7.4)이고,
 * 나머지는 우리 앱이 정해 둔 표시라 바꾸는 칸이 없다.
 */
fun appGeneralSettings(store: SettingsStore) = listOf(
    AircraftSetting(
        "지도 전환", AircraftSetting.Kind.Choice(MapSource.entries.map { it.title }, dropdown = true), "모든 지도의 레이어 버튼과 같은 값",
        read = { store.mapSource.first().ordinal }, write = { v -> store.setMapSource(MapSource.entries[v as Int]) },
    ),
    AircraftSetting("현재 비행경로 표시", AircraftSetting.Kind.Info, "비행 지도의 주황 궤적(10.5.4)", read = { "늘 표시" }),
    AircraftSetting("GPS 표시", AircraftSetting.Kind.Info, read = { "WGS84 십진 도" }),
    AircraftSetting("단위 설정", AircraftSetting.Kind.Info, read = { "미터법(m · m/s)" }),
)
