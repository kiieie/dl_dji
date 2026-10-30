package com.example.myfirstapp.ui.flight

import android.util.Log
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.nextZoom
import com.example.myfirstapp.data.OpLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 카메라 뷰 공통 카메라 조작(10.4.3): 렌즈, 줌 +/−, 사진/영상, 녹화. 자동·수동 비행 ViewModel이 같이 쓴다.
 * 상태는 각 ViewModel의 [FlightUiState]에 둔다([get]·[set]). 화면 값은 누른 대로 바로 바꾸고, 기체가 거절하면 되돌린다
 * (그 사이 다음 누름으로 값이 바뀌었으면 그대로 둔다).
 * 명령은 누른 차례대로 하나씩 보낸다(렌즈 전환이 끝난 뒤 줌).
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
        val was = get().lens
        set { it.copy(lens = lens) }
        call("렌즈 전환", { if (it.lens == lens) it.copy(lens = was) else it }) { setVideoLens(lens) }
    }

    /** 줌 +/−. 광각이면 먼저 망원으로 바꾸고 지금 배율을 건다(누른 단계는 그다음부터). */
    fun zoom(up: Boolean) {
        val s = get()
        val ratio = if (s.lens == Lens.ZOOM) nextZoom(s.zoomRatio, up) else s.zoomRatio
        if (s.lens != Lens.ZOOM) setLens(Lens.ZOOM)
        OpLog.log(tag, "zoom", mapOf("ratio" to ratio))
        set { it.copy(zoomRatio = ratio) }
        call("줌", { if (it.zoomRatio == ratio) it.copy(zoomRatio = s.zoomRatio) else it }) { setZoom(ratio) }
    }

    fun setVideoMode(on: Boolean) {
        if (get().videoMode == on || get().recordingSinceMs != null) return
        OpLog.log(tag, "videoMode", mapOf("on" to on))
        set { it.copy(videoMode = on) }
        call("영상 모드", { if (it.videoMode == on) it.copy(videoMode = !on) else it }) { setVideoMode(on) }
    }

    fun toggleRecording() {
        val was = get().recordingSinceMs
        val on = was == null
        OpLog.log(tag, "record", mapOf("on" to on))
        set { it.copy(recordingSinceMs = if (on) System.currentTimeMillis() else null) }
        call("녹화", { it.copy(recordingSinceMs = was) }) { setRecording(on) }
    }

    /** 녹화를 멈추고 사진 모드로 돌린다(자동 비행 재개 전, 10.4.3). */
    fun backToPhoto() {
        if (get().recordingSinceMs != null) toggleRecording()
        setVideoMode(false)
    }

    /**
     * 재개 전용: 녹화 정지와 사진 모드 전환을 기체가 받을 때까지 기다린다. 미션 재개와 동시에 보내면 첫 지점 사진을 놓칠 수 있다.
     * 영상 모드가 아니면 바로 돌아온다.
     */
    suspend fun backToPhotoAndWait() = lock.withLock {
        val d = drone()
        if (get().recordingSinceMs != null) {
            set { it.copy(recordingSinceMs = null) }
            d?.setRecording(false)?.onFailure { e -> Log.w("Camera", "녹화 정지 실패: ${e.message}") }
        }
        if (get().videoMode) {
            set { it.copy(videoMode = false) }
            d?.setVideoMode(false)?.onFailure { e -> Log.w("Camera", "사진 모드 실패: ${e.message}") }
        }
    }

    /** 재개 전용: 일시정지 중 바꾼 망원 배율을 미션 사진 배율로 되돌린다(지점 망원 사진이 그 배율로 찍힘). */
    suspend fun restoreZoom(ratio: Double) = lock.withLock {
        // 화면 값이 같아도 카메라는 다를 수 있으니(거절·되돌림 사이) 늘 보낸다
        set { it.copy(zoomRatio = ratio) }
        drone()?.setZoom(ratio)?.onFailure { e -> Log.w("Camera", "줌 되돌림 실패: ${e.message}") }
    }

    /** 카메라 명령 차례(공정한 [Mutex]라 누른 순서대로). */
    private val lock = Mutex()

    private fun call(what: String, undo: (FlightUiState) -> FlightUiState, block: suspend DroneGateway.() -> Result<Unit>) {
        val d = drone() ?: return
        scope.launch {
            lock.withLock { d.block() }.onFailure { e ->
                Log.w("Camera", "$what 실패: ${e.message}")
                OpLog.log(tag, "cameraFail", mapOf("what" to what, "error" to e.message))
                set(undo)
            }
        }
    }
}
