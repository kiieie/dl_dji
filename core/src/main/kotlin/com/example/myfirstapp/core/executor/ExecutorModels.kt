package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.CaptureInfo

/** 실행 상태(6.4 상태도). */
enum class ExecState {
    Idle, PreflightCheck, Takeoff, PreparingLeg, FlyingLeg, Arrived, Capturing, Downloading, Analyzing, Waiting,
    Paused, LinkLost, ReturningHome, SortieDone, MissionDone;

    /** 기체가 공중에 있을 수 있는 상태. 연결 끊김·기체 주도 RTH를 이 상태들에서 받는다. */
    val airborne: Boolean
        get() = this in setOf(Takeoff, PreparingLeg, FlyingLeg, Arrived, Capturing, Downloading, Analyzing, Waiting, Paused)
}

/** 운용자 명령. 하드웨어 버튼 입력은 `DroneEvent`로 들어온다. */
sealed interface Command {
    /** 비행 시작(Idle) 또는 배터리 교체 후 다음 소티(SortieDone). */
    data object Start : Command

    /** 비행 전 점검 경고를 운용자가 확인하고 그대로 시작(Pilot 2처럼 판단은 조종사, 2026-10-30 사용자 요청). 기체 연결은 여전히 필요하다. */
    data object StartIgnoringWarnings : Command

    /** 화면 [일시정지] 버튼(보조 수단). */
    data object Pause : Command

    /** 화면 [재개] 버튼(보조 수단). */
    data object Resume : Command

    /** RTH 버튼 또는 RTH 권고 확인. */
    data object ReturnHome : Command

    /** 일시정지 중: 현재 목표 전주를 건너뜀. */
    data class Skip(val reason: String = "운용자 건너뛰기") : Command

    /** 일시정지 중: 현재 목표 전주 완료 처리(사진이 있을 때만). */
    data object MarkDone : Command

    /** 일시정지 중: 수동 촬영(F-12). */
    data object ManualCapture : Command

    /** RC 셔터 버튼을 [atMs]에 누름(기체가 이미 찍음). 떠 있는 동안 SD에서 그 사진을 찾아 가장 가까운 전주에 수동 사진으로 붙인다(6.4 RC 셔터). */
    data class HardwareShutter(val atMs: Long) : Command

    /** SortieDone에서 남은 전주를 포기하고 미션 종료. */
    data object EndMission : Command
}

data class VisitView(val sortieIndex: Int, val order: Int, val pole: Pole, val status: VisitStatus, val captureCount: Int)

data class ExecutorUiState(
    val state: ExecState = ExecState.Idle,
    val sortieIndex: Int = 0,
    val target: VisitView? = null,
    val visits: List<VisitView> = emptyList(),
    /** 대기 카운트다운(초). Waiting에서만 값이 있다. */
    val countdownSec: Int? = null,
    /** RTH 권고 후 자동 RTH까지 남은 초. */
    val rthCountdownSec: Int? = null,
    val lastDetections: List<Detection> = emptyList(),
    /** 운용자에게 보여 줄 마지막 안내·경고. */
    val message: String? = null,
    val preflightFailures: List<String> = emptyList(),
    /** 방식 A: 다운로드·탐지를 기다리는 사진 수. */
    val photosInProgress: Int = 0,
)

/**
 * 앱 재시작 복구의 SD 대조(6.4): 목표 전주에 도착한 뒤 SD에 저장되었지만 DB에 없는 사진.
 * 도착 기록이 없으면 그 전주에서 찍은 사진도 없다.
 * ponytail: 기체 사진 시각과 조종기 시각을 같은 시계로 본다. 차이가 크면 PoC에서 허용 오차를 둔다.
 */
fun recoveredCaptures(arrivedAtMs: Long?, sdMedia: List<CaptureInfo>, dbMediaIds: Set<String>): List<CaptureInfo> =
    if (arrivedAtMs == null) emptyList() else sdMedia.filter { it.takenAtMs >= arrivedAtMs && it.mediaId !in dbMediaIds }

/** RC 셔터 사진을 찾을 때 누른 시각보다 이만큼 앞선 SD 사진까지 본다(SD 시각은 초 단위로 잘리고 기체·조종기 시계가 조금 다르다). */
const val SHUTTER_SKEW_MS = 3_000L

/** 셔터 사진(미션 중 RC 셔터, 수동 비행 셔터)을 전주에 붙이는 최대 거리(6.4, 2026-10-30 사용자 결정 50m → 20m). */
const val SHUTTER_POLE_LINK_M = 20.0

/** RC 셔터를 누른 뒤 사진이 SD 목록에 오를 때까지 기다리는 시간. */
const val SHUTTER_SETTLE_MS = 2_000L

/**
 * RC 셔터 사진(6.4): 누른 시각 [SHUTTER_SKEW_MS] 전부터 SD에 저장되었고 아직 기록하지 않은 사진 중 가장 새 것 한 장.
 * ponytail: 누른 무렵 찍힌 자동 사진이 아직 기록되기 전이면 그 사진을 고를 수 있다. 셔터 사진을 정확히 집으려면 SD 사진 번호를 누르기 전후로 비교한다.
 */
fun shutterPhoto(sdMedia: List<CaptureInfo>, pressedAtMs: Long, knownIds: Set<String>): CaptureInfo? =
    sdMedia.filter { it.takenAtMs >= pressedAtMs - SHUTTER_SKEW_MS && it.mediaId !in knownIds }
        .maxWithOrNull(compareBy<CaptureInfo>({ it.takenAtMs }, { it.mediaId.toLongOrNull() }))

/** SD 목록의 사진에는 위치가 없으므로 셔터를 누를 때의 기체 위치·자세를 넣는다. 지점 번호는 없다(수동 사진). */
fun CaptureInfo.takenAt(t: Telemetry) =
    copy(position = t.position, altitudeM = t.altitudeM, gimbalPitchDeg = t.gimbalPitchDeg, headingDeg = t.headingDeg, viewpoint = null)
