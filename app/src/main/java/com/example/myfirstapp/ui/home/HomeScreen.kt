package com.example.myfirstapp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.drone.AircraftStatus
import com.example.myfirstapp.drone.djiAircraftStatus
import com.example.myfirstapp.ui.flight.PoleDone
import com.example.myfirstapp.ui.flight.PoleFailed
import com.example.myfirstapp.ui.flight.PolePending

/**
 * 홈(Pilot 2 홈페이지, 10.0 사용성 원칙): 자주 쓰는 것은 크게.
 * 위: 앱 이름, 기체 상태 칩(누르면 세부), 작은 [설정] [연동 검증].
 * 왼쪽: 최근 미션 카드와 큰 [이어서 비행]/[비행 시작], [결과·판독]. 오른쪽: 큰 [카메라 뷰로 들어가기], 중간 [비행경로] [앨범].
 */
@Composable
fun HomeScreen(
    dao: ProjectDao,
    onRoutes: () -> Unit,
    onAlbum: (projectId: Long, missionId: Long) -> Unit,
    onFlight: (projectId: Long, missionId: Long) -> Unit,
    onSettings: () -> Unit,
    onVerification: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(dao, ::djiAircraftStatus))
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val latest = s.latest
    var showAircraft by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("전주 점검", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            AircraftChip(s.aircraft, Modifier.weight(1f)) { showAircraft = true }
            TextButton(onClick = onVerification) { Text("연동 검증") }
            OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("⚙ 설정") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // 최근 미션: 현장에서 가장 많이 누르는 곳
            Card(Modifier.weight(1.2f).fillMaxHeight(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(20.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("최근 미션", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (latest == null) {
                        Text("아직 미션이 없습니다", style = MaterialTheme.typography.titleLarge)
                        Text("[비행경로]에서 전주 파일을 가져오고 계획을 세우세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            "${latest.projectName} · #${latest.missionId}", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text("완료 ${latest.done} / ${latest.total} · 남은 전주 ${latest.remaining}", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { if (latest.total == 0) 0f else latest.done.toFloat() / latest.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(Modifier.weight(1f))
                    if (latest != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (latest.remaining > 0) {
                                Button(
                                    onClick = { onFlight(latest.projectId, latest.missionId) },
                                    modifier = Modifier.weight(1f).height(72.dp), shape = RoundedCornerShape(12.dp),
                                ) { Text(if (latest.done == 0) "비행 시작" else "이어서 비행", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                            }
                            FilledTonalButton(
                                onClick = { onAlbum(latest.projectId, latest.missionId) }, enabled = latest.done > 0,
                                modifier = Modifier.height(72.dp), shape = RoundedCornerShape(12.dp),
                            ) { Text("결과·판독", fontSize = 18.sp) }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { if (latest != null && latest.remaining > 0) onFlight(latest.projectId, latest.missionId) else onRoutes() },
                    modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(12.dp),
                ) { Text("카메라 뷰로 들어가기", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalButton(onClick = onRoutes, modifier = Modifier.weight(1f).height(72.dp), shape = RoundedCornerShape(12.dp)) {
                        Text("비행경로", fontSize = 18.sp)
                    }
                    FilledTonalButton(
                        onClick = { latest?.let { onAlbum(it.projectId, it.missionId) } }, enabled = latest != null,
                        modifier = Modifier.weight(1f).height(72.dp), shape = RoundedCornerShape(12.dp),
                    ) { Text("앨범", fontSize = 18.sp) }
                }
            }
        }
    }

    if (showAircraft) AircraftDialog(s.aircraft) { showAircraft = false }
}

/** 기체 상태 칩(Pilot 2 상태 관리 요약): 상태색 점, 기종, 주의 이유. 누르면 세부. */
@Composable
private fun AircraftChip(a: AircraftStatus, modifier: Modifier, onClick: () -> Unit) {
    val (color, label) = statusOf(a)
    Card(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Text(a.model ?: "기체 연결 안 됨", fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            a.batteryPct?.let { Text("배터리 $it%", maxLines = 1) }
            Text("›", fontSize = 20.sp)
        }
    }
}

@Composable
private fun statusOf(a: AircraftStatus) = when (a.level) {
    null -> MaterialTheme.colorScheme.outline to "조종기만 연결됨"
    AircraftStatus.Level.OK -> PoleDone to "정상 · 이륙 가능"
    AircraftStatus.Level.WARN -> PolePending to "주의: ${a.issues.joinToString(", ")}"
    AircraftStatus.Level.ERROR -> PoleFailed to "확인 필요: ${a.issues.joinToString(", ")}"
}

/** 기체 세부(Pilot 2 상태 관리 화면 대신): 값과 확인할 일. */
@Composable
private fun AircraftDialog(a: AircraftStatus, onClose: () -> Unit) {
    val (color, label) = statusOf(a)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(a.model ?: "기체 연결 안 됨") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = color, fontWeight = FontWeight.Bold)
                Item("배터리", a.batteryPct?.let { "$it%" })
                Item("GNSS 위성", a.satellites?.let { "${it}개" })
                Item("SD 카드", a.sdFreeMb?.let { if (a.sdState == "NORMAL") "${it / 1024}GB 남음" else a.sdState })
                Item("LDM(로컬 데이터 모드)", if (a.ldm) "켜짐" else "꺼짐")
                Item("시뮬레이터", if (a.simulator) "켜짐" else "꺼짐")
                Text("기체 설정은 ⚙ 설정의 기체 탭에서 바꿉니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

@Composable
private fun Item(label: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "--", fontWeight = FontWeight.Bold)
    }
}
