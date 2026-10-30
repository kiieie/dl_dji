package com.example.myfirstapp.ui.mission

import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.myfirstapp.ui.FullscreenAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.MissionSummary
import com.example.myfirstapp.data.ProjectDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val STATUS_TITLE = mapOf("PLANNED" to "계획됨", "IN_PROGRESS" to "진행 중", "DONE" to "완료", "PARTIAL" to "부분 완료")

private fun time(ms: Long) = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(ms))

/** 미션 목록 ①-1(10장): 프로젝트 안의 미션 카드와 [새 계획] [비행 시작] [이어하기] [결과·판독]. */
@Composable
fun MissionListScreen(
    projectId: Long,
    dao: ProjectDao,
    onNewPlan: () -> Unit,
    onFlight: (missionId: Long) -> Unit,
    onReplan: (missionId: Long) -> Unit,
    onResult: (missionId: Long) -> Unit,
) {
    val files = LocalContext.current.getExternalFilesDir(null)
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
            }
        }
    }
    MissionListContent(s, vm, onNewPlan, onResult)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionListContent(
    s: MissionListUiState,
    vm: MissionListViewModel,
    onNewPlan: () -> Unit,
    onResult: (Long) -> Unit,
) {
    Scaffold(
        // 얇은 머리글(Pilot 2 비행경로 라이브러리, 매뉴얼 56쪽): 프로젝트 이름 · 미션 수 · [+ 새 계획]
        topBar = {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(s.projectName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("미션 ${s.missions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Button(onClick = onNewPlan, modifier = Modifier.height(COMPACT_BUTTON), contentPadding = COMPACT_PADDING) { Text("+ 새 계획") }
            }
        },
    ) { padding ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.missions.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("미션이 없습니다. [+ 새 계획]으로 점검 영역을 정하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(s.missions, key = { it.id }) { m -> MissionCard(m, onFly = { vm.fly(m) }, onResult = { onResult(m.id) }, onDelete = { vm.askDelete(m) }) }
            }
        }
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

/** 버튼은 보이는 높이 36dp로 통일한다(누르는 영역은 Material3가 48dp로 잡아 준다, 10.1 장갑 조작). */
internal val COMPACT_BUTTON = 36.dp
internal val COMPACT_PADDING = PaddingValues(horizontal = 14.dp)

/**
 * 미션 카드(Pilot 2 비행경로 라이브러리 카드, 매뉴얼 56쪽): 왼쪽에 이름·상태·시각과 진행 한 줄·얇은 막대,
 * 오른쪽에 작은 버튼(비행이 강조, 결과, 삭제는 흐리게).
 */
@Composable
private fun MissionCard(m: MissionSummary, onFly: () -> Unit, onResult: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("미션 #${m.id}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        STATUS_TITLE[m.status] ?: m.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Text(
                        m.lastFlightAt?.let { "비행 ${time(it)}" } ?: "계획 ${time(m.createdAt)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                Text(
                    listOfNotNull(
                        "전주 ${m.doneCount}/${m.poleCount}",
                        "남은 ${m.remainingCount}".takeIf { m.remainingCount > 0 && m.status != "PARTIAL" },
                        "소티 ${m.sortieCount}",
                        "검출 ${m.detectedCount}",
                        "판독 ${m.reviewedCount}/${m.doneCount}",
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { if (m.poleCount == 0) 0f else m.doneCount.toFloat() / m.poleCount },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    drawStopIndicator = {},
                )
            }
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    m.status == "PLANNED" -> Button(onClick = onFly, modifier = Modifier.height(COMPACT_BUTTON), contentPadding = COMPACT_PADDING) { Text("▶ 비행") }
                    m.remainingCount > 0 && m.status != "PARTIAL" -> Button(onClick = onFly, modifier = Modifier.height(COMPACT_BUTTON), contentPadding = COMPACT_PADDING) { Text("▶ 이어하기") }
                }
                if (m.doneCount > 0) OutlinedButton(onClick = onResult, modifier = Modifier.height(COMPACT_BUTTON), contentPadding = COMPACT_PADDING) { Text("결과") }
                TextButton(onClick = onDelete, modifier = Modifier.height(COMPACT_BUTTON), contentPadding = COMPACT_PADDING) {
                    Text("삭제", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
