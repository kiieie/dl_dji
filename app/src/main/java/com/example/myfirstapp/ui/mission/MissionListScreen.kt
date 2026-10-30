package com.example.myfirstapp.ui.mission

import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.R
import com.example.myfirstapp.data.MissionSummary
import com.example.myfirstapp.data.ProjectDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STATUS_TITLE = mapOf("PLANNED" to "계획됨", "IN_PROGRESS" to "진행 중", "DONE" to "완료", "PARTIAL" to "부분 완료")

private fun time(ms: Long) = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(ms))

/** 미션 목록 ①-1(10장): 프로젝트 안의 미션 카드와 [새 계획] [비행 시작] [이어하기] [결과·판독]. */
@Composable
fun MissionListScreen(
    projectId: Long,
    dao: ProjectDao,
    onBack: () -> Unit,
    onNewPlan: () -> Unit,
    onFlight: (missionId: Long) -> Unit,
    onReplan: (missionId: Long) -> Unit,
    onResult: (missionId: Long) -> Unit,
    /** 비행경로 화면 오른쪽에 넣을 때 true: 뒤로 버튼을 빼고 프로젝트마다 따로 ViewModel을 둔다. */
    embedded: Boolean = false,
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
    MissionListContent(s, vm, onBack.takeUnless { embedded }, onNewPlan, onResult)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionListContent(
    s: MissionListUiState,
    vm: MissionListViewModel,
    onBack: (() -> Unit)?,
    onNewPlan: () -> Unit,
    onResult: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.projectName) },
                navigationIcon = {
                    onBack?.let { IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "나가기") } }
                },
                actions = { Button(onClick = onNewPlan, modifier = Modifier.padding(end = 16.dp).heightIn(min = 48.dp)) { Text("+ 새 계획") } },
            )
        },
    ) { padding ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.missions.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("미션이 없습니다. [+ 새 계획]으로 점검 영역을 정하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.missions, key = { it.id }) { m -> MissionCard(m, onFly = { vm.fly(m) }, onResult = { onResult(m.id) }, onDelete = { vm.askDelete(m) }) }
            }
        }
    }
    s.deleting?.let { m ->
        AlertDialog(
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
        AlertDialog(
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

/** 미션 카드: 위에 상태·진행, 아래에 버튼(자주 쓰는 비행이 가장 크고, 삭제는 작게 오른쪽 끝). */
@Composable
private fun MissionCard(m: MissionSummary, onFly: () -> Unit, onResult: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("미션 #${m.id}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(STATUS_TITLE[m.status] ?: m.status, color = MaterialTheme.colorScheme.primary)
                Text(
                    m.lastFlightAt?.let { "마지막 비행 ${time(it)}" } ?: "계획 ${time(m.createdAt)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text("검출 ${m.detectedCount}", color = if (m.detectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text("판독 ${m.reviewedCount}/${m.doneCount}", maxLines = 1)
            }
            Text(
                listOfNotNull("완료 ${m.doneCount}/${m.poleCount}", "남은 ${m.remainingCount}".takeIf { m.remainingCount > 0 && m.status != "PARTIAL" }, "소티 ${m.sortieCount}")
                    .joinToString(" · "),
                maxLines = 1,
            )
            LinearProgressIndicator(progress = { if (m.poleCount == 0) 0f else m.doneCount.toFloat() / m.poleCount }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                when {
                    m.status == "PLANNED" -> Button(onClick = onFly, modifier = Modifier.heightIn(min = 52.dp)) { Text("비행 시작") }
                    m.remainingCount > 0 && m.status != "PARTIAL" -> Button(onClick = onFly, modifier = Modifier.heightIn(min = 52.dp)) { Text("이어하기") }
                }
                if (m.doneCount > 0) OutlinedButton(onClick = onResult, modifier = Modifier.heightIn(min = 52.dp)) { Text("결과·판독") }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
