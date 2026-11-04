package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.route.LocalProjection
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

// AR 투영(10.5.2, Pilot 2 매뉴얼 p60 "AR 프로젝션"): 홈포인트·웨이포인트·핀포인트를 카메라 영상 위 자리로. 화면은 그리기만 한다(단위 테스트 `ArTest`).

/** 지금 렌즈의 가로 화각(°): 광각은 [Tuning.Display.WIDE_HFOV_DEG], 망원은 광각 대비 배율([zoomRatio] × [Tuning.Display.ZOOM_RATIO_PER_WIDE])만큼 좁다(tan 비례). */
fun lensHfovDeg(lens: Lens, zoomRatio: Double): Double {
    val r = if (lens == Lens.ZOOM) (zoomRatio * Tuning.Display.ZOOM_RATIO_PER_WIDE).coerceAtLeast(1.0) else 1.0
    return Math.toDegrees(2 * atan(tan(Math.toRadians(Tuning.Display.WIDE_HFOV_DEG / 2)) / r))
}

/**
 * AR 카메라: 기체 위치·고도(이륙 지점 기준), 카메라가 보는 방위 [yawDeg](정북 0°, 시계 방향)·피치 [pitchDeg](수평 0°, 위 +, 아래 -90°),
 * 가로 화각 [hfovDeg], 영상 비율 [aspect](폭/높이). 세로 화각은 비율로 정한다.
 */
data class ArCamera(val at: GeoPoint, val altitudeM: Double, val yawDeg: Double, val pitchDeg: Double, val hfovDeg: Double, val aspect: Double) {
    companion object {
        /**
         * 텔레메트리에서: 방위 = 기수 + 짐벌 요(기수 기준, `KeyYawRelativeToAircraftHeading`), 피치 = 짐벌 피치, 화각 = [lensHfovDeg]. 위치를 모르면 null.
         * ponytail: 기체 기울기(롤·피치)는 짐벌이 잡는다고 보고 넣지 않는다. 화면이 기울어 보이면 짐벌 롤을 더한다.
         */
        fun of(t: Telemetry, lens: Lens, zoomRatio: Double, aspect: Double): ArCamera? = t.position?.let {
            ArCamera(it, t.altitudeM, t.headingDeg + t.gimbalYawRelDeg, t.gimbalPitchDeg, lensHfovDeg(lens, zoomRatio), aspect)
        }
    }
}

/**
 * 투영 결과: 영상 안 자리 [x]·[y](0~1, 왼쪽 위 0)와 영상 안인지 [inView]. 밖이거나 뒤면 가운데에서 그쪽 방향의 가장자리 자리이고,
 * [arrowDeg]는 가운데에서 본 방향(위 0°, 시계 방향, Pilot 2 가장자리 화살표). [distanceM]은 기체에서 수평 거리.
 */
data class ArPoint(val x: Double, val y: Double, val inView: Boolean, val arrowDeg: Double, val distanceM: Double)

/** [at]·[altitudeM](이륙 지점 기준)을 [cam] 영상에 투영한다. 기체와 거의 같은 자리(0.5m 안)면 null. */
fun arProject(cam: ArCamera, at: GeoPoint, altitudeM: Double): ArPoint? {
    val e = LocalProjection(cam.at).toEnu(at)
    val up = altitudeM - cam.altitudeM
    val dist = hypot(e.x, e.y)
    if (hypot(dist, up) < 0.5) return null
    val yaw = Math.toRadians(cam.yawDeg)
    val pitch = Math.toRadians(cam.pitchDeg)
    // 카메라 축(동·북·위 성분): 앞 = (sinψ cosθ, cosψ cosθ, sinθ), 오른쪽 = (cosψ, −sinψ, 0), 위 = (−sinψ sinθ, −cosψ sinθ, cosθ)
    val zc = e.x * sin(yaw) * cos(pitch) + e.y * cos(yaw) * cos(pitch) + up * sin(pitch)
    val xc = e.x * cos(yaw) - e.y * sin(yaw)
    val yc = -e.x * sin(yaw) * sin(pitch) - e.y * cos(yaw) * sin(pitch) + up * cos(pitch)
    val th = tan(Math.toRadians(cam.hfovDeg / 2))
    // 화면 단위 방향(가운데 0, 가장자리 ±1이 되도록 화각으로 나눔). 앞이면 거리 zc로 나눈 것이 자리다
    val dx = xc / th
    val dy = yc / (th / cam.aspect)
    if (zc > 0 && abs(dx) <= zc && abs(dy) <= zc) return ArPoint(0.5 + dx / zc / 2, 0.5 - dy / zc / 2, true, arrowDeg(dx, dy), dist)
    // 밖·뒤: 가운데에서 그 방향으로 가장자리. 바로 뒤(방향 없음)면 아래
    val (ax, ay) = if (hypot(dx, dy) < 1e-9) 0.0 to -1.0 else dx to dy
    val k = 1 / max(abs(ax), abs(ay))
    return ArPoint(0.5 + ax * k / 2, 0.5 - ay * k / 2, false, arrowDeg(ax, ay), dist)
}

