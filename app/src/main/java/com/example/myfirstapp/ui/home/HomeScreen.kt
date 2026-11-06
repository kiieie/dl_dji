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
import com.example.myfirstapp.InspectionApp
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
import com.example.myfirstapp.drone.djiGeoSummary
import com.example.myfirstapp.drone.djiHmsIssues
import com.example.myfirstapp.core.model.GeoSummary
import com.example.myfirstapp.core.model.HmsIssue
import com.example.myfirstapp.core.model.hmsLevel
import com.example.myfirstapp.ui.procedure.hmsColor
import com.example.myfirstapp.ui.procedure.hmsWord
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.album.Photo
import androidx.compose.ui.graphics.Brush
import com.example.myfirstapp.ui.flight.PoleDone
import com.example.myfirstapp.ui.flight.PoleFailed
import com.example.myfirstapp.ui.flight.PolePending
import com.example.myfirstapp.ui.mission.STATUS_TITLE
import com.example.myfirstapp.ui.mission.statusColor
import com.example.myfirstapp.ui.theme.DarkButton
import com.example.myfirstapp.ui.theme.HomeTileFallback
import com.example.myfirstapp.ui.theme.Warning
import androidx.compose.ui.draw.alpha
import com.example.myfirstapp.ui.theme.numberStyle
import kotlin.math.cos
import kotlinx.coroutines.launch
import com.example.myfirstapp.core.executor.sdFreeLabel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.myfirstapp.ui.theme.AircraftBody
import com.example.myfirstapp.ui.theme.AircraftPropTip
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize

