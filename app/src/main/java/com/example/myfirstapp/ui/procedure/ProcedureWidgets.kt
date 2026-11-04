package com.example.myfirstapp.ui.procedure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.executor.PreflightCheck
import com.example.myfirstapp.ui.AppBadge
import com.example.myfirstapp.ui.MENU_PROPERTIES
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.ErrorContainer
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import java.util.Locale

// 절차 화면(비행 전 점검·웨이포인트 경로 체크리스트, 10.5.5) 공통 조각. Pilot 2 녹화(p2s_pre, p2s_5·6, 0242)와 같은 배치를
// 밝은 바탕([com.example.myfirstapp.ui.theme.LightScreen], 2026-11-04 사용자 결정)으로 그린다. 색은 테마 역할(onSurface 등)만 쓴다.

/** 머리글 44dp(Pilot 2 흰 바탕): 왼쪽 우리 표지, 가운데 제목, 오른쪽 ✕. */
@Composable
internal fun ProcedureHeader(title: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp)) {
        AppBadge(Modifier.align(Alignment.CenterStart))
        Text(title, Modifier.align(Alignment.Center), fontSize = FontSize.Number, fontWeight = FontWeight.Bold)
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd).size(Dimens.TouchMedium).semantics { contentDescription = "닫기" }) {
            Text("✕", fontSize = FontSize.Number)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 상태 줄 한 칸: 회색 이름 + 값(경고면 [color]). 아이콘이 없는 항목(비행 모드·GNSS·모델 GPU)에 쓴다. */
@Composable
internal fun StatusCell(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        buildAnnotatedString {
            if (label.isNotEmpty()) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = FontSize.Caption)) { append("$label ") }
            withStyle(numberStyle(FontSize.Number).toSpanStyle().copy(color = color)) { append(value) }
        },
        modifier, maxLines = 1,
    )
}

/** 상태 줄 아이콘(Pilot 2 비행 전 점검·체크리스트 위 줄): 기체 배터리 · 조종기 · SD 카드 · 홈포인트. */
internal enum class StatusIcon { BATTERY, RC, SD, HOME, AIRCRAFT }

/** 상태 줄 한 칸(Pilot 2): 회색 그림 + 값. 글 이름 대신 그림으로 무엇인지 보인다(설명은 [label]). */
@Composable
internal fun StatusIconCell(icon: StatusIcon, label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.semantics { contentDescription = "$label $value" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusGlyph(icon)
        Text(value, style = numberStyle(FontSize.Number), color = color, maxLines = 1)
    }
}

/** [StatusIcon] 그림 20dp(선 그림, 회색. 홈포인트만 노란 원 H). */
@Composable
internal fun StatusGlyph(icon: StatusIcon) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp)) {
        val w = 1.6.dp.toPx()
        val r = CornerRadius(2.dp.toPx())
        when (icon) {
            StatusIcon.BATTERY -> {
                drawRect(c, Offset(size.width * 0.38f, 0f), Size(size.width * 0.24f, size.height * 0.1f))
                drawRoundRect(c, Offset(size.width * 0.25f, size.height * 0.1f), Size(size.width * 0.5f, size.height * 0.88f), r, style = Stroke(w))
                drawRect(c, Offset(size.width * 0.33f, size.height * 0.45f), Size(size.width * 0.34f, size.height * 0.45f))
            }
            StatusIcon.RC -> {
                // 조종기: 몸체와 안테나 둘, 스틱 둘
                drawRoundRect(c, Offset(0f, size.height * 0.35f), Size(size.width * 0.72f, size.height * 0.55f), r, style = Stroke(w))
                drawLine(c, Offset(size.width * 0.12f, size.height * 0.35f), Offset(size.width * 0.12f, size.height * 0.12f), w)
                drawLine(c, Offset(size.width * 0.6f, size.height * 0.35f), Offset(size.width * 0.6f, size.height * 0.12f), w)
                drawCircle(c, size.width * 0.07f, Offset(size.width * 0.2f, size.height * 0.62f))
                drawCircle(c, size.width * 0.07f, Offset(size.width * 0.52f, size.height * 0.62f))
                drawRoundRect(c, Offset(size.width * 0.8f, size.height * 0.2f), Size(size.width * 0.2f, size.height * 0.7f), r)
            }
            StatusIcon.SD -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.05f); lineTo(size.width * 0.65f, size.height * 0.05f)
                    lineTo(size.width * 0.82f, size.height * 0.25f); lineTo(size.width * 0.82f, size.height * 0.95f)
                    lineTo(size.width * 0.2f, size.height * 0.95f); close()
                }
                drawPath(p, c)
                listOf(0.32f, 0.44f, 0.56f).forEach { x -> drawRect(Color.White, Offset(size.width * x, size.height * 0.12f), Size(size.width * 0.06f, size.height * 0.18f)) }
            }
            StatusIcon.AIRCRAFT -> {
                // 위에서 본 쿼드콥터(홈포인트 [기체] 버튼)
                drawLine(c, Offset(size.width * 0.2f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.8f), w)
                drawLine(c, Offset(size.width * 0.8f, size.height * 0.2f), Offset(size.width * 0.2f, size.height * 0.8f), w)
                listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.2f to 0.8f, 0.8f to 0.8f).forEach { (x, y) ->
                    drawCircle(c, size.width * 0.16f, Offset(size.width * x, size.height * y), style = Stroke(w))
                }
            }
            StatusIcon.HOME -> {
                drawCircle(Caution, size.minDimension * 0.45f)
                val h = size.minDimension * 0.22f
                drawLine(Color.Black, Offset(center.x - h * 0.6f, center.y - h), Offset(center.x - h * 0.6f, center.y + h), w)
                drawLine(Color.Black, Offset(center.x + h * 0.6f, center.y - h), Offset(center.x + h * 0.6f, center.y + h), w)
                drawLine(Color.Black, Offset(center.x - h * 0.6f, center.y), Offset(center.x + h * 0.6f, center.y), w)
            }
        }
    }
}

