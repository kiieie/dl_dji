package com.example.myfirstapp.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.example.myfirstapp.R
import com.example.myfirstapp.drone.AircraftStatus
import com.example.myfirstapp.drone.CheckItem
import com.example.myfirstapp.drone.djiAircraftCheck
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.myfirstapp.ui.FullscreenAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.example.myfirstapp.InspectionApp
import kotlinx.coroutines.flow.first
import com.example.myfirstapp.core.input.fiveD
import com.example.myfirstapp.core.input.moveIndex
import com.example.myfirstapp.core.port.RcButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import com.example.myfirstapp.drone.AircraftSetting
import com.example.myfirstapp.drone.CAMERA_SETTINGS_TAB
import com.example.myfirstapp.drone.djiAircraftConnected
import com.example.myfirstapp.drone.djiAircraftSettingTabs
import com.example.myfirstapp.ui.procedure.ChoiceField
import com.example.myfirstapp.ui.procedure.DecimalField
import com.example.myfirstapp.ui.procedure.HomeButton
import com.example.myfirstapp.ui.procedure.Segmented
import com.example.myfirstapp.ui.procedure.StatusIcon
import com.example.myfirstapp.ui.procedure.ValueBox
import kotlin.math.roundToInt
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Muted

/**
 * ⋯ 설정(10.5.6, Pilot 2 설정 메뉴 57쪽): 머리글 40dp(가운데 고른 탭 이름, Pilot 2 패널 제목), 왼쪽 160dp 탭, 오른쪽 내용. 밝은 바탕(`LightScreen`, 10.1).
 * 탭: 상태 점검(추가) · Pilot 2 탭(비행 컨트롤러 · 장애물 감지 · 조종기 · 이미지 전송 · 인텔리전트 배터리 정보 · 짐벌) · 카메라 설정(추가) · 일반 ·
 * 앱 설정(추가: 비행 기본값, 모델, 지도). 상태 점검은 기체 종류·시리얼·펌웨어, 비행 모드 F/N/S, 확인 필요 항목을 맨 위에 보인다.
 * 기체 탭은 SDK로 읽고 쓰고, 기체가 없으면 모의 값이다. [initialGeneral]이면 앱 설정 탭으로 연다(화면 ⚙).
 * [initialCamera]면 카메라 설정 탭으로 연다(카메라 뷰 카메라 설정 아이콘, 10.5.2).
 */
@Composable
fun SettingsHub(onBack: () -> Unit, initialGeneral: Boolean = false, initialCamera: Boolean = false) {
    val settings = (LocalContext.current.applicationContext as InspectionApp).settings
    val vm: AircraftSettingsViewModel = viewModel(
        factory = AircraftSettingsViewModel.factory({ djiAircraftSettingTabs(fake = !djiAircraftConnected(), general = appGeneralSettings(settings)) }) {
            djiAircraftCheck(settings.flightDefaults.first().rthAltitudeM)
        },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    // 0 상태 점검, 1..n 기체 탭, n+1 앱 설정
    val general = vm.tabs.size + 1
    val names = listOf("상태 점검") + vm.tabs.map { it.first } + "앱 설정"
    var tab by rememberSaveable { mutableIntStateOf(if (initialGeneral) general else 0) }
    var guide by rememberSaveable { mutableStateOf<String?>(null) }
    val c = s.check
    fun pick(i: Int) {
        tab = i
        if (i == 0) vm.recheck() else if (i < general) vm.openTab(i - 1)
    }
    LaunchedEffect(initialCamera) {
        if (initialCamera) vm.tabs.indexOfFirst { it.first == CAMERA_SETTINGS_TAB }.takeIf { it >= 0 }?.let { pick(it + 1) }
    }
    // RC 5D(10.4.3): ↕ 탭 고르기. 대화상자가 떠 있으면 받지 않는다(← 뒤로는 MainActivity 몫)
    val rc = (LocalContext.current.applicationContext as InspectionApp).rcButtons
    LaunchedEffect(vm, guide == null && s.confirming == null) {
        if (guide == null && s.confirming == null) rc.fiveD().collect { r ->
            r.actions.forEach { b ->
                if (b == RcButton.FIVE_D_UP || b == RcButton.FIVE_D_DOWN) {
                    val i = moveIndex(tab, if (b == RcButton.FIVE_D_UP) -1 else 1, general + 1)
                    if (i != tab) pick(i)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(Dimens.HeaderList).padding(start = 4.dp, end = 10.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSmall)) {
                    Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
                }
                com.example.myfirstapp.ui.AppBadge()
            }
            Text(names[tab], Modifier.align(Alignment.Center), fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
            Row(
                Modifier.align(Alignment.CenterEnd).height(26.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (c?.connected == true) MaterialTheme.colorScheme.tertiary else Muted))
                Text(if (c?.connected == true) "${c.model ?: "기체"} 연결됨" else "기체 연결 안 됨", fontSize = FontSize.Caption)
            }
        }
        Row(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.width(160.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .verticalScroll(rememberScrollState()).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                names.forEachIndexed { i, name ->
                    TabRow(name, tab == i, badge = if (i == 0) c?.issues?.takeIf { it > 0 } else null) { pick(i) }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                when (tab) {
                    0 -> StatusTab(s, vm, onGuide = { guide = it })
                    general -> SettingsScreen(Modifier.fillMaxSize())
                    else -> AircraftTab(vm.tabs[tab - 1].second, s, vm, Modifier.fillMaxSize())
                }
            }
        }
    }

    guide?.let { g ->
        FullscreenAlertDialog(
            onDismissRequest = { guide = null },
            title = { Text("보정 안내") },
            text = { Text(g) },
            confirmButton = { TextButton(onClick = { guide = null; vm.recheck() }) { Text("다시 점검") } },
            dismissButton = { TextButton(onClick = { guide = null }) { Text("닫기") } },
        )
    }
    s.confirming?.let { c ->
        FullscreenAlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(c.label) },
            text = { Text((c.kind as AircraftSetting.Kind.Action).confirm.orEmpty()) },
            confirmButton = { TextButton(onClick = vm::confirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("실행") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("취소") } },
        )
    }
}

