package com.example.myfirstapp.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlin.math.cos
import kotlin.math.min

/**
 * 경로 미리보기: 소티마다 이륙 지점(H)에서 방문 순서대로 초록 선, 전주 점(끝남 초록, 남음 주황)과 전주번호.
 * 위경도를 위도 cos로 보정한 평면에 그대로 맞춘다(수 km 안의 미리보기라 충분). [labels]가 거짓이면(라이브러리 카드 그림) 번호를 쓰지 않는다.
 */
@Composable
internal fun RoutePreview(route: List<List<RoutePoint>>, home: GeoPoint?, modifier: Modifier, labels: Boolean = true) {
    val measurer = rememberTextMeasurer()
    val routeColor = MaterialTheme.colorScheme.tertiary
    val labelStyle = numberStyle(FontSize.Caption, FontWeight.Medium).copy(color = TextSoft)
    Canvas(modifier) {
        val pts = route.flatten()
        if (pts.isEmpty()) return@Canvas
        val lats = pts.map { it.lat } + listOfNotNull(home?.lat)
        val lons = pts.map { it.lon } + listOfNotNull(home?.lon)
        val k = cos(Math.toRadians(lats.average()))
        val minX = lons.min() * k
        val maxY = lats.max()
        val spanX = (lons.max() * k - minX).coerceAtLeast(1e-6)
        val spanY = (maxY - lats.min()).coerceAtLeast(1e-6)
        val pad = (if (labels) 20 else 10).dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val scale = min(w / spanX, h / spanY)
        val ox = pad + (w - spanX * scale) / 2
        val oy = pad + (h - spanY * scale) / 2
        fun at(lat: Double, lon: Double) = Offset((ox + (lon * k - minX) * scale).toFloat(), (oy + (maxY - lat) * scale).toFloat())

        val homeAt = home?.let { at(it.lat, it.lon) }
        route.forEach { sortie ->
            val path = Path()
            val first = homeAt ?: at(sortie[0].lat, sortie[0].lon)
            path.moveTo(first.x, first.y)
            sortie.forEach { p -> at(p.lat, p.lon).let { path.lineTo(it.x, it.y) } }
            drawPath(path, routeColor, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        }
        val r = (if (pts.size > 60) 2.5 else 4.0).dp.toPx()
        pts.forEach { p ->
            val c = at(p.lat, p.lon)
            drawCircle(Color.Black, r + 1.2.dp.toPx(), c)
            drawCircle(if (p.done) routeColor else Warning, r, c)
        }
        // 전주번호(10.4.3): 점 오른쪽 위, 어두운 바탕. 앞 번호와 겹치면 건너뛴다(방문 순서가 앞인 것이 남음). 많으면 모두 생략
        if (labels && pts.size <= MAX_LABELS) {
            val placed = mutableListOf<Rect>()
            val gap = 2.dp.toPx()
            pts.forEach { p ->
                val c = at(p.lat, p.lon)
                val t = measurer.measure(p.label, labelStyle)
                val box = Rect(Offset(c.x + r + gap, c.y - r - t.size.height), Size(t.size.width + 2 * gap, t.size.height.toFloat()))
                if (placed.any { it.overlaps(box) }) return@forEach
                placed += box
                drawRoundRect(LabelBg, box.topLeft, box.size, CornerRadius(gap))
                drawText(t, topLeft = Offset(box.left + gap, box.top))
            }
        }
        if (homeAt != null) {
            drawCircle(Caution, (if (labels) 8 else 5).dp.toPx(), homeAt)
            val t = measurer.measure("H", TextStyle(fontSize = FontSize.Micro, fontWeight = FontWeight.Bold, color = Color.Black))
            drawText(t, topLeft = Offset(homeAt.x - t.size.width / 2f, homeAt.y - t.size.height / 2f))
        }
    }
}

/** 미리보기에 전주번호를 쓰는 최대 전주 수. */
private const val MAX_LABELS = 40

private val LabelBg = Color(0xB3000000)
