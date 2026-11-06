package com.example.myfirstapp.ui

import androidx.activity.compose.BackHandler
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
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.data.copyZipsToTree
import com.example.myfirstapp.ui.mission.FiveDHints
import com.example.myfirstapp.ui.mission.LibraryDialogs
import com.example.myfirstapp.ui.mission.LibraryFilters
import com.example.myfirstapp.ui.mission.MissionGrid
import com.example.myfirstapp.ui.mission.RouteViewScreen
import com.example.myfirstapp.ui.mission.MissionListViewModel
import com.example.myfirstapp.ui.mission.MissionNav
import com.example.myfirstapp.ui.mission.SelectionBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import java.io.File
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.numberStyle
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize

/**
 * 라이브러리(10.5.8, Pilot 2 비행 경로 라이브러리 p2s_lib·0164): 머리글(`<` 표지 · 라이브러리 · + 🔍 ☑), 필터 줄(프로젝트(선로) · 상태 · 정렬),
 * 경로 카드 2열(경로 그림·이름·기체·시간, 우리 배지와 주 버튼, ⋯ 결과·다시 날기·즐겨찾기·삭제). ☑ 다중 선택: 전체 선택, 즐겨찾기·삭제·내보내기.
 * `+`: 새 계획 · 전주 파일 가져오기 · 경로 가져오기(KMZ/KML, 5.7), 프로젝트를 거르고 있으면 그 프로젝트의 속성 갱신 · 삭제.
 */
