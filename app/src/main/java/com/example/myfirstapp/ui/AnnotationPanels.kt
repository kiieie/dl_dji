package com.example.myfirstapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.Annotation
import com.example.myfirstapp.core.model.AnnotationColor
import com.example.myfirstapp.core.model.AnnotationKind
import com.example.myfirstapp.core.model.Annotations
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.SurfaceContainerHighest
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.numberStyle

// 핀포인트·지도 주석 화면 조각(10.4.7, Pilot 2 매뉴얼 p62~64와 11-04 녹화): 카메라 뷰 핀포인트 버튼, 지도 왼쪽 도구(핀포인트·선·영역·⊗),
// 길게 누름 설정(색 5종·목록), 핀포인트 목록, 선·영역 편집, 지도 핀포인트 카드. 상태와 저장은 [AnnotationViewModel].

internal val AnnotationColor.ui: Color get() = Color(hex.toColorInt())

/** Pilot 2 핀포인트 마름모(테두리만). */
@Composable
internal fun Diamond(c: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply { moveTo(w / 2, 0f); lineTo(w, w / 2); lineTo(w / 2, w); lineTo(0f, w / 2); close() }
        drawPath(p, c, style = Stroke(1.5.dp.toPx()))
    }
}

/**
 * 카메라 뷰 왼쪽 열 핀포인트(Pilot 2, 매뉴얼 p62): 누르면 지금 기체 위치·고도에 핀포인트(위성이 모자라면 "위치 레코딩 실패"),
 * 길게 누르면 설정(새 핀포인트 색 5종, 핀포인트 목록). C1은 광각/줌이라(10.5.10) 화면 버튼만 둔다.
 */
@Composable
internal fun PinpointButton(t: Telemetry?, modifier: Modifier) {
    val vm = annotationViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.size(Dimens.TouchSmall).clip(RoundedCornerShape(3.dp))
                .combinedClickable(onClickLabel = "핀포인트", onLongClickLabel = "핀포인트 설정", onLongClick = { settings = true }) { vm.pinAircraft(t) },
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_pinpoint), contentDescription = "핀포인트", modifier = Modifier.size(22.dp), tint = Accent) }
        if (settings) PinSettings(s, vm, onDismiss = { settings = false })
        AnnotationNotice(s.notice)
        if (s.listOpen) PinListPanel(s, vm, t?.position)
    }
}

/**
 * 지도 왼쪽 도구(Pilot 2 지도 뷰, 11-04 녹화 0151): 흰 칸에 핀포인트(누름 = 지도 가운데 십자선, 길게 = 설정) · 선 · 영역 · ⊗ 모든 주석 지우기.
 * 그리는 중인 도구는 검은 바탕 흰 아이콘이고, 다시 누르면 마친다.
 */
@Composable
internal fun AnnotationTools(s: AnnotationUiState, vm: AnnotationViewModel, aircraft: GeoPoint?, onPin: () -> Unit, modifier: Modifier) {
    var settings by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.width(Dimens.TouchSmall).clip(RoundedCornerShape(4.dp)).background(Color.White)) {
            Box(
                Modifier.size(Dimens.TouchSmall).combinedClickable(onClickLabel = "지도 가운데에 핀포인트", onLongClickLabel = "핀포인트 설정", onLongClick = { settings = true }, onClick = onPin),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_pinpoint), contentDescription = "핀포인트", modifier = Modifier.size(20.dp), tint = Accent) }
            listOf(AnnotationKind.LINE to R.drawable.ic_line, AnnotationKind.AREA to R.drawable.ic_area).forEach { (kind, icon) ->
                val on = s.draft?.kind == kind
                Box(
                    Modifier.size(Dimens.TouchSmall).background(if (on) Color.Black else Color.White)
                        .clickable(onClickLabel = if (on) "${kind.title} 마치기" else "${kind.title} 그리기") { if (on) vm.finishDraft() else vm.startDraft(kind) },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(icon), contentDescription = kind.title, modifier = Modifier.size(16.dp), tint = if (on) Color.White else Color.Black) }
            }
            val any = s.items.isNotEmpty() || s.draft != null
            Box(
                Modifier.size(Dimens.TouchSmall).clickable(enabled = any, onClickLabel = "모든 주석 지우기") { confirmClear = true },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_clear_all), contentDescription = "모든 주석 지우기", modifier = Modifier.size(18.dp), tint = if (any) Color.Black else Color(0xFFB0B0B0)) }
        }
        if (settings) PinSettings(s, vm, onDismiss = { settings = false })
        AnnotationNotice(s.notice)
        if (s.listOpen) PinListPanel(s, vm, aircraft)
    }
    if (confirmClear) FullscreenAlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("모든 주석 지우기") },
        text = { Text("핀포인트 ${s.pins.size}개와 선·영역 ${s.items.size - s.pins.size}개를 모두 지웁니다. 되돌릴 수 없습니다.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAll() }) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
    )
}

