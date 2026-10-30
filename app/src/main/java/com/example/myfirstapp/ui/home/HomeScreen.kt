package com.example.myfirstapp.ui.home

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.myfirstapp.ui.FullscreenAlertDialog
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
 * 왼쪽: 최근 미션 카드와 큰 [이어서 비행]/[비행 시작](자동 점검 비행), [결과·판독].
 * 오른쪽: 큰 [수동 비행](Pilot 2 카메라 뷰, 미션 없이 스틱으로), 중간 [비행경로] [앨범].
 */
@Composable
fun HomeScreen(
    dao: ProjectDao,
    onRoutes: () -> Unit,
    onAlbum: (projectId: Long, missionId: Long) -> Unit,
    onFlight: (projectId: Long, missionId: Long) -> Unit,
    onCamera: () -> Unit,
    onSettings: () -> Unit,
    onVerification: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(dao, ::djiAircraftStatus))
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val latest = s.latest
    var showAircraft by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 소속 표시: 공식 로고 파일을 받으면 이미지(res/drawable/kepco_logo)로 바꾼다. 로고 모양을 흉내 내 그리지 않는다
            Column {
                Text("KEPCO", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = KepcoBlue, letterSpacing = 2.sp)
                Text("전주 점검", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            AircraftChip(s.aircraft, Modifier.weight(1f)) { showAircraft = true }
            TextButton(onClick = onVerification, modifier = Modifier.height(36.dp)) { Text("연동 검증", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = onSettings, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("⚙ 설정") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 최근 미션: 현장에서 가장 많이 누르는 곳
            Surface(Modifier.weight(1.2f).fillMaxHeight(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("최근 미션", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (latest == null) {
                        Text("아직 미션이 없습니다", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("[비행경로]에서 전주 파일을 가져오고 계획을 세우세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            "${latest.projectName} · #${latest.missionId}", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text("완료 ${latest.done} / ${latest.total} · 남은 전주 ${latest.remaining}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(
                            progress = { if (latest.total == 0) 0f else latest.done.toFloat() / latest.total },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            drawStopIndicator = {},
                        )
                    }
                    Box(Modifier.weight(1f))
                    if (latest != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 홈에서 가장 자주 누르는 곳: 주 버튼 56dp(10.2)
                            if (latest.remaining > 0) {
                                Button(
                                    onClick = { onFlight(latest.projectId, latest.missionId) },
                                    modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(10.dp),
                                ) { Text(if (latest.done == 0) "▶ 비행 시작" else "▶ 이어서 비행", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                            }
                            OutlinedButton(
                                onClick = { onAlbum(latest.projectId, latest.missionId) }, enabled = latest.done > 0,
                                modifier = Modifier.height(56.dp), shape = RoundedCornerShape(10.dp),
                            ) { Text("결과·판독") }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 수동 비행(Pilot 2 카메라 뷰 타일). 채운 버튼은 왼쪽 [비행 시작] 하나라 이 타일은 강조 바탕 카드로 둔다(10.2)
                Surface(
                    onClick = onCamera, modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("수동 비행", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("카메라 뷰 · 언제든 촬영 · 이어서 비행", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeTile("비행경로", "전주 파일 · 계획 · 미션", Modifier.weight(1f), onClick = onRoutes)
                    HomeTile("앨범", "결과 · 판독 · 내보내기", Modifier.weight(1f), enabled = latest != null) { latest?.let { onAlbum(it.projectId, it.missionId) } }
                }
            }
        }
    }

    if (showAircraft) AircraftDialog(s.aircraft) { showAircraft = false }
}

/** 홈 아래 타일(Pilot 2 홈 비행경로·앨범 타일): 이름과 한 줄 설명. */
@Composable
private fun HomeTile(title: String, subtitle: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.height(72.dp), shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 기체 상태 칩(Pilot 2 상태 관리 요약): 상태색 점, 기종, 주의 이유. 누르면 세부. */
@Composable
private fun AircraftChip(a: AircraftStatus, modifier: Modifier, onClick: () -> Unit) {
    val (color, label) = statusOf(a)
    Surface(onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(a.model ?: "기체 연결 안 됨", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            a.batteryPct?.let { Text("배터리 $it%", style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
            Text("›", style = MaterialTheme.typography.titleMedium)
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
    FullscreenAlertDialog(
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

/** 소속 글자 색(파랑). */
private val KepcoBlue = androidx.compose.ui.graphics.Color(0xFF4C8DFF)
