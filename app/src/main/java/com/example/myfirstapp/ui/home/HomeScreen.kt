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
import com.example.myfirstapp.BuildConfig
import com.example.myfirstapp.R
import com.example.myfirstapp.data.clearCacheAndLogs
import com.example.myfirstapp.ui.AppBadge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.VerticalDivider
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
 * 홈(Pilot 2 홈페이지 p2s_1·0225, 10.5.5): 왼쪽 위 우리 표지 · 프로필 · 데이터 보호, 최근 미션 카드(우리 것, [미션 비행 시작]),
 * 타일 비행 경로 · 앨범. 오른쪽 기체 판(HMS 칸, 기체 이름, 페이로드 · 조종기, 연결 상태)과 [카메라 뷰 입력](→ 비행 전 점검 → 카메라 뷰).
 * GEO 구역 카드는 로컬 DB PoC 뒤, 클라우드·아카데미·DJI CARE는 제외(10.5.5). 연동 검증(PoC)은 표지를 길게 누르면 열린다.
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
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(dao, ::djiAircraftStatus) { clearCacheAndLogs(context.applicationContext) })
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    LifecycleResumeEffect(vm) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }
    val latest = s.latest
    // 화면 배치 상태: 열린 판(기체 세부, 프로필, 데이터 보호, 앱 정보, 비행 기록 안내)
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    // RC `<`(안드로이드 뒤로)를 홈에서 누르면 바로 꺼지지 않고 묻는다(10.4.4)
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    if (confirmExit) {
        val activity = context as? Activity
        FullscreenAlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("앱을 종료할까요?") },
            confirmButton = { TextButton(onClick = { activity?.finish() }) { Text("종료") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("취소") } },
        )
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1.35f).fillMaxHeight().padding(start = 12.dp, end = 8.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(Dimens.HeaderList), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppBadge(Modifier.combinedClickable(onClick = {}, onLongClick = onVerification))
                IconButton(onClick = { sheet = HomeSheet.PROFILE }, modifier = Modifier.size(Dimens.TouchMedium)) {
                    Icon(painterResource(R.drawable.ic_person), contentDescription = "프로필", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { sheet = HomeSheet.PRIVACY }, modifier = Modifier.size(Dimens.TouchMedium)) {
                    Icon(painterResource(R.drawable.ic_shield), contentDescription = "데이터 보호", modifier = Modifier.size(20.dp))
                }
            }
            MissionCard(latest, Modifier.weight(1f).fillMaxWidth(), onRoutes, onFlight, onNewPlan)
            Row(Modifier.padding(top = 8.dp).height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeTile(R.drawable.ic_route, "비행 경로", "라이브러리 · 전주 파일 · 계획", Modifier.weight(1f), onClick = onRoutes)
                HomeTile(R.drawable.ic_album, "앨범", "사진 모아 보기", Modifier.weight(1f)) { onAlbum(latest?.projectId ?: 0, latest?.missionId ?: 0) }
            }
        }
        AircraftPanel(s.aircraft, Modifier.weight(1f).fillMaxHeight(), onHms = onAircraftCheck, onDetail = { sheet = HomeSheet.AIRCRAFT }, onCamera = onCamera)
    }

    when (sheet) {
        HomeSheet.AIRCRAFT -> AircraftDialog(s.aircraft) { sheet = null }
        HomeSheet.PROFILE -> SheetDialog("프로필", onClose = { sheet = null }) {
            SheetItem("비행 기록", "지난 비행 리플레이(궤적·시간 막대·사진)") { sheet = HomeSheet.REPLAY }
            SheetItem("오프라인 지도", "MBTiles 추가·삭제, 배경 지도") { sheet = null; onSettings() }
            SheetItem("설정", "비행 기본값 · 탐지 모델 · 지도") { sheet = null; onSettings() }
            SheetItem("앱 정보", "버전 · 데이터 주권") { sheet = HomeSheet.ABOUT }
        }
        HomeSheet.REPLAY -> SheetDialog("비행 기록", onClose = { sheet = null }) {
            Text("리플레이(궤적·시간 막대·사진)는 F묶음에서 만듭니다. 지금은 미션 결과 화면과 운용 로그(logs/)로 확인하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HomeSheet.ABOUT -> SheetDialog("앱 정보", onClose = { sheet = null }) {
            Item("앱", "KEPCO 전주 점검 ${BuildConfig.VERSION_NAME}")
            Item("기체", "Mavic 3 Enterprise · RC Pro Enterprise")
            Item("DJI SDK", "MSDK 5.18 · LDM(로컬 데이터 모드)")
            Text("비행·사진·위치 기록은 조종기 안에만 둡니다. 외부로 보내는 것은 배경 지도 타일 받기뿐입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HomeSheet.PRIVACY -> SheetDialog("데이터 보호", onClose = { sheet = null }) {
            Item("LDM(로컬 데이터 모드)", if (s.aircraft.ldm) "켜짐 · DJI 서버로 보내지 않음" else "꺼짐 · 비행 시작 불가")
            Item("외부 전송", "배경 지도 타일만")
            Text("비행 기록·사진·위치·전주 파일은 보내지 않습니다(7.2). 배경 지도(Bing·VWorld) 타일은 계획을 저장할 때 미리 받습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { sheet = HomeSheet.CLEAR }, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonStd)) { Text("캐시·로그 삭제") }
        }
        HomeSheet.CLEAR -> FullscreenAlertDialog(
            onDismissRequest = { sheet = HomeSheet.PRIVACY },
            title = { Text("캐시·로그 삭제") },
            text = { Text("지도 캐시(본 타일)·임시 파일·운용 로그를 지웁니다. 미션 기록·사진·미리 받은 지도는 그대로입니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { sheet = null; vm.clearCacheAndLogs() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { sheet = HomeSheet.PRIVACY }) { Text("취소") } },
        )
        null -> Unit
    }
    s.message?.let { msg ->
        FullscreenAlertDialog(onDismissRequest = vm::dismissMessage, text = { Text(msg) }, confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("확인") } })
    }
}

/** 홈에서 여는 판. */
private enum class HomeSheet { AIRCRAFT, PROFILE, REPLAY, ABOUT, PRIVACY, CLEAR }

@Composable
private fun SheetDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    FullscreenAlertDialog(
        onDismissRequest = onClose, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

@Composable
private fun SheetItem(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * 오른쪽 기체 판(Pilot 2): 위 연결 상태 글과 HMS 칸(표준 녹 / 경고 주황 / 확인 빨강, 누르면 기체 점검), 가운데 기체 이름,
 * 페이로드 · 조종기 두 칸, 아래 [카메라 뷰 입력].
 */
@Composable
private fun AircraftPanel(a: AircraftStatus, modifier: Modifier, onHms: () -> Unit, onDetail: () -> Unit, onCamera: () -> Unit) {
    val (color, label) = statusOf(a)
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(label, Modifier.weight(1f).padding(start = 6.dp), fontSize = FontSize.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (hms, hmsColor) = when (a.level) {
                null -> "연결 안 됨" to MaterialTheme.colorScheme.outline
                AircraftStatus.Level.OK -> "표준" to MaterialTheme.colorScheme.tertiary
                AircraftStatus.Level.WARN -> "경고" to Warning
                AircraftStatus.Level.ERROR -> "확인" to MaterialTheme.colorScheme.error
            }
            Surface(onClick = onHms, shape = RoundedCornerShape(4.dp), color = hmsColor) {
                Text("$hms ›", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().clickable(onClickLabel = "기체 세부", onClick = onDetail), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(a.model?.uppercase() ?: "기체 연결 안 됨", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (a.simulator) Text("시뮬레이터", fontSize = FontSize.Caption, color = Warning)
            Row(Modifier.padding(top = 18.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                PanelFigure(if (a.connected) "M3E" else "--", "페이로드 1")
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PanelFigure("A", "조종기")
                if (a.batteryPct != null) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PanelFigure("${a.batteryPct}%", "배터리")
                }
            }
        }
        Button(onClick = onCamera, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) {
            Text("카메라 뷰 입력", fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PanelFigure(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = numberStyle(18.sp))
        Text(label, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
