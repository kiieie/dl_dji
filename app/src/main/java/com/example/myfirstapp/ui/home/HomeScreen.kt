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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 * 홈(Pilot 2 홈페이지, 매뉴얼 51~53쪽): 왼쪽 타일 [비행경로] [앨범] [설정] [연동 검증], 오른쪽 기체 상태 카드와
 * 큰 [카메라 뷰로 들어가기]. 카메라 뷰는 가장 최근 미션에 남은 전주가 있으면 그 비행 화면을, 없으면 비행경로를 연다.
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

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("배전 전주 점검", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile("비행경로", "전주 가져오기 · 계획 · 미션", Modifier.weight(1f), onClick = onRoutes)
                Tile(
                    "앨범", latest?.let { "${it.projectName} 미션 #${it.missionId} 사진·판독" } ?: "비행한 미션 없음", Modifier.weight(1f),
                    enabled = latest != null,
                ) { latest?.let { onAlbum(it.projectId, it.missionId) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Tile("설정", "비행 기본값 · 기체 · 모델 · 지도", Modifier.weight(1f), onClick = onSettings)
                Tile("연동 검증", "SDK · LDM · 기체 PoC", Modifier.weight(1f), onClick = onVerification)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AircraftCard(s.aircraft, Modifier.fillMaxWidth().weight(1f))
            Text(
                latest?.let { "최근 미션: ${it.projectName} #${it.missionId} · 남은 전주 ${it.remaining}/${it.total}" } ?: "최근 미션 없음",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { if (latest != null && latest.remaining > 0) onFlight(latest.projectId, latest.missionId) else onRoutes() },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("카메라 뷰로 들어가기", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Tile(title: String, subtitle: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        onClick = onClick, enabled = enabled, modifier = modifier.height(140.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** 상태 관리 카드: 기체 연결·기종과 이륙 전 확인 값. 녹(정상)·주(주의)·빨(문제). */
@Composable
private fun AircraftCard(a: AircraftStatus, modifier: Modifier) {
    val (color, label) = when (a.level) {
        null -> MaterialTheme.colorScheme.outline to "기체 연결 안 됨"
        AircraftStatus.Level.OK -> PoleDone to "정상 · 이륙 가능"
        AircraftStatus.Level.WARN -> PolePending to "주의"
        AircraftStatus.Level.ERROR -> PoleFailed to "확인 필요"
    }
    Card(modifier, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                Text(a.model ?: "조종기만 연결됨", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(if (a.issues.isEmpty()) label else "$label: ${a.issues.joinToString(", ")}", color = color, fontWeight = FontWeight.Bold)
            if (a.connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Item("배터리", a.batteryPct?.let { "$it%" })
                    Item("GNSS", a.satellites?.let { "${it}개" })
                    Item("SD", a.sdFreeMb?.let { if (a.sdState == "NORMAL") "${it / 1024}GB 남음" else a.sdState })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Item("LDM", if (a.ldm) "켜짐" else "꺼짐")
                if (a.simulator) Item("시뮬레이터", "켜짐")
            }
        }
    }
}

@Composable
private fun Item(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "--", style = MaterialTheme.typography.titleMedium, color = Color.Unspecified)
    }
}