/** 빨간 차단 배너(Pilot 2 체크리스트 "GPS 오류…"): 넘어갈 수 없는 실패. */
@Composable
internal fun BlockingBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().background(ErrorContainer, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
        Text(text, fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** 주황 참고 배너(Pilot 2 "참고: 기체 암이…"): 연한 주황 바탕, 주황 테두리. */
@Composable
internal fun CautionBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().background(Warning.copy(alpha = 0.10f), RoundedCornerShape(4.dp)).border(1.dp, Warning, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(13.dp), tint = Warning)
        Text(text, fontSize = FontSize.Body, color = Warning)
    }
}

/** 우리 점검 한 줄: 녹 ●/빨 ● 점, 이름, 값. */
@Composable
internal fun CheckLine(c: PreflightCheck, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (c.ok) Tertiary else Error))
        Text(c.label, fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(c.value, Modifier.weight(1f), fontSize = FontSize.Body, color = if (c.ok) MaterialTheme.colorScheme.onSurface else Error, maxLines = 1, textAlign = TextAlign.End)
    }
}

/**
 * 설정 한 줄(Pilot 2: 이름 · (범위) · 오른쪽 조작). 아래 구분선(Pilot 2 줄 사이 선, 두 열 안에서는 [TwoColumns]가 긋는다).
 * 이름이 길면 잘리지 않고 두 줄로 넘어간다("(20~1500 m)"가 잘리던 것, 11-04 3차).
 */
@Composable
internal fun SettingLine(label: String, hint: String? = null, modifier: Modifier = Modifier, divider: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Column(modifier) {
        Row(Modifier.heightIn(min = 46.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildAnnotatedString {
                    append(label)
                    hint?.let { withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = FontSize.Caption)) { append(" $it") } }
                },
                Modifier.weight(1f), fontSize = FontSize.Label, maxLines = 2,
            )
            content()
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 두 열 설정 사이 가운데 세로선(Pilot 2 체크리스트). 두 열을 담은 Row에 `height(IntrinsicSize.Min)`을 준다. */
@Composable
internal fun CenterDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))

/**
 * 두 열 한 줄(Pilot 2 설정 두 열): 왼쪽 · 가운데 선 · 오른쪽을 같은 높이로 두고, 아래에 양쪽 줄 선을 같은 높이에 긋는다
 * (두 열을 따로 쌓으면 한쪽 줄이 높을 때 선이 어긋났다, 11-04 3차). [left]·[right]는 [SettingLine]`(divider = false)` 한 줄씩.
 */
