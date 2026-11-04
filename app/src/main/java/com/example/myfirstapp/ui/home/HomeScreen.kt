package com.example.myfirstapp.ui.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.ShotMode
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.drone.AircraftStatus
import com.example.myfirstapp.drone.djiAircraftStatus
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.flight.PoleDone
import com.example.myfirstapp.ui.flight.PoleFailed
import com.example.myfirstapp.ui.flight.PolePending
import com.example.myfirstapp.ui.mission.STATUS_TITLE
import com.example.myfirstapp.ui.mission.statusColor
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlin.math.cos
import kotlin.math.min
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize

/**
 * 홈(10.4 시안, Pilot 2 홈페이지): 머리글 40dp(앱 이름, 기체 칩, 설정), 왼쪽 최근 미션 카드(경로 미리보기,
 * 전주·소티·사진, 채운 버튼 하나), 오른쪽 타일 4개(수동 비행, 비행경로, 앨범, 기체 점검).
 * 연동 검증(PoC)은 앱 이름을 길게 누르면 열린다.
 */
@Composable
fun HomeScreen(
    dao: ProjectDao,
    onRoutes: () -> Unit,
    onAlbum: (projectId: Long, missionId: Long) -> Unit,
    onFlight: (projectId: Long, missionId: Long) -> Unit,
    onNewPlan: (projectId: Long) -> Unit,
    onCamera: () -> Unit,
    onSettings: () -> Unit,
    onAircraftCheck: () -> Unit,
    onVerification: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(dao, ::djiAircraftStatus))
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    LifecycleResumeEffect(vm) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }
    val latest = s.latest
    var showAircraft by remember { mutableStateOf(false) }
    // RC `<`(안드로이드 뒤로)를 홈에서 누르면 바로 꺼지지 않고 묻는다(10.4.4)
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    if (confirmExit) {
        val activity = LocalContext.current as? Activity
        FullscreenAlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("앱을 종료할까요?") },
            confirmButton = { TextButton(onClick = { activity?.finish() }) { Text("종료") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("취소") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.HeaderList).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 소속 표시: 공식 로고 파일을 받으면 이미지로 바꾼다. 로고 모양을 흉내 내 그리지 않는다
            Text(
                buildAnnotatedString {
                    append("KEPCO ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("전주 점검") }
                },
                fontSize = FontSize.Title, fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onVerification),
            )
            Spacer(Modifier.weight(1f))
            AircraftChip(s.aircraft) { showAircraft = true }
            IconButton(onClick = onSettings, modifier = Modifier.size(Dimens.TouchSmall)) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "설정", modifier = Modifier.size(16.dp))
            }
        }
        Row(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MissionCard(latest, Modifier.weight(1.45f).fillMaxHeight(), onRoutes, onFlight, onNewPlan)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTile(R.drawable.ic_camera, "수동 비행", "카메라 뷰", Modifier.weight(1f), iconTint = Accent, onClick = onCamera)
                    HomeTile(R.drawable.ic_route, "비행경로", "전주 파일 · 계획", Modifier.weight(1f), onClick = onRoutes)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTile(R.drawable.ic_album, "앨범", "사진 모아 보기", Modifier.weight(1f)) {
                        onAlbum(latest?.projectId ?: 0, latest?.missionId ?: 0)
                    }
                    HomeTile(R.drawable.ic_shield, "기체 점검", "상태 · 설정", Modifier.weight(1f), onClick = onAircraftCheck)
                }
            }
        }
    }

    if (showAircraft) AircraftDialog(s.aircraft) { showAircraft = false }
}

/** 최근 미션 카드: 위 경로 미리보기, 아래 이름·수치·채운 버튼. 판독은 앨범에서만 하므로 끝난 미션은 [새 계획]. 미션이 없으면 비행경로로 안내. */
@Composable
private fun MissionCard(
    m: LatestMission?,
    modifier: Modifier,
    onRoutes: () -> Unit,
    onFlight: (Long, Long) -> Unit,
    onNewPlan: (Long) -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box {
            if (m != null) RoutePreview(m.route, m.home, Modifier.fillMaxWidth().fillMaxHeight(0.62f).padding(top = 28.dp))
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("최근 미션", fontSize = FontSize.Caption, color = TextSoft)
                    if (m != null) StatusBadge(m.status)
                }
                Spacer(Modifier.weight(1f))
                if (m == null) {
                    Text("아직 미션이 없습니다", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("비행경로에서 전주 파일을 가져오고 계획을 세우세요.", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MainButton("비행경로 열기", onRoutes)
                } else {
                    Text(
                        buildAnnotatedString {
                            append(m.projectName)
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" #${m.missionId}") }
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                        Figure("전주", "${m.done}", "/${m.total}")
                        Figure("소티", "${m.sorties}")
                        Figure("사진", "${m.photos}")
                        Text(if (m.shotMode == ShotMode.DIAGONAL4) "4방향 사선" else "전주 위", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        m.total == 0 -> MainButton("비행경로 열기", onRoutes)
                        m.remaining > 0 -> MainButton(if (m.done == 0) "미션 비행 시작" else "미션 이어서 비행", { onFlight(m.projectId, m.missionId) }, play = true)
                        else -> MainButton("새 계획", { onNewPlan(m.projectId) })
                    }
                }
            }
        }
    }
}

/** 화면에 하나뿐인 채운 버튼(44dp). */
@Composable
private fun MainButton(label: String, onClick: () -> Unit, play: Boolean = false) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) {
        if (play) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusBadge(status: String) {
    val color = statusColor(status)
    Text(
        STATUS_TITLE[status] ?: status, fontSize = FontSize.Caption, fontWeight = FontWeight.SemiBold, color = color, lineHeight = 16.sp,
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp),
    )
}

/** 라벨 + 큰 수치(Barlow Condensed) + 작은 뒤붙임(예: /20). */
@Composable
private fun Figure(label: String, value: String, suffix: String = "") {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("$label ") }
            withStyle(numberStyle(17.sp).toSpanStyle()) { append(value) }
            if (suffix.isNotEmpty()) withStyle(numberStyle(FontSize.Label).toSpanStyle().copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(suffix) }
        },
        maxLines = 1,
    )
}

/** 오른쪽 타일(Pilot 2 홈 타일): 위 아이콘, 아래 이름과 한 줄 설명. */
@Composable
private fun HomeTile(
    icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier,
    enabled: Boolean = true,
    iconTint: Color = Color.White,
    onClick: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(painterResource(icon), contentDescription = null, tint = if (enabled) iconTint else dim, modifier = Modifier.size(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = FontSize.Title, fontWeight = FontWeight.Bold, color = if (enabled) Color.White else dim)
                Text(subtitle, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 기체 상태 칩(26dp): 상태색 점, 기종(없으면 "기체 연결 안 됨"), 배터리. 누르면 세부. */
@Composable
private fun AircraftChip(a: AircraftStatus, onClick: () -> Unit) {
    val (color, _) = statusOf(a)
    Surface(onClick = onClick, modifier = Modifier.height(26.dp), shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(a.model ?: "기체 연결 안 됨", fontSize = FontSize.Caption, color = TextSoft, maxLines = 1)
            a.batteryPct?.let { Text("$it%", style = numberStyle(FontSize.Label), color = TextSoft) }
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

/** 기체 세부: 값과 확인할 일. 바꾸는 것은 기체 점검(설정)에서. */
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
                Text("기체 설정은 [기체 점검]에서 바꿉니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
