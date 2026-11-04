package com.example.myfirstapp.core.port

import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Obstacles
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.route.Viewpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 기체 제어 포트. DJI SDK 호출은 이 인터페이스의 구현(`DjiDroneGateway`) 안에만 둔다.
 * 실패는 [Result]로 돌려주며 예외를 던지지 않는다.
 */
interface DroneGateway {
    val telemetry: StateFlow<Telemetry>

    /** 텔레메트리에서 해석한 기체 이벤트. 연결 끊김 판정(3초)도 어댑터가 한다(6.4). */
    val events: Flow<DroneEvent>

    /** 방향별 장애물 거리(충돌 경고, 13번). 값을 모르면 null. 기본은 빈 흐름(장애물 감지 없음). */
    val obstacles: Flow<Obstacles?> get() = emptyFlow()

    suspend fun preflight(): PreflightReport

    /** 이륙 후 안전 고도에 도달하면 돌아온다. */
    suspend fun takeoff(): Result<Unit>

    /** 구간 미션(6.1 방식 B)을 생성·업로드·시작한다. 완료는 [DroneEvent.LegCompleted]로 알린다. */
    suspend fun startLeg(leg: LegMission): Result<Unit>

    /**
     * 방식 A(6.1): 소티의 촬영 지점 전체를 미션 하나로 만들어 올리고 시작한다. 지점마다 기체가 짐벌을 맞추고 찍는다.
     * 진행은 [DroneEvent.WaypointReached], [DroneEvent.PhotoTaken], [DroneEvent.MissionFinished]로 알린다.
     */
    suspend fun startSortie(points: List<Viewpoint>, params: FlightParams): Result<Unit> =
        Result.failure(UnsupportedOperationException("소티 미션 미지원"))

    /** 미션을 멈추고 제자리 호버(화면 일시정지). */
    suspend fun pauseMission(): Result<Unit> = stopMission()

    /** 멈춘 미션을 이어 간다. 기체는 멈춘 지점으로 돌아간 뒤 계속한다(PoC ⑥). */
    suspend fun resumeMission(): Result<Unit> = Result.failure(UnsupportedOperationException("미션 재개 미지원"))

    /** 진행 중이거나 일시정지된 미션을 종료한다. 미션이 없으면 성공으로 본다. */
    suspend fun stopMission(): Result<Unit>

    suspend fun capture(lens: Lens, gimbalPitchDeg: Double, zoomRatio: Double): Result<CaptureInfo>

    suspend fun download(capture: CaptureInfo, mode: DownloadMode): Result<ByteArray>

    /** 카메라 SD에서 [sinceMs] 이후 저장된 사진 목록. 앱 재시작 복구의 SD 대조(6.4)에 쓴다. */
    suspend fun listMedia(sinceMs: Long): Result<List<CaptureInfo>>

    suspend fun returnHome(): Result<Unit>

    /** 착륙 보호 확인: [Telemetry.landingConfirmNeeded]일 때 마저 내려앉는다. */
    suspend fun confirmLanding(): Result<Unit> = Result.failure(UnsupportedOperationException("착륙 확인 미지원"))

    /** FPV 영상 렌즈(광각/망원) 바꾸기(Pilot 2 카메라 뷰 전환). */
    suspend fun setVideoLens(lens: Lens): Result<Unit> = Result.failure(UnsupportedOperationException("렌즈 전환 미지원"))

    /** 짐벌 중앙 ↔ 아래 전환(Pilot 2 짐벌 모드, RC C2). */
    suspend fun toggleGimbal(): Result<Unit> = Result.failure(UnsupportedOperationException("짐벌 전환 미지원"))

    /** 망원 렌즈 줌 배율(10.4.3 줌 +/−). 기체가 받는 범위([zoomRange]) 밖이면 범위 안으로 맞춘다. */
    suspend fun setZoom(ratio: Double): Result<Unit> = Result.failure(UnsupportedOperationException("줌 미지원"))

    /** 망원 렌즈가 받는 줌 배율 범위. 모르면 null(그때는 [ZOOM_STEPS] 그대로). */
    suspend fun zoomRange(): ClosedFloatingPointRange<Double>? = null

    /** 기체가 알려 주는 지금 줌 배율(명령 뒤 다시 읽어 화면에 실제 값을 보인다). 모르면 null. */
    suspend fun zoomRatio(): Double? = null

    /** 기체가 알려 주는 지금 FPV 렌즈(렌즈 전환 확인). 모르면 null. */
    suspend fun videoLens(): Lens? = null