private fun arrowDeg(dx: Double, dy: Double) = Math.toDegrees(atan2(dx, dy)).mod(360.0)

/** AR 표지 종류(매뉴얼 p60): 다음 웨이포인트(실선 ▽), 그다음 웨이포인트(점선 ▽), 홈포인트(노란 H), 핀포인트(색 ◇). */
enum class ArKind { NEXT_WAYPOINT, LATER_WAYPOINT, HOME, PIN }

/** AR로 그릴 웨이포인트: 번호(지도 번호와 같음), 위치, 촬영 고도(이륙 지점 기준). */
data class ArWaypoint(val number: Int, val at: GeoPoint, val altitudeM: Double)

/** AR 대상. [edge]면 영상 밖·뒤에 있어도 가장자리에 붙여 보인다(홈·다음 웨이포인트·고른 핀포인트). [color]는 핀포인트 색. */
data class ArTarget(val kind: ArKind, val at: GeoPoint, val altitudeM: Double, val label: String, val edge: Boolean, val color: AnnotationColor? = null)

/** 그릴 표지: 대상, 투영 자리, 크기 비율(핀포인트만 거리에 따라, 나머지 1). */
data class ArMark(val target: ArTarget, val point: ArPoint, val scale: Double)

/**
 * AR 대상 고르기(매뉴얼 p60·p63): 홈포인트(고도 0 = 이륙 지점), 웨이포인트 앞의 둘(다음 = 실선, 그다음 = 점선), 핀포인트(고도를 모르면 이륙 지점 높이).
 * 가장자리에 남기는 것은 홈·다음 웨이포인트·[selectedPin]뿐이다(다른 핀포인트는 영상 안일 때만, Pilot 2와 같음).
 */
fun arTargets(home: GeoPoint?, waypoints: List<ArWaypoint>, pins: List<Annotation>, selectedPin: Long?): List<ArTarget> = buildList {
    pins.filter { it.kind == AnnotationKind.PIN && it.points.isNotEmpty() }.forEach { p ->
        add(ArTarget(ArKind.PIN, p.points[0], p.altitudeM ?: 0.0, p.name, edge = p.id == selectedPin, color = p.color))
    }
    waypoints.take(2).forEachIndexed { i, w ->
        add(ArTarget(if (i == 0) ArKind.NEXT_WAYPOINT else ArKind.LATER_WAYPOINT, w.at, w.altitudeM, "${w.number}", edge = i == 0))
    }
    home?.let { add(ArTarget(ArKind.HOME, it, 0.0, "H", edge = true)) }
}

/** [targets]를 투영해 보일 것만: 영상 안은 모두, 밖·뒤는 [ArTarget.edge]만. 그리는 순서는 목록 순서(뒤가 위). */
fun arMarks(cam: ArCamera, targets: List<ArTarget>): List<ArMark> = targets.mapNotNull { t ->
    val p = arProject(cam, t.at, t.altitudeM)?.takeIf { it.inView || t.edge } ?: return@mapNotNull null
    ArMark(t, p, if (t.kind == ArKind.PIN) arPinScale(p.distanceM) else 1.0)
}

/** 핀포인트 크기 비율(매뉴얼 p63 "가까우면 크게, 멀면 작게"): [Tuning.Display.AR_PIN_NEAR_M] 안 1, [Tuning.Display.AR_PIN_FAR_M] 밖 [Tuning.Display.AR_PIN_MIN_SCALE], 사이는 거리에 비례. */
fun arPinScale(distanceM: Double): Double {
    val f = ((distanceM - Tuning.Display.AR_PIN_NEAR_M) / (Tuning.Display.AR_PIN_FAR_M - Tuning.Display.AR_PIN_NEAR_M)).coerceIn(0.0, 1.0)
    return 1 - f * (1 - Tuning.Display.AR_PIN_MIN_SCALE)
}
