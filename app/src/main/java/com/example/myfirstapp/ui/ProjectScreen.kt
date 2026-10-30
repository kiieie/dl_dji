package com.example.myfirstapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.myfirstapp.ui.FullscreenAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.core.csv.CsvImportResult
import com.example.myfirstapp.core.csv.PoleCsvParser
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ProjectSummary
import com.example.myfirstapp.ui.mission.MissionListScreen
import java.text.DateFormat
import java.util.Date

/**
 * 비행경로 라이브러리(Pilot 2 비행경로, 10.0): 한 화면에 왼쪽 프로젝트 목록, 오른쪽 고른 프로젝트의 미션(마스터-디테일).
 * 전주 파일 가져오기는 자주 쓰지 않으므로 [+ 전주 파일] 대화상자로 둔다.
 */
@Composable
fun ProjectScreen(
    dao: ProjectDao,
    onBack: () -> Unit,
    onNewPlan: (projectId: Long) -> Unit,
    onFlight: (projectId: Long, missionId: Long) -> Unit,
    onReplan: (projectId: Long, missionId: Long) -> Unit,
    onResult: (projectId: Long, missionId: Long) -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val vm: ProjectViewModel = viewModel(factory = ProjectViewModel.factory(dao, resolver))
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onCsvPicked) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    // 새 프로젝트를 가져오면 그것을 고른다. 고른 것이 없거나 지워졌으면 맨 위
    LaunchedEffect(s.projects.firstOrNull()?.id) { s.projects.firstOrNull()?.let { selectedId = it.id } }
    val selected = s.projects.firstOrNull { it.id == selectedId } ?: s.projects.firstOrNull()

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(260.dp).fillMaxHeight().padding(start = 4.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                Text("비행경로", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { importing = true }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+ 전주 파일") }
            }
            if (s.projects.isEmpty()) Text(
                "프로젝트가 없습니다. [+ 전주 파일]로 전주 CSV를 가져오세요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp),
            )
            val listState = rememberLazyListState()
            LaunchedEffect(s.projects.firstOrNull()?.id) { listState.scrollToItem(0) }
            LazyColumn(state = listState, modifier = Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.projects, key = { it.id }) { p -> ProjectRow(p, p.id == selected?.id, onClick = { selectedId = p.id }, onDelete = { vm.askDelete(p) }) }
            }
        }
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (selected != null) {
                MissionListScreen(
                    selected.id, dao, onBack = onBack, embedded = true,
                    onNewPlan = { onNewPlan(selected.id) },
                    onFlight = { onFlight(selected.id, it) },
                    onReplan = { onReplan(selected.id, it) },
                    onResult = { onResult(selected.id, it) },
                )
            }
        }
    }

    if (importing || s.result != null) {
        ImportDialog(
            s, vm,
            onPick = { picker.launch(arrayOf("*/*")) },
            onClose = {
                importing = false
                vm.cancelImport()
            },
        )
    }

    s.deleting?.let { project ->
        FullscreenAlertDialog(
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

@Composable
private fun ProjectRow(p: ProjectSummary, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    // 고른 프로젝트는 왼쪽 강조 막대와 밝은 바탕(Pilot 2 라이브러리의 선택 카드 테두리 자리)
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "전주 ${p.poleCount}개 · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(p.createdAt))}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            TextButton(onClick = onDelete, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("삭제", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 전주 파일 가져오기(F-01): 파일 고르기 → 미리보기·이름 → 저장. 저장하면 닫힌다. */
@Composable
private fun ImportDialog(s: ProjectUiState, vm: ProjectViewModel, onPick: () -> Unit, onClose: () -> Unit) {
    FullscreenAlertDialog(
        onDismissRequest = onClose,
        title = { Text("전주 파일 가져오기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("CSV 파일 선택") }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                when (val r = s.result) {
                    null -> Text("열 이름 예: 전주번호, 위도, 경도, 선로명, 비고 (WGS84)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is CsvImportResult.HeaderError -> {
                        Text("필수 열을 찾지 못했습니다: ${r.missing.joinToString { it.label }}", color = MaterialTheme.colorScheme.error)
                        Text("파일의 열: ${r.header.joinToString()}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    is CsvImportResult.Success -> {
                        Text("인코딩 ${r.encoding} · 전주 ${r.poles.size}개 · 제외 ${r.issues.size}행")
                        OutlinedTextField(s.projectName, vm::setProjectName, Modifier.fillMaxWidth(), label = { Text("프로젝트 이름") }, singleLine = true)
                        r.issues.take(3).forEach { Text("${it.line}행 ${it.poleId.orEmpty()}: ${it.message}", color = MaterialTheme.colorScheme.error, maxLines = 1) }
                        r.poles.take(3).forEach {
                            Text("${it.poleId}  ${it.position.lat}, ${it.position.lon}  ${it.lineName.orEmpty()}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = s.canSave, onClick = { vm.save() }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

private val PoleCsvParser.Column.label
    get() = when (this) {
        PoleCsvParser.Column.POLE_ID -> "전주번호"
        PoleCsvParser.Column.LAT -> "위도"
        PoleCsvParser.Column.LON -> "경도"
        PoleCsvParser.Column.LINE_NAME -> "선로명"
        PoleCsvParser.Column.NOTE -> "비고"
    }