/** 안내(Pilot 2 "이용 가능한 위성이 충분하지 않습니다. 위치 레코딩 실패"): 왼쪽 열 오른쪽. 팝업이라 미션 칸 위에 보인다. */
@Composable
private fun AnnotationNotice(text: String?) {
    text ?: return
    Popup(offset = with(LocalDensity.current) { IntOffset(46.dp.roundToPx(), 55.dp.roundToPx()) }, properties = MENU_PROPERTIES) {
        Text(
            text, Modifier.width(Dimens.CardWidth + 64.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = FontSize.Body, color = Color.White,
        )
    }
}

/** 핀포인트 길게 누름(매뉴얼 p63 ①②): 새 핀포인트 색 5종, [모든 핀포인트 보기 >]. AR 표시 스위치는 AR(10.5.2) 뒤. */
@Composable
private fun PinSettings(s: AnnotationUiState, vm: AnnotationViewModel, onDismiss: () -> Unit) {
    Popup(offset = with(LocalDensity.current) { IntOffset(40.dp.roundToPx(), 0) }, onDismissRequest = onDismiss, properties = MENU_PROPERTIES) {
        Column(Modifier.width(290.dp).background(CardBg, RoundedCornerShape(4.dp))) {
            Row(Modifier.fillMaxWidth().height(Dimens.ButtonPrimary)) {
                AnnotationColor.entries.forEach { c ->
                    Box(
                        Modifier.weight(1f).height(Dimens.ButtonPrimary).background(if (c == s.pinColor) SurfaceContainerHighest else Color.Transparent)
                            .clickable(onClickLabel = "새 핀포인트 ${c.title}") { vm.setPinColor(c) },
                        contentAlignment = Alignment.Center,
                    ) { Diamond(c.ui, 18.dp) }
                }
            }
            HorizontalDivider(color = SurfaceContainerHighest)
            Row(
                Modifier.fillMaxWidth().height(Dimens.ButtonPrimary).clickable { onDismiss(); vm.openList(true) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("모든 핀포인트 보기", Modifier.weight(1f), fontSize = FontSize.Body, color = Color.White)
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
            }
        }
    }
}

/** 화면 오른쪽 위(상단 바 아래)에 붙는 패널 자리. 카메라 뷰·지도 어디서 열어도 같은 자리. */
private class RightPanelPosition(private val top: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(windowSize.width - popupContentSize.width, top)
}

private enum class PinSort(val title: String) { OLD("만든 순"), NEW("최근 순"), NAME("이름 순") }

/**
 * 핀포인트 목록(매뉴얼 p63 아래): 머리 "핀포인트"·✕, 색 거르기(여럿 고름)·정렬(만든 순/최근 순/이름 순), 줄마다 마름모·이름·휴지통.
 * 줄을 누르면 펼쳐 색 5종·이름 바꾸기·위도·경도·ALT·기체까지 거리. 내보내기는 미션 ZIP(4.3), AR 표시는 AR 뒤.
 */
@Composable
private fun PinListPanel(s: AnnotationUiState, vm: AnnotationViewModel, aircraft: GeoPoint?) {
    val density = LocalDensity.current
    // 상단 바 아래 화면 끝까지(팝업 안의 창 크기는 팝업 것이라 여기서 잰다)
    val height = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } - Dimens.TopBar
    var filter by rememberSaveable { mutableStateOf(emptySet<AnnotationColor>()) }
    var sort by rememberSaveable { mutableStateOf(PinSort.OLD) }
    var renaming by remember { mutableStateOf<Annotation?>(null) }
    val shown = s.pins.filter { filter.isEmpty() || it.color in filter }.let { l ->
        when (sort) {
            PinSort.OLD -> l
            PinSort.NEW -> l.reversed()
            PinSort.NAME -> l.sortedBy { it.name }
        }
    }
    Popup(popupPositionProvider = remember(density) { RightPanelPosition(with(density) { Dimens.TopBar.roundToPx() }) }, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(Dimens.SidePanel).height(height).background(CardBg)) {
            PanelHeader("핀포인트") { TextButton(onClick = { vm.openList(false) }) { Text("✕", fontSize = FontSize.Title, color = Color.White) } }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.border(1.dp, Accent, RoundedCornerShape(4.dp))) {
                    AnnotationColor.entries.forEach { c ->
                        val on = c in filter
                        Box(
                            Modifier.size(Dimens.TouchSmall).background(if (on) SurfaceContainerHighest else Color.Transparent)
                                .clickable(onClickLabel = "${c.title}만 보기") { filter = if (on) filter - c else filter + c },
                            contentAlignment = Alignment.Center,
                        ) { Diamond(c.ui, 14.dp) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { sort = PinSort.entries[(sort.ordinal + 1) % PinSort.entries.size] }) { Text(sort.title, fontSize = FontSize.Caption, color = Color.White) }
            }
            HorizontalDivider(color = SurfaceContainerHighest)
            if (shown.isEmpty()) Text(
                if (s.pins.isEmpty()) "핀포인트가 없습니다. 카메라 뷰 왼쪽 ◇를 누르면 지금 기체 위치에, 지도 왼쪽 ◇를 누르면 지도 가운데에 남깁니다." else "고른 색의 핀포인트가 없습니다.",
                Modifier.padding(12.dp), fontSize = FontSize.Body, color = TextSoft,
            )
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { p ->
                    val open = s.expanded == p.id
                    Row(
                        Modifier.fillMaxWidth().height(Dimens.ButtonPrimary).clickable(onClickLabel = "${p.name} 펼치기") { vm.expand(if (open) null else p.id) }.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Diamond(p.color.ui)
                        Text(p.name, Modifier.weight(1f), fontSize = FontSize.Body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(
                            Modifier.size(Dimens.ButtonPrimary).clickable(onClickLabel = "${p.name} 지우기") { vm.delete(p.id) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_delete), contentDescription = "지우기", modifier = Modifier.size(18.dp), tint = Color.White) }
                    }
                    if (open) Column(Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.25f))) {
                        Row(Modifier.fillMaxWidth()) {
                            AnnotationColor.entries.forEach { c ->
                                Box(
                                    Modifier.weight(1f).height(Dimens.TouchSmall).background(if (c == p.color) Accent else Color.Transparent)
                                        .clickable(onClickLabel = c.title) { vm.recolor(p.id, c) },
                                    contentAlignment = Alignment.Center,
                                ) { Diamond(if (c == p.color) Color.White else c.ui) }
                            }
                        }
                        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { pinFacts(p, aircraft).forEach { (k, v) -> Fact(k, v) } }
                            TextButton(onClick = { renaming = p }) { Text("이름 바꾸기", fontSize = FontSize.Caption, color = Color.White) }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { p -> RenameDialog(p.name, onDone = { vm.rename(p.id, it) }, onDismiss = { renaming = null }) }
}

/** 핀포인트 값: 위도·경도·ALT(이륙 지점 기준), 기체를 알면 수평 거리. */
private fun pinFacts(p: Annotation, aircraft: GeoPoint?): List<Pair<String, String>> = listOfNotNull(
    aircraft?.let { "거리" to "%.0f m".format(LocalProjection.distanceM(it, p.points[0])) },
    "ALT" to (p.altitudeM?.let { "%.1f m".format(it) } ?: "-"),
    "위도" to "%.6f".format(p.points[0].lat),
    "경도" to "%.6f".format(p.points[0].lon),
)

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, Modifier.width(40.dp), fontSize = FontSize.Caption, color = TextSoft)
        Text(value, style = numberStyle(FontSize.Body), color = Color.White)
    }
}