@Composable
internal fun TwoColumns(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp), contentAlignment = Alignment.CenterStart) { left() }
        CenterDivider()
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) { right() }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        HorizontalDivider(Modifier.weight(1f).padding(end = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        CenterDivider()
        HorizontalDivider(Modifier.weight(1f).padding(start = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Pilot 2 숫자 칸: [−big|−step] 값 [+step|+big](또는 [−] 값 [+]). ± 칸은 붙여서 묶는다. 값을 모르면 "--"이고 누를 수 없다. 범위 밖으로는 가지 않는다.
 */
@Composable
internal fun StepperCells(value: Int?, step: Int, big: Int?, range: IntRange, enabled: Boolean = true, onSet: (Int) -> Unit) {
    val on = value != null && enabled
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StepGroup(listOfNotNull(big?.let { -it }, -step), on) { d -> value?.let { onSet((it + d).coerceIn(range)) } }
        ValueBox(value?.toString() ?: "--", 48.dp)
        StepGroup(listOfNotNull(step, big), on) { d -> value?.let { onSet((it + d).coerceIn(range)) } }
    }
}

/** 붙은 ± 칸 묶음(Pilot 2 [−100|−10]). 칸 사이는 1dp 밝은 선. */
@Composable
private fun StepGroup(deltas: List<Int>, enabled: Boolean, onStep: (Int) -> Unit) {
    Row(Modifier.height(Dimens.TouchMedium).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface)) {
        deltas.forEachIndexed { i, d ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight())
            val text = if (d > 0) "+$d" else "$d"
            Box(
                // Pilot 2 칸 폭(0242 약 38dp): 넓으면 이름 "(20~1500 m)"가 들어갈 자리가 없다
                Modifier.width(38.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = enabled, onClickLabel = text) { onStep(d) },
                contentAlignment = Alignment.Center,
            ) { Text(text, style = numberStyle(FontSize.Label), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) }
        }
    }
}

/** 값 칸(Pilot 2 회색 상자): 스테퍼 가운데 값, 최대 비행 거리 값. */
@Composable
internal fun ValueBox(text: String, width: Dp, enabled: Boolean = true) {
    Box(Modifier.width(width).height(Dimens.TouchMedium).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        Text(text, style = numberStyle(FontSize.Number), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, maxLines = 1)
    }
}

/**
 * 소수 값 칸(Pilot 2 "비행 임무 속도 3.0"): 누르면 숫자 키보드로 고치고, 완료·포커스를 잃을 때 [step] 단위로 반올림해 [range] 안으로 넣는다.
 * 글자로만 바꾸고 판단(반올림·범위)은 [onSet]에 넘기기 전 여기서 한다(값 칸 하나의 입력 정리).
 */
@Composable
internal fun DecimalField(
    value: Double, range: ClosedFloatingPointRange<Double>, step: Double, enabled: Boolean, width: Dp = 120.dp, decimals: Int = 1, onSet: (Double) -> Unit,
) {
    val shown = String.format(Locale.ROOT, "%.${decimals}f", value)
    var text by remember(shown) { mutableStateOf(shown) }
    fun commit() {
        val v = text.replace(',', '.').toDoubleOrNull()
        val fixed = v?.let { (Math.round(it / step) * step).coerceIn(range) }
        if (fixed != null && fixed != value) onSet(fixed) else text = shown
    }
    val fg = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    BasicTextField(
        value = text, onValueChange = { text = it }, enabled = enabled, singleLine = true,
        textStyle = numberStyle(FontSize.Number).copy(color = fg, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.width(width).onFocusChanged { if (!it.isFocused) commit() }.semantics { contentDescription = "값 $shown" },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().height(Dimens.TouchMedium).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) { inner() }
        },
    )
}

/** 고르기 칸(Pilot 2 ˅ 목록): 지금 값과 ˅, 누르면 선택지. [selected]가 -1·null이면 "--". */
@Composable
internal fun ChoiceField(options: List<String>, selected: Int?, width: Dp = 168.dp, enabled: Boolean = true, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val ok = enabled && selected != null && selected >= 0
    Box {
        Row(
            Modifier.width(width).height(Dimens.TouchMedium).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = ok) { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.getOrNull(selected ?: -1) ?: "--", Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Icon(
                painterResource(R.drawable.ic_chevron_down), contentDescription = null, modifier = Modifier.size(14.dp),
                tint = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }, properties = MENU_PROPERTIES) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

/** 칸 나누기(Pilot 2 정지 · 회피 · 꺼짐). 고른 칸은 짙은 바탕 흰 글자, 나머지는 테두리만. */
@Composable
internal fun Segmented(options: List<String>, selected: Int?, enabled: Boolean = true, onPick: (Int) -> Unit) {
    Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.width(72.dp).height(Dimens.ButtonChip).clip(RoundedCornerShape(3.dp)).background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                    .clickable(enabled = enabled && selected != null) { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    o, fontSize = FontSize.Label, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
