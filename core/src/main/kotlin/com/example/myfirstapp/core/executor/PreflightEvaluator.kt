package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.LocalProjection

/** 비행 전 점검 판정(6.7). 실패 사유 목록을 돌려주며, 비어 있으면 통과다. */
object PreflightEvaluator {
    const val MIN_GPS_SATELLITES = 12
    const val MIN_GPS_LEVEL = 4
    const val MIN_SD_FREE_MB = 1024L
    /** 저온에서는 배터리를 예열해야 한다. 초기값이며 필드 테스트로 조정한다. */
    val BATTERY_TEMP_RANGE = 15.0..50.0

    fun evaluate(
        report: PreflightReport,
        params: FlightParams,
        sortieEstBatteryPct: Double,
        firstPole: GeoPoint?,
    ): List<String> = buildList {
        if (!report.connected) {
            add("기체가 연결되지 않았습니다.")
            return@buildList
        }
        if (report.gpsSatellites < MIN_GPS_SATELLITES || report.gpsLevel < MIN_GPS_LEVEL) {
            add("GPS 신호가 약합니다(위성 ${report.gpsSatellites}개, 등급 ${report.gpsLevel}).")
        }
        if (!report.homeRecorded || report.home == null) add("홈 포인트가 기록되지 않았습니다.")
        if (report.batteryPct < sortieEstBatteryPct + params.batteryReservePct) {
            add("배터리가 부족합니다(현재 ${report.batteryPct.toInt()}%, 필요 ${(sortieEstBatteryPct + params.batteryReservePct).toInt()}%).")
        }
        if (report.batteryTempC !in BATTERY_TEMP_RANGE) add("배터리 온도가 범위를 벗어났습니다(${report.batteryTempC}℃).")
        if (report.sdFreeMb < MIN_SD_FREE_MB) add("SD 카드 여유 공간이 부족합니다(${report.sdFreeMb}MB).")
        if (!report.ldmEnabled) add("LDM(로컬 데이터 모드)이 꺼져 있습니다.")
        if (!report.obstacleAvoidanceOn) add("장애물 회피가 꺼져 있습니다.")
        if (report.rthAltitudeM > FlightParams.MAX_ALTITUDE_M) add("RTH 고도가 150m를 넘습니다(${report.rthAltitudeM}m).")
        if (!report.lostLinkActionIsRth) add("통신 상실 동작이 RTH가 아닙니다.")
        if (report.home != null && firstPole != null) {
            val d = LocalProjection.distanceM(report.home, firstPole)
            if (d > params.vlosRadiusM) add("첫 전주가 가시권 반경 밖입니다(${d.toInt()}m).")
        }
    }
}
