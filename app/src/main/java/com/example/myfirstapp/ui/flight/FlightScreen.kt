package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.OnTertiaryContainer
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.numberStyle
import com.example.myfirstapp.drone.DjiFpvView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import com.example.myfirstapp.core.model.flightTitle
import com.example.myfirstapp.ui.theme.Brand
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.SurfaceContainerHighest

/** 지도의 전주 색(데이터 표시, 10.1 예외): 완료·건너뜀·실패·진행·대기. 기체·홈·조종기 표지 색은 Color.kt(MAP_*). */
internal val PoleDone = Color(0xFF4CD08A)
internal val PoleSkipped = Color(0xFF80858E)
internal val PoleFailed = Color(0xFFFF5449)
internal val PoleActive = Color(0xFF5B9BFF)
internal val PolePending = Color(0xFFFF9F43)

/** 영상·지도 위 버튼과 글자 바탕(10.1 DJI 톤): 뒤가 비치도록 반투명 검정. */

/**
 * 비행 화면 ④(10장). 상태와 명령은 [FlightViewModel]이 갖고, 이 화면은 그리기와 입력 전달만 한다.
 * 비행 중에는 촬영에만 집중한다: 판독(검출 보기)은 [onShowResult]로 미션이 끝난 뒤 결과 화면에서 한다(2026-10-30 사용자 요청).
 */
