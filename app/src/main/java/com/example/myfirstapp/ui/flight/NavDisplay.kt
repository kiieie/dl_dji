package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.ObstacleDirection
import com.example.myfirstapp.core.model.ObstacleLevel
import com.example.myfirstapp.core.model.ObstacleWarning
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Viewpoints
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 내비게이션 디스플레이(10.4.3 시안, Pilot 2 57~58쪽) 300×104: 원판에 기수(흰 화살표, 위에 기수 각도)와 홈(H) 방향,
 * 왼쪽 수평 속도(초록 큰 숫자)·짐벌 피치, 오른쪽 고도(초록)·수직 속도·홈 거리. 북쪽이 위다.
 */
@Composable
fun NavDisplay(t: Telemetry, home: GeoPoint?, obstacles: ObstacleWarning = ObstacleWarning(), modifier: Modifier = Modifier) {
    val homeDist = if (t.position != null && home != null) LocalProjection.distanceM(t.position!!, home) else null
    val homeBearing = if (t.position != null && home != null && homeDist!! > 1) Viewpoints.bearing(t.position!!, home) else null
    val measurer = rememberTextMeasurer()
    Box(modifier.size(300.dp, 104.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 42.dp.toPx()
            val c = Offset(size.width / 2, 58.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.55f), r, c)
            drawCircle(Color.White.copy(alpha = 0.5f), r, c, style = Stroke(1.dp.toPx()))
            drawLine(Color.White, Offset(c.x, c.y - r), Offset(c.x, c.y - r + 6.dp.toPx()), strokeWidth = 2.dp.toPx())
            // 충돌 경고 부채꼴(13번): 기수 기준 앞·오른쪽·뒤·왼쪽 90° 칸, 주의 주황·위험 빨강. 위·아래는 상단 상태 칸 글자로만 보인다
            obstacles.levels.forEach { (dir, level) ->
                val offset = when (dir) { ObstacleDirection.FRONT -> 0; ObstacleDirection.RIGHT -> 90; ObstacleDirection.BACK -> 180; ObstacleDirection.LEFT -> 270; else -> return@forEach }
                val color = if (level == ObstacleLevel.DANGER) Error else Warning
                // Compose 각도는 3시가 0°라 방위(12시 0°)에서 90°를 뺀다
                val start = (t.headingDeg + offset - 90 - 35).toFloat()
                val tl = Offset(c.x - r, c.y - r)
                drawArc(color.copy(alpha = 0.35f), start, 70f, useCenter = true, topLeft = tl, size = Size(2 * r, 2 * r))
                val w = 4.dp.toPx()
                drawArc(color, start, 70f, useCenter = false, topLeft = tl + Offset(w / 2, w / 2), size = Size(2 * r - w, 2 * r - w), style = Stroke(w))
            }
            homeBearing?.let { b ->
                val a = Math.toRadians(b)
                val d = (r * 0.7f * ((homeDist!! / AppConfig.Map.NAV_RANGE_M).coerceIn(0.3, 1.0))).toFloat()
                val p = Offset(c.x + d * sin(a).toFloat(), c.y - d * cos(a).toFloat())
                drawCircle(Caution, 5.dp.toPx(), p)
                val h = measurer.measure("H", TextStyle(fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color.Black))
                drawText(h, topLeft = Offset(p.x - h.size.width / 2f, p.y - h.size.height / 2f))
            }
            val u = 1.dp.toPx()
            rotate(t.headingDeg.toFloat(), c) {
                drawPath(
                    Path().apply {
                        moveTo(c.x, c.y - 12 * u); lineTo(c.x + 8 * u, c.y + 4 * u); lineTo(c.x, c.y); lineTo(c.x - 8 * u, c.y + 4 * u); close()
                    },
                    Color.White,
                )
            }
        }
        Text("%03d".format(t.headingDeg.toInt().mod(360)), Modifier.align(Alignment.TopCenter), style = numberStyle(11.sp), color = Color.White)
        Column(Modifier.offset(y = 34.dp).width(94.dp), horizontalAlignment = Alignment.End) {
            Text("짐벌 %.0f°".format(t.gimbalPitchDeg), style = numberStyle(11.sp, FontWeight.Medium), color = TextSoft)
            Text("%04.1f".format(t.speedMps), style = numberStyle(24.sp), color = Tertiary)
            Text("SPD m/s", fontSize = 11.sp, color = TextSoft)
        }
        Column(Modifier.align(Alignment.TopEnd).offset(y = 34.dp).width(94.dp)) {
            Text("%.1f VS".format(if (abs(t.verticalSpeedMps) < 0.05) 0.0 else t.verticalSpeedMps), style = numberStyle(11.sp, FontWeight.Medium), color = TextSoft)
            Text("%05.1f".format(t.altitudeM), style = numberStyle(24.sp), color = Tertiary)
            Text("ALT m · ${homeDist?.let { "%.0fm".format(it) } ?: "--"}", fontSize = 11.sp, color = TextSoft)
        }
    }
}
