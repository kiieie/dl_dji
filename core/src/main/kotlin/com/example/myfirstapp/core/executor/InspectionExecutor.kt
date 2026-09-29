package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.executor.ExecState.Analyzing
import com.example.myfirstapp.core.executor.ExecState.Arrived
import com.example.myfirstapp.core.executor.ExecState.Capturing
import com.example.myfirstapp.core.executor.ExecState.Downloading
import com.example.myfirstapp.core.executor.ExecState.FlyingLeg
import com.example.myfirstapp.core.executor.ExecState.Idle
import com.example.myfirstapp.core.executor.ExecState.LinkLost
import com.example.myfirstapp.core.executor.ExecState.MissionDone
import com.example.myfirstapp.core.executor.ExecState.Paused
import com.example.myfirstapp.core.executor.ExecState.PreflightCheck
import com.example.myfirstapp.core.executor.ExecState.PreparingLeg
import com.example.myfirstapp.core.executor.ExecState.ReturningHome
import com.example.myfirstapp.core.executor.ExecState.SortieDone
import com.example.myfirstapp.core.executor.ExecState.Takeoff
import com.example.myfirstapp.core.executor.ExecState.Waiting
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.Detector
import com.example.myfirstapp.core.port.DroneEvent
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.core.port.InspectionRecorder
import com.example.myfirstapp.core.port.LegMission
import com.example.myfirstapp.core.port.Notifier
import com.example.myfirstapp.core.port.Sound
import com.example.myfirstapp.core.port.StorageMonitor
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.SortiePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

data class ExecutorConfig(
    /** 도착 후 처리(촬영~분석) 전체 타임아웃(6.5). */
    val arrivalTimeoutMs: Long = 60_000,
    /** 구간 업로드·시작 재시도(6.2). */
    val legRetries: Int = 3,
    val legRetryDelayMs: Long = 2_000,
    /** RTH 권고 후 무응답이면 자동 RTH까지의 시간(5.5). */
    val rthPromptSec: Int = 10,
)

/**
 * 실행 상태머신(6.4). 모든 입력(운용자 명령, 기체 이벤트, 텔레메트리, 작업 결과)을
 * 하나의 채널에 넣고 한 코루틴에서 순서대로 처리한다. 그래서 내부 상태에 잠금이 필요 없다.
 *
 * 오래 걸리는 작업(업로드, 촬영, 다운로드, 분석, 대기)은 상태마다 하나의 작업(step)으로 실행하고,
 * 결과를 다시 채널로 보낸다. 상태가 바뀌면 이전 작업은 취소되고 늦게 온 결과는 세대 번호로 버린다.
 */
