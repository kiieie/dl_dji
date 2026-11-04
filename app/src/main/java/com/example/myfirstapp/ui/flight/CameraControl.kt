package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.FocusMode
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.ShootMode
import com.example.myfirstapp.core.model.shootModeBlock
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.ZOOM_STEPS
import com.example.myfirstapp.core.port.nextZoom
import com.example.myfirstapp.core.port.snapZoom
import com.example.myfirstapp.core.port.zoomSteps
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.drone.DjiTimeoutException
import com.example.myfirstapp.drone.djiDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 카메라 뷰 안내문 자리·크기(Pilot 2 11-04 녹화): 가운데 큰 글(파노라마 불가), 가운데 작은 글(촬영 중 조작 금지), 왼쪽 열 핀포인트 옆(위치 레코딩 실패). */
enum class NoticeKind { CENTER, SMALL, PINPOINT }

/** 카메라 뷰 안내문(10.5.7, Pilot 2 토스트). [AppConfig.CameraView.NOTICE_MS] 뒤 사라진다. */
data class CameraNotice(val text: String, val kind: NoticeKind)

/**
 * 카메라 뷰 공통 카메라 조작(10.4.3): 렌즈, 줌 +/−, 사진/영상, 녹화, 촬영 모드 메뉴(단일·타이머·SMART), ☀ 노출 보정, 안내문. 자동·수동 비행 ViewModel이 같이 쓴다.
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
    /** 타이머 사진 한 장마다(수동 사진 경로: 받아서 가까운 전주 또는 기타로, [ManualPhotos]). */
    private val onTimerPhoto: suspend (CaptureInfo) -> Unit = {},
) {
    /** 기체 줌 범위 안의 단계(처음 줌을 누를 때 읽는다). */
    private var steps: List<Double> = ZOOM_STEPS
    private var stepsRead = false

    fun setLens(lens: Lens) {
        if (busy()) return
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
        if (busy()) return
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
        if (busy()) return
        val next = (current ?: FocusMode.AFS).next()
        OpLog.log(tag, "focusMode", mapOf("mode" to next.name))
        call("초점 모드", { it }) { setFocusMode(next) }
    }

    /** 탭 초점(10.5.2): 영상 안 위치(0~1). */
    fun focusAt(x: Double, y: Double) {
        if (busy()) return
        OpLog.log(tag, "focusAt", mapOf("x" to Math.round(x * 100) / 100.0, "y" to Math.round(y * 100) / 100.0))
        call("탭 초점", { it }) { focusAt(x, y) }
    }

    fun setVideoMode(on: Boolean) {
        if (get().videoMode == on || get().recordingSinceMs != null || busy()) return
        OpLog.log(tag, "videoMode", mapOf("on" to on))
        set { it.copy(videoMode = on) }
        // 사진으로 돌아오면 카메라는 일반 사진이다: SMART였으면 다시 건다
        call("영상 모드", { if (it.videoMode == on) it.copy(videoMode = !on) else it }) {
            val r = setVideoMode(on)
            if (r.isSuccess && !on && get().shootMode == ShootMode.SMART) setShootMode(ShootMode.SMART) else r
        }
    }

    fun toggleRecording() {
        if (get().recordingSinceMs == null && busy()) return
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

    /** 녹화·타이머를 멈추고 단일 사진 모드로 돌린다(일시정지를 벗어날 때, 10.4.3). */
    fun backToPhoto() {
        scope.launch { backToPhotoAndWait() }
    }

    /**
     * 재개·업로드 전용: 타이머 정지, 녹화 정지, 사진 모드, 단일 촬영(SMART 풀기)을 기체가 받을 때까지 기다린다.
     * 미션 재개와 동시에 보내면 첫 지점 사진을 놓칠 수 있고, KMZ 촬영 동작이 SMART·인터벌로 찍히면 안 된다. 바꿀 것이 없으면 바로 돌아온다.
     */
    suspend fun backToPhotoAndWait() {
        if (get().timerRunning) {
            drone()?.stopInterval()
            timerJob?.join()
        }
        lock.withLock {
            val d = drone()
            set { it.copy(shootMenu = false) }
            if (get().recordingSinceMs != null) {
                set { it.copy(recordingSinceMs = null) }
                d?.setRecording(false)?.let { logResult("녹화 정지", it) }
            }
            if (get().videoMode) {
                set { it.copy(videoMode = false) }
                d?.setVideoMode(false)?.let { logResult("사진 모드", it) }
            }
            if (get().shootMode != ShootMode.SINGLE) {
                set { it.copy(shootMode = ShootMode.SINGLE) }
                d?.setShootMode(ShootMode.SINGLE)?.let { logResult("단일 촬영", it) }
            }
        }
    }

    /** 타이머 촬영 중·SMART 촬영 중에는 카메라를 바꾸지 않는다(Pilot 2 "촬영 중 기체 또는 카메라 작동 금지"). 막으면 안내문을 띄운다. */
    private fun busy(): Boolean = (get().timerRunning || get().capturing).also { if (it) notice(BUSY, NoticeKind.SMALL) }

    /** Pilot 2 안내문(10.5.7)을 [AppConfig.CameraView.NOTICE_MS] 동안 보인다. 그 사이 다른 안내문이 오면 그것으로 바꾼다. */
    fun notice(text: String, kind: NoticeKind) {
        val n = CameraNotice(text, kind)
        set { it.copy(notice = n) }
        scope.launch {
            delay(AppConfig.CameraView.NOTICE_MS)
            set { if (it.notice === n) it.copy(notice = null) else it }
        }
    }

    /** 수동 사진에 기체 위치가 없음(위성 부족): 전주에 붙이지 못하고 기타로 간다. Pilot 2 핀포인트 문구 그대로. */
    fun noPosition() = notice(NO_POSITION, NoticeKind.PINPOINT)

    private var modesRead = false

    /** 촬영 모드 메뉴(10.5.7) 열기: 촬영 중이면 안내문만. 처음 열 때 기체가 받는 모드·간격을 읽는다. */
    fun openShootMenu() {
        if (busy()) return
        set { it.copy(shootMenu = true) }
        val d = drone() ?: return
        if (modesRead) return
        modesRead = true
        scope.launch { d.photoModes()?.let { m -> set { it.copy(photoModes = m) } } }
    }

    fun closeShootMenu() = set { it.copy(shootMenu = false) }

    /**
     * 촬영 모드 메뉴의 단일·타이머·파노라마·SMART. 고를 수 없으면([shootModeBlock]: 파노라마, 기체가 모르는 모드) 안내문만 띄우고 거짓.
     * 카메라는 단일·SMART만 바로 바꾼다. 타이머는 셔터를 누를 때 인터벌로 바꾸고 끝나면 돌린다(KMZ 지점 사진이 인터벌로 찍히지 않게).
     */
    fun setShootMode(mode: ShootMode): Boolean {
        if (busy()) return false
        val s = get()
        shootModeBlock(mode, s.photoModes.modes, s.telemetry?.isFlying == true)?.let {
            notice(it, NoticeKind.CENTER)
            return false
        }
        if (s.videoMode) setVideoMode(false)
        if (s.shootMode == mode) return true
        OpLog.log(tag, "shootMode", mapOf("mode" to mode.name))
        set { it.copy(shootMode = mode) }
        call("촬영 모드", { if (it.shootMode == mode) it.copy(shootMode = s.shootMode) else it }) { setShootMode(mode) }
        return true
    }

    /** 타이머 간격 고르기(메뉴 셋째 열). */
    fun setTimer(sec: Double) {
        if (setShootMode(ShootMode.TIMER)) set { it.copy(timerSec = sec) }
    }

    private var timerJob: Job? = null

    /** 타이머 셔터: 쉬고 있으면 고른 간격으로 시작, 돌고 있으면 멈춤(늦은 사진까지 받고 끝남). 사진마다 [onTimerPhoto]. */
    fun toggleTimer() {
        val d = drone() ?: return
        if (get().timerRunning) {
            OpLog.log(tag, "timer", mapOf("stop" to true))
            scope.launch { d.stopInterval() }
            return
        }
        val sec = get().timerSec
        OpLog.log(tag, "timer", mapOf("sec" to sec))
        set { it.copy(timerRunning = true, shootMenu = false) }
        timerJob = scope.launch {
            val r = try {
                // 받기는 타이머와 따로 돈다: 멈춤(재개 전 기다림)이 받기를 기다리지 않게
                d.intervalShots(sec).collect { info -> scope.launch { onTimerPhoto(info) } }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            logResult("타이머", r)
            set { it.copy(timerRunning = false) }
        }
    }

    /** ☀ 노출 손잡이(10.5.7): EV를 [ev]로(1/3 단계, ±[AppConfig.CameraView.EV_MAX]). 화면 값은 기체 알림(노출 띠 EV)으로 바뀐다. */
    fun setEv(ev: Double) {
        if (busy()) return
        val v = ev.coerceIn(-AppConfig.CameraView.EV_MAX, AppConfig.CameraView.EV_MAX)
        OpLog.log(tag, "ev", mapOf("ev" to Math.round(v * 10) / 10.0))
        call("노출 보정", { it }) { setExposureCompensation(v) }
    }

    /** 재개 전용: 일시정지 중 바꾼 망원 배율을 미션 사진 배율로 되돌린다(지점 망원 사진이 그 배율로 찍힘). */
    suspend fun restoreZoom(ratio: Double) = lock.withLock {
        // 화면 값이 같아도 카메라는 다를 수 있으니(거절·되돌림 사이) 늘 보낸다
        set { it.copy(zoomRatio = ratio) }
        drone()?.setZoom(ratio)?.let { logResult("줌 되돌림", it) }
    }

    /** 카메라 명령 차례(공정한 [Mutex]라 누른 순서대로). */
    private val lock = Mutex()

    companion object {
        /** Pilot 2 안내문 문구(11-04 녹화). */
        const val BUSY = "촬영 중 기체 또는 카메라 작동 금지"
        const val NO_POSITION = "이용 가능한 위성이 충분하지 않습니다. 위치 레코딩 실패"
    }

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
