package com.example.myfirstapp.ui.flight

import android.util.Log
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.nextZoom
import com.example.myfirstapp.data.OpLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 카메라 뷰 공통 카메라 조작(10.4.3): 렌즈, 줌 +/−, 사진/영상, 녹화. 자동·수동 비행 ViewModel이 같이 쓴다.
 * 상태는 각 ViewModel의 [FlightUiState]에 둔다([get]·[set]). 실패는 로그만 남긴다(화면 값은 누른 대로 둔다).
 */
internal class CameraControl(
    private val scope: CoroutineScope,
    private val drone: () -> DroneGateway?,
    private val get: () -> FlightUiState,
    private val set: ((FlightUiState) -> FlightUiState) -> Unit,
    private val tag: String,
) {
    fun setLens(lens: Lens) {
        OpLog.log(tag, "lens", mapOf("lens" to lens.name))
        set { it.copy(lens = lens) }
        call("렌즈 전환") { setVideoLens(lens) }
    }

    /** 줌 +/−. 광각이면 먼저 망원으로 바꾸고 지금 배율을 건다(누른 단계는 그다음부터). */
    fun zoom(up: Boolean) {
        val s = get()
        val ratio = if (s.lens == Lens.ZOOM) nextZoom(s.zoomRatio, up) else s.zoomRatio
        if (s.lens != Lens.ZOOM) setLens(Lens.ZOOM)
        OpLog.log(tag, "zoom", mapOf("ratio" to ratio))
        set { it.copy(zoomRatio = ratio) }
        call("줌") { setZoom(ratio) }
    }

    fun setVideoMode(on: Boolean) {
        if (get().videoMode == on || get().recordingSinceMs != null) return
        OpLog.log(tag, "videoMode", mapOf("on" to on))
        set { it.copy(videoMode = on) }
        call("영상 모드") { setVideoMode(on) }
    }

    fun toggleRecording() {
        val on = get().recordingSinceMs == null
        OpLog.log(tag, "record", mapOf("on" to on))
        set { it.copy(recordingSinceMs = if (on) System.currentTimeMillis() else null) }
        call("녹화") { setRecording(on) }
    }

    /** 녹화를 멈추고 사진 모드로 돌린다(자동 비행 재개 전, 10.4.3). */
    fun backToPhoto() {
        if (get().recordingSinceMs != null) toggleRecording()
        setVideoMode(false)
    }

    private fun call(what: String, block: suspend DroneGateway.() -> Result<Unit>) {
        val d = drone() ?: return
        scope.launch { d.block().onFailure { e -> Log.w("Camera", "$what 실패: ${e.message}") } }
    }
}