    /** 카메라 사진 ↔ 영상 모드(10.4.3 사진/영상). */
    suspend fun setVideoMode(on: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException("영상 모드 미지원"))

    /** 녹화 시작·정지. 영상은 SD에만 남는다(받지 않음). */
    suspend fun setRecording(on: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException("녹화 미지원"))
}

/** 미션은 올렸지만 기체가 시작을 거절함([DroneGateway.startSortie]). 다시 올려도 같으므로 재시도하지 않는다(지형 추적 KMZ, 2026-10-30 시뮬레이터). */
class MissionStartRejected(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 줌 단계(10.4.3). */
val ZOOM_STEPS = listOf(1.0, 2.0, 4.0, 7.0, 14.0, 28.0)

/** 기체 줌 범위 안의 단계: 범위 밖 단계는 범위 끝으로 모은다. */
fun zoomSteps(range: ClosedFloatingPointRange<Double>?): List<Double> = if (range == null) ZOOM_STEPS else ZOOM_STEPS.map { it.coerceIn(range) }.distinct()

/** [current]에서 한 단계 위([up]) 또는 아래. 단계 사이 값이면 가장 가까운 다음 단계로 간다. */
fun nextZoom(current: Double, up: Boolean, steps: List<Double> = ZOOM_STEPS): Double =
    if (up) steps.firstOrNull { it > current + 1e-6 } ?: steps.last()
    else steps.lastOrNull { it < current - 1e-6 } ?: steps.first()

/** 구간 미션: 현재 위치에서 이동 고도로 목표 상공까지 간 뒤 점검 고도로 내려간다(6.2). */
data class LegMission(
    val target: GeoPoint,
    val transitAltitudeM: Double,
    val inspectAltitudeM: Double,
    val speedMps: Double,
    val rthAltitudeM: Double,
)

/** 카메라 SD에 저장된 사진 정보. [mediaId]로 비행 후 원본을 다시 찾는다. [viewpoint]는 촬영 지점 번호(5.6, `CAPTURE.viewpoint`). */
data class CaptureInfo(
    val mediaId: String,
    val lens: Lens,
    val position: GeoPoint?,
    val altitudeM: Double,
    val gimbalPitchDeg: Double,
    val headingDeg: Double,
    val takenAtMs: Long,
    val viewpoint: Int? = null,
    /** 설비 조준 사진(5.6): 설치기기 쪽으로 짐벌을 내려 한 번 더 찍은 것. */
    val equipment: Boolean = false,
)

sealed interface DroneEvent {
    data object LegCompleted : DroneEvent
    data class LegFailed(val reason: String) : DroneEvent

    /** 방식 A: 소티 미션의 [index]번 지점에 도착(지점 번호는 [DroneGateway.startSortie]에 넘긴 순서). */
    data class WaypointReached(val index: Int) : DroneEvent

    /** 방식 A: [index]번 지점에서 미션 동작으로 사진이 찍힘. */
    data class PhotoTaken(val index: Int, val capture: CaptureInfo) : DroneEvent

    /** 방식 A: 마지막 지점을 지나 미션이 끝남(완료 동작 RTH가 이어짐). */
    data object MissionFinished : DroneEvent

    /** RC 하드웨어 일시정지 버튼으로 미션이 일시정지됨(비행 모드로 감지). */
    data object PausedByRc : DroneEvent

    /** 하드웨어 재개 입력(C1/C2 매핑 또는 재누름, 방식은 PoC ⑥에서 확정). */
    data object ResumeByRc : DroneEvent

    data object ObstacleAvoidance : DroneEvent
    data object GpsDegraded : DroneEvent

    /** 기체 주도 RTH(저전압, 통신 상실, RC RTH 버튼). */
    data object GoHomeStarted : DroneEvent
    data object Landed : DroneEvent
    data object ConnectionLost : DroneEvent
    data object ConnectionRestored : DroneEvent
}

/** 비행 전 점검에 쓰는 기체 측 값(6.7). 판정은 도메인의 `PreflightEvaluator`가 한다. */
data class PreflightReport(
    val connected: Boolean,
    val gpsSatellites: Int,
    val gpsLevel: Int,
    val homeRecorded: Boolean,
    val batteryPct: Double,
    val batteryTempC: Double,
    val sdFreeMb: Long,
    val ldmEnabled: Boolean,
    val obstacleAvoidanceOn: Boolean,
    val rthAltitudeM: Double,
    val lostLinkActionIsRth: Boolean,
    val home: GeoPoint?,
)