/** 왼쪽 탭 한 줄 36dp. 고른 줄은 #2A2A2A + 왼쪽 3dp 파랑 막대, 확인 필요 수는 주황 배지. */
@Composable
private fun TabRow(name: String, selected: Boolean, badge: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.RowList).clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) Accent else Color.Transparent))
        Text(
            name, Modifier.weight(1f).padding(start = 8.dp), fontSize = FontSize.Body, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        badge?.let {
            Box(Modifier.padding(end = 8.dp).size(16.dp).clip(CircleShape).background(Warning), contentAlignment = Alignment.Center) {
                Text("$it", fontSize = FontSize.Micro, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

/** 상태 점검(10.4.3 ⑧): 요약 48dp, 기체·조종기 정보 30dp, 항목 40dp(확인 필요가 맨 위, [보정 안내]). */
@Composable
private fun StatusTab(s: AircraftSettingsUiState, vm: AircraftSettingsViewModel, onGuide: (String) -> Unit) {
    val c = s.check
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val issues = c?.issues ?: 0
            Box(
                Modifier.size(28.dp).clip(CircleShape).background((if (issues > 0) Warning else MaterialTheme.colorScheme.tertiary).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (issues > 0) R.drawable.ic_warning else R.drawable.ic_shield), contentDescription = null, modifier = Modifier.size(14.dp),
                    tint = if (issues > 0) Warning else MaterialTheme.colorScheme.tertiary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when { c == null -> "점검 중"; issues > 0 -> "확인 ${issues}건 · 비행 전에 처리"; else -> "모두 정상" },
                    fontSize = FontSize.Label, fontWeight = FontWeight.Bold,
                )
                Text(
                    s.checkedAt?.let { "마지막 점검 " + SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(it)) } ?: "기체에서 읽는 중",
                    fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = vm::recheck, enabled = !s.checking, modifier = Modifier.height(Dimens.TouchSmall), shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurface),
            ) { Text(if (s.checking) "점검 중" else "다시 점검", fontSize = FontSize.Caption) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoPair("기체", c?.model ?: "--", listOfNotNull(c?.serial?.let { "SN $it" }, c?.firmware?.let { "FW $it" }).joinToString(" · "))
            InfoPair("조종기", "RC Pro Enterprise", listOfNotNull(c?.rcSerial?.let { "SN $it" }, c?.rcFirmware?.let { "FW $it" }).joinToString(" · "))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val list = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().scrollbar(list), state = list, contentPadding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)) {
            items(c?.sorted.orEmpty(), key = { it.name }) { item -> CheckRow(item, c?.flightMode, onGuide) }
        }
    }
}

