package com.example.myfirstapp.ui.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.data.MissionSummary
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.MENU_PROPERTIES
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize

internal val STATUS_TITLE = mapOf("PLANNED" to "계획됨", "IN_PROGRESS" to "진행 중", "DONE" to "완료", "PARTIAL" to "부분 완료", "REPLACED" to "대체됨")

/** 상태 색(10.4.1): 계획됨 파랑, 진행 중·부분 완료 주황, 완료 초록, 새 계획으로 대체됨 회색. */
@Composable
internal fun statusColor(status: String) = when (status) {
    "DONE" -> MaterialTheme.colorScheme.tertiary
    "IN_PROGRESS", "PARTIAL" -> Warning
    "REPLACED" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Accent
}

private fun time(ms: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(ms))

/**
 * 미션 표(10.4.3 비행경로 오른쪽): 미션·상태·전주·소티·사진·계획일, 최신이 맨 위.
 * 고른 줄에만 채운 주 버튼, 나머지 줄은 회색 버튼과 ⋯(결과·삭제). 5D ↕ 고르기, ● 주 동작.
 */
@Composable
fun MissionListScreen(
    projectId: Long,
    dao: ProjectDao,
    onFlight: (missionId: Long) -> Unit,
    onReplan: (missionId: Long) -> Unit,
    onResult: (missionId: Long) -> Unit,
    /** 바깥 대화상자(가져오기·프로젝트 삭제)가 떠 있으면 false: 5D를 받지 않는다. */
    enabled5d: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val files = context.getExternalFilesDir(null)
    val vm: MissionListViewModel = viewModel(
        key = "missions-$projectId",
        factory = MissionListViewModel.factory(dao, projectId) { mid -> files?.let { File(it, "projects/$projectId/missions/$mid") } },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.nav.collect { n ->
            when (n) {
                is MissionNav.Flight -> onFlight(n.missionId)
                is MissionNav.Replan -> onReplan(n.missionId)
                is MissionNav.Result -> onResult(n.missionId)
            }
        }
    }
    // 5D는 이 표가 화면에 있을 때만, 고른 프로젝트의 ViewModel 하나에만 넘긴다
    val rc = (context.applicationContext as InspectionApp).rcButtons
    LaunchedEffect(vm, enabled5d) { if (enabled5d) rc.collect(vm::on5d) }
    MissionListContent(s, vm, onResult, modifier)
}

/** 열 너비: 미션, 상태, 전주, 소티, 사진(계획일은 남는 폭, 버튼 116dp). */
private val COLS = listOf(48.dp, 76.dp, 64.dp, 44.dp, 44.dp)

@Composable
private fun MissionListContent(s: MissionListUiState, vm: MissionListViewModel, onResult: (Long) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(Dimens.HeaderSection).padding(start = 15.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("미션", "상태", "전주", "소티", "사진").forEachIndexed { i, t -> HeadText(t, Modifier.width(COLS[i])) }
            HeadText("계획일", Modifier.weight(1f))
            Spacer(Modifier.width(116.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            s.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.missions.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("미션이 없습니다. [새 계획]으로 점검 영역을 정하세요.", fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val list = rememberLazyListState()
                val selected = s.selected
                // 5D로 고른 줄이 화면 밖이면 따라간다
                LaunchedEffect(selected?.id) {
                    val i = s.missions.indexOf(selected)
                    val visible = list.layoutInfo.visibleItemsInfo
                    if (i >= 0 && visible.isNotEmpty() && (i <= visible.first().index || i >= visible.last().index)) list.animateScrollToItem(i)
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().scrollbar(list),
                    state = list, contentPadding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(s.missions, key = { it.id }) { m ->
                        MissionRow(
                            m, m.id == selected?.id,
                            menuOpen = s.menuFor == m.id, onMenu = { open -> vm.setMenu(m.takeIf { open }) },
                            onSelect = { vm.select(m) }, onRun = { vm.run(m) }, onResult = { onResult(m.id) }, onDelete = { vm.askDelete(m) },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FiveDHints(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 14.dp))
    }
    s.deleting?.let { m ->
        FullscreenAlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("미션 #${m.id} 삭제") },
            text = { Text("계획(소티 ${m.sortieCount}개, 전주 ${m.poleCount}개)과 사진 ${m.doneCount}개 전주의 기록·판독을 모두 지웁니다. 되돌릴 수 없습니다. 필요하면 먼저 결과를 내보내세요.") },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDelete) { Text("취소") } },
        )
    }
    s.confirm?.let { m ->
        FullscreenAlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text("마지막 비행이 오늘이 아닙니다") },
            text = {
                Text(
                    "미션 #${m.id}의 마지막 비행은 ${time(m.lastFlightAt!!)}입니다. 남은 전주는 ${m.remainingCount}개입니다.\n" +
                        "다른 날·다른 장소라면 이륙 지점을 다시 정해 남은 전주로 새 계획을 세우세요.",
                )
            },
            confirmButton = { Button(onClick = vm::confirmContinue) { Text("이어서 비행") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::dismissConfirm) { Text("취소") }
                    OutlinedButton(onClick = vm::confirmReplan) { Text("남은 전주로 새 계획") }
                }
            },
        )
    }
}