@Composable
private fun PanelHeader(title: String, trailing: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(Dimens.HeaderList)) {
        Text(title, Modifier.align(Alignment.Center), fontSize = FontSize.Title, fontWeight = FontWeight.Bold, color = Color.White)
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

@Composable
private fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    FullscreenAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text); onDismiss() }, enabled = text.isNotBlank()) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * 선·영역 편집(매뉴얼 p64 "선 편집"·"영역 편집"): 오른쪽 패널. 이름(파랑, 누르면 바꿈), 색 5종(원), 길이·넓이, 아래 [점 삭제] [선/영역 지우기], 오른쪽 위 노란 [확인].
 * 지도를 누르면 꼭짓점을 더하고, 꼭짓점을 누르면 고른다(점 삭제 대상).
 */
@Composable
internal fun DraftPanel(d: Annotation, vm: AnnotationViewModel, modifier: Modifier) {
    var renaming by remember { mutableStateOf(false) }
    Column(modifier.width(Dimens.SidePanel).background(CardBg)) {
        PanelHeader("${d.kind.title} 편집") { TextButton(onClick = vm::finishDraft) { Text("확인", fontSize = FontSize.Label, color = Caution) } }
        HorizontalDivider(color = SurfaceContainerHighest)
        Row(Modifier.fillMaxWidth().height(Dimens.ButtonPrimary).clickable(onClickLabel = "이름 바꾸기") { renaming = true }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("이름", Modifier.weight(1f), fontSize = FontSize.Body, color = Color.White)
            Text(d.name, fontSize = FontSize.Body, color = Accent, maxLines = 1)
        }
        HorizontalDivider(color = SurfaceContainerHighest)
        Text("색", Modifier.padding(start = 14.dp, top = 8.dp), fontSize = FontSize.Body, color = Color.White)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            AnnotationColor.entries.forEach { c ->
                Box(
                    Modifier.size(Dimens.TouchSmall).clip(CircleShape).clickable(onClickLabel = c.title) { vm.setDraftColor(c) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(c.ui).then(if (c == d.color) Modifier.border(BorderStroke(2.dp, Color.White), CircleShape) else Modifier))
                }
            }
        }
        HorizontalDivider(color = SurfaceContainerHighest)
        val measure = if (d.kind == AnnotationKind.LINE) "길이: %.1f m".format(Annotations.lengthM(d.points)) else "넓이: %.1f m²".format(Annotations.areaM2(d.points))
        Text(if (d.kind == AnnotationKind.LINE) "길이" else "넓이", Modifier.padding(start = 14.dp, top = 8.dp), fontSize = FontSize.Body, color = Color.White)
        Text(measure, Modifier.padding(start = 14.dp, top = 2.dp, bottom = 8.dp), style = numberStyle(FontSize.Caption), color = TextSoft)
        Text(
            if (d.complete) "지도를 누르면 고른 점 뒤에 점을 더합니다." else "지도를 눌러 점을 ${d.kind.minPoints}개 이상 찍으세요(지금 ${d.points.size}개).",
            Modifier.padding(horizontal = 14.dp).weight(1f), fontSize = FontSize.Caption, color = TextSoft,
        )
        Row(Modifier.fillMaxWidth().height(Dimens.ButtonPrimary)) {
            TextButton(onClick = vm::deleteVertex, enabled = d.points.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("점 삭제", fontSize = FontSize.Body, color = Color.White) }
            TextButton(onClick = vm::clearDraft, enabled = d.points.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("${d.kind.title} 지우기", fontSize = FontSize.Body, color = Color.White) }
        }
    }
    if (renaming) RenameDialog(d.name, onDone = vm::setDraftName, onDismiss = { renaming = false })
}

