package com.example.myfirstapp.core.port

import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Telemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 기체 제어 포트. DJI SDK 호출은 이 인터페이스의 구현(`DjiDroneGateway`) 안에만 둔다.
 * 실패는 [Result]로 돌려주며 예외를 던지지 않는다.
 */
interface DroneGateway {
    val telemetry: StateFlow<Telemetry>

    /** 텔레메트리에서 해석한 기체 이벤트. 연결 끊김 판정(3초)도 어댑터가 한다(6.4). */
    val events: Flow<DroneEvent>

    suspend fun preflight(): PreflightReport

    /** 이륙 후 안전 고도에 도달하면 돌아온다. */
    suspend fun takeoff(): Result<Unit>

    /** 구간 미션(6.1 방식 B)을 생성·업로드·시작한다. 완료는 [DroneEvent.LegCompleted]로 알린다. */
    suspend fun startLeg(leg: LegMission): Result<Unit>

    /** 진행 중이거나 일시정지된 미션을 종료한다. 미션이 없으면 성공으로 본다. */
    suspend fun stopMission(): Result<Unit>

    suspend fun capture(lens: Lens, gimbalPitchDeg: Double, zoomRatio: Double): Result<CaptureInfo>

    suspend fun download(capture: CaptureInfo, mode: DownloadMode): Result<ByteArray>

    /** 카메라 SD에서 [sinceMs] 이후 저장된 사진 목록. 앱 재시작 복구의 SD 대조(6.4)에 쓴다. */
    suspend fun listMedia(sinceMs: Long): Result<List<CaptureInfo>>

    suspend fun returnHome(): Result<Unit>
}

/** 구간 미션: 현재 위치에서 이동 고도로 목표 상공까지 간 뒤 점검 고도로 내려간다(6.2). */
data class LegMission(
    val target: GeoPoint,
    val transitAltitudeM: Double,
    val inspectAltitudeM: Double,
    val speedMps: Double,
    val rthAltitudeM: Double,
)

/** 카메라 SD에 저장된 사진 정보. [mediaId]로 비행 후 원본을 다시 찾는다. */
data class CaptureInfo(
    val mediaId: String,
    val lens: Lens,
    val position: GeoPoint?,
    val altitudeM: Double,
    val gimbalPitchDeg: Double,
    val headingDeg: Double,
    val takenAtMs: Long,
)

sealed interface DroneEvent {
    data object LegCompleted : DroneEvent
    data class LegFailed(val reason: String) : DroneEvent

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
