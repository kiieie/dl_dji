package com.example.myfirstapp.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.route.TileMath
import com.example.myfirstapp.ui.theme.MapThumbCasing
import kotlinx.coroutines.flow.Flow
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
import com.example.myfirstapp.ui.flight.PoleDone
import com.example.myfirstapp.ui.theme.MAP_POLE_PENDING
import com.example.myfirstapp.ui.theme.MAP_POLE_PENDING_STROKE
import com.example.myfirstapp.ui.theme.MapThumbBg
import androidx.compose.foundation.background
import com.example.myfirstapp.ui.theme.numberStyle

/** 경로 그림 지도 바탕(10.4.10)을 내는 ViewModel 함수: 그림 픽셀 너비·높이와 둘레 여백 픽셀. */
internal typealias MapThumbSource = (w: Int, h: Int, pad: Int) -> Flow<ImageBitmap?>

/**
 * 경로 미리보기: 미리 받은 지도 바탕([mapThumb], 10.4.10, 없으면 연한 지도 색 [MapThumbBg]) 위에
 * 소티마다 이륙 지점(H)에서 방문 순서대로 초록 선, 전주 점(끝남 초록, 남음은 비행 지도와 같은 밝은 회색에 어두운 테두리)과 전주번호.
 * 선·점과 바탕이 같은 웹 메르카토르 배치([TileMath.ThumbView])라 겹친다. [labels]가 거짓이면(라이브러리 카드 그림) 번호를 쓰지 않는다.
 */
@Composable
internal fun RoutePreview(
    route: List<List<RoutePoint>>,
    home: GeoPoint?,
    modifier: Modifier,
    labels: Boolean = true,
    mapThumb: MapThumbSource? = null,
) {
    val measurer = rememberTextMeasurer()
    val routeColor = MaterialTheme.colorScheme.tertiary
    val labelStyle = numberStyle(FontSize.Caption, FontWeight.Medium).copy(color = TextSoft)
    val pending = Color(android.graphics.Color.parseColor(MAP_POLE_PENDING))
    val pendingStroke = Color(android.graphics.Color.parseColor(MAP_POLE_PENDING_STROKE))
    val pad = with(LocalDensity.current) { (if (labels) 20 else 10).dp.roundToPx() }
    var px by remember { mutableStateOf(IntSize.Zero) }
    val source by rememberUpdatedState(mapThumb)
    // 그림 크기·경로가 바뀌면 바탕을 다시 받는다(만들기는 ViewModel·data 몫, 여기서는 받기만)
    val bg by produceState<ImageBitmap?>(null, px, pad, route, home) {
        val f = source ?: return@produceState
        if (px.width <= 0 || px.height <= 0 || route.all { it.isEmpty() }) return@produceState
        f(px.width, px.height, pad).collect { value = it }
    }
    Canvas(modifier.background(MapThumbBg).onSizeChanged { px = it }) {
        val pts = route.flatten()
        val view = TileMath.ThumbView.fit(thumbPoints(route, home), size.width.toInt(), size.height.toInt(), pad, AppConfig.Map.THUMB_MIN_SPAN_M)
        if (pts.isEmpty() || view == null) return@Canvas
        bg?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
        fun at(lat: Double, lon: Double) = Offset(view.x(lon).toFloat(), view.y(lat).toFloat())

        val homeAt = home?.let { at(it.lat, it.lon) }
        route.forEach { sortie ->
            if (sortie.isEmpty()) return@forEach
            val path = Path()
            val first = homeAt ?: at(sortie[0].lat, sortie[0].lon)
            path.moveTo(first.x, first.y)
            sortie.forEach { p -> at(p.lat, p.lon).let { path.lineTo(it.x, it.y) } }
            // 위성 위에서는 녹색 선 밑에 어두운 테두리(밝은 길·논에서도 보이게)
            if (bg != null) drawPath(path, MapThumbCasing, style = Stroke(4.5.dp.toPx(), join = StrokeJoin.Round))
            drawPath(path, routeColor, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
        }
        val r = (if (pts.size > 60) 2.5 else 4.0).dp.toPx()
        pts.forEach { p ->
            val c = at(p.lat, p.lon)
            drawCircle(if (p.done) Color.White else pendingStroke, r + 1.2.dp.toPx(), c)
            drawCircle(if (p.done) PoleDone else pending, r, c)
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
