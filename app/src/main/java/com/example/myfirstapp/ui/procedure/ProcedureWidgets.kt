package com.example.myfirstapp.ui.procedure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.executor.PreflightCheck
import com.example.myfirstapp.ui.AppBadge
import com.example.myfirstapp.ui.MENU_PROPERTIES
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.ErrorContainer
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Inactive
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle

// 절차 화면(비행 전 점검·웨이포인트 경로 체크리스트, 10.5.5) 공통 조각. Pilot 2 녹화(p2s_pre, p2s_5·6, 0242)와 같은 배치를 어두운 바탕으로 그린다.

/** 머리글 44dp: 왼쪽 우리 표지, 가운데 제목, 오른쪽 ✕(Pilot 2). */
@Composable
internal fun ProcedureHeader(title: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp)) {
        AppBadge(Modifier.align(Alignment.CenterStart))
        Text(title, Modifier.align(Alignment.Center), fontSize = FontSize.Number, fontWeight = FontWeight.Bold)
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd).size(Dimens.TouchMedium).semantics { contentDescription = "닫기" }) {
            Text("✕", fontSize = FontSize.Number, color = Color.White)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 상태 줄 한 칸: 회색 이름 + 흰 값(경고면 [color]). */
@Composable
internal fun StatusCell(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = FontSize.Caption)) { append("$label ") }
            withStyle(numberStyle(FontSize.Number).toSpanStyle().copy(color = color)) { append(value) }
        },
        modifier, maxLines = 1,
    )
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

/** 주황 참고 배너(Pilot 2 "참고: 기체 암이…"). */
@Composable
internal fun CautionBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().background(Warning.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).border(1.dp, Warning, RoundedCornerShape(4.dp))
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
        Text(c.label, fontSize = FontSize.Body, color = TextSoft, maxLines = 1)
        Text(c.value, Modifier.weight(1f), fontSize = FontSize.Body, color = if (c.ok) Color.White else Error, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** 설정 한 줄(Pilot 2: 이름 · (범위) · 오른쪽 조작). 아래 구분선. */
@Composable
internal fun SettingLine(label: String, hint: String? = null, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.heightIn(min = 46.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            buildAnnotatedString {
                append(label)
                hint?.let { withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = FontSize.Caption)) { append("  $it") } }
            },
            Modifier.weight(1f), fontSize = FontSize.Label, maxLines = 1,
        )
        content()
    }
}

/**
 * Pilot 2 숫자 칸: −big −step 값 +step +big(또는 − 값 +). 값을 모르면 "--"이고 누를 수 없다. 범위 밖으로는 가지 않는다.
 */
@Composable
internal fun StepperCells(value: Int?, step: Int, big: Int?, range: IntRange, enabled: Boolean = true, onSet: (Int) -> Unit) {
    val steps = listOfNotNull(big?.let { -it }, -step) to listOfNotNull(step, big)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.first.forEach { d -> StepCell("$d", value != null && enabled) { value?.let { onSet((it + d).coerceIn(range)) } } }
        Box(Modifier.padding(horizontal = 2.dp).width(52.dp).height(Dimens.TouchMedium).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
            Text(value?.toString() ?: "--", style = numberStyle(FontSize.Number))
        }
        steps.second.forEach { d -> StepCell("+$d", value != null && enabled) { value?.let { onSet((it + d).coerceIn(range)) } } }
    }
}

@Composable
private fun StepCell(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(42.dp).height(Dimens.TouchMedium).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClickLabel = text, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = numberStyle(FontSize.Label), color = if (enabled) Color.White else Inactive) }
}

/** 고르기 칸(Pilot 2 ˅ 목록): 지금 값과 화살표, 누르면 선택지. [selected]가 -1·null이면 "--". */
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
            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null, modifier = Modifier.size(12.dp), tint = if (ok) Color.White else Inactive)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, properties = MENU_PROPERTIES) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

/** 칸 나누기(Pilot 2 정지 · 회피 · 꺼짐). 고른 칸은 흰 바탕 검은 글자. */
@Composable
internal fun Segmented(options: List<String>, selected: Int?, enabled: Boolean = true, onPick: (Int) -> Unit) {
    Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.width(72.dp).height(Dimens.ButtonChip).clip(RoundedCornerShape(3.dp)).background(if (on) Color.White else Color.Transparent)
                    .clickable(enabled = enabled && selected != null) { onPick(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = FontSize.Label, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Color.Black else Color.White) }
        }
    }
}
