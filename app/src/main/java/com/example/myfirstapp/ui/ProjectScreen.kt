package com.example.myfirstapp.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.core.csv.CsvImportResult
import com.example.myfirstapp.core.csv.PoleCsvParser
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 10장 ① 프로젝트 목록(왼쪽)과 ② 데이터 가져오기(오른쪽). 가로 화면 한 장에 둔다. */
@Composable
fun ProjectScreen(
    dao: ProjectDao,
    onOpenProject: (ProjectSummary) -> Unit,
    onOpenResult: (ProjectSummary, Long) -> Unit,
    onOpenVerification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by remember(dao) { dao.observeProjects() }.collectAsState(initial = emptyList())
    var result by remember { mutableStateOf<CsvImportResult?>(null) }
    var projectName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            result = null
            message = null
            runCatching { withContext(Dispatchers.IO) { readCsv(context, uri) } }
                .onSuccess { (fileName, parsed) ->
                    projectName = fileName.substringBeforeLast('.')
                    result = parsed
                }
                .onFailure { message = "파일을 읽지 못했습니다: ${it.message}" }
        }
    }

    Row(modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("프로젝트", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenVerification) { Text("연동 검증") }
            }
            if (projects.isEmpty()) Text("프로젝트가 없습니다. 오른쪽에서 전주 CSV를 가져오세요.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { project ->
                    Card(onClick = { onOpenProject(project) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text("전주 ${project.poleCount}개 · ${DateFormat.getDateTimeInstance().format(Date(project.createdAt))}")
                            }
                            project.lastMissionId?.let { m -> TextButton(onClick = { onOpenResult(project, m) }) { Text("결과") } }
                            TextButton(onClick = { deleting = project }) { Text("삭제") }
                        }
                    }
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("전주 데이터 가져오기", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("CSV 파일 선택")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            when (val r = result) {
                null -> Text("열 이름 예: 전주번호, 위도, 경도, 선로명, 비고 (WGS84)")
                is CsvImportResult.HeaderError -> {
                    Text("필수 열을 찾지 못했습니다: ${r.missing.joinToString { it.label }}", color = MaterialTheme.colorScheme.error)
                    Text("파일의 열: ${r.header.joinToString()}")
                }
                is CsvImportResult.Success -> {
                    Text("인코딩 ${r.encoding} · 전주 ${r.poles.size}개 · 제외 ${r.issues.size}행")
                    OutlinedTextField(projectName, { projectName = it }, Modifier.fillMaxWidth(), label = { Text("프로젝트 이름") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = r.poles.isNotEmpty() && projectName.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    runCatching { dao.importProject(projectName.trim(), r.poles) }
                                        .onSuccess {
                                            message = "저장했습니다: ${projectName.trim()} (전주 ${r.poles.size}개)"
                                            result = null
                                        }
                                        .onFailure { message = "저장하지 못했습니다: ${it.message}" }
                                }
                            },
                        ) { Text("저장") }
                        OutlinedButton(onClick = { result = null }) { Text("취소") }
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

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("프로젝트 삭제") },
            text = { Text("${project.name} 프로젝트와 전주 ${project.poleCount}개를 삭제합니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { dao.deleteProject(project.id) }; deleting = null }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

private const val PREVIEW_ROWS = 50
private const val MAX_BYTES = 10L * 1024 * 1024

private val PoleCsvParser.Column.label
    get() = when (this) {
        PoleCsvParser.Column.POLE_ID -> "전주번호"
        PoleCsvParser.Column.LAT -> "위도"
        PoleCsvParser.Column.LON -> "경도"
        PoleCsvParser.Column.LINE_NAME -> "선로명"
        PoleCsvParser.Column.NOTE -> "비고"
    }

/** SAF로 고른 파일을 읽어 파싱한다. 파일 이름은 프로젝트 이름 기본값으로 쓴다. */
private fun readCsv(context: Context, uri: Uri): Pair<String, CsvImportResult> {
    val resolver = context.contentResolver
    var name = "가져온 전주"
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) {
            if (!it.isNull(0)) name = it.getString(0)
            if (!it.isNull(1)) size = it.getLong(1)
        }
    }
    require((size ?: 0) <= MAX_BYTES) { "10MB를 넘는 파일입니다" }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("파일을 열 수 없습니다")
    return name to PoleCsvParser.parse(bytes)
}
