package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Viewpoints
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 내비게이션 디스플레이(Pilot 2 카메라 뷰 아래 가운데, 매뉴얼 57~58쪽): 나침반 위에 기수와 홈 방향,
 * 왼쪽에 수평·수직 속도, 오른쪽에 고도·홈 거리·짐벌 틸트. 기수 위쪽(진행 방향이 위)이 아니라 북쪽 위로 그린다.
 */
@Composable
fun NavDisplay(t: Telemetry, home: GeoPoint?, modifier: Modifier = Modifier) {
    val homeDist = if (t.position != null && home != null) LocalProjection.distanceM(t.position!!, home) else null
    val homeBearing = if (t.position != null && home != null && homeDist!! > 1) Viewpoints.bearing(t.position!!, home) else null
    Row(
        modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Value("H.S", "%.1f m/s".format(t.speedMps))
            Value("V.S", "%.1f m/s".format(if (kotlin.math.abs(t.verticalSpeedMps) < 0.05) 0.0 else t.verticalSpeedMps))
        }
        Canvas(Modifier.size(80.dp)) {
            val r = min(size.width, size.height) / 2 - 4
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.White.copy(alpha = 0.6f), r, c, style = Stroke(1.5f))
            // 북쪽 눈금
            drawLine(Color(0xFFFF5449), Offset(c.x, c.y - r), Offset(c.x, c.y - r + 10), strokeWidth = 4f)
            // 홈 방향(테두리 위 점)
            homeBearing?.let { b ->
                val a = Math.toRadians(b)
                drawCircle(PoleDone, 7f, Offset(c.x + (r * sin(a)).toFloat(), c.y - (r * cos(a)).toFloat()))
            }
            // 기체: 기수 방향을 가리키는 삼각형
            rotate(t.headingDeg.toFloat(), c) {
                val p = Path().apply {
                    moveTo(c.x, c.y - 18)
                    lineTo(c.x + 11, c.y + 12)
                    lineTo(c.x, c.y + 6)
                    lineTo(c.x - 11, c.y + 12)
                    close()
                }
                drawPath(p, Aircraft)
            }
        }
        Column {
            Value("ALT", "%.1f m".format(t.altitudeM))
            Value("D", homeDist?.let { "%.0f m".format(it) } ?: "--")
            Value("짐벌", "%.0f°".format(t.gimbalPitchDeg))
        }
    }
}

@Composable
private fun Value(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