@Composable
fun FlightScreen(
    projectId: Long,
    missionId: Long,
    dao: ProjectDao,
    onExit: () -> Unit,
    onShowResult: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /** 카메라 설정 아이콘(10.5.2): 기체 점검의 짐벌·카메라 탭. */
    onCameraSettings: () -> Unit = {},
    /** 앨범: 비행 화면은 스택에 남아 실행기가 이어진다. */
    onAlbum: () -> Unit = {},
    /** 큰 지도 [라이브러리](10.5.4): 비행경로 화면. 떠날 수 있을 때만 켠다. */
    onLibrary: () -> Unit = {},
    /** 계획 화면 ▶에서 왔으면 미션을 읽은 뒤 비행 전 점검을 한 번 연다(10.5.8 → 10.5.5). */
    openPreflight: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: FlightViewModel = viewModel(factory = FlightViewModel.factory(
            dao, projectId, missionId, context.getExternalFilesDir(null), app.settings, app.rcButtons, app.djiReady, app.djiInitialized, app.precise,
        ))
    val s by vm.state.collectAsStateWithLifecycle()
    var preOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.loading) {
        if (openPreflight && !preOpened && !s.loading && s.error == null) {
            preOpened = true
            vm.openPreflight()
        }
    }
    // RC 5D 누름 재개는 이 화면이 맨 위일 때만(앨범·설정에서 누른 5D로 재개하지 않게)
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.active = true
        onPauseOrDispose { vm.active = false }
    }
    // 비행 중에는 뒤로 가기로 화면을 떠나지 못하게 한다(떠나면 실행기가 멈춘다).
    BackHandler { if (s.canExit || s.error != null) onExit() }

    when {
        s.loading -> Centered { Text("미션을 불러오는 중입니다") }
        s.error != null -> Centered {
            Text(s.error!!)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowResult) { Text("결과 보기") }
                OutlinedButton(onClick = onExit) { Text("나가기") }
            }
        }
        else -> FlightContent(s, missionId, vm, onExit, onShowResult, onOpenSettings, onCameraSettings, onAlbum, onLibrary)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun FlightContent(
    s: FlightUiState,
    missionId: Long,
    vm: FlightViewModel,
    onExit: () -> Unit,
    onShowResult: () -> Unit,
    onOpenSettings: () -> Unit,
    onCameraSettings: () -> Unit,
    onAlbum: () -> Unit,
    onLibrary: () -> Unit,
) {
    val ui = s.exec
    // 10.5.2 Pilot 2 카메라 뷰 배치(시안 Flight3): FPV 전체 위에 격자·줌 영역·탭 초점, 상단 바, 가운데 위 줌 상자, 오른쪽 위 노출 띠·카메라 설정,
    // 왼쪽 열(핀포인트 · 짐벌 메뉴 · AR · ZOOM)과 그 오른쪽 미션 칸(⏸/▶), 오른쪽 열(사진/영상 · 셔터 · 재생)과 세로 줌, 아래 가운데 내비 원판, 왼쪽 아래 지도.
    // 큰 지도(10.5.4, 미니맵 누름·RC C2)일 때는 오른쪽 열 대신 지도 도구, 오른쪽 아래 [라이브러리], 왼쪽 아래 칸은 카메라. RTH는 RC RTH 버튼(Pilot 2와 같음)
    val mapMain = s.mapMain
    val paused = ui.state == ExecState.Paused
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fpv: @Composable (Modifier) -> Unit = { m -> FpvView(s.simulated, m) }
        if (mapMain) FlightMap(s, interactive = true, pad = listOf(220.dp, 90.dp, 60.dp, 120.dp), Modifier.fillMaxSize(), vm::setMapSource, vm::clearTrack)
        else {
            fpv(Modifier.fillMaxSize())
            // 탭 초점·초점 모드는 일시정지 중에만(지점 사진 초점을 건드리지 않게)
            CameraOverlay(s.lens, s.zoomRatio, focusEnabled = paused, onFocus = vm::focusAt, Modifier.fillMaxSize())
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            // 마지막 사진(9.6): 미니맵 바로 위(미니맵을 키우면 그 위), 가장 최근 사진의 설비 상자
            s.lastPhoto?.let { LastPhotoThumb(it, Modifier) }
            MiniMap(mapMain, onSwap = vm::swapView, Modifier) { m ->
                if (mapMain) fpv(m) else FlightMap(s, interactive = false, pad = List(4) { 8.dp }, m)
            }
        }

        val sortie = if (s.sortieCount > 1 || ui.sortieIndex > 0) " ${ui.sortieIndex + 1}/${s.sortieCount}" else ""
        TopBar(s, flightTitle(s.telemetry, "#$missionId$sortie"), onExit, onOpenSettings, onTitleClick = vm::openPreflight)
        if (!mapMain) {
            ZoomBox(s.lens, s.zoomRatio, Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 9.dp))
            ExposureStrip(s.camera, s.videoMode, focusEnabled = paused, onFocusMode = vm::cycleFocus, Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar + 8.dp, end = 48.dp))
            CameraLeftColumn(s.lens, vm::setLens, vm::setGimbal, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = Dimens.TopBar + 19.dp))
            CameraColumn(
                s = s, shutterEnabled = paused || s.recordingSinceMs != null, onShutter = vm::shutter,
                cameraEnabled = paused, onVideoMode = vm::setVideoMode, onZoom = vm::zoom, onCameraSettings = onCameraSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar + 8.dp, end = 6.dp), onAlbum = onAlbum,
            )
        }
        val notices = listOfNotNull(ui.message?.let { it to Color.White }) + ui.preflightFailures.map { "점검 실패: $it" to Error }
        NoticeLines(notices, Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 48.dp).widthIn(max = 300.dp))
        if (ui.state != ExecState.SortieDone) {
            MissionChip(s, vm, onShowResult, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = Dimens.TopBar + 19.dp))
            if (s.simulated) MockRcControls(vm, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = Dimens.TopBar + 168.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.telemetry?.landingConfirmNeeded == true) LandingConfirmButton(vm::confirmLanding)
            s.telemetry?.let { NavDisplay(it, s.home, s.obstacles, s.nextWaypoint) }
        }
        if (mapMain) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { LibraryButton(enabled = s.canExit, onLibrary) }

        if (ui.state == ExecState.SortieDone) SortieDonePanel(s, vm)
        // 절차 화면(10.5.5): 비행 전 점검 → 체크리스트. 화면 전체를 덮는다
        if (s.page != null) ProcedurePages(s, vm)
        if (ui.terrainFallbackAsk) TerrainFallbackDialog(vm)
    }
}

/** 지형 추적 시작 거절(5.6, 2026-10-30 사용자 결정): 이륙 지점 기준으로 다시 올릴지 묻는다. 그동안 기체는 멈춰 호버링한다. */
@Composable
private fun TerrainFallbackDialog(vm: FlightViewModel) {
    FullscreenAlertDialog(
        onDismissRequest = vm::dismissTerrainFallback,
        title = { Text("지형 추적으로 시작하지 못했습니다") },
        text = { Text("이륙 지점 기준으로 다시 올리고 시작할까요? 비탈에서는 지면과의 거리가 달라지니 고도를 확인하세요. 취소하면 일시정지로 남습니다(수동 조종·RTH).") },
        confirmButton = { Button(onClick = vm::flyTakeoffHeight) { Text("이륙 기준으로 시작") } },
        dismissButton = { TextButton(onClick = vm::dismissTerrainFallback) { Text("취소") } },
    )
}

/**
 * 왼쪽 위 미션 칸(10.5.2, Pilot 2 "⏸ 비행 경로 수행 중… 50%"): 초록 원 ⏸(일시정지 = 수동 조종, RC 일시정지와 같음) / ▶(대기: 비행 전 점검,
 * 올림: 시작, 일시정지: 재개), 상태·진행률, 전주·사진 수(우리 추가), 지금 전주. 일시정지면 [건너뛰기]·[완료 처리], 끝나면 [결과 보기].
 * 제목 ˅를 누르면 RC 버튼 안내를 펼친다.
 */
