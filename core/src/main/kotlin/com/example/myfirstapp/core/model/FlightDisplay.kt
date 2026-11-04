package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Viewpoints
import kotlin.math.cos
import kotlin.math.sin

// Pilot 2 카메라 뷰 표시 규칙(10.5.1~10.5.3). 화면은 그리기만 하고 고르는 것은 여기서 한다(단위 테스트 `FlightDisplayTest`).

/** 상단 바 가운데 비행 상태 글(10.5.1). [warn]이면 주황(비전 포지셔닝·연결 안 됨). */
data class FlightTitle(val text: String, val warn: Boolean = false)

/**
 * Pilot 2 문구: "N 모드 - 대기" / "N 모드 - 비전 포지셔닝" / "N 모드 - 수동 비행" / "비행 경로 수행 중" / "RTH 진행 중" / "착륙".
 * N은 RC 비행 모드 스위치 글자. [suffix](미션 번호 등)는 " · " 뒤에 붙인다.
 */
fun flightTitle(t: Telemetry?, suffix: String? = null): FlightTitle {
    val base = when {
        t == null || !t.connected -> FlightTitle("기체 연결 안 됨", warn = true)
        t.flightMode == FlightMode.MISSION -> FlightTitle("비행 경로 수행 중")
        t.flightMode == FlightMode.GO_HOME -> FlightTitle("RTH 진행 중")
        t.flightMode == FlightMode.LANDING -> FlightTitle("착륙")
        t.flightMode == FlightMode.TAKING_OFF -> FlightTitle("이륙")
        else -> {
            val mode = "${t.rcMode ?: 'N'} 모드"
            when {
                t.visionPositioning -> FlightTitle("$mode - 비전 포지셔닝", warn = true)
                t.isFlying -> FlightTitle("$mode - 수동 비행")
                else -> FlightTitle("$mode - 대기")
            }
        }
    }
    return if (suffix.isNullOrBlank()) base else base.copy(text = "${base.text} · $suffix")
}

/** 배터리 등급(10.5.1): 낮음 문턱 이하 주황, 심각 문턱 이하 빨강. */
enum class BatteryLevel { OK, LOW, CRITICAL }

fun batteryLevel(pct: Double, lowPct: Int, criticalPct: Int): BatteryLevel = when {
    pct <= criticalPct -> BatteryLevel.CRITICAL
    pct <= lowPct -> BatteryLevel.LOW
    else -> BatteryLevel.OK
}

/** 배터리 띠 한 칸: 띠 폭에 대한 비율 [from]~[to](0~1)과 색 등급. */
data class BandSegment(val from: Float, val to: Float, val level: BatteryLevel)

/**
 * 배터리 띠(Pilot 2, 11-04 녹화 대조): 땅에서는 남은 양([pct])까지 초록 한 칸. 떠 있고 RTH 필요량([rthPct])을 알면
 * 빨강 0~착륙 필요량([landPct], 모르면 심각 문턱 [criticalPct], RTH 필요량을 넘지 않음) · 주황 ~RTH 필요량(노란 H 자리) · 초록 ~남은 양.
 * 칸은 남은 양에서 자른다. 낮음·심각 문턱은 칸이 아니라 화면의 흰 점이다.
 */
fun batteryBand(pct: Double, flying: Boolean, rthPct: Int?, landPct: Int?, criticalPct: Int): List<BandSegment> {
    val p = (pct / 100).coerceIn(0.0, 1.0)
    val rth = rthPct?.takeIf { flying && it > 0 }?.let { it / 100.0 }
        ?: return listOfNotNull(BandSegment(0f, p.toFloat(), BatteryLevel.OK).takeIf { p > 0 })
    val land = minOf((landPct ?: criticalPct) / 100.0, rth)
    return listOf(Triple(0.0, land, BatteryLevel.CRITICAL), Triple(land, rth, BatteryLevel.LOW), Triple(rth, 1.0, BatteryLevel.OK))
        .mapNotNull { (a, b, lv) -> minOf(b, p).takeIf { it > a }?.let { BandSegment(a.toFloat(), it.toFloat(), lv) } }
}

/** 배터리 띠 시간 라벨(초): 기체 값이 있으면 그것, 없으면 RTH 필요량([needPct])을 뺀 배터리를 호버 소모율로 나눈 추정. 연결이 없으면 null. */
fun remainingFlightSec(t: Telemetry, needPct: Int): Int? =
    t.remainingFlightSec ?: if (!t.connected) null else ((t.batteryPct - needPct).coerceAtLeast(0.0) / Tuning.Energy.HOVER_PCT_PER_SEC).toInt()

/**
 * 광각 화면의 줌 영역 사각형(10.5.2): 망원 [ratio]배가 찍을 범위의 한 변 비율(화면 대비, 0~1). 배율이 오르면 작아진다.
 * 1이면 화면 전체다(화면은 1 미만일 때만 그린다).
 */
fun zoomFrameFraction(ratio: Double): Double = (1.0 / (ratio * Tuning.Display.ZOOM_RATIO_PER_WIDE)).coerceIn(0.0, 1.0)

/**
 * 내비 원판(10.5.3): 기수가 위인 원판에서 [to]가 놓이는 자리. (x 오른쪽, y 아래) 단위 원 좌표이고
 * 중심에서의 거리는 [Tuning.Display.NAV_RIM_M] 넘으면 가장자리(1). 위치를 모르거나 같은 자리(0.5m 안)면 null.
 */
fun navPoint(from: GeoPoint?, to: GeoPoint?, headingDeg: Double): Pair<Double, Double>? {
    if (from == null || to == null) return null
    val d = LocalProjection.distanceM(from, to)
    if (d < 0.5) return null
    val r = (d / Tuning.Display.NAV_RIM_M).coerceAtMost(1.0)
    val a = Math.toRadians(navAngle(Viewpoints.bearing(from, to), headingDeg))
    return r * sin(a) to -r * cos(a)
}

/** 기수가 위인 원판에서 방위 [bearingDeg]의 화면 각(위 0°, 시계 방향). 나침반 글자·풍향에도 쓴다. */
fun navAngle(bearingDeg: Double, headingDeg: Double): Double = (bearingDeg - headingDeg).mod(360.0)

/** 조종기 점을 보일지(Pilot 2: 기체에서 [Tuning.Display.NAV_RC_MIN_M] 넘을 때). */
fun showRcDot(aircraft: GeoPoint?, rc: GeoPoint?): Boolean =
    aircraft != null && rc != null && LocalProjection.distanceM(aircraft, rc) > Tuning.Display.NAV_RC_MIN_M
