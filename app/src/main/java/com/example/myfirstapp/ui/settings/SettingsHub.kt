package com.example.myfirstapp.ui.settings

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
 * 설정(Pilot 2 설정 메뉴, 매뉴얼 57쪽): 왼쪽 탭 목록, 오른쪽 내용. 기체 탭은 SDK로 읽고 쓰고,
 * 마지막 [일반] 탭은 앱 설정(비행 기본값, 모델, 지도)이다. 현장에서 Pilot 2 없이 본 앱만 쓰기 위한 화면(10장).
 */
@Composable
fun SettingsHub(onBack: () -> Unit) {
    val vm: AircraftSettingsViewModel = viewModel(factory = AircraftSettingsViewModel.factory(::djiAircraftSettingTabs))
    val s by vm.state.collectAsStateWithLifecycle()
    // 마지막 칸이 [일반]. 처음에는 일반을 연다(기체 없이도 쓰는 값)
    var tab by rememberSaveable { mutableIntStateOf(vm.tabs.size) }

    Row(Modifier.fillMaxSize()) {
        // 왼쪽 탭 줄(Pilot 2 설정 탭, 매뉴얼 57쪽 8번): 화면 높이(432dp)에 다 들어가지 않을 수 있어 스크롤한다
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(168.dp).fillMaxHeight()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("‹ 설정", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                }
                (vm.tabs.map { it.first } + "일반").forEachIndexed { i, name ->
                    val selected = tab == i
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .selectable(selected, role = Role.Tab) {
                                tab = i
                                if (i < vm.tabs.size) vm.openTab(i)
                            }
                            .wrapContentHeight().padding(horizontal = 16.dp),
                    )
                }
            }
        }
        if (tab >= vm.tabs.size) {
            SettingsScreen(Modifier.weight(1f))
        } else {
            AircraftTab(vm.tabs[tab].second, s, vm, Modifier.weight(1f))
        }
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

@Composable
private fun AircraftTab(items: List<AircraftSetting>, s: AircraftSettingsUiState, vm: AircraftSettingsViewModel, modifier: Modifier) {
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
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
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
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