@Composable
private fun MissionChip(s: FlightUiState, vm: FlightViewModel, onShowResult: () -> Unit, modifier: Modifier) {
    val ui = s.exec
    val all = ui.visits
    val done = all.count { it.status == VisitStatus.DONE || it.status == VisitStatus.SKIPPED || it.status == VisitStatus.FAILED }
    val planned = s.params?.let { p -> all.sumOf { p.photosPerPole(it.pole) } } ?: all.size
    val photos = all.sumOf { it.captureCount }
    val pct = if (all.isEmpty()) 0 else done * 100 / all.size
    val paused = ui.state == ExecState.Paused
    val canPause = ui.state.airborne && !paused && ui.state != ExecState.ReturningHome
    var open by rememberSaveable { mutableStateOf(false) }
    // (원 버튼 그림, 설명, 동작): ▶ true / ⏸ false
    val action: Triple<Boolean, String, () -> Unit>? = when {
        ui.state == ExecState.Idle -> Triple(true, "비행 시작(비행 전 점검)", vm::openPreflight)
        // 체크리스트에서 올리고 닫았으면 다시 열어 [시작](10.5.5)
        ui.state == ExecState.Uploaded -> Triple(true, "시작(체크리스트)", { vm.confirmPreflight() })
        paused -> Triple(true, "재개", vm::resume)
        canPause -> Triple(false, "일시정지 · 수동 비행", { vm.pause() })
        else -> null
    }
    val title = when {
        ui.state == ExecState.Idle -> "비행 대기"
        ui.state == ExecState.Uploaded -> "업로드 완료 · 시작 대기"
        paused -> "일시정지 · 수동 비행"
        ui.state == ExecState.ReturningHome -> "RTH 진행 중"
        ui.state == ExecState.MissionDone -> stateLabel(ui.state, ui.remaining)
        canPause -> "비행 경로 수행 중…"
        else -> stateLabel(ui.state, ui.remaining)
    }
    Column(
        modifier.width(Dimens.CardWidth).background(CardBg, RoundedCornerShape(4.dp)).border(2.dp, Brand, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            action?.let { (play, label, onClick) -> ChipButton(play, label, onClick) }
            Column(Modifier.weight(1f).clickable(onClickLabel = if (open) "안내 접기" else "안내 펼치기") { open = !open }) {
                Text("$title ${if (open) "˄" else "˅"}", fontSize = FontSize.Caption, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$pct%", style = numberStyle(FontSize.Body), color = Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            CardFigure("전주", "$done", "/${all.size}")
            CardFigure("사진", "$photos", "/$planned")
            Text(
                ui.target?.pole?.displayName ?: "", Modifier.weight(1f), fontSize = FontSize.Caption, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            )
        }
        if (paused) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CardButton("건너뛰기", MaterialTheme.colorScheme.surfaceContainer, Color.White, { vm.skip() }, Modifier.weight(1f), height = Dimens.ButtonChip)
            CardButton("완료 처리", MaterialTheme.colorScheme.surfaceContainer, Color.White, { vm.markDone() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        }
        if (ui.state == ExecState.MissionDone) CardButton("결과 보기", MaterialTheme.colorScheme.primary, Color.White, onShowResult, height = Dimens.ButtonChip)
        if (open) {
            Text(
                buildAnnotatedString {
                    append("RC ")
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("Ⅱ") }
                    append(" 일시정지 · ")
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("▶") }
                    append(" 재개(일시정지 중 5D 누름) · C1 렌즈 · C2 지도 · 셔터 = 수동 사진")
                },
                fontSize = FontSize.Micro, color = TextSoft,
            )
        }
    }
}

/** 미션 칸 초록 원 버튼(Pilot 2): ▶([play]) 또는 ⏸. */
@Composable
private fun ChipButton(play: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(Dimens.TouchSmall).clip(CircleShape).background(Tertiary).clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            if (play) drawPath(Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }, Color.Black)
            else {
                drawRect(Color.Black, Offset(size.width * 0.1f, 0f), Size(size.width * 0.3f, size.height))
                drawRect(Color.Black, Offset(size.width * 0.6f, 0f), Size(size.width * 0.3f, size.height))
            }
        }
    }
}