/** 지도에서 누른 핀포인트 카드(매뉴얼 p62 4c): 이름, 기체까지 거리, ALT, 위도·경도, [편집](목록에서 펼침) [삭제]. 홈포인트로 설정은 없음. */
@Composable
internal fun PinCard(p: Annotation, aircraft: GeoPoint?, vm: AnnotationViewModel, modifier: Modifier) {
    Column(modifier.width(Dimens.CardWidth).background(CardBg, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Diamond(p.color.ui)
            Text(p.name, Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("✕", Modifier.clickable(onClickLabel = "닫기") { vm.select(null) }.padding(horizontal = 6.dp), fontSize = FontSize.Body, color = TextSoft)
        }
        pinFacts(p, aircraft).forEach { (k, v) -> Fact(k, v) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.openList(true, expand = p.id) }, modifier = Modifier.height(Dimens.ButtonChip)) { Text("편집", fontSize = FontSize.Caption) }
            OutlinedButton(onClick = { vm.delete(p.id) }, modifier = Modifier.height(Dimens.ButtonChip)) { Text("삭제", fontSize = FontSize.Caption) }
        }
    }
}

/** 지도 가운데 십자선(Pilot 2 "-¦-"): 지도 뷰 핀포인트가 놓일 자리. */
@Composable
internal fun MapCrosshair(modifier: Modifier) {
    Canvas(modifier.size(24.dp).semantics { contentDescription = "지도 가운데" }) {
        val w = size.width
        val gap = w * 0.3f
        val stroke = 1.5.dp.toPx()
        listOf(0f to w / 2 - gap / 2, w / 2 + gap / 2 to w).forEach { (a, b) ->
            drawLine(Color.White, Offset(a, w / 2), Offset(b, w / 2), stroke)
            drawLine(Color.White, Offset(w / 2, a), Offset(w / 2, b), stroke)
        }
    }
}
