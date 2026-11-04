package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.route.nearestWithin

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

    /**
     * RC 셔터(6.4, 2026-11-04 앱이 찍음): 일시정지·수동 조종 중이면 앱이 [lens]로 찍어 20m 안의 가장 가까운 남은 전주에 수동 사진으로 붙인다.
     * 미션 비행 중(자동 촬영 중)·땅에서는 받지 않는다.
     */
    data class RcShutter(val lens: Lens) : Command

    /** SortieDone에서 남은 전주를 포기하고 미션 종료. */
    data object EndMission : Command

    /** 지형 추적 시작 거절 확인 창(5.6): [이륙 기준으로 시작] — 이륙 지점 기준 KMZ로 다시 올리고 시작한다. */
    data object FlyTakeoffHeight : Command

    /** 지형 추적 시작 거절 확인 창의 [취소]: 일시정지로 남는다(수동 조종·RTH). */
    data object DismissTerrainFallback : Command
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
    /** 방식 A: 받기·저장을 기다리는 사진 수. */
    val photosInProgress: Int = 0,
    /** 지형 추적 미션 시작이 거절되어 이륙 기준으로 바꿀지 묻는 중(5.6, 2026-10-30 사용자 결정). */
    val terrainFallbackAsk: Boolean = false,
) {
    /** 남은 전주(미점검·진행 중) 수. 남긴 채 미션을 끝내면 부분 완료다. */
    val remaining: Int get() = visits.count { it.status == VisitStatus.PENDING || it.status == VisitStatus.IN_PROGRESS }
}

/**
 * 앱 재시작 복구의 SD 대조(6.4): 목표 전주에 도착한 뒤 SD에 저장되었지만 DB에 없는 사진.
 * 도착 기록이 없으면 그 전주에서 찍은 사진도 없다.
 * ponytail: 기체 사진 시각과 조종기 시각을 같은 시계로 본다. 차이가 크면 PoC에서 허용 오차를 둔다.
 */
fun recoveredCaptures(arrivedAtMs: Long?, sdMedia: List<CaptureInfo>, dbMediaIds: Set<String>): List<CaptureInfo> =
    if (arrivedAtMs == null) emptyList() else sdMedia.filter { it.takenAtMs >= arrivedAtMs && it.mediaId !in dbMediaIds }

/** 셔터 사진을 붙일 전주: 찍은 위치에서 [Tuning.Photo.SHUTTER_POLE_LINK_M] 안의 가장 가까운 후보. 위치가 없거나 20m 안에 없으면 null(SD에만 남김). */
fun <T> shutterLinkTarget(position: GeoPoint?, candidates: List<T>, pos: (T) -> GeoPoint): T? =
    position?.let { nearestWithin(it, candidates, Tuning.Photo.SHUTTER_POLE_LINK_M, pos) }
