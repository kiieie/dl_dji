package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.ObstacleDirection
import com.example.myfirstapp.core.model.ObstacleLevel
import com.example.myfirstapp.core.model.ObstacleWarning
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.model.navAngle
import com.example.myfirstapp.core.model.navPoint
import com.example.myfirstapp.core.model.showRcDot
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Inactive
import com.example.myfirstapp.ui.theme.NavDiscBg
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 내비 원판 왼쪽 위 다음 웨이포인트(10.5.3): 번호(지도 웨이포인트 번호와 같음), 기체에서의 수평 거리, 원판 테두리 파란 ▽ 방향을 정할 위치. */
data class NextWaypoint(val number: Int, val distanceM: Double, val at: GeoPoint)

/**
 * 내비게이션 디스플레이(10.5.3, Pilot 2 카메라 뷰 아래 가운데) 300×120. 원판은 채운 어두운 원이고 **기수가 위**, 30° 눈금과 N·E·S·W·숫자 방위가 돈다(위에 기수 방위 숫자).
 * 방위 글자는 눈금 안쪽 띠, 그 안에 얇은 원. 가운데 기체, 노란 H(홈 방향), 파란 점(조종기, 5m 넘을 때), 둘 다 [Tuning.Display.NAV_RIM_M] 넘으면 가장자리.
 * 다음 웨이포인트는 테두리 눈금 띠에 파란 ▽(글자를 가리지 않음).
 * 장애물: 앞·오른쪽·뒤·왼쪽 부채꼴(감지 범위 안 초록, 경고 주황, 제동 빨강), 오른쪽 세로 막대 위·아래, 감지 꺼짐 "OFF"·값 없음 "NA".
 * 원판 왼쪽 세로 막대는 짐벌 피치(파란 표지, 위 +35° ~ 아래 -90°, 30°마다 눈금, 긴 흰 눈금 0°).
 * 왼쪽 열: WS 풍속·풍향 화살표, 짐벌 각(0°면 노랑), 작은 "SPD m/s"와 속도. 오른쪽 열: VS, 고도와 작은 "ALT m", ASL(모르면 "--").
 * 아래: 홈까지 거리와 오른쪽 위 작은 노란 H. 왼쪽 위: 다음 웨이포인트 거리(위)와 번호▽(아래).
 */
