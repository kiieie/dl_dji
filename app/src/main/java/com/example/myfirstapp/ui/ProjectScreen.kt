package com.example.myfirstapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.core.csv.CsvImportResult
import com.example.myfirstapp.core.csv.PoleCsvParser
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ProjectSummary
import java.text.DateFormat
import java.util.Date

/** 10장 ① 프로젝트 목록(왼쪽)과 ② 데이터 가져오기(오른쪽). 가로 화면 한 장에 둔다. */
@Composable
fun ProjectScreen(
    dao: ProjectDao,
    onOpenProject: (ProjectSummary) -> Unit,
    onOpenVerification: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val vm: ProjectViewModel = viewModel(factory = ProjectViewModel.factory(dao, resolver))
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onCsvPicked) }

    Row(modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("프로젝트", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenVerification) { Text("연동 검증") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("설정") }
            }
            if (s.projects.isEmpty()) Text("프로젝트가 없습니다. 오른쪽에서 전주 CSV를 가져오세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.projects, key = { it.id }) { project ->
                    Card(
                        onClick = { onOpenProject(project) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "전주 ${project.poleCount}개 · ${DateFormat.getDateTimeInstance().format(Date(project.createdAt))}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.askDelete(project) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
                        }
                    }
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("전주 데이터 가져오기", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("CSV 파일 선택")
            }
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            when (val r = s.result) {
                null -> Text("열 이름 예: 전주번호, 위도, 경도, 선로명, 비고 (WGS84)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                is CsvImportResult.HeaderError -> {
                    Text("필수 열을 찾지 못했습니다: ${r.missing.joinToString { it.label }}", color = MaterialTheme.colorScheme.error)
                    Text("파일의 열: ${r.header.joinToString()}")
                }
                is CsvImportResult.Success -> {
                    Text("인코딩 ${r.encoding} · 전주 ${r.poles.size}개 · 제외 ${r.issues.size}행")
                    OutlinedTextField(s.projectName, vm::setProjectName, Modifier.fillMaxWidth(), label = { Text("프로젝트 이름") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = s.canSave, onClick = vm::save) { Text("저장") }
                        OutlinedButton(onClick = vm::cancelImport) { Text("취소") }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f)) {
                        items(r.issues) { Text("${it.line}행 ${it.poleId.orEmpty()}: ${it.message}", color = MaterialTheme.colorScheme.error) }
                        items(r.poles.take(PREVIEW_ROWS)) { Text("${it.poleId}  ${it.position.lat}, ${it.position.lon}  ${it.lineName.orEmpty()}") }
                        if (r.poles.size > PREVIEW_ROWS) item { Text("… 외 ${r.poles.size - PREVIEW_ROWS}개") }
                    }
                }
            }
        }
    }

    s.deleting?.let { project ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("프로젝트 삭제") },
            text = { Text("${project.name} 프로젝트와 전주 ${project.poleCount}개를 삭제합니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDelete) { Text("취소") } },
        )
    }
}

private const val PREVIEW_ROWS = 50

private val PoleCsvParser.Column.label
    get() = when (this) {
        PoleCsvParser.Column.POLE_ID -> "전주번호"
        PoleCsvParser.Column.LAT -> "위도"
        PoleCsvParser.Column.LON -> "경도"
        PoleCsvParser.Column.LINE_NAME -> "선로명"
        PoleCsvParser.Column.NOTE -> "비고"
    }