@Composable
private fun HeadText(text: String, modifier: Modifier) = Text(text, modifier, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 목록 아래 5D 안내(10.4.2). */
@Composable
internal fun FiveDHints(modifier: Modifier, hints: List<Pair<String, String>> = listOf("5D ↕" to "고르기", "5D ●" to "실행", "5D ←" to "뒤로")) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        hints.forEach { (k, v) ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TextSoft, fontWeight = FontWeight.SemiBold)) { append(k) }
                    append(" $v")
                },
                fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 미션 한 줄(40dp). 고른 줄은 #2A2A2A 바탕과 왼쪽 3dp 파랑 막대(10.4.1). */
@Composable
private fun MissionRow(
    m: MissionSummary, selected: Boolean, menuOpen: Boolean, onMenu: (Boolean) -> Unit,
    onSelect: () -> Unit, onRun: () -> Unit, onResult: () -> Unit, onDelete: () -> Unit,
) {
    val action = m.primaryAction()
    val photos = remember(m.flightParams, m.poleCount) { m.poleCount * flightParamsFromJson(m.flightParams).photosPerPole() }
    val text = if (selected) Color.White else TextSoft
    Surface(
        onClick = onSelect, modifier = Modifier.fillMaxWidth().height(Dimens.RowTable), shape = RoundedCornerShape(4.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) Accent else Color.Transparent))
            Text("#${m.id}", Modifier.width(COLS[0]).padding(start = 8.dp), style = numberStyle(FontSize.Number), color = text)
            Row(Modifier.width(COLS[1]), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val c = statusColor(m.status)
                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                Text(STATUS_TITLE[m.status] ?: m.status, fontSize = FontSize.Caption, color = c, maxLines = 1)
            }
            Text(
                buildAnnotatedString {
                    append("${m.doneCount}")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("/${m.poleCount}") }
                },
                Modifier.width(COLS[2]), style = numberStyle(FontSize.Title), color = text,
            )
            Text("${m.sortieCount}", Modifier.width(COLS[3]), style = numberStyle(FontSize.Title), color = text)
            Text("$photos", Modifier.width(COLS[4]), style = numberStyle(FontSize.Title), color = text)
            Text(
                time(m.createdAt), Modifier.weight(1f), style = numberStyle(FontSize.Label, FontWeight.Medium), maxLines = 1,
                color = if (selected) TextSoft else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.width(116.dp), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (action != null) {
                    Button(
                        onClick = onRun, modifier = Modifier.width(84.dp).height(Dimens.ButtonChip), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(0.dp),
                        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surfaceContainer, Color.White),
                    ) { Text(action.label, fontSize = FontSize.Body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
                }
                MoreMenu(showResult = m.doneCount > 0 && action != MissionAction.RESULT, menuOpen, onMenu, onResult, onDelete)
            }
        }
    }
}

/** ⋯ 메뉴: 결과(주 버튼이 결과가 아닐 때), 삭제. 열림 상태는 ViewModel이 들고 있어 열린 동안 5D를 받지 않는다. */
@Composable
private fun MoreMenu(showResult: Boolean, open: Boolean, onOpen: (Boolean) -> Unit, onResult: () -> Unit, onDelete: () -> Unit) {
    Box {
        IconButton(onClick = { onOpen(true) }, modifier = Modifier.size(Dimens.TouchMedium).semantics { contentDescription = "더 보기" }) { Text("⋯", fontSize = FontSize.Number, color = TextSoft) }
        DropdownMenu(open, onDismissRequest = { onOpen(false) }, properties = MENU_PROPERTIES) {
            if (showResult) DropdownMenuItem(text = { Text("결과") }, onClick = { onOpen(false); onResult() })
            DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, onClick = { onOpen(false); onDelete() })
        }
    }
}
