package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.Canvas
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
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 내비 원판 왼쪽 위 다음 웨이포인트(10.5.3): 번호(지도 웨이포인트 번호와 같음)와 기체에서의 수평 거리. */
data class NextWaypoint(val number: Int, val distanceM: Double)

/**
 * 내비게이션 디스플레이(10.5.3, Pilot 2 카메라 뷰 아래 가운데) 300×120. 원판은 **기수가 위**이고 30° 눈금과 N·E·S·W·숫자 방위가 돈다(위에 기수 방위 숫자).
 * 가운데 기체, 노란 H(홈 방향), 파란 점(조종기, 5m 넘을 때), 둘 다 [Tuning.Display.NAV_RIM_M] 넘으면 가장자리.
 * 장애물: 앞·오른쪽·뒤·왼쪽 부채꼴(감지 범위 안 초록, 경고 주황, 제동 빨강), 오른쪽 세로 막대 위·아래, 감지 꺼짐 "OFF"·값 없음 "NA".
 * 왼쪽 열: WS 풍속·풍향 화살표, 짐벌 피치(0°면 노랑), SPD. 오른쪽 열: VS, ALT, ASL(모르면 "--"). 아래: 홈까지 거리. 왼쪽 위: 다음 웨이포인트.
 */
@Composable
fun NavDisplay(t: Telemetry, home: GeoPoint?, obstacles: ObstacleWarning = ObstacleWarning(), next: NextWaypoint? = null, modifier: Modifier = Modifier) {
    val homeDist = if (t.position != null && home != null) LocalProjection.distanceM(t.position!!, home) else null
    val homeAt = navPoint(t.position, home, t.headingDeg)
    val rcAt = navPoint(t.position, t.rcPosition, t.headingDeg)?.takeIf { showRcDot(t.position, t.rcPosition) }
    val sensed = obstacles.sensed
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = FontSize.Micro, color = Color.White)
    Box(modifier.size(300.dp, 120.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 42.dp.toPx()
            val c = Offset(size.width / 2, 62.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.6f), r, c)
            drawCircle(Color.White.copy(alpha = 0.6f), r, c, style = Stroke(1.dp.toPx()))
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
            // 30° 눈금과 방위 글자(N·E·S·W, 나머지는 방위/10)
            for (b in 0 until 360 step 30) {
                val a = navAngle(b.toDouble(), t.headingDeg)
                drawLine(Color.White, at(a, r), at(a, r - 4.dp.toPx()), 1.dp.toPx())
                val label = when (b) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "${b / 10}" }
                val m = measurer.measure(label, if (b % 90 == 0) small.copy(fontWeight = FontWeight.Bold) else small.copy(color = TextSoft))
                val p = at(a, r - 11.dp.toPx())
                drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y - m.size.height / 2f))
            }
            // 기수 표시(위 가운데 초록 눈금)
            drawLine(Tertiary, Offset(c.x, c.y - r - 3.dp.toPx()), Offset(c.x, c.y - r + 5.dp.toPx()), 2.dp.toPx())
            homeAt?.let { (x, y) ->
                val p = Offset(c.x + (x * r).toFloat(), c.y + (y * r).toFloat())
                drawCircle(Caution, 6.dp.toPx(), p)
                val h = measurer.measure("H", TextStyle(fontSize = FontSize.Micro, fontWeight = FontWeight.Bold, color = Color.Black))
                drawText(h, topLeft = Offset(p.x - h.size.width / 2f, p.y - h.size.height / 2f))
            }
            rcAt?.let { (x, y) -> drawCircle(Accent, 3.5.dp.toPx(), Offset(c.x + (x * r).toFloat(), c.y + (y * r).toFloat())) }
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
        next?.let {
            Text("${it.number}▽ ${"%.0f".format(it.distanceM)}m", Modifier.offset(x = 70.dp, y = 6.dp), style = numberStyle(FontSize.Caption), color = Color.White)
        }
        Column(Modifier.offset(y = 30.dp).width(98.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WS ${t.windSpeedMps?.let { "%04.1f".format(it) } ?: "--"} ", style = numberStyle(FontSize.Caption, FontWeight.Medium), color = Color.White)
                // 풍향 화살표(기수가 위인 원판과 같은 기준)
                t.windDirDeg?.let { w -> Text("↑", Modifier.rotate(navAngle(w, t.headingDeg).toFloat()), style = numberStyle(FontSize.Caption), color = Color.White) }
                val level = abs(t.gimbalPitchDeg) < 0.5
                Text(" %.0f°".format(t.gimbalPitchDeg), style = numberStyle(FontSize.Caption, FontWeight.Medium), color = if (level) Caution else Color.White)
            }
            Text("%04.1f".format(t.speedMps), style = numberStyle(22.sp), color = Tertiary)
            Text("SPD m/s", fontSize = FontSize.Micro, color = Tertiary)
        }
        Column(Modifier.align(Alignment.TopEnd).offset(y = 30.dp).width(98.dp)) {
            Text("%.1f VS".format(if (abs(t.verticalSpeedMps) < 0.05) 0.0 else t.verticalSpeedMps), style = numberStyle(FontSize.Caption, FontWeight.Medium), color = Color.White)
            Text("%05.1f".format(t.altitudeM), style = numberStyle(22.sp), color = Tertiary)
            Text("${t.altitudeAslM?.let { "%06.1f".format(it) } ?: "--"} ASL", style = numberStyle(FontSize.Micro), color = Color.White)
        }
        Text(
            homeDist?.let { "%.0fm".format(it) } ?: "--", Modifier.align(Alignment.BottomCenter).semantics { contentDescription = "홈까지 거리" },
            style = numberStyle(FontSize.Micro), color = Color.White,
        )
    }
}

/** 부채꼴 색(10.5.3): 제동 빨강, 경고 주황, 감지 범위 안 초록. */
private fun sectorColor(l: ObstacleLevel?): Color? = when (l) {
    ObstacleLevel.DANGER -> Error
    ObstacleLevel.CAUTION -> Warning
    ObstacleLevel.NONE -> Tertiary
    null -> null
}
