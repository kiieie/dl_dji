package com.example.myfirstapp.core.model

enum class DownloadMode { PREVIEW, ORIGINAL }

/** 촬영 방식(5.6). [OVERHEAD]는 전주 상공 1곳, [DIAGONAL4]는 전주 대각선 4곳에서 머리를 향해 찍는다. */
enum class ShotMode { OVERHEAD, DIAGONAL4 }

/**
 * 비행 파라미터(6.3). 기본값은 설계서의 기본값을 따른다.
 * 실행 시점의 값이 미션에 그대로 저장된다(4.2).
 */
data class FlightParams(
    val transitAltitudeM: Double = 50.0,
    val inspectAltitudeM: Double = 30.0,
    val speedMps: Double = 8.0,
    val offsetDistanceM: Double = 0.0,
    val offsetBearingDeg: Double = 0.0,
    val gimbalPitchDeg: Double = -90.0,
    val lenses: List<Lens> = listOf(Lens.WIDE, Lens.ZOOM),
    val zoomRatio: Double = 7.0,
    val waitSec: Int = 5,
    val batteryReservePct: Double = 30.0,
    val vlosRadiusM: Double = 2000.0,
    val downloadMode: DownloadMode = DownloadMode.ORIGINAL,
    val shotMode: ShotMode = ShotMode.DIAGONAL4,
    /** 4방향 사선(5.6): 비행 고도(이륙 지점 기준), 전주까지 수평거리, 조준 높이(지상 기준). */
    val diagonalAltitudeM: Double = 27.0,
    val diagonalDistanceM: Double = 10.0,
    val aimHeightM: Double = 17.5,
    /** 방식 A(5.6): 다른 전주의 촬영 지점 사이(또는 이륙·복귀 구간)가 이보다 길면 이동 고도로 올라갔다 내려온다. 저장하지 않는 조정값. */
    val transitLegThresholdM: Double = 60.0,
    /** 실시간 지형 추적(5.6, 2026-10-30 기본 켬): 켜면 KMZ 고도가 지면 기준(`realTimeFollowSurface`), 끄면 이륙 지점 기준. */
    val terrainFollow: Boolean = true,
    /**
     * 설비 조준 사진(5.6, 2026-10-30 사용자 결정): 4방향 사선에서 설치기기가 있는 전주는 지점마다 조준 높이에서 이만큼 아래(m)를 한 장 더 찍는다.
     * 기기가 여럿이면 가장 많이 내리는 값. 비우면 설비 사진을 찍지 않는다.
     */
    val equipmentDropM: Map<Equipment, Double> = Equipment.entries.associateWith { 2.0 },
) {
    /** 촬영 고도: 4방향 사선은 사선 고도, 전주 위는 점검 고도(5.6). */
    val shotAltitudeM: Double get() = if (shotMode == ShotMode.DIAGONAL4) diagonalAltitudeM else inspectAltitudeM

    /**
     * 전주 하나에서 받을 사진 파일 수(5.6): 촬영 지점 수(사선 4, 전주 위 1) × 렌즈 수, 사선에서 설치기기가 있으면 설비 조준 사진으로 2배.
     * [pole]을 모르면 설비 사진은 셈하지 않는다.
     */
    fun photosPerPole(pole: Pole? = null): Int {
        val points = if (shotMode == ShotMode.DIAGONAL4) 4 else 1
        val aims = if (shotMode == ShotMode.DIAGONAL4 && equipmentsOf(pole?.equipment).any { it in equipmentDropM }) 2 else 1
        return points * lenses.distinct().size.coerceAtLeast(1) * aims
    }

    /** RTH 고도 = 이동 고도 + 10m, 법정 상한 150m을 넘지 않는다(6.3). */
    val rthAltitudeM: Double get() = minOf(transitAltitudeM + RTH_MARGIN_M, MAX_ALTITUDE_M)

    /** 설정값 검증. 문제가 없으면 빈 목록을 돌려준다. */
    fun validate(): List<String> = buildList {
        if (transitAltitudeM !in 30.0..120.0) add("이동 고도는 30~120m여야 합니다.")
        if (inspectAltitudeM <= 0.0 || inspectAltitudeM > transitAltitudeM) add("점검 고도는 0m 초과, 이동 고도 이하여야 합니다.")
        if (transitAltitudeM + RTH_MARGIN_M > MAX_ALTITUDE_M) add("RTH 고도가 150m를 넘습니다.")
        if (speedMps !in 2.0..15.0) add("비행 속도는 2~15m/s여야 합니다.")
        if (offsetDistanceM < 0.0) add("오프셋 거리는 0m 이상이어야 합니다.")
        if (gimbalPitchDeg !in -90.0..0.0) add("짐벌 피치는 -90~0°여야 합니다.")
        if (lenses.isEmpty()) add("촬영 렌즈를 하나 이상 선택해야 합니다.")
        if (waitSec < 0) add("대기 시간은 0초 이상이어야 합니다.")
        if (batteryReservePct < MIN_RESERVE_PCT) add("배터리 예비율은 20% 이상이어야 합니다.")
        if (vlosRadiusM <= 0.0) add("가시권 반경은 0m보다 커야 합니다.")
        if (shotMode == ShotMode.DIAGONAL4) {
            // 지형 추적이면 지면 기준이라 경사지에서 여유가 줄지 않아 전선 위 7.5m(대각 10m 배치)까지 허용한다(5.6)
            // 지형 추적은 기체 아래 땅 기준이라 비탈 아래쪽 사선 지점에서는 전선 여유가 줄어든다. 현장 PoC 전까지 지형 추적이어도 27m(2026-10-30 사용자 결정)
            if (diagonalAltitudeM < WIRE_HEIGHT_M + WIRE_CLEARANCE_M) add("4방향 사선 고도는 전선(약 17m)보다 10m 이상 높아야 합니다(27m 이상).")
            if (diagonalAltitudeM > transitAltitudeM) add("4방향 사선 고도는 이동 고도 이하여야 합니다.")
            if (diagonalAltitudeM + RTH_MARGIN_M > MAX_ALTITUDE_M) add("4방향 사선 고도가 너무 높습니다.")
            if (diagonalDistanceM !in 3.0..50.0) add("전주까지 수평거리는 3~50m여야 합니다.")
            if (aimHeightM !in 0.0..diagonalAltitudeM) add("조준 높이는 0m 이상, 비행 고도 이하여야 합니다.")
        }
    }

    companion object {
        const val MAX_ALTITUDE_M = 150.0
        const val RTH_MARGIN_M = 10.0
        const val MIN_RESERVE_PCT = 20.0
        /** 배전 전선 높이(전주 16m + 가공 1m)와 사선 촬영 때 지킬 최소 간격(5.6). */
        const val WIRE_HEIGHT_M = 17.0
        const val WIRE_CLEARANCE_M = 10.0
    }
}
