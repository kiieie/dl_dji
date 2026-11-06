package com.example.myfirstapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.core.model.Equipment
import com.example.myfirstapp.core.model.PoleCodes
import com.example.myfirstapp.core.model.Voltage
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Warning

/** 설비 기호(10.4.3 판독·앨범 범례): 고/저압과 설치기기. 엑셀 값(4.5)에서 고른다. */
/** [short]: 지도 글자 라벨에 쓰는 한두 글자(계획·경로 보기 지도, 10.5.8). */
enum class PoleSymbol(val title: String, val short: String) {
    HIGH("고압", "고"), LOW("저압", "저"), BOTH("고저압", "고저"), TRANSFORMER("변압기", "변"), SWITCH("개폐기", "개"), ARRESTER("피뢰기", "피"),
}

/** 엑셀 `고/저압`·`설치기기` 값 → 기호. 판정 규칙은 [PoleCodes]에 있다. */
fun poleSymbols(voltage: String?, equipment: String?): List<PoleSymbol> = buildList {
    when (PoleCodes.voltageOf(voltage)) {
        Voltage.BOTH -> add(PoleSymbol.BOTH)
        Voltage.HIGH -> add(PoleSymbol.HIGH)
        Voltage.LOW -> add(PoleSymbol.LOW)
        null -> Unit
    }
    val e = PoleCodes.equipmentsOf(equipment)
    if (Equipment.TRANSFORMER in e) add(PoleSymbol.TRANSFORMER)
    if (Equipment.SWITCH in e) add(PoleSymbol.SWITCH)
    if (Equipment.ARRESTER in e) add(PoleSymbol.ARRESTER)
}

/** 기호 줄. 기호마다 접근성 이름을 단다. */
@Composable
/** [ink]: 변압기·개폐기 선 색(어두운 바탕 흰색, 밝은 패널은 글자색). */
fun PoleSymbolRow(symbols: List<PoleSymbol>, size: Dp = 12.dp, modifier: Modifier = Modifier, ink: Color = Color.White) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        symbols.forEach { s -> PoleSymbolIcon(s, size, ink) }
    }
}

@Composable
fun PoleSymbolIcon(s: PoleSymbol, size: Dp = 12.dp, ink: Color = Color.White) {
    Canvas(Modifier.size(size * (if (s == PoleSymbol.TRANSFORMER || s == PoleSymbol.SWITCH || s == PoleSymbol.ARRESTER) 14f / 12f else 1f), size).semantics { contentDescription = s.title }) {
        val u = this.size.height / 12f
        when (s) {
            PoleSymbol.HIGH -> drawPath(diamond(u), Warning)
            PoleSymbol.LOW -> drawCircle(Accent, 4.3f * u, center, style = Stroke(1.6f * u))
            PoleSymbol.BOTH -> {
                drawPath(diamond(u), Warning, style = Stroke(1.5f * u))
                drawCircle(Accent, 1.8f * u, center)
            }
            PoleSymbol.TRANSFORMER -> {
                drawCircle(ink, 3.3f * u, Offset(5 * u, 6 * u), style = Stroke(1.4f * u))
                drawCircle(ink, 3.3f * u, Offset(9 * u, 6 * u), style = Stroke(1.4f * u))
            }
            PoleSymbol.SWITCH -> {
                val w = 1.5f * u
                drawLine(ink, Offset(1 * u, 8.5f * u), Offset(4.5f * u, 8.5f * u), w)
                drawLine(ink, Offset(9.5f * u, 8.5f * u), Offset(13 * u, 8.5f * u), w)
                drawLine(ink, Offset(4.5f * u, 8.5f * u), Offset(10 * u, 3 * u), w)
            }
            PoleSymbol.ARRESTER -> drawPath(
                Path().apply {
                    moveTo(8.5f * u, 0.5f * u); lineTo(3.5f * u, 6.5f * u); lineTo(7 * u, 6.5f * u); lineTo(5.5f * u, 11.5f * u)
                    lineTo(10.5f * u, 5 * u); lineTo(7 * u, 5 * u); close()
                },
                Caution,
            )
        }
    }
}

private fun DrawScope.diamond(u: Float) = Path().apply {
    moveTo(6 * u, 1 * u); lineTo(11 * u, 6 * u); lineTo(6 * u, 11 * u); lineTo(1 * u, 6 * u); close()
}