@Composable
private fun InfoPair(label: String, name: String, detail: String) {
    Text(
        buildAnnotatedString {
            append("$label ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)) { append(name) }
            if (detail.isNotEmpty()) withStyle(numberStyle(FontSize.Body, FontWeight.Medium).toSpanStyle()) { append("  $detail") }
        },
        fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** 점검 한 줄 40dp: 상태 점, 이름, 값(비행 모드는 F/N/S 칸), 판정 또는 [보정 안내]. 확인 필요 줄은 바탕을 밝게. */
@Composable
private fun CheckRow(item: CheckItem, mode: Char?, onGuide: (String) -> Unit) {
    val color = when (item.level) {
        AircraftStatus.Level.OK -> MaterialTheme.colorScheme.tertiary
        AircraftStatus.Level.WARN -> Warning
        AircraftStatus.Level.ERROR -> MaterialTheme.colorScheme.error
    }
    val bad = item.level != AircraftStatus.Level.OK
    Row(
        Modifier.fillMaxWidth().height(Dimens.RowTable).clip(RoundedCornerShape(4.dp)).background(if (bad) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(item.name, Modifier.width(96.dp), fontSize = FontSize.Body, fontWeight = if (bad) FontWeight.SemiBold else FontWeight.Normal, color = if (bad) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.name == "비행 모드") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf('F', 'N', 'S').forEach { m ->
                    val on = m == mode
                    Text(
                        "$m", Modifier.width(22.dp).background(if (on) (if (m == 'N') MaterialTheme.colorScheme.tertiary else Warning) else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp)),
                        style = numberStyle(FontSize.Label), color = if (on) Color.Black else Muted, textAlign = TextAlign.Center,
                    )
                }
                Text("자동 비행은 N에서만", Modifier.padding(start = 6.dp), fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(item.value, Modifier.weight(1f), fontSize = FontSize.Body, color = if (bad) color else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val g = item.guide
        if (g != null) {
            Button(onClick = { onGuide(g) }, modifier = Modifier.height(Dimens.ButtonChip), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("보정 안내", fontSize = FontSize.Caption, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(when (item.level) { AircraftStatus.Level.OK -> "정상"; AircraftStatus.Level.WARN -> "주의"; AircraftStatus.Level.ERROR -> "확인" }, fontSize = FontSize.Caption, color = color)
        }
    }
}

/** 기체 탭: 위 줄(읽는 중·결과·[다시 읽기]), Pilot 2 설정 줄. 방향 칸(장애물 감지 [수평|상방|하방])이 있으면 첫 칸 항목 앞에 칸 나누기, 고른 칸 항목만. */
@Composable
private fun AircraftTab(items: List<AircraftSetting>, s: AircraftSettingsUiState, vm: AircraftSettingsViewModel, modifier: Modifier) {
    val connected = s.check?.connected == true
    val groups = items.mapNotNull { it.group }.distinct()
    var group by rememberSaveable(groups.joinToString()) { mutableStateOf(groups.firstOrNull()) }
    val firstGrouped = items.indexOfFirst { it.group != null }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when { s.loading -> "기체에서 읽는 중…"; connected -> "기체 값"; else -> "모의 값(기체 연결 안 됨)" }, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            OutlinedButton(onClick = vm::reload) { Text("다시 읽기") }
        }
        items.forEachIndexed { i, item ->
            if (i == firstGrouped) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Segmented(groups, groups.indexOf(group)) { group = groups[it] }
                }
            }
            if (item.group == null || item.group == group) SettingRow(item, s.values[item.id], s.ranges[item.id], connected, vm)
        }
    }
}

/**
 * 설정 한 줄(Pilot 2): 위 구분선, 이름 · 오른쪽 조작, 아래 설명 글. 값을 못 읽으면(기체 미연결, 지원 안 함) "--"이고 바꿀 수 없다.
 * 동작은 Pilot 2처럼 가운데 파란 글자 줄, Pilot 2에서만 바꾸는 항목은 흐린 줄. 숫자 범위는 기체가 알려 준 [range](없으면 항목의 앱 범위).
 */
@Composable
private fun SettingRow(item: AircraftSetting, value: Any?, range: IntRange?, connected: Boolean, vm: AircraftSettingsViewModel) {
    val k = item.kind
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (k is AircraftSetting.Kind.Action) {
        TextButton(onClick = { vm.ask(item) }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.TouchLarge)) { Text(item.label, fontSize = FontSize.Label) }
        return
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            buildAnnotatedString {
                append(item.label)
                if (k is AircraftSetting.Kind.Decimal) withStyle(SpanStyle(color = muted, fontSize = FontSize.Caption)) { append(" (${rangeText(k.range.start, k.range.endInclusive, k.unit)})") }
            },
            Modifier.weight(1f), fontSize = FontSize.Label, maxLines = 2,
            color = if (k == AircraftSetting.Kind.Pilot2) MaterialTheme.colorScheme.outline else Color.Unspecified,
        )
        when (k) {
            is AircraftSetting.Kind.Number -> {
                val v = value as? Int
                val r = range ?: k.range
                if (k.slider) {
                    SliderValue(v?.toDouble(), r.first.toDouble()..r.last.toDouble(), { "${it.roundToInt()}${k.unit}" }) { vm.change(item, it.roundToInt().coerceIn(r)) }
                } else {
                    Text(rangeText(r.first, r.last, k.unit), fontSize = FontSize.Caption, color = muted)
                    if (v == null) ValueBox("--", 120.dp, enabled = false)
                    else DecimalField(v.toDouble(), r.first.toDouble()..r.last.toDouble(), 1.0, enabled = true, decimals = 0) { vm.change(item, it.roundToInt()) }
                }
            }
            is AircraftSetting.Kind.Decimal -> {
                val v = value as? Double
                SliderValue(v, k.range, null) { vm.change(item, snap(it, k.step, k.range)) }
                if (v == null) ValueBox("--", 64.dp, enabled = false) else DecimalField(v, k.range, k.step, enabled = true, width = 64.dp) { vm.change(item, it) }
            }
            AircraftSetting.Kind.Toggle -> Switch(checked = value == true, enabled = value is Boolean, onCheckedChange = { vm.change(item, it) })
            is AircraftSetting.Kind.Choice -> {
                val i = (value as? Int)?.takeIf { it >= 0 }
                if (k.dropdown) ChoiceField(k.options, i, enabled = i != null) { vm.change(item, it) } else Segmented(k.options, i) { vm.change(item, it) }
            }
            AircraftSetting.Kind.HomePoint -> {
                HomeButton(StatusIcon.AIRCRAFT, "H", "홈포인트를 기체 위치로", connected) { vm.change(item, 0) }
                HomeButton(StatusIcon.RC, "A", "홈포인트를 조종기 위치로", connected) { vm.change(item, 1) }
            }
            AircraftSetting.Kind.Pilot2 -> Text("Pilot 2에서 설정", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.outline)
            AircraftSetting.Kind.Info, is AircraftSetting.Kind.Range ->
                Text(value?.toString() ?: "--", Modifier.widthIn(max = 300.dp), fontSize = FontSize.Body, color = muted, textAlign = TextAlign.End, maxLines = 2)
            is AircraftSetting.Kind.Action -> Unit
        }
    }
    item.note?.let { Text(it, Modifier.padding(bottom = 6.dp), fontSize = FontSize.Caption, color = muted) }
}

/** 슬라이더(Pilot 2 짐벌 피치 평활도·장애물 거리): 손을 떼면 [onSet]. [text]가 있으면 오른쪽에 값 글. 값을 모르면 누를 수 없다. */
@Composable
private fun SliderValue(value: Double?, range: ClosedFloatingPointRange<Double>, text: ((Double) -> String)?, onSet: (Double) -> Unit) {
    var drag by remember(value) { mutableStateOf(value?.toFloat()) }
    Slider(
        value = drag ?: range.start.toFloat(), onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { onSet(it.toDouble()) } },
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(), enabled = value != null, modifier = Modifier.width(180.dp),
    )
    text?.let { Text(drag?.let { d -> it(d.toDouble()) } ?: "--", Modifier.width(48.dp), style = numberStyle(FontSize.Label), textAlign = TextAlign.End) }
}

/** 슬라이더 값을 값 칸과 같은 단위로 반올림해 범위 안에 넣는다. */
private fun snap(v: Double, step: Double, range: ClosedFloatingPointRange<Double>) = (Math.round(v / step) * step).coerceIn(range)

/** Pilot 2 범위 글 "20~1500 m", "1.0~10.0 m". */
private fun rangeText(from: Number, to: Number, unit: String) = "$from~$to${if (unit.isEmpty()) "" else " $unit"}"
