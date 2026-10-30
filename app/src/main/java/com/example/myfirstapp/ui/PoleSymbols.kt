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
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Warning

/** 설비 기호(10.4.3 판독·앨범 범례): 고/저압과 설치기기. 엑셀 값(4.5)에서 고른다. */
enum class PoleSymbol(val title: String) { HIGH("고압"), LOW("저압"), BOTH("고저압"), TRANSFORMER("변압기"), SWITCH("개폐기"), ARRESTER("피뢰기") }

/** 엑셀 `고/저압`·`설치기기` 값 → 기호. 설치기기는 "가공뱅크/피뢰기"처럼 여러 개일 수 있다. */
fun poleSymbols(voltage: String?, equipment: String?): List<PoleSymbol> = buildList {
    when {
        voltage == null -> Unit
        "고저" in voltage -> add(PoleSymbol.BOTH)
        "고" in voltage -> add(PoleSymbol.HIGH)
        "저" in voltage -> add(PoleSymbol.LOW)
    }
    val e = equipment.orEmpty()
    if ("뱅크" in e || "변압" in e) add(PoleSymbol.TRANSFORMER)
    if ("개폐" in e) add(PoleSymbol.SWITCH)
    if ("피뢰" in e) add(PoleSymbol.ARRESTER)
}

/** 기호 줄. 기호마다 접근성 이름을 단다. */
@Composable
fun PoleSymbolRow(symbols: List<PoleSymbol>, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        symbols.forEach { s -> PoleSymbolIcon(s, size) }
    }
}

@Composable
fun PoleSymbolIcon(s: PoleSymbol, size: Dp = 12.dp) {
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
                drawCircle(Color.White, 3.3f * u, Offset(5 * u, 6 * u), style = Stroke(1.4f * u))
                drawCircle(Color.White, 3.3f * u, Offset(9 * u, 6 * u), style = Stroke(1.4f * u))
            }
            PoleSymbol.SWITCH -> {
                val w = 1.5f * u
                drawLine(Color.White, Offset(1 * u, 8.5f * u), Offset(4.5f * u, 8.5f * u), w)
                drawLine(Color.White, Offset(9.5f * u, 8.5f * u), Offset(13 * u, 8.5f * u), w)
                drawLine(Color.White, Offset(4.5f * u, 8.5f * u), Offset(10 * u, 3 * u), w)
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
