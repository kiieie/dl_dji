package com.example.myfirstapp.ui.flight

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.home.LatestMission
import com.example.myfirstapp.ui.home.loadLatestMission
import com.example.myfirstapp.data.listMbtiles
import com.example.myfirstapp.data.onlineSatelliteTilesRetrying
import com.example.myfirstapp.drone.DjiDroneGateway
import com.example.myfirstapp.drone.djiAircraftConnected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 수동 비행 카메라 뷰 상태. 지도·상단 바는 비행 화면과 같은 [FlightUiState]를 쓴다. */
data class ManualFlightUiState(
    val flight: FlightUiState = FlightUiState(loading = false),
    val connected: Boolean = false,
    val shooting: Boolean = false,
    val shots: Int = 0,
    val message: String? = null,
    /** 왼쪽 위 경로 알약(Pilot 2 55쪽)이 이어서 열 최근 미션. 없으면 알약을 숨긴다. */
    val latest: LatestMission? = null,
)

/**
 * 수동 비행 카메라 뷰(Pilot 2 카메라 뷰, 10.0): 미션 없이 기체 영상·계기·지도를 보고 조종사가 스틱으로 난다.
 * 셔터는 언제든 찍고 원본을 `manual/<날짜>/`에 받는다(전주와 연결하지 않음). 자동 점검 비행은 미션의 [비행 시작]으로 한다.
 */
class ManualFlightViewModel(
    dao: ProjectDao,
    private val appFilesDir: File?,
    rcButtons: Flow<RcButton> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(ManualFlightUiState())
    val state: StateFlow<ManualFlightUiState> = _state.asStateFlow()
    private val drone: DroneGateway? = if (djiAircraftConnected()) DjiDroneGateway(viewModelScope, File(appFilesDir, "manual/kmz")) else null

    init {
        OpLog.log("manual", "open", mapOf("connected" to (drone != null)))
        _state.update { it.copy(connected = drone != null, message = if (drone == null) "기체가 연결되지 않았습니다" else null) }
        drone?.let { d -> viewModelScope.launch { d.telemetry.collect { t -> _state.update { it.copy(flight = it.flight.copy(telemetry = t, home = t.home)) } } } }
        viewModelScope.launch {
            val files = appFilesDir?.let { listMbtiles(File(it, "maps")) }.orEmpty()
            _state.update { it.copy(flight = it.flight.copy(mapFiles = files)) }
            val tiles = onlineSatelliteTilesRetrying()
            _state.update { it.copy(flight = it.flight.copy(onlineTiles = tiles)) }
        }
        viewModelScope.launch { loadLatestMission(dao)?.let { m -> _state.update { it.copy(latest = m) } } }
        // RC: 셔터 버튼은 기체가 직접 찍는다. C2는 짐벌 전환(비행 화면과 같음)
        viewModelScope.launch { rcButtons.collect { if (it == RcButton.C2) toggleGimbal() } }
    }

    /** 셔터: 찍고 원본을 앱 폴더에 받는다. */
    fun shoot() {
        val d = drone ?: return
        if (_state.value.shooting) return
        val lens = _state.value.flight.lens
        _state.update { it.copy(shooting = true) }
        viewModelScope.launch {
            val result = runCatching {
                val info = d.capture(lens, 0.0, 1.0).getOrThrow()
                val bytes = d.download(info, DownloadMode.ORIGINAL).getOrThrow()
                val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
                val file = File(appFilesDir, "manual/$day/manual_${info.takenAtMs}_${lens}.jpg")
                withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                file
            }
            OpLog.log("manual", "shoot", mapOf("ok" to result.isSuccess, "file" to result.getOrNull()?.name, "error" to result.exceptionOrNull()?.message))
            _state.update {
                it.copy(
                    shooting = false, shots = it.shots + if (result.isSuccess) 1 else 0,
                    message = result.fold({ f -> "저장했습니다: ${f.name}" }, { e -> "촬영 실패: ${e.message}" }),
                )
            }
        }
    }

    fun setLens(lens: Lens) {
        OpLog.log("manual", "lens", mapOf("lens" to lens.name))
        _state.update { it.copy(flight = it.flight.copy(lens = lens)) }
        drone?.let { d -> viewModelScope.launch { d.setVideoLens(lens) } }
    }

    fun toggleGimbal() {
        OpLog.log("manual", "gimbal")
        drone?.let { d -> viewModelScope.launch { d.toggleGimbal() } }
    }

    fun returnHome() {
        OpLog.log("manual", "rth")
        drone?.let { d -> viewModelScope.launch { d.returnHome().onFailure { e -> _state.update { it.copy(message = "RTH 실패: ${e.message}") } } } }
    }

    fun confirmLanding() {
        OpLog.log("manual", "confirmLanding")
        drone?.let { d -> viewModelScope.launch { d.confirmLanding().onFailure { e -> Log.w("Manual", "착륙 확인 실패: ${e.message}") } } }
    }

    override fun onCleared() {
        (drone as? DjiDroneGateway)?.close()
    }

    companion object {
        fun factory(dao: ProjectDao, appFilesDir: File?, rcButtons: Flow<RcButton>) =
            viewModelFactory { initializer { ManualFlightViewModel(dao, appFilesDir, rcButtons) } }
    }
}
