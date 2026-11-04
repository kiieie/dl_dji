package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.LocalProjection
import kotlin.math.ceil

/**
 * 비행 전 점검 항목 하나(Pilot 2 비행 전 체크리스트처럼 값과 통과 여부를 함께 보여 준다). [problem]은 실패 때의 사유다.
 * [blocking]이면 실패할 때 운용자가 경고를 확인해도 시작할 수 없다(기체 연결, LDM — 데이터 주권 7.2, 2026-10-30 사용자 결정).
 */
data class PreflightCheck(val label: String, val value: String, val ok: Boolean, val problem: String, val blocking: Boolean = false)

/** 비행 전 점검 판정(6.7). */
/** SD 남은 용량 글(Pilot 2 "211.4G", 10.5.5): 모든 화면(비행 전 점검·체크리스트·홈·기체 점검)이 같은 꼴을 쓴다. */
fun sdFreeLabel(mb: Long): String = String.format(java.util.Locale.ROOT, "%.1fG", mb / 1024.0)

object PreflightEvaluator {
    /** 배터리 항목 이름. 비행 전 점검 화면이 이 줄을 한 줄 전체로 크게 보인다. */
    const val BATTERY = "배터리"

    /** 첫 전주 거리 항목 이름(미션이 없는 수동 비행에는 빠진다). */
    const val FIRST_POLE = "첫 전주 거리"

    /** 모든 항목. 기체가 연결되지 않았으면 연결 항목 하나만 돌려준다. */
    fun checklist(
        report: PreflightReport,
        params: FlightParams,
        sortieEstBatteryPct: Double,
        firstPole: GeoPoint?,
    ): List<PreflightCheck> {
        if (!report.connected) return listOf(PreflightCheck("기체 연결", "연결 안 됨", false, "기체가 연결되지 않았습니다.", blocking = true))
        // 업로드 전 배터리 판단(5.5): 남은 전주로 다시 센 소티 예상 사용량(올림) + 예비율
        val use = ceil(sortieEstBatteryPct).toInt()
        val reserve = params.batteryReservePct.toInt()
        val now = report.batteryPct.toInt()
        val firstDist = if (report.home != null && firstPole != null) LocalProjection.distanceM(report.home, firstPole) else null
        return listOf(
            PreflightCheck("기체 연결", "연결됨", true, ""),
            PreflightCheck(
                "GNSS", "위성 ${report.gpsSatellites}개 · 등급 ${report.gpsLevel}",
                report.gpsSatellites >= Tuning.Preflight.MIN_GPS_SATELLITES && report.gpsLevel >= Tuning.Preflight.MIN_GPS_LEVEL,
                "GPS 신호가 약합니다(위성 ${report.gpsSatellites}개, 등급 ${report.gpsLevel}).",
            ),
            PreflightCheck("홈포인트", if (report.homeRecorded && report.home != null) "기록됨" else "미설정", report.homeRecorded && report.home != null, "홈포인트가 기록되지 않았습니다."),
            PreflightCheck(
                BATTERY, "지금 $now% · 예상 사용 $use% (+예비 $reserve%)", report.batteryPct >= use + params.batteryReservePct,
                "배터리가 부족합니다(지금 $now%, 예상 사용 $use% + 예비 $reserve% = ${use + reserve}%).",
            ),
            PreflightCheck("배터리 온도", "${report.batteryTempC}℃", report.batteryTempC in Tuning.Preflight.BATTERY_TEMP_RANGE, "배터리 온도가 범위를 벗어났습니다(${report.batteryTempC}℃)."),
            PreflightCheck("SD 카드", sdFreeLabel(report.sdFreeMb), report.sdFreeMb >= Tuning.Preflight.MIN_SD_FREE_MB, "SD 카드 여유 공간이 부족합니다(${report.sdFreeMb}MB)."),
            PreflightCheck(
                "LDM", if (report.ldmEnabled) "켜짐" else "꺼짐 · 시작 불가", report.ldmEnabled,
                "LDM(로컬 데이터 모드)이 꺼져 있어 비행을 시작할 수 없습니다. 조종기 Wi-Fi·SIM을 끄고 앱을 다시 시작하세요.", blocking = true,
            ),
            PreflightCheck("장애물 감지", if (report.obstacleAvoidanceOn) "켜짐" else "꺼짐", report.obstacleAvoidanceOn, "장애물 회피가 꺼져 있습니다."),
            PreflightCheck("RTH 고도", "${report.rthAltitudeM.toInt()}m", report.rthAltitudeM <= Tuning.Safety.MAX_ALTITUDE_M, "RTH 고도가 150m를 넘습니다(${report.rthAltitudeM}m)."),
            PreflightCheck("신호 끊김 조치", if (report.lostLinkActionIsRth) "RTH" else "RTH 아님", report.lostLinkActionIsRth, "신호 끊김 조치가 RTH가 아닙니다."),
            PreflightCheck(
                FIRST_POLE, firstDist?.let { "${it.toInt()}m" } ?: "--",
                firstDist == null || firstDist <= params.vlosRadiusM, "첫 전주가 가시권 반경 밖입니다(${firstDist?.toInt()}m, 반경 ${params.vlosRadiusM.toInt()}m).",
            ),
        )
    }

    /** 수동 비행(미션 없음, 10.5.5 [카메라 뷰 입력])의 항목: 소티 배터리 예상·첫 전주 거리를 뺀다. */
    fun manual(report: PreflightReport): List<PreflightCheck> =
        checklist(report, FlightParams(), 0.0, null).filterNot { it.label == BATTERY || it.label == FIRST_POLE }

    /** 경고를 확인해도 시작할 수 없는 실패가 있는지. */
    fun blocked(checks: List<PreflightCheck>) = checks.any { !it.ok && it.blocking }

    /** 실패 사유 목록. 비어 있으면 통과다. */
    fun evaluate(report: PreflightReport, params: FlightParams, sortieEstBatteryPct: Double, firstPole: GeoPoint?): List<String> =
        checklist(report, params, sortieEstBatteryPct, firstPole).filterNot { it.ok }.map { it.problem }
}