@Composable
private fun CardFigure(label: String, value: String, suffix: String) {
    Text(
        buildAnnotatedString {
            append("$label ")
            withStyle(numberStyle(FontSize.Body).toSpanStyle().copy(color = Color.White)) { append(value) }
            append(suffix)
        },
        fontSize = FontSize.Caption, color = TextSoft, maxLines = 1,
    )
}

@Composable
private fun CardButton(label: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), height: Dp = 34.dp) {
    Button(
        onClick = onClick, modifier = modifier.height(height), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 6.dp),
        colors = ButtonDefaults.buttonColors(bg, fg),
    ) { Text(label, fontSize = FontSize.Body, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/** FPV 영상. 실제 기체는 카메라 스트림, 모의 기체는 안내 문구(10장). */
@Composable
private fun FpvView(simulated: Boolean, modifier: Modifier) {
    if (!simulated) return DjiFpvView(modifier.background(Color.Black))
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Text("FPV 영상 (모의 기체: 영상 없음)", color = Color.White.copy(alpha = 0.35f), style = MaterialTheme.typography.bodySmall)
    }
}

/** 착륙 보호: 지면 바로 위에서 기체가 확인을 기다린다(Pilot 2 [착륙 확인]). RC 왼쪽 스틱을 내려도 된다. */
@Composable
internal fun LandingConfirmButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(bottom = 6.dp).size(200.dp, Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) {
        Text("착륙 확인", fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
    }
}

/** 모의 조작(모의 기체 전용): RC 일시정지 버튼, RC RTH 버튼(실제 기체는 펌웨어), 배터리 −30%, 장애물(없음 → 8m → 4m). */
@Composable
private fun MockRcControls(vm: FlightViewModel, modifier: Modifier) {
    // [장애물]이 들어가게 카드보다 조금 넓다
    Row(modifier.width(Dimens.CardWidth + 56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("모의 RC", fontSize = FontSize.Caption, color = TextSoft)
        CardButton("Ⅱ", CardBg, Color.White, { vm.rcPause() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        CardButton("RTH", CardBg, Color.White, { vm.returnHome() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        CardButton("−30%", CardBg, Color.White, { vm.dropBattery() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        CardButton("장애물", CardBg, Color.White, { vm.cycleObstacle() }, Modifier.weight(1f), height = Dimens.ButtonChip)
    }
}

/**
 * 소티 완료 화면(10장): 조종사 확인만 둔다. 배터리 교체·기체 확인을 체크해야 [다음 소티 시작]이 켜진다.
 * 판독은 미션이 끝난 뒤 결과 화면에서 한다(2026-10-30 사용자 요청).
 */
@Composable
private fun SortieDonePanel(s: FlightUiState, vm: FlightViewModel) {
    val ui = s.exec
    val remaining = ui.visits.count { it.status == VisitStatus.PENDING }
    var battery by rememberSaveable(ui.sortieIndex) { mutableStateOf(false) }
    var props by rememberSaveable(ui.sortieIndex) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(6.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("소티 ${ui.sortieIndex + 1}/${s.sortieCount} 완료", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("남은 전주 $remaining", color = MaterialTheme.colorScheme.onSurfaceVariant)
                CheckRow("배터리 교체", battery) { battery = it }
                CheckRow("프로펠러·기체 확인", props) { props = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = vm::nextSortie, enabled = battery && props, modifier = Modifier.height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) { Text("다음 소티 시작") }
                    OutlinedButton(onClick = vm::endMission, modifier = Modifier.height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) { Text("미션 종료") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // 줄 전체를 눌러 체크한다(장갑 낀 손가락, MD3 체크박스 목록)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/** 상태 이름. 미션을 끝냈는데 남은 전주가 있으면([remaining]) 부분 완료. */
internal fun stateLabel(s: ExecState, remaining: Int = 0) = when (s) {
    ExecState.Idle -> "비행 대기"
    ExecState.PreflightCheck -> "비행 전 점검"
    ExecState.Uploaded -> "업로드 완료 · 시작 대기"
    ExecState.Takeoff -> "이륙 중"
    ExecState.PreparingLeg -> "구간 준비"
    ExecState.FlyingLeg -> "임무 비행"
    ExecState.Arrived -> "도착"
    ExecState.Capturing -> "촬영 중"
    ExecState.Downloading -> "다운로드 중"
    ExecState.Analyzing -> "분석 중"
    ExecState.Waiting -> "대기"
    ExecState.Paused -> "수동 비행(일시정지)"
    ExecState.LinkLost -> "연결 끊김"
    ExecState.ReturningHome -> "복귀 중"
    ExecState.SortieDone -> "소티 완료"
    ExecState.MissionDone -> if (remaining > 0) "부분 완료 · 남은 전주 $remaining" else "미션 완료"
}