@Composable
fun ProjectScreen(
    dao: ProjectDao,
    /** 처음 거를 프로젝트(홈·복구·비행 화면 [라이브러리]에서 올 때). null이면 모두. */
    initialProjectId: Long? = null,
    onBack: () -> Unit,
    onNewPlan: (projectId: Long) -> Unit,
    onFlight: (projectId: Long, missionId: Long) -> Unit,
    onReplan: (projectId: Long, missionId: Long) -> Unit,
    onResult: (projectId: Long, missionId: Long) -> Unit,
    /** [다시 날기]: 같은 경로로 계획 화면. */
    onCopy: (projectId: Long, missionId: Long) -> Unit = { _, _ -> },
    /** ⋯ [비행 다시 보기](10.4.6). */
    onReplay: (missionId: Long) -> Unit = {},
    /** `+` 경로 가져오기(5.7): 맞은 프로젝트의 계획 화면을 그 파일로. */
    onImportRoute: (projectId: Long, uri: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val pvm: ProjectViewModel = viewModel(factory = ProjectViewModel.factory(dao, resolver))
    val ps by pvm.state.collectAsStateWithLifecycle()
    val files = context.getExternalFilesDir(null)
    val vm: MissionListViewModel = viewModel(
        factory = MissionListViewModel.factory(
            dao, initialProjectId, missionDir = { pid, mid -> files?.let { File(it, "projects/$pid/missions/$mid") } },
            copyToFolder = { tree, list -> copyZipsToTree(resolver, tree, list) }, mapThumbs = (context.applicationContext as InspectionApp).mapThumbs,
            mapsDir = files?.let { File(it, "maps") }, settings = (context.applicationContext as InspectionApp).settings,
        ),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(pvm::onCsvPicked) }
    val attrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(pvm::onAttrFilePicked) }
    val routePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(pvm::onRouteFilePicked) }
    LaunchedEffect(ps.routeImport) { ps.routeImport?.let { (pid, uri) -> pvm.onRouteOpened(); onImportRoute(pid, uri) } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> vm.onExportFolder(uri) }
    LaunchedEffect(s.exported) { if (s.exported.isNotEmpty()) folderPicker.launch(null) }
    LaunchedEffect(vm) {
        vm.nav.collect { n ->
            val m = n.mission
            when (n) {
                is MissionNav.Flight -> onFlight(m.projectId, m.id)
                is MissionNav.Replan -> onReplan(m.projectId, m.id)
                is MissionNav.Result -> onResult(m.projectId, m.id)
                is MissionNav.Copy -> onCopy(m.projectId, m.id)
                is MissionNav.Replay -> onReplay(m.id)
            }
        }
    }
    // 새로 가져온 프로젝트(전에 없던 id가 맨 위)면 그것으로 거른다. 거르던 프로젝트를 지우면 모두로
    var knownIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    LaunchedEffect(ps.projects) {
        val ids = ps.projects.map { it.id }.toLongArray()
        if (ids.isNotEmpty() && knownIds?.contains(ids[0]) == false) vm.setProject(ids[0])
        val f = s.projectFilter
        if (ids.isNotEmpty() && f != null && f !in ids) vm.setProject(null)
        knownIds = ids
    }
    val filtered = ps.projects.firstOrNull { it.id == s.projectFilter }
    // `+` 메뉴와 [새 계획] 프로젝트 고르기(화면 배치 상태)
    var addMenu by remember { mutableStateOf(false) }
    var choosePlan by remember { mutableStateOf(false) }
    // RC 5D(10.4.4): 대화상자·메뉴가 없을 때만 카드 고르기
    val rc = (context.applicationContext as InspectionApp).rcButtons
    val enabled5d = !ps.overlayOpen && !addMenu && !choosePlan
    LaunchedEffect(vm, enabled5d) { if (enabled5d) rc.collect(vm::on5d) }

    // 카드를 눌러 연 경로 보기(10.5.8): 뒤로(5D ← 포함)는 목록으로
    BackHandler(enabled = s.viewing != null, onBack = vm::closeView)
    val viewing = s.viewing
    if (viewing != null) RouteViewScreen(viewing, s, vm)
    else Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(Dimens.HeaderList).background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, end = 8.dp)) {
            if (s.selecting) {
                IconButton(onClick = { vm.setSelecting(false) }, modifier = Modifier.size(Dimens.TouchSmall).align(Alignment.CenterStart).semantics { contentDescription = "선택 끝내기" }) {
                    Text("✕", fontSize = FontSize.Number)
                }
                Text("비행 경로 라이브러리(${s.checked.size})", Modifier.align(Alignment.Center), fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
                TextButton(onClick = vm::checkAll, modifier = Modifier.align(Alignment.CenterEnd)) { Text("전체 선택") }
            } else {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSmall)) {
                        Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
                    }
                    AppBadge()
                }
                val q = s.query
                if (q == null) Text("라이브러리", Modifier.align(Alignment.Center), fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
                else SearchField(q, vm::setQuery, Modifier.align(Alignment.Center))
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        HeaderIcon(R.drawable.ic_add, "추가") { addMenu = true }
                        DropdownMenu(addMenu, onDismissRequest = { addMenu = false }, properties = MENU_PROPERTIES) {
                            DropdownMenuItem(
                                text = { Text(filtered?.let { "새 계획 · ${it.name}" } ?: "새 계획") },
                                enabled = ps.projects.isNotEmpty(),
                                onClick = { addMenu = false; filtered?.let { onNewPlan(it.id) } ?: run { choosePlan = true } },
                            )
                            DropdownMenuItem(text = { Text("전주 파일 가져오기(엑셀·CSV)") }, onClick = { addMenu = false; pvm.openImport() })
                            // Pilot 2 경로 가져오기(KMZ/KML, 5.7): MIME이 제각각이라 모든 파일에서 고른다
                            DropdownMenuItem(text = { Text("경로 가져오기(KMZ/KML)") }, enabled = ps.projects.isNotEmpty(), onClick = { addMenu = false; routePicker.launch(arrayOf("*/*")) })
                            filtered?.let { p ->
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("${p.name} 속성 갱신") }, onClick = { addMenu = false; pvm.openAttrUpdate(p) })
                                DropdownMenuItem(text = { Text("${p.name} 삭제", color = MaterialTheme.colorScheme.error) }, onClick = { addMenu = false; pvm.askDelete(p) })
                            }
                        }
                    }
                    HeaderIcon(R.drawable.ic_search, "검색") { vm.setQuery(if (s.query == null) "" else null) }
                    IconButton(onClick = { vm.setSelecting(true) }, modifier = Modifier.size(Dimens.TouchMedium).semantics { contentDescription = "여러 개 선택" }) {
                        Text("☑", fontSize = FontSize.Number)
                    }
                }
            }
        }
        LibraryFilters(s, ps.projects, vm)
        MissionGrid(s, vm, Modifier.weight(1f).fillMaxWidth())
        if (s.selecting) SelectionBar(s, vm)
        else FiveDHints(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 14.dp), listOf("5D ↕→" to "고르기", "5D ●" to "실행", "5D ←" to "뒤로"))
    }

    LibraryDialogs(s, vm)
    if (choosePlan) {
        FullscreenAlertDialog(
            onDismissRequest = { choosePlan = false },
            title = { Text("새 계획: 프로젝트 고르기") },
            text = {
                LazyColumn(Modifier.height(240.dp)) {
                    items(ps.projects, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().height(Dimens.RowList).clickable { choosePlan = false; onNewPlan(p.id) }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("전주 ${p.poleCount}", style = numberStyle(FontSize.Label, FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosePlan = false }) { Text("닫기") } },
        )
    }
    if (ps.importing) ImportDialog(ps, pvm, onPick = { picker.launch(arrayOf("*/*")) }, onClose = pvm::cancelImport)
    ps.attrTarget?.let { AttrUpdateDialog(it, ps, pvm, onPick = { attrPicker.launch(arrayOf("*/*")) }) }
    ps.notice?.let { notice ->
        FullscreenAlertDialog(
            onDismissRequest = pvm::dismissNotice,
            title = { Text(ps.noticeTitle) },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = pvm::dismissNotice) { Text("확인") } },
        )
    }
    ps.deleting?.let { project ->
        FullscreenAlertDialog(
            onDismissRequest = pvm::dismissDelete,
            title = { Text("프로젝트 삭제") },
            text = { Text("${project.name} 프로젝트와 전주 ${project.poleCount}개, 그 미션을 모두 삭제합니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = pvm::confirmDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = pvm::dismissDelete) { Text("취소") } },
        )
    }
}

/** 머리글 아이콘 버튼(Pilot 2 + 🔍). */
@Composable
private fun HeaderIcon(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(Dimens.TouchMedium).semantics { contentDescription = description }) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** 🔍 검색 칸: 프로젝트 이름·미션 번호. */
@Composable
private fun SearchField(value: String, onChange: (String?) -> Unit, modifier: Modifier) {
    Row(
        modifier.width(320.dp).height(Dimens.TouchSmall).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value, onValueChange = { onChange(it) }, Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = FontSize.Label), cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner -> if (value.isEmpty()) Text("프로젝트 이름·미션 번호", fontSize = FontSize.Label, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() },
        )
        IconButton(onClick = { onChange(null) }, modifier = Modifier.size(Dimens.TouchSmall).semantics { contentDescription = "검색 닫기" }) { Text("✕", fontSize = FontSize.Body) }
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
