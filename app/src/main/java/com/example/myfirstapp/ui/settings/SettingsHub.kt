package com.example.myfirstapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
        Column(Modifier.width(220.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 설정") }
            (vm.tabs.map { it.first } + "일반").forEachIndexed { i, name ->
                NavigationDrawerItem(
                    label = { Text(name) },
                    selected = tab == i,
                    onClick = {
                        tab = i
                        if (i < vm.tabs.size) vm.openTab(i)
                    },
                )
            }
        }
        if (tab >= vm.tabs.size) {
            SettingsScreen(onBack = onBack)
        } else {
            AircraftTab(vm.tabs[tab].second, s, vm, Modifier.weight(1f))
        }
    }

    s.confirming?.let { c ->
        AlertDialog(
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
    Column(modifier.fillMaxHeight().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (s.loading) "기체에서 읽는 중…" else "기체 값", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedButton(onClick = vm::reload) { Text("다시 읽기") }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider()
                    SettingRow(item, s.values[item.label], vm)
                }
            }
        }
    }
}

/** 값을 못 읽으면(기체 미연결, 지원 안 함) "--"로 두고 바꿀 수 없게 한다. */
@Composable
private fun SettingRow(item: AircraftSetting, value: Any?, vm: AircraftSettingsViewModel) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.titleSmall)
            item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when (val k = item.kind) {
            is AircraftSetting.Kind.Number -> {
                val v = value as? Int
                Stepper("", v?.let { "$it${k.unit}" } ?: "--", Modifier.width(220.dp)) { d ->
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
