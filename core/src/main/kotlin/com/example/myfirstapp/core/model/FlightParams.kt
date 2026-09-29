package com.example.myfirstapp.core.model

enum class DownloadMode { PREVIEW, ORIGINAL }

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
    val vlosRadiusM: Double = 500.0,
    val downloadMode: DownloadMode = DownloadMode.PREVIEW,
) {
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
    }

    companion object {
        const val MAX_ALTITUDE_M = 150.0
        const val RTH_MARGIN_M = 10.0
        const val MIN_RESERVE_PCT = 20.0
    }
}
