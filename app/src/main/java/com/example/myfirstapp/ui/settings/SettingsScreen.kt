package com.example.myfirstapp.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.ui.Stepper

/** ⑥ 설정(10장). 왼쪽은 비행 기본값, 오른쪽은 탐지 모델·오프라인 지도·상태. 로직은 [SettingsViewModel]에 있다. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            (context.applicationContext as InspectionApp).settings, context.getExternalFilesDir(null), context.contentResolver,
        ),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onModelFolderPicked) }
    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onMapFilePicked) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 뒤로") }
            Text("설정", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("비행 기본값", "새 계획의 초기값입니다. 이미 저장한 미션은 계획할 때의 값을 그대로 씁니다.") {
                    // 저장 버튼과 범위 오류는 카드 위쪽에 둔다. 아래에 두면 1080px 화면 밖으로 밀린다
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::saveParams, enabled = s.canSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("기본값 저장") }
                        OutlinedButton(onClick = vm::resetParams, modifier = Modifier.heightIn(min = 48.dp)) { Text("기본값으로 되돌리기") }
                    }
                    s.paramErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    FlightDefaults(s.params, vm::updateParams)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("탐지 모델", "앱 폴더 models/ · 고른 모델이 없거나 규격에 맞지 않으면 이름이 가장 뒤인 모델을 씁니다.") {
                    if (s.models.isEmpty()) Text("모델이 없습니다. 모의 탐지기(fake-1)로 비행합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.models.forEach { m ->
                        // 장갑 조작: 줄 전체를 눌러 고른다
                        Row(
                            Modifier.heightIn(min = 56.dp).selectable(
                                selected = m.dir == s.activeModel, enabled = m.error == null, role = Role.RadioButton,
                                onClick = { vm.selectModel(m.dir) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = m.dir == s.activeModel, onClick = null, enabled = m.error == null)
                            Column(Modifier.weight(1f)) {
                                Text(m.dir + if (m.dir == s.activeModel) " · 사용 중" else "", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    m.error?.let { "규격 불일치: $it" } ?: "버전 ${m.version} · 라벨 ${m.labels}개",
                                    color = if (m.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.askDelete(PendingDelete.Model(m.dir)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
                        }
                    }
                    OutlinedButton(onClick = { modelPicker.launch(null) }, enabled = !s.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("모델 폴더 가져오기")
                    }
                }
                Section("오프라인 지도", "앱 폴더 maps/ · 계획 화면에서 고릅니다.") {
                    if (s.maps.isEmpty()) Text("지도 파일이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.maps.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(formatSize(f.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.askDelete(PendingDelete.Map(f.name)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
                        }
                    }
                    OutlinedButton(onClick = { mapPicker.launch(arrayOf("*/*")) }, enabled = !s.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("지도 파일 추가")
                    }
                }
                Section("상태", null) {
                    StatusRow("LDM(로컬 데이터 모드)", "기체 연결 뒤 확인(PoC ④)")
                    StatusRow("외부 데이터 전송", "없음 · 앱은 오프라인으로만 동작합니다")
                }
            }
        }
    }

    s.deleting?.let { target ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text(if (target is PendingDelete.Model) "모델을 삭제할까요?" else "지도 파일을 삭제할까요?") },
            text = { Text("${target.name} 을(를) 앱 폴더에서 지웁니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                Button(
                    onClick = vm::confirmDelete,
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError),
                ) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDelete) { Text("취소") } },
        )
    }
}

/** F-09 비행 파라미터. 범위 검사는 [FlightParams.validate]가 한다(6.3). */
@Composable
private fun FlightDefaults(p: FlightParams, onChange: (FlightParams) -> Unit) {
    val rows: List<List<Triple<String, String, (Int) -> FlightParams>>> = listOf(
        listOf(
            Triple("이동 고도", "${p.transitAltitudeM.toInt()}m") { d -> p.copy(transitAltitudeM = p.transitAltitudeM + d * 5) },
            Triple("점검 고도", "${p.inspectAltitudeM.toInt()}m") { d -> p.copy(inspectAltitudeM = p.inspectAltitudeM + d * 5) },
        ),
        listOf(
            Triple("속도", "${p.speedMps.toInt()}m/s") { d -> p.copy(speedMps = p.speedMps + d) },
            Triple("예비율", "${p.batteryReservePct.toInt()}%") { d -> p.copy(batteryReservePct = p.batteryReservePct + d * 5) },
        ),
        listOf(
            Triple("오프셋 거리", "${p.offsetDistanceM.toInt()}m") { d -> p.copy(offsetDistanceM = (p.offsetDistanceM + d).coerceAtLeast(0.0)) },
            Triple("오프셋 방위", "${p.offsetBearingDeg.toInt()}°") { d -> p.copy(offsetBearingDeg = ((p.offsetBearingDeg + d * 15) % 360 + 360) % 360) },
        ),
        listOf(
            Triple("짐벌 피치", "${p.gimbalPitchDeg.toInt()}°") { d -> p.copy(gimbalPitchDeg = p.gimbalPitchDeg + d * 5) },
            Triple("대기 시간", "${p.waitSec}초") { d -> p.copy(waitSec = (p.waitSec + d).coerceAtLeast(0)) },
        ),
    )
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { (label, value, step) -> Stepper(label, value, Modifier.weight(1f)) { onChange(step(it)) } }
        }
    }
    Text("촬영 렌즈", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Lens.entries.forEach { lens ->
            val on = lens in p.lenses
            FilterChip(
                selected = on,
                onClick = { onChange(p.copy(lenses = if (on) p.lenses - lens else Lens.entries.filter { it in p.lenses || it == lens })) },
                label = { Text(LENS_NAMES.getValue(lens)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    Text("비행 중 다운로드", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        DownloadMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = p.downloadMode == mode,
                onClick = { onChange(p.copy(downloadMode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(i, DownloadMode.entries.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (mode == DownloadMode.PREVIEW) "미리보기(빠름)" else "원본") }
        }
    }
}

private val LENS_NAMES = mapOf(Lens.WIDE to "광각", Lens.ZOOM to "망원", Lens.THERMAL to "열화상")

@Composable
private fun Section(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatSize(bytes: Long) = if (bytes >= 1 shl 20) "%.1fMB".format(bytes / 1048576.0) else "${bytes / 1024}KB"
