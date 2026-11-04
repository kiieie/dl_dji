package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.core.model.FocusMode
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.ZOOM_STEPS
import com.example.myfirstapp.core.port.nextZoom
import com.example.myfirstapp.core.port.snapZoom
import com.example.myfirstapp.core.port.zoomSteps
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.drone.DjiTimeoutException
import com.example.myfirstapp.drone.djiDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 카메라 뷰 공통 카메라 조작(10.4.3): 렌즈, 줌 +/−, 사진/영상, 녹화. 자동·수동 비행 ViewModel이 같이 쓴다.
 * 상태는 각 ViewModel의 [FlightUiState]에 둔다([get]·[set]). 화면 값은 누른 대로 바로 바꾸고, 기체가 거절하거나 응답이 없으면 되돌린다
 * (그 사이 다음 누름으로 값이 바뀌었으면 그대로 둔다). 렌즈·줌은 명령 뒤 기체 값을 다시 읽어 실제 값을 보인다(2026-11-03 현장: 화면만 바뀌고 사진은 광각).
 * 명령은 누른 차례대로 하나씩 보낸다(렌즈 전환이 끝난 뒤 줌). 응답 없는 명령은 어댑터 시간 제한으로 실패하므로 뒤 명령이 막히지 않는다.
 */
internal class CameraControl(
    private val scope: CoroutineScope,
    private val drone: () -> DroneGateway?,
    private val get: () -> FlightUiState,
    private val set: ((FlightUiState) -> FlightUiState) -> Unit,
    private val tag: String,
) {
    /** 기체 줌 범위 안의 단계(처음 줌을 누를 때 읽는다). */
    private var steps: List<Double> = ZOOM_STEPS
    private var stepsRead = false

    fun setLens(lens: Lens) {
        OpLog.log(tag, "lens", mapOf("lens" to lens.name))
        val was = get().lens
        set { it.copy(lens = lens) }
        call("렌즈 전환", { if (it.lens == lens) it.copy(lens = was) else it }, read = {
            videoLens()?.let { actual ->
                OpLog.log(tag, "cameraRead", mapOf("what" to "렌즈", "want" to lens.name, "actual" to actual.name))
                val fix: (FlightUiState) -> FlightUiState = { s -> if (s.lens == lens || s.lens == was) s.copy(lens = actual) else s }
                fix
            }
        }) { setVideoLens(lens) }
    }

    /**
     * 줌 +/−·눈금 끌기(10.5.2): 망원 배율을 한 단계 바꾼다. 광각 보기에서도 렌즈는 그대로 두고 배율만 바꾼다
     * (Pilot 2와 같음: 광각 화면의 줌 영역 사각형이 작아지고, ZOOM으로 바꾸면 그 배율).
     */
    fun zoom(up: Boolean) {
        val s = get()
        val ratio = nextZoom(s.zoomRatio, up, steps)
        if (ratio == s.zoomRatio) return
        OpLog.log(tag, "zoom", mapOf("ratio" to ratio, "lens" to s.lens.name))
        set { it.copy(zoomRatio = ratio) }
        call("줌", { if (it.zoomRatio == ratio) it.copy(zoomRatio = s.zoomRatio) else it }, read = {
            zoomRatio()?.let { actual ->
                OpLog.log(tag, "cameraRead", mapOf("what" to "줌", "want" to ratio, "actual" to actual))
                // 기체 값(6.98 등)은 가까운 단계로 맞춘다: 다음 +/−가 같은 단계를 다시 고르지 않게
                val fix: (FlightUiState) -> FlightUiState = { st -> if (st.zoomRatio == ratio || st.zoomRatio == s.zoomRatio) st.copy(zoomRatio = snapZoom(actual, steps)) else st }
                fix
            }
        }) {
            if (!stepsRead) {
                stepsRead = true
                steps = zoomSteps(zoomRange())
            }
            setZoom(ratio)
        }
    }

    /** 노출 띠 AFS 누름: 초점 모드를 차례로(AFS → AFC → MF). 화면 값은 기체 알림으로 바뀐다. */
    fun cycleFocus(current: FocusMode?) {
        val next = (current ?: FocusMode.AFS).next()
        OpLog.log(tag, "focusMode", mapOf("mode" to next.name))
        call("초점 모드", { it }) { setFocusMode(next) }
    }

    /** 탭 초점(10.5.2): 영상 안 위치(0~1). */
    fun focusAt(x: Double, y: Double) {
        OpLog.log(tag, "focusAt", mapOf("x" to Math.round(x * 100) / 100.0, "y" to Math.round(y * 100) / 100.0))
        call("탭 초점", { it }) { focusAt(x, y) }
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

    /** RC 녹화 버튼(Pilot 2와 같음): 사진 모드면 영상 모드로 바꾼 뒤 녹화를 시작하고, 녹화 중이면 멈춘다. */
    fun recordButton() {
        if (get().recordingSinceMs == null && !get().videoMode) setVideoMode(true)
        toggleRecording()
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
            d?.setRecording(false)?.let { logResult("녹화 정지", it) }
        }
        if (get().videoMode) {
            set { it.copy(videoMode = false) }
            d?.setVideoMode(false)?.let { logResult("사진 모드", it) }
        }
    }

    /** 재개 전용: 일시정지 중 바꾼 망원 배율을 미션 사진 배율로 되돌린다(지점 망원 사진이 그 배율로 찍힘). */
    suspend fun restoreZoom(ratio: Double) = lock.withLock {
        // 화면 값이 같아도 카메라는 다를 수 있으니(거절·되돌림 사이) 늘 보낸다
        set { it.copy(zoomRatio = ratio) }
        drone()?.setZoom(ratio)?.let { logResult("줌 되돌림", it) }
    }

    /** 카메라 명령 차례(공정한 [Mutex]라 누른 순서대로). */
    private val lock = Mutex()

    /** 명령 결과를 운용 로그에(성공 `cameraOk`, 실패 `cameraFail`과 이유). */
    private fun logResult(what: String, r: Result<Unit>) = r.fold(
        { OpLog.log(tag, "cameraOk", mapOf("what" to what)) },
        { e -> OpLog.log(tag, "cameraFail", mapOf("what" to what, "error" to e.djiDetail())) },
    )

    /**
     * [block]을 차례대로 보내고 결과를 남긴다. 실패면 [undo], 그다음 [read]로 기체 실제 값을 읽어 화면에 맞춘다.
     * 응답 없음(시간 초과)이면 읽기도 답이 없을 것이라 건너뛴다(차례를 그만큼 오래 잡지 않게).
     */
    private fun call(
        what: String,
        undo: (FlightUiState) -> FlightUiState,
        read: (suspend DroneGateway.() -> ((FlightUiState) -> FlightUiState)?)? = null,
        block: suspend DroneGateway.() -> Result<Unit>,
    ) {
        val d = drone() ?: return
        scope.launch {
            lock.withLock {
                val r = d.block()
                logResult(what, r)
                if (r.isFailure) set(undo)
                if (r.exceptionOrNull() !is DjiTimeoutException) read?.let { d.it() }?.let(set)
            }
        }
    }
}
