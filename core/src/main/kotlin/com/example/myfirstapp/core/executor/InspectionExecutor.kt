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
import com.example.myfirstapp.core.port.MissionStartRejected
import com.example.myfirstapp.core.port.Notifier
import com.example.myfirstapp.core.port.Sound
import com.example.myfirstapp.core.port.StorageMonitor
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.SortiePlan
import com.example.myfirstapp.core.route.Viewpoint
import com.example.myfirstapp.core.route.Viewpoints
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
    /**
     * 6.1 방식 A: 소티 전체를 미션 하나로 날고(`startSortie`), 지점마다 찍힌 사진을 비행과 병렬로 처리한다.
     * false면 방식 B(전주마다 구간 미션, 도착 후 촬영·다운로드·분석·대기).
     */
    val sortieMission: Boolean = false,
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
    /** 앱 재시작 복구(6.4): 비정상 종료 때 진행 중이던 소티 번호. 값이 있으면 시작하자마자 기체 상태로 복귀 상태를 정한다. */
    private val recoverSortie: Int? = null,
) {
    /** 비행 파라미터. 지형 추적 시작이 거절되어 운용자가 이륙 기준을 고르면 `terrainFollow`만 끈다(5.6). */
    private var params = energy.flight

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
        data class Recover(val sortieIndex: Int) : Msg

        /** 방식 A 사진 처리 결과(다운로드 실패면 [bytes]가 null). */
        data class Photo(val captureId: Long, val bytes: ByteArray?, val detections: List<Detection>?, val error: String?) : Msg
    }

    private sealed interface StepResult {
        data class PreflightDone(val failures: List<String>, val home: GeoPoint?, val blocked: Boolean) : StepResult
        data class TakeoffDone(val ok: Boolean) : StepResult
        /** [terrainRejected]: 지형 추적 미션을 기체가 시작하지 않음(이륙 기준으로 바꿀지 묻는다). */
        data class LegReady(val ok: Boolean, val reason: String, val terrainRejected: Boolean = false) : StepResult
        data class Captured(val captures: List<CaptureInfo>) : StepResult
        data class Downloaded(val images: List<Pair<Long, ByteArray>>) : StepResult
        data class Analyzed(val detections: Map<Long, List<Detection>>, val error: String?) : StepResult
        data object WaitDone : StepResult
        data class Resumed(val ok: Boolean) : StepResult
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
    private var ignoreWarnings = false
    private var countdown: Int? = null
    private var message: String? = null
    private var preflightFailures: List<String> = emptyList()
    private var lastDetections: List<Detection> = emptyList()
    private var captured: List<Pair<Long, CaptureInfo>> = emptyList()
    private var terrainFallbackAsk = false

    private var arrivalToken = 0
    private var arrivalJob: Job? = null
    private var rthToken = 0
    private var rthJob: Job? = null
    private var rthCountdown: Int? = null

    // 방식 A: 올린 소티 미션의 지점과 그 지점의 방문, 미션이 기체에 살아 있는지, 처리 대기 사진
    private var missionPoints: List<Viewpoint> = emptyList()
    private var missionVisits: List<Visit> = emptyList()
    private var missionActive = false
    private var photosInProgress = 0
    private val photoQueue = Channel<Pair<Long, CaptureInfo>>(Channel.UNLIMITED)

    fun start() {
        scope.launch { drone.events.collect { inbox.send(Msg.Drone(it)) } }
        // 사진 처리(방식 A, RC 셔터)는 상태와 무관하게 차례로 한다. 착륙한 뒤에도 남은 사진을 끝까지 처리한다
        scope.launch { for ((id, info) in photoQueue) inbox.send(processPhoto(id, info)) }
        scope.launch { drone.telemetry.collect { inbox.send(Msg.Tele(it)) } }
        recoverSortie?.let { inbox.trySend(Msg.Recover(it)) }
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
            is Msg.Recover -> recover(m.sortieIndex)
            is Msg.Photo -> onPhotoProcessed(m)
        }
        publish()
    }

    private suspend fun onCommand(c: Command) {
        when (c) {
            Command.Start, Command.StartIgnoringWarnings -> when (state) {
                Idle, SortieDone -> {
                    ignoreWarnings = c == Command.StartIgnoringWarnings
                    startSortie()
                }
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
            Command.FlyTakeoffHeight -> if (state == Paused && terrainFallbackAsk) {
                terrainFallbackAsk = false
                params = params.copy(terrainFollow = false)
                message = "이륙 지점 기준으로 다시 올립니다."
                stopMissionBeforeLeg = true
                enter(PreparingLeg, "지형 추적 거절 → 이륙 기준")
            }
            Command.DismissTerrainFallback -> terrainFallbackAsk = false
        }
    }

    private suspend fun onDrone(e: DroneEvent) {
        when (e) {
            DroneEvent.LegCompleted -> if (state == FlyingLeg) enter(Arrived, "구간 미션 완료")
            is DroneEvent.WaypointReached -> if (missionActive) onWaypoint(e.index)
            is DroneEvent.PhotoTaken -> if (missionPoints.isNotEmpty()) onPhoto(e.index, e.capture)
            // 기체가 이미 찍었다. 자동 비행을 건드리지 않고 기록만 한다
            is DroneEvent.ShutterPhoto -> if (state.airborne || state == ReturningHome) onShutterPhoto(e.capture)
            DroneEvent.MissionFinished -> if (missionActive) {
                missionActive = false
                if (state == FlyingLeg) {
                    appInitiatedRth = false // KMZ 완료 동작(goHome)이 기체에서 이어진다
                    enter(ReturningHome, "소티 미션 완료")
                }
            }
            is DroneEvent.LegFailed -> if (state == FlyingLeg) pauseWithWarning("구간 실패: ${e.reason}")
            DroneEvent.PausedByRc -> requestPause("RC 일시정지 버튼", byRc = true)
            DroneEvent.ResumeByRc -> resume()
            DroneEvent.ObstacleAvoidance -> if (state == FlyingLeg) pauseWithWarning("장애물 회피 동작")
            DroneEvent.GpsDegraded -> if (state == FlyingLeg || state == PreparingLeg) pauseWithWarning("GPS 품질 저하")
            DroneEvent.GoHomeStarted -> if ((state.airborne || state == LinkLost) && state != ReturningHome) {
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
                // 운용자가 경고를 확인하고 시작했으면 막는 항목(연결 없음, LDM 꺼짐)만 막는다
                if (r.failures.isEmpty() || (ignoreWarnings && !r.blocked)) {
                    if (r.failures.isNotEmpty()) message = "점검 경고 ${r.failures.size}개를 확인하고 시작했습니다."
                    home = r.home
                    enter(Takeoff, "비행 전 점검 통과")
                } else {
                    message = "비행 전 점검 실패"
                    enter(Idle, "비행 전 점검 실패: ${r.failures.joinToString()}")
                }
            }
            is StepResult.TakeoffDone -> if (r.ok) enter(PreparingLeg, "이륙 완료") else pauseWithWarning("이륙 실패")
            is StepResult.LegReady -> if (r.ok) {
                if (config.sortieMission) {
                    missionActive = true
                    recorder.recordHeightMode(params.terrainFollow)
                }
                enter(FlyingLeg, if (config.sortieMission) "소티 미션 시작" else "구간 시작")
            } else {
                // 운용자가 고를 때까지 일시정지(호버). [재개]하면 지형 추적으로 한 번 더 해 본다
                terrainFallbackAsk = r.terrainRejected
                pauseWithWarning(r.reason)
            }
            is StepResult.Resumed -> if (state == Paused) {
                if (r.ok) {
                    enter(FlyingLeg, "미션 재개")
                } else {
                    // 미션이 기체에 없으면(앱 재시작 등) 남은 전주로 새 소티 미션을 올린다
                    missionActive = false
                    stopMissionBeforeLeg = true
                    enter(PreparingLeg, "재개: 남은 전주로 새 미션")
                }
            }
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
                    lastDetections.any { it.alert } -> notifier.play(Sound.ANOMALY)
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

    /** 앱 재시작 복구(6.4): 기체가 떠 있으면 일시정지로 돌아가 운용자가 [재개]하게 하고, 내려와 있으면 소티를 닫는다. */
    private suspend fun recover(index: Int) {
        if (state != Idle) return
        sortieIndex = index
        val t = drone.telemetry.value
        when {
            !t.connected -> {
                message = "앱이 다시 시작되었습니다. 기체 연결을 기다립니다."
                enter(LinkLost, "앱 재시작 복구: 연결 없음")
            }
            t.isFlying -> {
                message = "앱이 다시 시작되었습니다. 기체 위치를 확인하고 재개하세요."
                enter(Paused, "앱 재시작 복구")
            }
            else -> enter(SortieDone, "앱 재시작 복구: 착륙 상태")
        }
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
                    val pauseOnly = config.sortieMission && missionActive
                    scope.launch {
                        old?.join()
                        if (pauseOnly) drone.pauseMission() else drone.stopMission()
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
        if (config.sortieMission && missionActive) {
            // 방식 A: 기체에 남은 미션을 이어 간다(멈춘 지점으로 돌아간 뒤 계속, 6.1)
            launchStep { StepResult.Resumed(drone.resumeMission().isSuccess) }
            return
        }
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
        captured = captures.map { saveCapture(visit, it, CaptureSource.AUTO) to it }
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
        val id = saveCapture(visit, r.capture, CaptureSource.MANUAL)
        r.image?.let { recorder.saveImage(id, it) }
        visit.captureCount++
        // 운용자가 수동 촬영하면 그 결과로 완료 처리한다(6.4)
        if (visit.status != VisitStatus.DONE) setStatus(visit, VisitStatus.DONE)
        target = visit
        message = "${visit.pole.poleId} 수동 촬영 저장"
    }

    /**
     * RC 셔터 사진(6.4): 찍은 위치에서 [SHUTTER_POLE_LINK_M] 안의 가장 가까운 이번 실행의 방문(남은 전주)에 수동 사진으로 기록하고
     * 처리 줄(다운로드 → 탐지)에 넣는다. 없으면 기록하지 않고 SD에만 남긴다(앞선 비행에서 끝낸 전주는 이번 실행에 없다).
     * 전주 상태도 사진 수([Visit.captureCount])도 바꾸지 않는다: 완료 판정은 자동 사진과 일시정지 중 화면 수동 촬영만 본다.
     */
    private suspend fun onShutterPhoto(capture: CaptureInfo) {
        val visit = shutterLinkTarget(capture.position, visits) { it.pole.position }
        if (visit == null) {
            message = "셔터 사진을 연결할 전주가 없습니다(사진은 SD 카드에 있음)."
            return
        }
        val id = saveCapture(visit, capture.copy(viewpoint = null), CaptureSource.MANUAL)
        photosInProgress++
        photoQueue.send(id to capture)
        message = "${visit.pole.poleId} 셔터 사진 저장"
    }

    // ---------------------------------------------------------------- 방식 A(소티 미션)

    /** 지점 도착: 새 전주면 진행 중으로 두고, 마지막 지점까지 지나온 전주는 사진 유무로 마무리한다(4패스는 한 전주를 네 번 지난다). */
    private suspend fun onWaypoint(index: Int) {
        val v = missionVisits.getOrNull(index) ?: return
        missionVisits.take(index).distinct().filter { it !== v && missionVisits.lastIndexOf(it) < index }.forEach { settle(it) }
        if (v.status == VisitStatus.PENDING) {
            setStatus(v, VisitStatus.IN_PROGRESS)
            recorder.recordTransition(sortieIndex, state.name, Arrived.name, "지점 도착", v.pole.poleId) // VISIT.arrivedAt
            notifier.play(Sound.ARRIVED)
        }
        target = v
    }

    /** 미션 동작으로 찍힌 사진: 바로 기록하고(사진이 있으므로 그 전주는 완료 가능) 처리 줄에 넣는다. */
    private suspend fun onPhoto(index: Int, info: CaptureInfo) {
        val v = missionVisits.getOrNull(index) ?: return
        val capture = info.copy(viewpoint = missionPoints[index].index)
        val id = saveCapture(v, capture, CaptureSource.AUTO)
        v.captureCount++
        if (v.status == VisitStatus.PENDING) setStatus(v, VisitStatus.IN_PROGRESS)
        photosInProgress++
        photoQueue.send(id to capture)
    }

    /** 다운로드 → 탐지. 실패는 비행을 막지 않는다(6.5). */
    private suspend fun processPhoto(id: Long, info: CaptureInfo): Msg.Photo {
        val bytes = drone.download(info, params.downloadMode).getOrNull() ?: return Msg.Photo(id, null, null, "다운로드 실패")
        val det = detector ?: return Msg.Photo(id, bytes, null, "탐지 모델 없음")
        return try {
            Msg.Photo(id, bytes, det.detect(bytes), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Msg.Photo(id, bytes, null, e.message ?: e::class.simpleName)
        }
    }

    private suspend fun onPhotoProcessed(m: Msg.Photo) {
        photosInProgress--
        m.bytes?.let { recorder.saveImage(m.captureId, it) }
        m.detections?.let {
            recorder.saveDetections(m.captureId, detector?.modelVersion.orEmpty(), it)
            lastDetections = it
            // 비행 중에는 촬영에 집중한다: 탐지는 저장만 하고 알리지 않는다. 판독은 미션 뒤 결과 화면에서(10장, 2026-10-30 사용자 요청)
        }
        if (m.bytes == null) message = "사진 다운로드 실패 — 비행 후 원본을 받으세요."
    }

    /** 지나온 전주 마무리: 사진이 있으면 완료, 진행 중이었는데 사진이 없으면 실패(6.4 사진 없는 완료 금지). */
    private suspend fun settle(v: Visit) {
        when {
            v.status != VisitStatus.IN_PROGRESS -> Unit
            v.captureCount > 0 -> setStatus(v, VisitStatus.DONE)
            else -> setStatus(v, VisitStatus.FAILED, "사진 없음")
        }
    }

    /** 내려오거나 복귀할 때 진행 중인 전주: 사진이 있으면 완료, 없으면 다음 소티로 남긴다. */
    private suspend fun settleInProgress() {
        visits.filter { it.status == VisitStatus.IN_PROGRESS }
            .forEach { setStatus(it, if (it.captureCount > 0) VisitStatus.DONE else VisitStatus.PENDING) }
    }

    private suspend fun prepareSortie(points: List<Viewpoint>, stopFirst: Boolean): StepResult {
        if (stopFirst) drone.stopMission()
        if (!storage.hasEnoughSpace()) return StepResult.LegReady(false, "저장 공간 부족")
        repeat(config.legRetries + 1) { attempt ->
            val r = drone.startSortie(points, params)
            if (r.isSuccess) return StepResult.LegReady(true, "")
            // 올라갔는데 시작만 거절: 같은 KMZ는 다시 올려도 같다. 지형 추적이면 운용자에게 이륙 기준을 묻는다
            if (r.exceptionOrNull() is MissionStartRejected && params.terrainFollow) {
                return StepResult.LegReady(false, "지형 추적으로 시작하지 못했습니다. 이륙 지점 기준으로 다시 올리고 시작할까요?", terrainRejected = true)
            }
            if (attempt < config.legRetries) delay(config.legRetryDelayMs)
        }
        return StepResult.LegReady(false, "미션 업로드 실패(재시도 ${config.legRetries}회 초과)")
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
        recorder.recordTransition(sortieIndex, from.name, to.name, reason, target?.pole?.poleId)

        if (to !in setOf(Arrived, Capturing, Downloading, Analyzing)) {
            arrivalJob?.cancel()
            arrivalJob = null
        }
        if (to in setOf(Paused, LinkLost, ReturningHome, SortieDone, MissionDone, Idle)) cancelRthPrompt()
        if (to == Paused || to == LinkLost) pausePending = false
        if (to != Paused) terrainFallbackAsk = false

        when (to) {
            PreflightCheck -> {
                val plan = sorties[sortieIndex]
                val first = nextTarget()?.pole?.position
                launchStep {
                    val report = drone.preflight()
                    val checks = PreflightEvaluator.checklist(report, params, plan.estBatteryPct, first)
                    StepResult.PreflightDone(checks.filterNot { it.ok }.map { it.problem }, report.home, PreflightEvaluator.blocked(checks))
                }
            }
            Takeoff -> launchStep {
                StepResult.TakeoffDone(drone.telemetry.value.isFlying || drone.takeoff().isSuccess)
            }
            PreparingLeg -> if (config.sortieMission) {
                // 방식 A: 이 소티의 남은 전주 전체를 촬영 지점으로 펼쳐 미션 하나로 올린다
                val remaining = visits.filter {
                    it.sortieIndex == sortieIndex && (it.status == VisitStatus.PENDING || it.status == VisitStatus.IN_PROGRESS)
                }
                if (remaining.isEmpty()) return startRth("남은 전주 없음")
                val start = drone.telemetry.value.position ?: homePoint() ?: remaining.first().pole.position
                val byPole = remaining.associateBy { it.pole }
                missionPoints = Viewpoints.expand(remaining.map { it.pole }, params, start)
                missionVisits = missionPoints.map { byPole.getValue(it.pole) }
                target = remaining.first()
                val stopFirst = stopMissionBeforeLeg
                stopMissionBeforeLeg = false
                val points = missionPoints
                launchStep { prepareSortie(points, stopFirst) }
            } else {
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
                if (config.sortieMission) {
                    if (appInitiatedRth) missionActive = false
                    settleInProgress()
                }
                target?.let { if (it.status == VisitStatus.IN_PROGRESS) setStatus(it, VisitStatus.PENDING) }
                if (appInitiatedRth) launchStep {
                    drone.stopMission()
                    StepResult.RthCommanded(drone.returnHome().isSuccess)
                }
            }
            SortieDone -> {
                missionActive = false
                settleInProgress()
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

    private suspend fun saveCapture(v: Visit, info: CaptureInfo, source: CaptureSource): Long =
        recorder.saveCapture(v.sortieIndex, v.order, info, source)

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
            photosInProgress = photosInProgress,
            terrainFallbackAsk = terrainFallbackAsk,
        )
    }
}
