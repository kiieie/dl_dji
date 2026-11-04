package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.core.model.ArCamera
import com.example.myfirstapp.core.model.ArKind
import com.example.myfirstapp.core.model.ArMark
import com.example.myfirstapp.core.model.arMarks
import com.example.myfirstapp.core.model.arTargets
import com.example.myfirstapp.core.model.videoContentRect
import com.example.myfirstapp.ui.annotationViewModel
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.numberStyle
import com.example.myfirstapp.ui.ui
import kotlin.math.cos
import kotlin.math.sin

/**
 * AR 표시(10.5.2, Pilot 2 매뉴얼 p60·p63): 영상 위에 홈포인트(노란 원 H), 다음 웨이포인트(파란 ▽ · 번호 · 거리)와 그다음(점선 ▽ · 번호),
 * 핀포인트(그 색 ◇ · 이름, 가까우면 크게, 고른 것은 흰 테두리). 영상 밖·뒤면 가장자리 안쪽에 붙이고 그쪽으로 작은 삼각 화살표
 * (홈·다음 웨이포인트·고른 핀포인트만, 고르기·자리 계산은 `:core` [arMarks]). 실제 영상 자리([videoContentRect]) 안에 그리기만 한다.
 */
@Composable
internal fun ArOverlay(s: FlightUiState, stream: IntSize?, modifier: Modifier) {
    val t = s.telemetry ?: return
    val ns by annotationViewModel().state.collectAsStateWithLifecycle()
    val targets = remember(s.home, t.home, s.nextWaypoint?.ar, ns.items, ns.selected) {
        arTargets(t.home ?: s.home,s.nextWaypoint?.ar.orEmpty(), ns.pins, ns.selected)
    }
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val v = videoContentRect(size.width.toDouble(), size.height.toDouble(), stream?.width, stream?.height)
        if (v.width <= 0 || v.height <= 0) return@Canvas
        val cam = ArCamera.of(t, s.lens, s.zoomRatio, v.width / v.height) ?: return@Canvas
        val edge = EDGE_INSET.toPx()
        arMarks(cam, targets).forEach { m ->
            val x = (v.left + m.point.x * v.width).toFloat()
            val y = (v.top + m.point.y * v.height).toFloat()
            // 가장자리에 붙인 것은 표지가 잘리지 않게 안쪽으로
            val at = if (m.point.inView) Offset(x, y)
            else Offset(x.coerceIn(v.left.toFloat() + edge, (v.left + v.width).toFloat() - edge), y.coerceIn(v.top.toFloat() + edge, (v.top + v.height).toFloat() - edge))
            drawMark(m, at, measurer)
        }
    }
}

/** 가장자리 표지를 영상 끝에서 띄우는 거리(표지 반지름 + 화살표). */
private val EDGE_INSET = 24.dp

private fun DrawScope.drawMark(m: ArMark, at: Offset, measurer: TextMeasurer) {
    val tgt = m.target
    val c = when (tgt.kind) {
        ArKind.HOME -> Caution
        ArKind.PIN -> tgt.color?.ui ?: Accent
        else -> Accent
    }
    val r = (10.dp.toPx() * m.scale).toFloat()
    when (tgt.kind) {
        ArKind.HOME -> {
            drawCircle(Color.Black.copy(alpha = 0.35f), r, at)
            drawCircle(c, r, at, style = Stroke(2.dp.toPx()))
            label("H", at, c, measurer, plate = false)
        }
        ArKind.NEXT_WAYPOINT, ArKind.LATER_WAYPOINT -> {
            val tri = Path().apply { moveTo(at.x - r, at.y - r * 0.8f); lineTo(at.x + r, at.y - r * 0.8f); lineTo(at.x, at.y + r * 0.9f); close() }
            if (tgt.kind == ArKind.NEXT_WAYPOINT) {
                drawPath(tri, c)
                drawPath(tri, Color.White, style = Stroke(1.dp.toPx()))
                // 아래에 거리(Pilot 2 내비 "32m"와 같은 수평 거리)
                label("%.0fm".format(m.point.distanceM), Offset(at.x, at.y + r + 9.dp.toPx()), Color.White, measurer)
            } else {
                val dash = 3.dp.toPx()
                drawPath(tri, c, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))))
            }
            label(tgt.label, Offset(at.x, at.y - r - 9.dp.toPx()), Color.White, measurer)
        }
        ArKind.PIN -> {
            val d = Path().apply { moveTo(at.x, at.y - r); lineTo(at.x + r * 0.75f, at.y); lineTo(at.x, at.y + r); lineTo(at.x - r * 0.75f, at.y); close() }
            drawPath(d, c)
            drawPath(d, Color.White, style = Stroke(1.dp.toPx()))
            // 고른 핀포인트(Pilot 2 작은 프레임)
            if (tgt.edge) drawRect(Color.White, Offset(at.x - r - 3.dp.toPx(), at.y - r - 3.dp.toPx()), Size(2 * r + 6.dp.toPx(), 2 * r + 6.dp.toPx()), style = Stroke(1.dp.toPx()))
            label(tgt.label, Offset(at.x, at.y + r + 9.dp.toPx()), Color.White, measurer)
        }
    }
    if (!m.point.inView) {
        // 가운데에서 본 방향으로 작은 삼각형(Pilot 2 노란 H 옆 화살)
        val a = Math.toRadians(m.point.arrowDeg)
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val tip = Offset(at.x + dx * (r + 10.dp.toPx()), at.y + dy * (r + 10.dp.toPx()))
        val base = Offset(at.x + dx * (r + 3.dp.toPx()), at.y + dy * (r + 3.dp.toPx()))
        val w = 4.dp.toPx()
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(base.x - dy * w, base.y + dx * w); lineTo(base.x + dy * w, base.y - dx * w); close() }, c)
    }
}

/** [center]를 가운데로 하는 글. [plate]면 검은 반투명 바탕(영상 위에서 읽히게). */
private fun DrawScope.label(text: String, center: Offset, c: Color, measurer: TextMeasurer, plate: Boolean = true) {
    val layout = measurer.measure(text, numberStyle(FontSize.Body, FontWeight.Bold).copy(color = c))
    val tl = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    if (plate) drawRoundRect(
        Color.Black.copy(alpha = 0.45f), Offset(tl.x - 3.dp.toPx(), tl.y), Size(layout.size.width + 6.dp.toPx(), layout.size.height.toFloat()), CornerRadius(2.dp.toPx()),
    )
    drawText(layout, topLeft = tl)
}
