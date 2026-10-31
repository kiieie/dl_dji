package com.example.myfirstapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.R
import com.example.myfirstapp.core.csv.CsvImportResult
import com.example.myfirstapp.core.csv.PoleCsvParser
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ProjectSummary
import com.example.myfirstapp.ui.mission.MissionListScreen
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.numberStyle
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize

/**
 * 비행경로(10.4.3 시안): 머리글 아래 왼쪽 프로젝트 목록(216dp, 최신순 한 줄), 오른쪽 고른 프로젝트의 미션 표.
 * 전주 파일 가져오기는 자주 쓰지 않으므로 [전주 파일] 대화상자로 둔다. 속성 갱신·삭제는 줄의 ⋯ 메뉴(삭제는 길게 누르기도).
 */
@Composable
fun ProjectScreen(
    dao: ProjectDao,
    /** 처음 고를 프로젝트(홈·복구에서 미션 비행으로 갈 때). null이면 맨 위. */
    initialProjectId: Long? = null,
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
    val attrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onAttrFilePicked) }
    var selectedId by rememberSaveable { mutableStateOf(initialProjectId) }
    // 새로 가져온 프로젝트(전에 없던 id가 맨 위)면 그것을 고른다. 처음 읽을 때, 다른 화면에서 돌아올 때, 맨 위를 지울 때는 고른 것을 둔다
    var knownIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    LaunchedEffect(s.projects) {
        val ids = s.projects.map { it.id }.ifEmpty { return@LaunchedEffect }.toLongArray()
        if (knownIds?.contains(ids[0]) == false) selectedId = ids[0]
        knownIds = ids
    }
    val selected = s.projects.firstOrNull { it.id == selectedId } ?: s.projects.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        // 머리글 40dp(10.4.2): 뒤로 · 가운데 제목 · [전주 파일] [새 계획]
        Box(Modifier.fillMaxWidth().height(Dimens.HeaderList).padding(start = 4.dp, end = 10.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSmall).align(Alignment.CenterStart)) {
                Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
            }
            Text("비행경로", Modifier.align(Alignment.Center), fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderButton(R.drawable.ic_import, "전주 파일", onClick = vm::openImport)
                HeaderButton(R.drawable.ic_add, "새 계획", enabled = selected != null) { selected?.let { onNewPlan(it.id) } }
            }
        }
        Row(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.width(216.dp).fillMaxHeight(), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Row(Modifier.fillMaxWidth().height(Dimens.HeaderSection).padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("프로젝트 · 최신순", Modifier.weight(1f), fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("전주", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (s.projects.isEmpty()) Text(
                        "프로젝트가 없습니다. [전주 파일]로 배전 설비 엑셀·CSV를 가져오세요.", fontSize = FontSize.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp),
                    )
                    val listState = rememberLazyListState()
                    LaunchedEffect(s.projects.firstOrNull()?.id) { listState.scrollToItem(0) }
                    LazyColumn(
                        Modifier.fillMaxSize().scrollbar(listState), state = listState,
                        contentPadding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(s.projects, key = { it.id }) { p ->
                            ProjectRow(
                                p, p.id == selected?.id, menuOpen = s.menuFor == p.id, onClick = { selectedId = p.id },
                                onMenu = { vm.openMenu(if (it) p.id else null) }, onAttrUpdate = { vm.openAttrUpdate(p) }, onDelete = { vm.askDelete(p) },
                            )
                        }
                    }
                }
            }
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                if (selected != null) {
                    MissionListScreen(
                        selected.id, dao,
                        onFlight = { onFlight(selected.id, it) },
                        onReplan = { onReplan(selected.id, it) },
                        onResult = { onResult(selected.id, it) },
                        enabled5d = !s.overlayOpen,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (s.importing) ImportDialog(s, vm, onPick = { picker.launch(arrayOf("*/*")) }, onClose = vm::cancelImport)
    s.attrTarget?.let { AttrUpdateDialog(it, s, vm, onPick = { attrPicker.launch(arrayOf("*/*")) }) }

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

/** 머리글 보조 버튼(30dp, #1C1C1C). */
@Composable
private fun HeaderButton(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.height(Dimens.ButtonChip), shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surfaceContainer, Color.White),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = FontSize.Caption)
    }
}

/**
 * 프로젝트 한 줄(36dp): 이름, 전주 수, ⋯ 메뉴([전주 파일로 속성 갱신] [삭제]). 고른 줄은 #2A2A2A + 왼쪽 3dp 파랑 막대.
 * 길게 눌러도 삭제를 묻는다. 메뉴 열림은 ViewModel이 들고 있어 열린 동안 5D를 받지 않는다.
 */