/**
 * 홈(Pilot 2 홈페이지 p2s_1·0225, 10.5.5): 왼쪽 위 우리 표지 · 프로필 · 데이터 보호, 최근 미션 카드(우리 것, [미션 비행 시작]),
 * GEO 구역 카드 · 타일 비행 경로 · 앨범. 오른쪽 기체 판(HMS 칸 → HMS 목록, 기체 이름, 페이로드 · 조종기, 연결 상태)과 [카메라 뷰 입력](→ 비행 전 점검 → 카메라 뷰).
 * 클라우드·아카데미·DJI CARE·펌웨어 업데이트 안내는 제외(10.5.5·7.2). 연동 검증(PoC)은 표지를 길게 누르면 열린다.
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
    /** 기체 판 HMS 칸: HMS 목록(10.5.5). */
    onHms: () -> Unit,
    onVerification: () -> Unit,
    /** 프로필 → 비행 기록(10.4.6 비행 다시 보기). */
    onFlightRecords: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            dao, ::djiAircraftStatus, { djiHmsIssues(context.applicationContext) }, ::djiGeoSummary, (context.applicationContext as InspectionApp).mapThumbs,
        ) { clearCacheAndLogs(context.applicationContext) },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    LifecycleResumeEffect(vm) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }
    val latest = s.latest
    // 화면 배치 상태: 열린 판(기체 세부, 프로필, 데이터 보호, 앱 정보)
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
            MissionCard(latest, Modifier.weight(1f).fillMaxWidth(), onRoutes, onFlight, onNewPlan, vm::mapThumb)
            Row(Modifier.padding(top = 8.dp).height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeoCard(s.geo, Modifier.weight(1f))
                // Pilot 2 타일(p2s_1): 바탕 그림 위 흰 제목. 비행 경로는 최근 경로 그림, 앨범은 최근 사진
                HomeTile("비행 경로", Modifier.weight(1f), onClick = onRoutes) {
                    latest?.takeIf { it.route.isNotEmpty() }?.let { RoutePreview(it.route, it.home, Modifier.fillMaxSize(), labels = false, mapThumb = vm::mapThumb) }
                }
                HomeTile("앨범", Modifier.weight(1f), onClick = { onAlbum(latest?.projectId ?: 0, latest?.missionId ?: 0) }) {
                    s.latestPhoto?.let { Photo(it, Modifier.fillMaxSize(), sample = 8) }
                }
            }
        }
        AircraftPanel(s.aircraft, s.hms, Modifier.weight(1f).fillMaxHeight(), onHms = onHms, onDetail = { sheet = HomeSheet.AIRCRAFT }, onCamera = onCamera)
    }

    when (sheet) {
        HomeSheet.AIRCRAFT -> AircraftDialog(s.aircraft) { sheet = null }
        HomeSheet.PROFILE -> SheetDialog("프로필", onClose = { sheet = null }) {
            SheetItem("비행 기록", "지난 비행 다시 보기(궤적·시간 막대·사진)") { sheet = null; onFlightRecords() }
            SheetItem("오프라인 지도", "MBTiles 추가·삭제, 배경 지도") { sheet = null; onSettings() }
            SheetItem("설정", "비행 기본값 · 탐지 모델 · 지도") { sheet = null; onSettings() }
            SheetItem("앱 정보", "버전 · 데이터 주권") { sheet = HomeSheet.ABOUT }
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
private enum class HomeSheet { AIRCRAFT, PROFILE, ABOUT, PRIVACY, CLEAR }

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
 * 오른쪽 기체 판(Pilot 2): 위 연결 상태 글과 HMS 칸(HMS 항목 등급: 표준 녹 / 경고 주황 / 심각 빨강, 누르면 HMS 목록. 기체 없이도 조종기 HMS로 보인다),
 * 가운데 기체 이름, 페이로드 · 조종기 두 칸, 아래 [카메라 뷰 입력].
 */
@Composable
private fun AircraftPanel(a: AircraftStatus, hms: List<HmsIssue>, modifier: Modifier, onHms: () -> Unit, onDetail: () -> Unit, onCamera: () -> Unit) {
    val (color, label) = statusOf(a)
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(label, Modifier.weight(1f).padding(start = 6.dp), fontSize = FontSize.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val level = hmsLevel(hms)
            Surface(onClick = onHms, shape = RoundedCornerShape(4.dp), color = hmsColor(level)) {
                Text(
                    "${hmsWord(level, hms.count { it.level == level })} ›", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().clickable(onClickLabel = "기체 세부", onClick = onDetail), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // 기체 그림(Pilot 2 홈): DJI 그림 대신 간단한 쿼드콥터 선 그림. 연결 안 되면 흐리게
            AircraftDrawing(Modifier.size(200.dp, 110.dp).alpha(if (a.connected) 1f else 0.45f))
            // Pilot 2: 이름은 가운데(18sp), ⇋는 오른쪽. TooltipBox에는 align이 먹지 않아(⇋가 왼쪽에 붙음, 11-04 3차) 끝 정렬 Row에 둔다
            Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(a.model?.uppercase() ?: "기체 연결 안 됨", Modifier.align(Alignment.Center), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { AircraftSwitch(Modifier) }
            }
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
        // Pilot 2 [카메라 뷰 입력]은 짙은 회색. 파랑 채운 버튼은 우리 [미션 비행 시작] 하나(10.5.5)
        Button(
            onClick = onCamera, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(DarkButton, Color.White),
        ) {
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
    mapThumb: MapThumbSource,
) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box {
            if (m != null) {
                RoutePreview(m.route, m.home, Modifier.fillMaxWidth().fillMaxHeight(0.62f).padding(start = 10.dp, end = 10.dp, top = 34.dp).clip(RoundedCornerShape(4.dp)), mapThumb = mapThumb)
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("최근 미션", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        // 소티 지금/전체(10.5.11, 라이브러리·상단 바와 같은 정의)
                        Figure("소티", "${m.sortieNow}", "/${m.sorties}")
                        Figure("사진", "${m.photosTaken}", "/${m.photos}")
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

/**
 * GEO 구역 카드(Pilot 2 홈 "최고 고도: 1500m"): 기체에 있는 비행 안전 데이터로 SDK가 알린 주변 구역만 쓴다(조회·받기 없음, 7.2).
 * 최고 고도는 품은 높이 제한 구역(없으면 1500m), 아래 줄은 주변 구역 수와 가장 가까운 구역. 받은 적이 없으면 "로컬 데이터 없음".
 */
@Composable
private fun GeoCard(g: GeoSummary?, modifier: Modifier) {
    Surface(modifier.fillMaxHeight(), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GEO 구역", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(g?.let { "최고 고도: ${it.maxAltitudeM}m" } ?: "로컬 데이터 없음", fontSize = FontSize.Title, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                when {
                    g == null -> "기체 연결 뒤 SDK가 알림"
                    g.zones == 0 -> "주변 구역 없음"
                    else -> "주변 ${g.zones}곳 · ${g.nearest.orEmpty()} " + g.nearestM?.let { if (it <= 0.0) "안" else if (it < 1000) "${it.toInt()}m" else "%.1fkm".format(java.util.Locale.ROOT, it / 1000) }.orEmpty()
                },
                fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
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

/**
 * 타일(Pilot 2 홈 p2s_1): [background] 그림(최근 경로·최근 사진, 없으면 회색)을 꽉 채우고 왼쪽 위에 흰 제목.
 * 밝은 그림 위에서도 읽히게 위쪽에 어두운 그러데이션을 깐다.
 */
@Composable
private fun HomeTile(title: String, modifier: Modifier, onClick: () -> Unit, background: @Composable () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(6.dp), color = HomeTileFallback) {
        Box {
            background()
            Box(Modifier.fillMaxWidth().height(40.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
            Text(title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = FontSize.Title, fontWeight = FontWeight.Bold, color = Color.White)
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
                Item("SD 카드", a.sdFreeMb?.let { if (a.sdState == "NORMAL") sdFreeLabel(it.toLong()) else a.sdState })
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

/**
 * 기체 그림(Pilot 2 홈 기체 판, 10.5.5): 위에서 본 쿼드콥터 선 그림(몸체 · 팔 4개 · 모터 · 2날 프로펠러 · 앞 짐벌).
 * DJI 제품 그림은 쓰지 않는다(저작권). 크기는 [modifier]로 정한다.
 */
@Composable
private fun AircraftDrawing(modifier: Modifier) {
    Canvas(modifier) {
        val c = center
        val hubs = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).map { (sx, sy) -> Offset(c.x + sx * size.width * 0.3f, c.y + sy * size.height * 0.32f) }
        val arm = 5.dp.toPx()
        hubs.forEach { drawLine(AircraftBody, c, it, arm, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
        val bw = size.width * 0.16f
        val bh = size.height * 0.5f
        drawRoundRect(AircraftBody, Offset(c.x - bw / 2, c.y - bh / 2), Size(bw, bh), CornerRadius(bw / 3))
        // 앞 짐벌과 렌즈
        drawCircle(Color(0xFF1C1C1C), bw * 0.32f, Offset(c.x, c.y - bh / 2))
        drawCircle(Color(0xFF4DABF7), bw * 0.12f, Offset(c.x, c.y - bh / 2))
        val prop = size.width * 0.14f
        hubs.forEachIndexed { i, h ->
            drawCircle(AircraftBody, 5.dp.toPx(), h)
            // 2날 프로펠러를 조금씩 다른 각으로(돌고 있는 느낌), 끝은 주황
            val a = Math.toRadians((20 + i * 35).toDouble())
            val d = Offset((cos(a) * prop).toFloat(), (kotlin.math.sin(a) * prop).toFloat())
            drawLine(AircraftBody.copy(alpha = 0.8f), h - d, h + d, 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(AircraftPropTip, h + d * 0.8f, h + d, 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(AircraftPropTip, h - d * 0.8f, h - d, 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** 기체 바꾸기 ⇋(Pilot 2 기체 이름 옆): 1차는 M3E만 지원하므로 흐리게 두고, 누르면 "M3E만 지원" 안내. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AircraftSwitch(modifier: Modifier) {
    val tip = rememberTooltipState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("M3E만 지원") } },
        state = tip, modifier = modifier,
    ) {
        val tint = MaterialTheme.colorScheme.outline
        Canvas(
            Modifier.size(Dimens.TouchMedium).clickable(onClickLabel = "기체 바꾸기(M3E만 지원)") { scope.launch { tip.show() } }.padding(8.dp)
                .semantics { contentDescription = "기체 바꾸기(M3E만 지원)" },
        ) {
            val w = 2.dp.toPx()
            val y1 = size.height * 0.35f
            val y2 = size.height * 0.65f
            val head = size.width * 0.22f
            drawLine(tint, Offset(0f, y1), Offset(size.width, y1), w)
            drawLine(tint, Offset(size.width, y1), Offset(size.width - head, y1 - head * 0.8f), w)
            drawLine(tint, Offset(0f, y2), Offset(size.width, y2), w)
            drawLine(tint, Offset(0f, y2), Offset(head, y2 + head * 0.8f), w)
        }
    }
}
