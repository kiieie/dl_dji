package com.example.myfirstapp.ui.procedure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.executor.PreflightCheck
import com.example.myfirstapp.core.executor.PreflightEvaluator
import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.drone.AircraftSetting
import com.example.myfirstapp.drone.PreflightExtras
import com.example.myfirstapp.drone.PreflightSettings
import com.example.myfirstapp.drone.djiAircraftConnected
import com.example.myfirstapp.drone.djiPreflightExtras
import com.example.myfirstapp.drone.djiPreflightReport
import com.example.myfirstapp.drone.djiPreflightSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 비행 화면 위 절차 화면(10.5.5): 비행 전 점검 → 웨이포인트 경로 체크리스트. */
enum class ProcedurePage { PREFLIGHT, CHECKLIST }

/** 비행 전 점검 화면 상태(10.5.5). [values]는 설정 이름 → 기체에서 읽은 값(못 읽으면 없음). */
data class PreflightUiState(
    val report: PreflightReport? = null,
    val extras: PreflightExtras = PreflightExtras(),
    /** 우리 점검 항목(6.7): 배터리 예상, LDM, GNSS, 첫 전주 거리, SD, 장애물 감지 등. */
    val checks: List<PreflightCheck> = emptyList(),
    val values: Map<String, Any?> = emptyMap(),
    val message: String? = null,
    /** 기체 없이 모의 값으로 보이는 중. */
    val simulated: Boolean = false,
) {
    /** 확인해도 넘어갈 수 없는 실패(기체 연결, LDM). */
    val blocked get() = PreflightEvaluator.blocked(checks)

    /** HMS 칸(Pilot 2 "표준"): 확인할 항목 수. */
    val warnings get() = checks.count { !it.ok }
}

/**
 * Pilot 2 비행 전 점검(10.5.5) 한 화면: 상태 줄·우리 점검 항목은 [AppConfig.Preflight.STATUS_MS]마다 다시 읽고, 설정은 열 때 읽고 바꾸면 쓰고 그 항목만 다시 읽는다.
 * [report]·[checksOf]는 비행 화면이면 그 실행기의 기체·소티 판정(배터리 예상·첫 전주), 수동 비행이면 [PreflightEvaluator.manual].
 */
class PreflightViewModel(
    private val report: suspend () -> PreflightReport?,
    private val extras: suspend () -> PreflightExtras,
    val settings: PreflightSettings,
    private val checksOf: (PreflightReport) -> List<PreflightCheck>,
    simulated: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(PreflightUiState(simulated = simulated))
    val state: StateFlow<PreflightUiState> = _state.asStateFlow()

    /** 화면이 보이는 동안만 상태를 다시 읽는다. */
    private val active = MutableStateFlow(true)
    fun setActive(on: Boolean) { active.value = on }

    /** 설정 값을 기체에서 다시 읽는다(화면을 열 때마다). */
    fun reloadValues() {
        viewModelScope.launch {
            val values = settings.all.filter { it.kind !is AircraftSetting.Kind.Action }.associate { it.label to it.load() }
            _state.update { it.copy(values = values) }
        }
    }

    init {
        viewModelScope.launch {
            active.collectLatest { on ->
                while (on) {
                    refreshStatus()
                    delay(AppConfig.Preflight.STATUS_MS)
                }
            }
        }
    }

    private suspend fun refreshStatus() {
        // 모의 기체: 위 점검 줄의 RTH 고도도 아래 스테퍼와 같은 메모리 설정에서 읽는다(11-04 RC: 60m ↔ 100 어긋남)
        val r = report()?.let { r -> if (!_state.value.simulated) r else (settings.rth.load() as? Int)?.let { r.copy(rthAltitudeM = it.toDouble()) } ?: r } ?: return
        val x = extras()
        val checks = checksOf(r)
        // 운용 로그(4.4): 처음 읽은 판정만 남긴다(2초마다 다시 읽음)
        if (_state.value.report == null) OpLog.log("preflight", "checklist", checks.associate { it.label to "${if (it.ok) "OK" else "WARN"} ${it.value}" })
        _state.update { it.copy(report = r, extras = x, checks = checks) }
    }

    /** 설정 바꾸기·홈포인트 동작. 쓰고 그 항목을 다시 읽는다(기체가 범위 밖 값을 고칠 수 있음). */
    fun change(setting: AircraftSetting, value: Any) {
        viewModelScope.launch {
            val result = runCatching { setting.save(value) }
            OpLog.log("preflight", setting.label, mapOf("value" to value.toString(), "ok" to result.isSuccess, "error" to result.exceptionOrNull()?.message))
            val now = if (setting.kind is AircraftSetting.Kind.Action) null else setting.load()
            _state.update {
                it.copy(
                    values = if (setting.kind is AircraftSetting.Kind.Action) it.values else it.values + (setting.label to now),
                    message = result.fold({ "${setting.label}: 적용했습니다" }, { e -> "${setting.label} 실패: ${e.message}" }),
                )
            }
            refreshStatus()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        /**
         * 기체가 붙어 있으면 DJI 키, 아니면 모의 값(10.5.5: 기체 없이도 화면을 그린다).
         * [report]·[checksOf]를 주면 비행 화면 실행기와 같은 기체·판정을 쓴다.
         */
        fun factory(
            report: (suspend () -> PreflightReport?)? = null,
            checksOf: (PreflightReport) -> List<PreflightCheck> = PreflightEvaluator::manual,
        ) = viewModelFactory {
            initializer {
                val real = djiAircraftConnected()
                // 모의 기체의 점검 값(만들기만 하고 돌리지 않으므로 빈 scope)
                val fake = FakeDroneGateway(CoroutineScope(EmptyCoroutineContext), GeoPoint(0.0, 0.0)).preflightReport
                PreflightViewModel(
                    report = report ?: if (real) ({ djiPreflightReport() }) else ({ fake }),
                    extras = if (real) ({ djiPreflightExtras() }) else ({ PreflightExtras('N', FakeDroneGateway.Config().batteryVoltageV) }),
                    settings = djiPreflightSettings().let { if (real) it else it.inMemory() },
                    checksOf = checksOf, simulated = !real,
                )
            }
        }
    }
}