@Composable
private fun ProjectRow(
    p: ProjectSummary, selected: Boolean, menuOpen: Boolean, onClick: () -> Unit,
    onMenu: (Boolean) -> Unit, onAttrUpdate: () -> Unit, onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.RowList).clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onDelete),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) Accent else Color.Transparent))
        Text(
            p.name, Modifier.weight(1f).padding(start = 8.dp), fontSize = FontSize.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Color.White else TextSoft,
        )
        Text(
            "${p.poleCount}", Modifier.padding(start = 8.dp), style = numberStyle(FontSize.Label, FontWeight.Medium),
            color = if (selected) TextSoft else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { onMenu(true) }, modifier = Modifier.size(Dimens.TouchMedium).semantics { contentDescription = "더 보기" }) { Text("⋯", fontSize = FontSize.Number, color = TextSoft) }
            DropdownMenu(menuOpen, onDismissRequest = { onMenu(false) }, properties = MENU_PROPERTIES) {
                DropdownMenuItem(text = { Text("전주 파일로 속성 갱신") }, onClick = onAttrUpdate)
                DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, onClick = { onMenu(false); onDelete() })
            }
        }
    }
}

/** 전주 속성 갱신(4.5.7): 파일 고르기 → 바뀔·새·없는 전주 수 미리보기 → [갱신]. 좌표·미션·사진·판독은 그대로 둔다. */
@Composable
private fun AttrUpdateDialog(p: ProjectSummary, s: ProjectUiState, vm: ProjectViewModel, onPick: () -> Unit) {
    FullscreenAlertDialog(
        onDismissRequest = vm::closeAttrUpdate,
        title = { Text("${p.name} 속성 갱신") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonStd)) { Text("엑셀·CSV 파일 선택") }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (val d = s.attrDiff) {
                    null -> Text("전산화번호로 전주를 맞춰 고/저압·설치기기·규격 등 속성만 바꿉니다. 좌표·미션·사진·판독은 그대로입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Text("바뀔 전주 ${d.changed.size}개")
                        Text("파일에만 있는 새 전주 ${d.newInFile}개(추가하지 않음)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("파일에 없는 전주 ${d.missingInFile}개(그대로 둠)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { Button(enabled = !s.attrDiff?.changed.isNullOrEmpty(), onClick = vm::confirmAttrUpdate) { Text("갱신") } },
        dismissButton = { TextButton(onClick = vm::closeAttrUpdate) { Text("닫기") } },
    )
}

/** 전주 파일 가져오기(F-01): 파일 고르기 → 미리보기·이름 → 저장. 저장하면 닫힌다. */
@Composable
private fun ImportDialog(s: ProjectUiState, vm: ProjectViewModel, onPick: () -> Unit, onClose: () -> Unit) {
    FullscreenAlertDialog(
        onDismissRequest = onClose,
        title = { Text("전주 파일 가져오기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonStd)) { Text("엑셀·CSV 파일 선택") }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                when (val r = s.result) {
                    null -> Text("배전 설비 엑셀(전산화번호, 선로번호, 전주규격, 설치기기, 경도·위도) 또는 CSV(전주번호, 위도, 경도)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is CsvImportResult.HeaderError -> {
                        Text("필수 열을 찾지 못했습니다: ${r.missing.joinToString { it.label }}", color = MaterialTheme.colorScheme.error)
                        Text("파일의 열: ${r.header.joinToString()}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    is CsvImportResult.Success -> {
                        Text("${r.encoding} · 전주 ${r.poles.size}개 · 제외 ${r.issues.size}행")
                        r.poles.count { it.guy }.takeIf { it > 0 }?.let { Text("지선주 ${it}개는 계획에서 기본 제외", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        OutlinedTextField(s.projectName, vm::setProjectName, Modifier.fillMaxWidth(), label = { Text("프로젝트 이름") }, singleLine = true)
                        r.issues.take(3).forEach { Text("${it.line}행 ${it.poleId.orEmpty()}: ${it.message}", color = MaterialTheme.colorScheme.error, maxLines = 1) }
                        r.poles.take(3).forEach {
                            Text("${it.displayName}  ${it.poleId}  ${"%.5f, %.5f".format(it.position.lat, it.position.lon)}  ${it.lengthM?.let { m -> "${m.toInt()}M" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
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
        else -> name
    }