@Composable
fun NavDisplay(t: Telemetry, home: GeoPoint?, obstacles: ObstacleWarning = ObstacleWarning(), next: NextWaypoint? = null, modifier: Modifier = Modifier) {
    val homeDist = if (t.position != null && home != null) LocalProjection.distanceM(t.position!!, home) else null
    val homeAt = navPoint(t.position, home, t.headingDeg)
    val rcAt = navPoint(t.position, t.rcPosition, t.headingDeg)?.takeIf { showRcDot(t.position, t.rcPosition) }
    val nextAt = next?.let { navPoint(t.position, it.at, t.headingDeg) }
    val sensed = obstacles.sensed
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = FontSize.Micro, color = Color.White)
    Box(modifier.size(300.dp, 120.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 42.dp.toPx()
            val c = Offset(size.width / 2, 62.dp.toPx())
            drawCircle(NavDiscBg, r, c)
            drawCircle(Color.White.copy(alpha = 0.35f), r, c, style = Stroke(1.dp.toPx()))
            fun at(angleDeg: Double, radius: Float): Offset {
                val a = Math.toRadians(angleDeg)
                return Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat())
            }
            // 장애물 부채꼴(기수 기준이라 앞이 위). Compose 각도는 3시가 0°라 90°를 뺀다
            sensed?.let { o ->
                listOf(ObstacleDirection.FRONT to 0, ObstacleDirection.RIGHT to 90, ObstacleDirection.BACK to 180, ObstacleDirection.LEFT to 270).forEach { (dir, deg) ->
                    val color = sectorColor(o.sector(dir)) ?: return@forEach
                    val w = 4.dp.toPx()
                    drawArc(color.copy(alpha = 0.3f), deg - 90f - 35f, 70f, useCenter = true, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r))
                    drawArc(color, deg - 90f - 35f, 70f, useCenter = false, topLeft = c - Offset(r - w / 2, r - w / 2), size = Size(2 * r - w, 2 * r - w), style = Stroke(w))
                }
            }
            // 30° 눈금(테두리)과 방위 글자(N·E·S·W, 나머지는 방위/10). 글자는 눈금 안쪽 띠: 테두리의 다음 웨이포인트 ▽가 글자를 가리지 않게(11-04 3차)
            for (b in 0 until 360 step 30) {
                val a = navAngle(b.toDouble(), t.headingDeg)
                drawLine(Color.White, at(a, r), at(a, r - 4.dp.toPx()), 1.dp.toPx())
                val label = when (b) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "${b / 10}" }
                val m = measurer.measure(label, if (b % 90 == 0) small.copy(fontWeight = FontWeight.Bold) else small.copy(color = TextSoft))
                val p = at(a, r - 15.dp.toPx())
                drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y - m.size.height / 2f))
            }
            // 안쪽 얇은 원(Pilot 2)
            drawCircle(Color.White.copy(alpha = 0.3f), r - 23.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            // 기수 표시(위 가운데 초록 눈금)
            drawLine(Tertiary, Offset(c.x, c.y - r - 3.dp.toPx()), Offset(c.x, c.y - r + 5.dp.toPx()), 2.dp.toPx())
            homeAt?.let { (x, y) ->
                val p = Offset(c.x + (x * r).toFloat(), c.y + (y * r).toFloat())
                drawCircle(Caution, 6.dp.toPx(), p)
                val h = measurer.measure("H", TextStyle(fontSize = FontSize.Micro, fontWeight = FontWeight.Bold, color = Color.Black))
                drawText(h, topLeft = Offset(p.x - h.size.width / 2f, p.y - h.size.height / 2f))
            }
            rcAt?.let { (x, y) -> drawCircle(Accent, 3.5.dp.toPx(), Offset(c.x + (x * r).toFloat(), c.y + (y * r).toFloat())) }
            // 다음 웨이포인트: 테두리 눈금 띠에 파란 ▽(Pilot 2), 거리와 관계없이 방향만. 방위 글자는 그 안쪽이라 겹치지 않는다
            nextAt?.let { (x, y) ->
                val n = hypot(x, y).takeIf { it > 0 } ?: return@let
                val rr = r - 4.dp.toPx()
                val p = Offset(c.x + (x / n * rr).toFloat(), c.y + (y / n * rr).toFloat())
                val a = 4.dp.toPx()
                drawPath(Path().apply { moveTo(p.x - a, p.y - a * 0.7f); lineTo(p.x + a, p.y - a * 0.7f); lineTo(p.x, p.y + a); close() }, Accent, style = Stroke(1.5.dp.toPx()))
            }
            // 가운데 기체(기수가 위)
            val u = 1.dp.toPx()
            drawPath(
                Path().apply { moveTo(c.x, c.y - 10 * u); lineTo(c.x + 7 * u, c.y + 6 * u); lineTo(c.x, c.y + 2 * u); lineTo(c.x - 7 * u, c.y + 6 * u); close() },
                Color.White,
            )
            // 오른쪽 세로 막대: 위쪽 반은 위 장애물, 아래쪽 반은 아래 장애물(가까울수록 길게)
            val barX = c.x + r + 12.dp.toPx()
            val barTop = c.y - r + 6.dp.toPx()
            val half = r - 6.dp.toPx()
            val bw = 3.dp.toPx()
            drawRect(Inactive, Offset(barX, barTop), Size(bw, 2 * half))
            sensed?.let { o ->
                listOf(ObstacleDirection.UP to true, ObstacleDirection.DOWN to false).forEach { (dir, up) ->
                    val color = sectorColor(o.sector(dir)) ?: return@forEach
                    val m = o.distancesM[dir] ?: return@forEach
                    val len = (half * (1 - m / Tuning.Obstacle.SENSING_M)).toFloat().coerceIn(2 * u, half)
                    drawRect(color, Offset(barX, if (up) c.y - len else c.y), Size(bw, len))
                }
            }
            // 왼쪽 세로 막대: 짐벌 피치(Pilot 2 파란 표지). 위 끝 +35°, 아래 끝 -90°, 흰 눈금은 0°
            val gx = c.x - r - 12.dp.toPx() - bw
            val up = Tuning.Display.GIMBAL_BAR_UP_DEG
            val span = up - Tuning.Display.GIMBAL_BAR_DOWN_DEG
            drawRect(Color.White.copy(alpha = 0.45f), Offset(gx + bw / 2 - u / 2, barTop), Size(u, 2 * half))
            // 30°마다 짧은 눈금(Pilot 2), 0°는 긴 흰 눈금
            var deg = 30.0
            while (deg >= Tuning.Display.GIMBAL_BAR_DOWN_DEG) {
                if (deg != 0.0) drawRect(Color.White.copy(alpha = 0.7f), Offset(gx, barTop + 2 * half * ((up - deg) / span).toFloat() - u / 2), Size(bw, u))
                deg -= 30.0
            }
            drawRect(Color.White, Offset(gx - u, barTop + 2 * half * (up / span).toFloat()), Size(bw + 2 * u, u))
            val f = ((up - t.gimbalPitchDeg) / span).toFloat().coerceIn(0f, 1f)
            drawRoundRect(Accent, Offset(gx - u, barTop + 2 * half * f - 2.5f * u), Size(bw + 2 * u, 5 * u), CornerRadius(u))
            // 감지 꺼짐 OFF·값 없음 NA(원판 아래쪽 안)
            val off = when {
                sensed == null -> "NA"
                !sensed.sensingOn -> "OFF"
                else -> null
            }
            off?.let {
                val m = measurer.measure(it, TextStyle(fontSize = FontSize.Micro, fontWeight = FontWeight.Bold, color = Error))
                drawText(m, topLeft = Offset(c.x - m.size.width / 2f, c.y + r * 0.45f))
            }
        }
        Text(
            "%03d".format(t.headingDeg.toInt().mod(360)), Modifier.align(Alignment.TopCenter).semantics { contentDescription = "기수 방위" },
            style = numberStyle(FontSize.Caption), color = Tertiary,
        )
        // 다음 웨이포인트(Pilot 2 p2_mission2): 거리 위, 번호▽ 아래, 원판 왼쪽 위
        next?.let {
            Column(Modifier.offset(x = 72.dp).width(48.dp), horizontalAlignment = Alignment.End) {
                Text("${"%.0f".format(it.distanceM)}m", style = numberStyle(FontSize.Caption), color = Color.White, maxLines = 1)
                Text("${it.number}▽", style = numberStyle(FontSize.Caption), color = Accent, maxLines = 1)
            }
        }
        Column(Modifier.offset(y = 30.dp).width(92.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WS ${t.windSpeedMps?.let { "%04.1f".format(it) } ?: "--"} ", style = numberStyle(FontSize.Caption, FontWeight.Medium), color = Color.White)
                // 풍향 화살표(기수가 위인 원판과 같은 기준)
                t.windDirDeg?.let { w -> Text("↑", Modifier.rotate(navAngle(w, t.headingDeg).toFloat()), style = numberStyle(FontSize.Caption), color = Color.White) }
                val level = abs(t.gimbalPitchDeg) < 0.5
                Text(" %.0f°".format(t.gimbalPitchDeg), style = numberStyle(FontSize.Caption, FontWeight.Medium), color = if (level) Caution else Color.White)
            }
            // Pilot 2: 작은 두 줄 "SPD / m/s"가 숫자 왼쪽
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                UnitLabel("SPD", "m/s", Alignment.End)
                Text("%04.1f".format(t.speedMps), style = numberStyle(22.sp), color = Tertiary)
            }
        }
        // 오른쪽 열은 장애물 막대(원판 오른쪽 12dp) 밖에서 시작한다
        Column(Modifier.align(Alignment.TopEnd).offset(y = 30.dp).width(88.dp)) {
            Text("%.1f VS".format(if (abs(t.verticalSpeedMps) < 0.05) 0.0 else t.verticalSpeedMps), style = numberStyle(FontSize.Caption, FontWeight.Medium), color = Color.White)
            // Pilot 2: 작은 두 줄 "ALT / m"가 숫자 오른쪽
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("%05.1f".format(t.altitudeM), style = numberStyle(22.sp), color = Tertiary)
                UnitLabel("ALT", "m", Alignment.Start)
            }
            Text("${t.altitudeAslM?.let { "%06.1f".format(it) } ?: "--"} ASL", style = numberStyle(FontSize.Micro), color = Color.White)
        }
        Text(
            homeDist?.let { "%.0fm".format(it) } ?: "--", Modifier.align(Alignment.BottomCenter).semantics { contentDescription = "홈까지 거리" },
            style = numberStyle(FontSize.Micro), color = Color.White,
        )
        // 홈 거리 표지: 원판 오른쪽 아래 작은 노란 H(Pilot 2 p2_mission2)
        Box(
            Modifier.align(Alignment.BottomCenter).offset(x = 24.dp, y = (-8).dp).size(11.dp).clip(CircleShape).background(Caution),
            contentAlignment = Alignment.Center,
        ) { Text("H", style = numberStyle(8.sp, FontWeight.Bold), color = Color.Black, maxLines = 1) }
    }
}

/** 숫자 옆 작은 두 줄 단위(Pilot 2 "SPD/m/s", "ALT/m"), 초록. */
@Composable
private fun UnitLabel(name: String, unit: String, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align) {
        val st = numberStyle(FontSize.Micro, FontWeight.Medium).copy(lineHeight = FontSize.Micro)
        Text(name, style = st, color = Tertiary)
        Text(unit, style = st, color = Tertiary)
    }
}

/** 부채꼴 색(10.5.3): 제동 빨강, 경고 주황, 감지 범위 안 초록. */
private fun sectorColor(l: ObstacleLevel?): Color? = when (l) {
    ObstacleLevel.DANGER -> Error
    ObstacleLevel.CAUTION -> Warning
    ObstacleLevel.NONE -> Tertiary
    null -> null
}
