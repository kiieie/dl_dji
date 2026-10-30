package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.LocalProjection

/** 비행 전 점검 항목 하나(Pilot 2 비행 전 체크리스트처럼 값과 통과 여부를 함께 보여 준다). [problem]은 실패 때의 사유다. */
data class PreflightCheck(val label: String, val value: String, val ok: Boolean, val problem: String)

/** 비행 전 점검 판정(6.7). */
object PreflightEvaluator {
    const val MIN_GPS_SATELLITES = 12
    const val MIN_GPS_LEVEL = 4
    const val MIN_SD_FREE_MB = 1024L
    /** 저온에서는 배터리를 예열해야 한다. 초기값이며 필드 테스트로 조정한다. */
    val BATTERY_TEMP_RANGE = 15.0..50.0

    /** 모든 항목. 기체가 연결되지 않았으면 연결 항목 하나만 돌려준다. */
    fun checklist(
        report: PreflightReport,
        params: FlightParams,
        sortieEstBatteryPct: Double,
        firstPole: GeoPoint?,
    ): List<PreflightCheck> {
        if (!report.connected) return listOf(PreflightCheck("기체 연결", "연결 안 됨", false, "기체가 연결되지 않았습니다."))
        val need = sortieEstBatteryPct + params.batteryReservePct
        val firstDist = if (report.home != null && firstPole != null) LocalProjection.distanceM(report.home, firstPole) else null
        return listOf(
            PreflightCheck("기체 연결", "연결됨", true, ""),
            PreflightCheck(
                "GNSS", "위성 ${report.gpsSatellites}개 · 등급 ${report.gpsLevel}",
                report.gpsSatellites >= MIN_GPS_SATELLITES && report.gpsLevel >= MIN_GPS_LEVEL,
                "GPS 신호가 약합니다(위성 ${report.gpsSatellites}개, 등급 ${report.gpsLevel}).",
            ),
            PreflightCheck("홈 포인트", if (report.homeRecorded && report.home != null) "기록됨" else "없음", report.homeRecorded && report.home != null, "홈 포인트가 기록되지 않았습니다."),
            PreflightCheck(
                "배터리", "${report.batteryPct.toInt()}% (필요 ${need.toInt()}%)", report.batteryPct >= need,
                "배터리가 부족합니다(현재 ${report.batteryPct.toInt()}%, 필요 ${need.toInt()}%).",
            ),
            PreflightCheck("배터리 온도", "${report.batteryTempC}℃", report.batteryTempC in BATTERY_TEMP_RANGE, "배터리 온도가 범위를 벗어났습니다(${report.batteryTempC}℃)."),
            PreflightCheck("SD 카드", "${report.sdFreeMb / 1024}GB 남음", report.sdFreeMb >= MIN_SD_FREE_MB, "SD 카드 여유 공간이 부족합니다(${report.sdFreeMb}MB)."),
            PreflightCheck("LDM", if (report.ldmEnabled) "켜짐" else "꺼짐", report.ldmEnabled, "LDM(로컬 데이터 모드)이 꺼져 있습니다."),
            PreflightCheck("장애물 감지", if (report.obstacleAvoidanceOn) "켜짐" else "꺼짐", report.obstacleAvoidanceOn, "장애물 회피가 꺼져 있습니다."),
            PreflightCheck("RTH 고도", "${report.rthAltitudeM.toInt()}m", report.rthAltitudeM <= FlightParams.MAX_ALTITUDE_M, "RTH 고도가 150m를 넘습니다(${report.rthAltitudeM}m)."),
            PreflightCheck("신호 상실 동작", if (report.lostLinkActionIsRth) "RTH" else "RTH 아님", report.lostLinkActionIsRth, "통신 상실 동작이 RTH가 아닙니다."),
            PreflightCheck(
                "첫 전주 거리", firstDist?.let { "${it.toInt()}m" } ?: "--",
                firstDist == null || firstDist <= params.vlosRadiusM, "첫 전주가 가시권 반경 밖입니다(${firstDist?.toInt()}m, 반경 ${params.vlosRadiusM.toInt()}m).",
            ),
        )
    }

    /** 실패 사유 목록. 비어 있으면 통과다. */
    fun evaluate(report: PreflightReport, params: FlightParams, sortieEstBatteryPct: Double, firstPole: GeoPoint?): List<String> =
        checklist(report, params, sortieEstBatteryPct, firstPole).filterNot { it.ok }.map { it.problem }
}