class InspectionExecutor(
    private val scope: CoroutineScope,
    private val drone: DroneGateway,
    private val recorder: InspectionRecorder,
    private val notifier: Notifier,
    private val storage: StorageMonitor,
    private val energy: EnergyModel,
    private val detector: Detector?,
    private val sorties: List<SortiePlan>,
    private val config: ExecutorConfig = ExecutorConfig(),
) {
    private val params = energy.flight

    private class Visit(val sortieIndex: Int, val order: Int, val pole: Pole) {
        var status = VisitStatus.PENDING
        var captureCount = 0
        fun view() = VisitView(sortieIndex, order, pole, status, captureCount)
    }

    private val visits = sorties.flatMapIndexed { s, plan -> plan.poles.mapIndexed { i, p -> Visit(s, i, p) } }

    private sealed interface Msg {
        data class Cmd(val command: Command) : Msg
        data class Drone(val event: DroneEvent) : Msg
        data class Tele(val telemetry: Telemetry) : Msg
        data class Step(val gen: Int, val result: StepResult) : Msg
        data class Countdown(val gen: Int, val sec: Int) : Msg
        data class Rth(val token: Int, val sec: Int) : Msg
        data class ArrivalTimeout(val token: Int) : Msg
    }

    private sealed interface StepResult {
        data class PreflightDone(val failures: List<String>, val home: GeoPoint?) : StepResult
        data class TakeoffDone(val ok: Boolean) : StepResult
        data class LegReady(val ok: Boolean, val reason: String) : StepResult
        data class Captured(val captures: List<CaptureInfo>) : StepResult
        data class Downloaded(val images: List<Pair<Long, ByteArray>>) : StepResult
        data class Analyzed(val detections: Map<Long, List<Detection>>, val error: String?) : StepResult
        data object WaitDone : StepResult
        data class RthCommanded(val ok: Boolean) : StepResult
        data class ManualCaptured(val capture: CaptureInfo?, val image: ByteArray?) : StepResult
        data class Crashed(val message: String) : StepResult
    }

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val _ui = MutableStateFlow(ExecutorUiState())
    val uiState: StateFlow<ExecutorUiState> = _ui.asStateFlow()

    private var state = Idle
    private var sortieIndex = 0
    private var target: Visit? = null
    private var home: GeoPoint? = null
    private var gen = 0
    private var stepJob: Job? = null
    private var pausePending = false
    private var stopMissionBeforeLeg = false
    private var appInitiatedRth = false
    private var countdown: Int? = null
    private var message: String? = null
    private var preflightFailures: List<String> = emptyList()
    private var lastDetections: List<Detection> = emptyList()
    private var captured: List<Pair<Long, CaptureInfo>> = emptyList()

    private var arrivalToken = 0
    private var arrivalJob: Job? = null
    private var rthToken = 0
    private var rthJob: Job? = null
    private var rthCountdown: Int? = null

    fun start() {
        scope.launch { drone.events.collect { inbox.send(Msg.Drone(it)) } }
        scope.launch { drone.telemetry.collect { inbox.send(Msg.Tele(it)) } }
        scope.launch { for (m in inbox) handle(m) }
        publish()
    }

    fun send(command: Command) {
        inbox.trySend(Msg.Cmd(command))
    }

    // ---------------------------------------------------------------- 입력 처리

    private suspend fun handle(m: Msg) {
        when (m) {
            is Msg.Cmd -> onCommand(m.command)
            is Msg.Drone -> onDrone(m.event)
            is Msg.Tele -> onTelemetry(m.telemetry)
            is Msg.Step -> if (m.gen == gen) onStep(m.result)
            is Msg.Countdown -> if (m.gen == gen) countdown = m.sec
            is Msg.Rth -> if (m.token == rthToken && rthJob != null) {
                if (m.sec > 0) rthCountdown = m.sec else startRth("RTH 권고 ${config.rthPromptSec}초 무응답")
            }
            is Msg.ArrivalTimeout -> if (m.token == arrivalToken) onArrivalTimeout()
        }
        publish()
    }

    private suspend fun onCommand(c: Command) {
        when (c) {
            Command.Start -> when (state) {
                Idle, SortieDone -> startSortie()
                else -> Unit
            }
            Command.Pause -> requestPause("화면 일시정지 버튼", byRc = false)
            Command.Resume -> resume()
            Command.ReturnHome -> if (state.airborne) startRth("운용자 RTH")
            // 건너뛰기·완료 처리는 재개하면 갈 전주가 대상이다(이미 완료된 전주를 덮어쓰지 않는다)
            is Command.Skip -> if (state == Paused) nextTarget()?.let {
                setStatus(it, VisitStatus.SKIPPED, c.reason)
                message = "${it.pole.poleId} 건너뜀"
            }
            Command.MarkDone -> if (state == Paused) nextTarget()?.let {
                if (it.captureCount > 0) {
                    setStatus(it, VisitStatus.DONE)
                    message = "${it.pole.poleId} 완료 처리"
                } else {
                    message = "사진이 없는 전주는 완료 처리할 수 없습니다. 촬영하거나 건너뛰세요."
                }
            }
            Command.ManualCapture -> if (state == Paused && stepJob?.isActive != true) launchStep {
                val info = drone.capture(params.lenses.first(), params.gimbalPitchDeg, params.zoomRatio).getOrNull()
                val image = info?.let { drone.download(it, params.downloadMode).getOrNull() }
                StepResult.ManualCaptured(info, image)
            }
            Command.EndMission -> if (state == SortieDone) enter(MissionDone, "운용자 미션 종료")
        }
    }

    private suspend fun onDrone(e: DroneEvent) {
        when (e) {
            DroneEvent.LegCompleted -> if (state == FlyingLeg) enter(Arrived, "구간 미션 완료")
            is DroneEvent.LegFailed -> if (state == FlyingLeg) pauseWithWarning("구간 실패: ${e.reason}")
            DroneEvent.PausedByRc -> requestPause("RC 일시정지 버튼", byRc = true)
            DroneEvent.ResumeByRc -> resume()
            DroneEvent.ObstacleAvoidance -> if (state == FlyingLeg) pauseWithWarning("장애물 회피 동작")
            DroneEvent.GpsDegraded -> if (state == FlyingLeg || state == PreparingLeg) pauseWithWarning("GPS 품질 저하")
            DroneEvent.GoHomeStarted -> if (state.airborne || state == LinkLost) {
                appInitiatedRth = false
                enter(ReturningHome, "기체 주도 RTH 감지")
            }
            DroneEvent.Landed -> if (state.airborne || state == LinkLost || state == ReturningHome) {
                enter(SortieDone, if (state == ReturningHome) "착륙" else "예상 밖 착륙")
            }
            DroneEvent.ConnectionLost -> if (state.airborne) {
                message = "기체 연결 끊김 — 기체는 자체 페일세이프(RTH)를 따릅니다."
                notifier.play(Sound.ERROR)
                enter(LinkLost, "기체 연결 끊김")
            }
            DroneEvent.ConnectionRestored -> if (state == LinkLost) {
                // 재연결 시 기체 비행 모드로 상태를 다시 맞춘다(6.4)
                when (drone.telemetry.value.flightMode) {
                    FlightMode.GO_HOME, FlightMode.LANDING -> {
                        appInitiatedRth = false
                        enter(ReturningHome, "재연결: 기체 RTH 중")
                    }
                    FlightMode.ON_GROUND -> enter(SortieDone, "재연결: 착륙 상태")
                    else -> enter(Paused, "재연결")
                }
            }
        }
    }

    private fun onTelemetry(t: Telemetry) {
        // 구간 비행 중 감시(5.5): 복귀에 필요한 배터리 + 20% 아래면 RTH 권고
        if (state == FlyingLeg && rthJob == null && energy.mustReturn(t.batteryPct, distance(t.position, homePoint()))) {
            promptRth("배터리 부족 — 복귀 권고")
        }
    }

    private suspend fun onStep(r: StepResult) {
        when (r) {
            is StepResult.PreflightDone -> {
                preflightFailures = r.failures
                if (r.failures.isEmpty()) {
                    home = r.home
                    enter(Takeoff, "비행 전 점검 통과")
                } else {
                    message = "비행 전 점검 실패"
                    enter(Idle, "비행 전 점검 실패: ${r.failures.joinToString()}")
                }
            }
            is StepResult.TakeoffDone -> if (r.ok) enter(PreparingLeg, "이륙 완료") else pauseWithWarning("이륙 실패")
            is StepResult.LegReady -> if (r.ok) enter(FlyingLeg, "구간 시작") else pauseWithWarning(r.reason)
            is StepResult.Captured -> onCaptured(r.captures)
            is StepResult.Downloaded -> {
                r.images.forEach { (id, bytes) -> recorder.saveImage(id, bytes) }
                if (r.images.isEmpty()) {
                    message = "다운로드 실패 — 촬영 기록만 남깁니다(비행 후 원본 다운로드)."
                    afterArrivalStep(Waiting, "다운로드 실패")
                } else {
                    afterArrivalStep(Analyzing, "이미지 수신") { launchAnalyze(r.images) }
                }
            }
            is StepResult.Analyzed -> {
                r.detections.forEach { (id, list) -> recorder.saveDetections(id, detector?.modelVersion.orEmpty(), list) }
                lastDetections = r.detections.values.flatten()
                when {
                    r.error != null -> {
                        message = "분석 건너뜀: ${r.error}"
                        notifier.play(Sound.ERROR)
                    }
                    lastDetections.isNotEmpty() -> notifier.play(Sound.ANOMALY)
                    else -> notifier.play(Sound.INSPECTION_OK)
                }
                afterArrivalStep(Waiting, "분석 완료")
            }
            StepResult.WaitDone -> onWaitDone()
            is StepResult.RthCommanded -> if (!r.ok) pauseWithWarning("RTH 명령 실패 — 수동으로 조종하세요")
            is StepResult.ManualCaptured -> onManualCaptured(r)
            is StepResult.Crashed -> pauseWithWarning("오류: ${r.message}")
        }
    }

    // ---------------------------------------------------------------- 흐름

    private suspend fun startSortie() {
        val next = sorties.indices.firstOrNull { s -> visits.any { it.sortieIndex == s && it.status == VisitStatus.PENDING } }
        if (next == null) {
            enter(MissionDone, "남은 전주 없음")
            return
        }
        sortieIndex = next
        target = null
        enter(PreflightCheck, "소티 ${next + 1} 시작")
    }

    /** 다음 미점검 전주: 현재 목표가 아직 미점검이면 그 전주, 아니면 소티 순서상 첫 미점검 전주(6.4). */
    private fun nextTarget(): Visit? {
        target?.let { if (it.status == VisitStatus.PENDING || it.status == VisitStatus.IN_PROGRESS) return it }
        return visits.firstOrNull { it.sortieIndex == sortieIndex && it.status == VisitStatus.PENDING }
    }

    /** 수동 촬영을 연결할 전주: 마지막 목표(촬영 후 멈췄다면 방금 점검한 전주, 사선 촬영용), 없으면 다음 전주. */
    private fun currentTarget(): Visit? = target ?: nextTarget()

    private suspend fun requestPause(reason: String, byRc: Boolean) {
        when (state) {
            // 진행 중 작업(촬영, 다운로드, 분석)은 끝낸 뒤 일시정지한다
            Capturing, Downloading, Analyzing -> {
                pausePending = true
                message = "현재 작업을 마치고 일시정지합니다."
            }
            Takeoff, PreparingLeg, FlyingLeg, Arrived, Waiting -> {
                if (!byRc) {
                    val old = stepJob
                    scope.launch {
                        old?.join()
                        drone.stopMission()
                    }
                }
                enter(Paused, reason)
            }
            else -> Unit
        }
    }

    private suspend fun pauseWithWarning(reason: String) {
        message = reason
        notifier.play(Sound.ERROR)
        enter(Paused, reason)
    }

    private suspend fun resume() {
        if (state != Paused) return
        val next = nextTarget()
        if (next == null) {
            startRth("재개: 이 소티에 남은 전주 없음")
            return
        }
        val pos = drone.telemetry.value.position
        if (!energy.canProceed(drone.telemetry.value.batteryPct, distance(pos, next.pole.position), distance(next.pole.position, homePoint()))) {
            message = "배터리 부족 — 재개할 수 없습니다. RTH 하세요."
            notifier.play(Sound.BATTERY_WARNING)
            return
        }
        notifier.play(Sound.RESUMED)
        // 하드웨어 일시정지는 미션을 종료하지 않았으므로 새 구간 전에 stopMission으로 끝낸다(6.4)
        stopMissionBeforeLeg = true
        enter(PreparingLeg, "재개")
    }

    private suspend fun onCaptured(captures: List<CaptureInfo>) {
        val visit = target ?: return
        if (captures.isEmpty()) {
            setStatus(visit, VisitStatus.FAILED, "촬영 실패")
            message = "${visit.pole.poleId} 촬영 실패"
            notifier.play(Sound.ERROR)
            afterArrivalStep(Waiting, "촬영 실패")
            return
        }
        captured = captures.map { recorder.saveCapture(visit.sortieIndex, visit.order, it, CaptureSource.AUTO) to it }
        visit.captureCount += captures.size
        // 사진이 있으므로 완료로 기록한다. 다운로드·분석이 실패해도 완료는 유지된다(6.4 재개 규칙)
        setStatus(visit, VisitStatus.DONE)
        afterArrivalStep(Downloading, "촬영 완료") {
            val list = captured
            launchStep {
                StepResult.Downloaded(
                    list.mapNotNull { (id, info) -> drone.download(info, params.downloadMode).getOrNull()?.let { id to it } },
                )
            }
        }
    }

    private fun launchAnalyze(images: List<Pair<Long, ByteArray>>) {
        val det = detector
        launchStep {
            if (det == null) return@launchStep StepResult.Analyzed(emptyMap(), "탐지 모델 없음")
            try {
                StepResult.Analyzed(images.associate { (id, bytes) -> id to det.detect(bytes) }, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 탐지기 실패는 비행을 막지 않는다(6.5). OOM도 여기서 잡는다
                StepResult.Analyzed(emptyMap(), e.message ?: e::class.simpleName)
            }
        }
    }

    /** 도착 후 단계 사이의 전이. 일시정지가 예약되어 있으면 다음 단계 대신 일시정지한다. */
    private suspend fun afterArrivalStep(next: ExecState, reason: String, onEnter: () -> Unit = {}) {
        if (pausePending) {
            enter(Paused, "$reason → 예약된 일시정지")
            return
        }
        enter(next, reason)
        onEnter()
    }

    private suspend fun onArrivalTimeout() {
        when (state) {
            Capturing -> {
                target?.let { setStatus(it, VisitStatus.FAILED, "도착 후 처리 시간 초과") }
                afterArrivalStep(Waiting, "촬영 시간 초과")
            }
            Downloading, Analyzing -> afterArrivalStep(Waiting, "도착 후 처리 시간 초과")
            else -> Unit
        }
    }

    private suspend fun onWaitDone() {
        val next = visits.firstOrNull { it.sortieIndex == sortieIndex && it.status == VisitStatus.PENDING }
        if (next == null) {
            startRth("소티 마지막 전주 완료")
            return
        }
        val t = drone.telemetry.value
        if (!energy.canProceed(t.batteryPct, distance(t.position, next.pole.position), distance(next.pole.position, homePoint()))) {
            if (rthJob == null) promptRth("배터리 부족 — 복귀 권고")
            return
        }
        enter(PreparingLeg, "대기 완료")
    }

    private suspend fun onManualCaptured(r: StepResult.ManualCaptured) {
        val visit = currentTarget()
        if (r.capture == null) {
            message = "수동 촬영 실패"
            notifier.play(Sound.ERROR)
            return
        }
        if (visit == null) {
            message = "연결할 전주가 없습니다(사진은 SD 카드에 있음)."
            return
        }
        val id = recorder.saveCapture(visit.sortieIndex, visit.order, r.capture, CaptureSource.MANUAL)
        r.image?.let { recorder.saveImage(id, it) }
        visit.captureCount++
        // 운용자가 수동 촬영하면 그 결과로 완료 처리한다(6.4)
        if (visit.status != VisitStatus.DONE) setStatus(visit, VisitStatus.DONE)
        target = visit
        message = "${visit.pole.poleId} 수동 촬영 저장"
    }

    // ---------------------------------------------------------------- RTH

    private fun promptRth(reason: String) {
        message = reason
        notifier.play(Sound.BATTERY_WARNING)
        val token = ++rthToken
        rthCountdown = config.rthPromptSec
        rthJob = scope.launch {
            for (s in config.rthPromptSec - 1 downTo 0) {
                delay(1_000)
                inbox.send(Msg.Rth(token, s))
            }
        }
    }

    private fun cancelRthPrompt() {
        rthJob?.cancel()
        rthJob = null
        rthCountdown = null
    }

    private suspend fun startRth(reason: String) {
        appInitiatedRth = true
        enter(ReturningHome, reason)
    }

    // ---------------------------------------------------------------- 상태 진입

    private suspend fun enter(to: ExecState, reason: String) {
        val from = state
        stepJob?.cancel()
        stepJob = null
        gen++
        countdown = null
        state = to
        recorder.recordTransition(from.name, to.name, reason, target?.pole?.poleId)

        if (to !in setOf(Arrived, Capturing, Downloading, Analyzing)) {
            arrivalJob?.cancel()
            arrivalJob = null
        }
        if (to in setOf(Paused, LinkLost, ReturningHome, SortieDone, MissionDone, Idle)) cancelRthPrompt()
        if (to == Paused || to == LinkLost) pausePending = false

        when (to) {
            PreflightCheck -> {
                val plan = sorties[sortieIndex]
                val first = nextTarget()?.pole?.position
                launchStep {
                    val report = drone.preflight()
                    StepResult.PreflightDone(PreflightEvaluator.evaluate(report, params, plan.estBatteryPct, first), report.home)
                }
            }
            Takeoff -> launchStep {
                StepResult.TakeoffDone(drone.telemetry.value.isFlying || drone.takeoff().isSuccess)
            }
            PreparingLeg -> {
                val visit = nextTarget() ?: return startRth("남은 전주 없음")
                target = visit
                if (visit.status == VisitStatus.PENDING) setStatus(visit, VisitStatus.IN_PROGRESS)
                val stopFirst = stopMissionBeforeLeg
                stopMissionBeforeLeg = false
                val leg = LegMission(
                    target = inspectionPoint(visit.pole),
                    transitAltitudeM = params.transitAltitudeM,
                    inspectAltitudeM = params.inspectAltitudeM,
                    speedMps = params.speedMps,
                    rthAltitudeM = params.rthAltitudeM,
                )
                launchStep { prepareLeg(leg, stopFirst) }
            }
            Arrived -> {
                notifier.play(Sound.ARRIVED)
                val token = ++arrivalToken
                arrivalJob = scope.launch {
                    delay(config.arrivalTimeoutMs)
                    inbox.send(Msg.ArrivalTimeout(token))
                }
                enter(Capturing, "도착 알림")
            }
            Capturing -> launchStep {
                val list = mutableListOf<CaptureInfo>()
                for (lens in params.lenses) {
                    drone.capture(lens, params.gimbalPitchDeg, params.zoomRatio).getOrNull()?.let { list += it }
                }
                StepResult.Captured(list)
            }
            Waiting -> {
                val g = gen
                launchStep {
                    for (s in params.waitSec downTo 1) {
                        inbox.send(Msg.Countdown(g, s))
                        delay(1_000)
                    }
                    StepResult.WaitDone
                }
            }
            Paused -> notifier.play(Sound.PAUSED)
            ReturningHome -> {
                target?.let { if (it.status == VisitStatus.IN_PROGRESS) setStatus(it, VisitStatus.PENDING) }
                if (appInitiatedRth) launchStep {
                    drone.stopMission()
                    StepResult.RthCommanded(drone.returnHome().isSuccess)
                }
            }
            SortieDone -> {
                visits.filter { it.status == VisitStatus.IN_PROGRESS }.forEach { setStatus(it, VisitStatus.PENDING) }
                target = null
                if (visits.none { it.status == VisitStatus.PENDING }) enter(MissionDone, "남은 전주 없음")
            }
            else -> Unit
        }
    }

    private suspend fun prepareLeg(leg: LegMission, stopFirst: Boolean): StepResult {
        if (stopFirst) drone.stopMission()
        if (!storage.hasEnoughSpace()) return StepResult.LegReady(false, "저장 공간 부족")
        repeat(config.legRetries + 1) { attempt ->
            if (drone.startLeg(leg).isSuccess) return StepResult.LegReady(true, "")
            if (attempt < config.legRetries) delay(config.legRetryDelayMs)
        }
        return StepResult.LegReady(false, "구간 업로드 실패(재시도 ${config.legRetries}회 초과)")
    }

    private fun launchStep(block: suspend () -> StepResult) {
        val g = gen
        stepJob = scope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StepResult.Crashed(e.message ?: e::class.simpleName.orEmpty())
            }
            inbox.send(Msg.Step(g, result))
        }
    }

    // ---------------------------------------------------------------- 보조

    private suspend fun setStatus(v: Visit, status: VisitStatus, reason: String? = null) {
        v.status = status
        recorder.updateVisit(v.sortieIndex, v.order, status, reason)
    }

    private fun homePoint(): GeoPoint? = home ?: drone.telemetry.value.home

    private fun distance(a: GeoPoint?, b: GeoPoint?): Double =
        if (a == null || b == null) 0.0 else LocalProjection.distanceM(a, b)

    /** 점검 위치: 전주 바로 위, 또는 오프셋(거리, 방위각) 만큼 떨어진 곳(6.3). */
    private fun inspectionPoint(p: Pole): GeoPoint {
        if (params.offsetDistanceM == 0.0) return p.position
        val b = Math.toRadians(params.offsetBearingDeg)
        return LocalProjection(p.position).toGeo(Enu(params.offsetDistanceM * sin(b), params.offsetDistanceM * cos(b)))
    }

    private fun publish() {
        _ui.value = ExecutorUiState(
            state = state,
            sortieIndex = sortieIndex,
            target = target?.view(),
            visits = visits.map { it.view() },
            countdownSec = countdown,
            rthCountdownSec = rthCountdown,
            lastDetections = lastDetections,
            message = message,
            preflightFailures = preflightFailures,
        )
    }
}
