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
import androidx.compose.ui.unit.sp
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.myfirstapp.drone.AircraftSetting
import com.example.myfirstapp.drone.djiAircraftSettingTabs
import com.example.myfirstapp.ui.Stepper

/**
 * 기체 점검·설정(10.4.3 ⑧, Pilot 2 설정 메뉴 57쪽): 머리글 40dp, 왼쪽 160dp 탭(상태 점검 · 기체 탭 · 일반), 오른쪽 내용.
 * 상태 점검은 기체 종류·시리얼·펌웨어, 비행 모드 F/N/S, 확인 필요 항목을 맨 위에 보인다. 기체 탭은 SDK로 읽고 쓰고,
 * [일반]은 앱 설정(비행 기본값, 모델, 지도)이다. [initialGeneral]이면 일반 탭으로 연다(화면 ⚙).
 */
@Composable
fun SettingsHub(onBack: () -> Unit, initialGeneral: Boolean = false) {
    val vm: AircraftSettingsViewModel = viewModel(factory = AircraftSettingsViewModel.factory(::djiAircraftSettingTabs, ::djiAircraftCheck))
    val s by vm.state.collectAsStateWithLifecycle()
    // 0 상태 점검, 1..n 기체 탭, n+1 일반
    val general = vm.tabs.size + 1
    var tab by rememberSaveable { mutableIntStateOf(if (initialGeneral) general else 0) }
    var guide by rememberSaveable { mutableStateOf<String?>(null) }
    val c = s.check

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(40.dp).padding(start = 4.dp, end = 10.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp).align(Alignment.CenterStart)) {
                Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
            }
            Text(if (tab == general) "설정" else "기체 점검", Modifier.align(Alignment.Center), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.align(Alignment.CenterEnd).height(26.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (c?.connected == true) MaterialTheme.colorScheme.tertiary else Color(0xFF8A8A8A)))
                Text(if (c?.connected == true) "${c.model ?: "기체"} 연결됨" else "기체 연결 안 됨", fontSize = 11.sp)
            }
        }
        Row(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.width(160.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .verticalScroll(rememberScrollState()).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                (listOf("상태 점검") + vm.tabs.map { it.first } + "일반").forEachIndexed { i, name ->
                    TabRow(name, tab == i, badge = if (i == 0) c?.issues?.takeIf { it > 0 } else null) {
                        tab = i
                        if (i == 0) vm.recheck() else if (i < general) vm.openTab(i - 1)
                    }
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
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) Accent else Color.Transparent))
        Text(
            name, Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Color.White else TextSoft,
        )
        badge?.let {
            Box(Modifier.padding(end = 8.dp).size(16.dp).clip(CircleShape).background(Warning), contentAlignment = Alignment.Center) {
                Text("$it", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
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
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    s.checkedAt?.let { "마지막 점검 " + SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(it)) } ?: "기체에서 읽는 중",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = vm::recheck, enabled = !s.checking, modifier = Modifier.height(32.dp), shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surfaceContainer, Color.White),
            ) { Text(if (s.checking) "점검 중" else "다시 점검", fontSize = 11.sp) }
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
            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(name) }
            if (detail.isNotEmpty()) withStyle(numberStyle(12.sp, FontWeight.Medium).toSpanStyle()) { append("  $detail") }
        },
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(4.dp)).background(if (bad) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(item.name, Modifier.width(96.dp), fontSize = 12.sp, fontWeight = if (bad) FontWeight.SemiBold else FontWeight.Normal, color = if (bad) Color.White else TextSoft)
        if (item.name == "비행 모드") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf('F', 'N', 'S').forEach { m ->
                    val on = m == mode
                    Text(
                        "$m", Modifier.width(22.dp).background(if (on) (if (m == 'N') MaterialTheme.colorScheme.tertiary else Warning) else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp)),
                        style = numberStyle(13.sp), color = if (on) Color.Black else Color(0xFF8A8A8A), textAlign = TextAlign.Center,
                    )
                }
                Text("자동 비행은 N에서만", Modifier.padding(start = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(item.value, Modifier.weight(1f), fontSize = 12.sp, color = if (bad) color else TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val g = item.guide
        if (g != null) {
            Button(onClick = { onGuide(g) }, modifier = Modifier.height(30.dp), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("보정 안내", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(when (item.level) { AircraftStatus.Level.OK -> "정상"; AircraftStatus.Level.WARN -> "주의"; AircraftStatus.Level.ERROR -> "확인" }, fontSize = 11.sp, color = color)
        }
    }
}

@Composable
private fun AircraftTab(items: List<AircraftSetting>, s: AircraftSettingsUiState, vm: AircraftSettingsViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (s.loading) "기체에서 읽는 중…" else "기체 값", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            OutlinedButton(onClick = vm::reload) { Text("다시 읽기") }
        }
        // Pilot 2 설정 목록: 한 줄에 이름·값, 줄 사이 구분선
        items.forEach { item ->
            HorizontalDivider()
            SettingRow(item, s.values[item.label], vm)
        }
    }
}

/** 값을 못 읽으면(기체 미연결, 지원 안 함) "--"로 두고 바꿀 수 없게 한다. */
@Composable
private fun SettingRow(item: AircraftSetting, value: Any?, vm: AircraftSettingsViewModel) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge)
            item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when (val k = item.kind) {
            is AircraftSetting.Kind.Number -> {
                val v = value as? Int
                Stepper("", v?.let { "$it${k.unit}" } ?: "--", Modifier.width(200.dp)) { d ->
                    if (v != null) vm.change(item, (v + d * k.step).coerceIn(k.range))
                }
            }
            AircraftSetting.Kind.Toggle -> Switch(checked = value == true, enabled = value is Boolean, onCheckedChange = { vm.change(item, it) })
            is AircraftSetting.Kind.Choice -> SingleChoiceSegmentedButtonRow {
                k.options.forEachIndexed { i, name ->
                    SegmentedButton(
                        selected = value == i, enabled = value is Int && value >= 0, onClick = { vm.change(item, i) },
                        shape = SegmentedButtonDefaults.itemShape(i, k.options.size),
                    ) { Text(name) }
                }
            }
            AircraftSetting.Kind.Info -> Text(value?.toString() ?: "--", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is AircraftSetting.Kind.Action -> Button(onClick = { vm.ask(item) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("실행") }
        }
    }
}
