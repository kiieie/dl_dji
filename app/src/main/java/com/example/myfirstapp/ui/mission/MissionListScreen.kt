package com.example.myfirstapp.ui.mission

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
) {
    val vm: MissionListViewModel = viewModel(factory = MissionListViewModel.factory(dao, projectId))
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.nav.collect { n ->
            when (n) {
                is MissionNav.Flight -> onFlight(n.missionId)
                is MissionNav.Replan -> onReplan(n.missionId)
            }
        }
    }
    MissionListContent(s, vm, onBack, onNewPlan, onResult)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionListContent(
    s: MissionListUiState,
    vm: MissionListViewModel,
    onBack: () -> Unit,
    onNewPlan: () -> Unit,
    onResult: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.projectName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "나가기") }
                },
                actions = { FilledTonalButton(onClick = onNewPlan, modifier = Modifier.padding(end = 16.dp)) { Text("+ 새 계획") } },
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
                items(s.missions, key = { it.id }) { m -> MissionCard(m, onFly = { vm.fly(m) }, onResult = { onResult(m.id) }) }
            }
        }
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

@Composable
private fun MissionCard(m: MissionSummary, onFly: () -> Unit, onResult: () -> Unit) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("미션 #${m.id} · ${STATUS_TITLE[m.status] ?: m.status}", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        "소티 ${m.sortieCount}", "완료 ${m.doneCount}/${m.poleCount}",
                        "남은 전주 ${m.remainingCount}".takeIf { m.remainingCount > 0 && m.status != "PARTIAL" },
                        m.lastFlightAt?.let { "마지막 비행 ${time(it)}" } ?: "계획 ${time(m.createdAt)}",
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { if (m.poleCount == 0) 0f else m.doneCount.toFloat() / m.poleCount },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Column(Modifier.width(160.dp)) {
                Text("검출 ${m.detectedCount}", color = if (m.detectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("1차 판독 ${m.reviewedCount}/${m.doneCount}")
            }
            if (m.doneCount > 0) OutlinedButton(onClick = onResult, modifier = Modifier.heightIn(min = 48.dp)) { Text("결과·판독") }
            when {
                m.status == "PLANNED" -> Button(onClick = onFly, modifier = Modifier.heightIn(min = 48.dp)) { Text("비행 시작") }
                m.remainingCount > 0 && m.status != "PARTIAL" -> Button(onClick = onFly, modifier = Modifier.heightIn(min = 48.dp)) { Text("이어하기") }
            }
        }
    }
}
