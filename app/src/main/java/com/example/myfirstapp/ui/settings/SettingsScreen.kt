package com.example.myfirstapp.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import com.example.myfirstapp.ui.FullscreenAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.Equipment
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.vworldAvailable
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.ShotMode
import com.example.myfirstapp.ui.Stepper

/**
 * ⑥ 설정의 [일반] 탭(10장, Pilot 2 일반 설정 자리): 비행 기본값, 탐지 모델, 오프라인 지도, 상태를 한 열 목록으로.
 * 탭 줄과 뒤로 가기는 [SettingsHub]에 있다. 로직은 [SettingsViewModel]에 있다.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            (context.applicationContext as InspectionApp).settings, context.getExternalFilesDir(null), context.contentResolver,
        ),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onModelFolderPicked) }
    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onMapFilePicked) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        Section("비행 기본값", "새 계획의 초기값입니다. 이미 저장한 미션은 계획할 때의 값을 그대로 씁니다.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::saveParams, enabled = s.canSave, modifier = Modifier.height(36.dp)) { Text("기본값 저장") }
                TextButton(onClick = vm::resetParams, modifier = Modifier.height(36.dp)) { Text("기본값으로 되돌리기", maxLines = 1) }
            }
            s.paramErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FlightDefaults(s.params, vm::updateParams)
        }
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
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            m.dir + if (m.dir == s.activeModel) " · 사용 중" else "",
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            m.error?.let { "규격 불일치: $it" } ?: "버전 ${m.version} · 라벨 ${m.labels}개",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (m.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.askDelete(PendingDelete.Model(m.dir)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
                }
            }
            OutlinedButton(onClick = { modelPicker.launch(null) }, enabled = !s.busy, modifier = Modifier.height(36.dp)) {
                Text("모델 폴더 가져오기")
            }
        }
        Section("배경 지도", "다음에 지도 화면을 열 때 적용됩니다 · 계획을 저장하면 미션 영역을 미리 받아 인터넷 없이도 보입니다.") {
            MapSourcePicker(s.mapSource, s.vworldLabels, vm::setMapSource, vm::setVworldLabels)
        }
        Section("오프라인 지도", "앱 폴더 maps/ · 계획·비행 지도에서 위성 배경 위에 겹쳐 그립니다.") {
            if (s.maps.isEmpty()) Text("지도 파일이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.maps.forEach { f ->
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatSize(f.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.askDelete(PendingDelete.Map(f.name)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
                }
            }
            OutlinedButton(onClick = { mapPicker.launch(arrayOf("*/*")) }, enabled = !s.busy, modifier = Modifier.height(36.dp)) {
                Text("지도 파일 추가")
            }
        }
        Section("상태", null) {
            StatusRow("LDM(로컬 데이터 모드)", "기체 연결 뒤 확인(PoC ④)")
            StatusRow("외부 데이터 전송", "없음 · 앱은 오프라인으로만 동작합니다")
        }
    }

    s.deleting?.let { target ->
        FullscreenAlertDialog(
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
    val rows: List<List<Triple<String, String, (Int) -> FlightParams>>> = listOf<List<Triple<String, String, (Int) -> FlightParams>>>(
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
            // 전주마다 대기는 두지 않는다: 방식 A는 멈추지 않고 촬영하고, 탐지는 뒤에서만 한다(2026-10-30 사용자 요청)
            Triple("가시권 반경", "${p.vlosRadiusM.toInt()}m") { d -> p.copy(vlosRadiusM = (p.vlosRadiusM + d * 100).coerceAtLeast(100.0)) },
        ),
    ) + if (p.shotMode == ShotMode.DIAGONAL4) {
        // 4방향 사선(5.6): 고도는 전선보다 10m 이상 높아야 저장된다
        listOf<List<Triple<String, String, (Int) -> FlightParams>>>(
            listOf(
                Triple("사선 고도", "${p.diagonalAltitudeM.toInt()}m") { d -> p.copy(diagonalAltitudeM = p.diagonalAltitudeM + d) },
                Triple("전주까지 거리", "${p.diagonalDistanceM.toInt()}m") { d -> p.copy(diagonalDistanceM = p.diagonalDistanceM + d) },
            ),
            listOf(
                Triple("조준 높이", "${"%.1f".format(p.aimHeightM)}m") { d -> p.copy(aimHeightM = p.aimHeightM + d * 0.5) },
                equipmentDrop(p, Equipment.TRANSFORMER),
            ),
            listOf(equipmentDrop(p, Equipment.SWITCH), equipmentDrop(p, Equipment.ARRESTER)),
        )
    } else {
        emptyList()
    }
    Text("촬영 방식", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ShotMode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = p.shotMode == mode,
                onClick = { onChange(p.copy(shotMode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(i, ShotMode.entries.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (mode == ShotMode.OVERHEAD) "전주 위" else "4방향 사선") }
        }
    }
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

/** 설비 조준 사진(5.6): 기기별로 머리에서 얼마나 내려 찍을지(0.5m 단위). 0에서 더 낮추면 끔(그 기기는 설비 사진 없음), 끔에서 높이면 0m. */
private fun equipmentDrop(p: FlightParams, e: Equipment): Triple<String, String, (Int) -> FlightParams> {
    val v = p.equipmentDropM[e]
    return Triple("${e.title} 조준(머리 아래)", v?.let { "${"%.1f".format(it)}m" } ?: "끔") { d ->
        val next = if (v == null) (if (d > 0) 0.0 else null) else (v + d * 0.5).takeIf { it >= 0.0 }?.coerceAtMost(10.0)
        p.copy(equipmentDropM = if (next == null) p.equipmentDropM - e else p.equipmentDropM + (e to next))
    }
}

/** 배경 지도 고르기(7.4): Bing 위성 / VWorld 위성 / VWorld 일반, VWorld 위성의 도로·지명 라벨. 키가 없으면 VWorld를 막는다. */
@Composable
private fun MapSourcePicker(source: MapSource, labels: Boolean, onSource: (MapSource) -> Unit, onLabels: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        MapSource.entries.forEachIndexed { i, src ->
            SegmentedButton(
                selected = source == src,
                onClick = { onSource(src) },
                enabled = src == MapSource.BING || vworldAvailable,
                shape = SegmentedButtonDefaults.itemShape(i, MapSource.entries.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(src.title) }
        }
    }
    if (!vworldAvailable) Text("VWorld 키 없음(secrets/vworld.properties)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("도로·지명 라벨(VWorld 위성)", Modifier.weight(1f))
        Switch(checked = labels, onCheckedChange = onLabels, enabled = source == MapSource.VWORLD_SATELLITE)
    }
}

private val LENS_NAMES = mapOf(Lens.WIDE to "광각", Lens.ZOOM to "망원", Lens.THERMAL to "열화상")

/** Pilot 2 설정처럼 바탕 없는 구역: 제목(강조색), 설명, 내용, 아래 구분선. */
@Composable
private fun Section(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatSize(bytes: Long) = if (bytes >= 1 shl 20) "%.1fMB".format(bytes / 1048576.0) else "${bytes / 1024}KB"
