package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.sortieLabel
import com.example.myfirstapp.core.route.RouteSummary
import com.example.myfirstapp.ui.procedure.durationLabel
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.LightOnSurface
import com.example.myfirstapp.ui.theme.LightOnSurfaceVariant
import com.example.myfirstapp.ui.theme.LightSurface
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.mission.LibraryPanel
import com.example.myfirstapp.ui.mission.MissionNav
import com.example.myfirstapp.ui.mission.libraryPanelViewModel
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.port.rcModeProblem
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
    /** 큰 지도 라이브러리 패널(10.5.8)의 [열기]·편집: 비행 화면을 닫고 그 미션·계획으로(떠날 수 있을 때만 켜짐). */
    onLibraryNav: (MissionNav) -> Unit = {},
    /** 계획 화면 ▶에서 왔으면 미션을 읽은 뒤 비행 전 점검을 한 번 연다(10.5.8 → 10.5.5). */
    openPreflight: Boolean = false,
    /** 비행 전 점검 "표준 HMS ›": HMS 목록(10.5.5). */
    onHms: () -> Unit = onOpenSettings,
) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: FlightViewModel = viewModel(factory = FlightViewModel.factory(
            dao, projectId, missionId, context.getExternalFilesDir(null), app.settings, app.rcButtons, app.djiReady, app.djiInitialized, app.precise, app.appScope,
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
        else -> FlightContent(s, projectId, missionId, dao, vm, onExit, onShowResult, onOpenSettings, onCameraSettings, onAlbum, onLibraryNav, onHms)
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
    projectId: Long,
    missionId: Long,
    dao: ProjectDao,
    vm: FlightViewModel,
    onExit: () -> Unit,
    onShowResult: () -> Unit,
    onOpenSettings: () -> Unit,
    onCameraSettings: () -> Unit,
    onAlbum: () -> Unit,
    onLibraryNav: (MissionNav) -> Unit,
    onHms: () -> Unit,
) {
    val ui = s.exec
    // 10.5.2 Pilot 2 카메라 뷰 배치(시안 Flight3): FPV 전체 위에 격자·줌 영역·탭 초점, 상단 바, 가운데 위 줌 상자, 오른쪽 위 노출 띠·카메라 설정,
    // 왼쪽 열(핀포인트 · 짐벌 메뉴 · AR · ZOOM)과 그 오른쪽 미션 칸(⏸/▶), 오른쪽 열(사진/영상 · 셔터 · 재생)과 세로 줌, 아래 가운데 내비 원판, 왼쪽 아래 지도.
    // 큰 지도(10.5.4, 미니맵 누름·RC C2)일 때는 오른쪽 열 대신 지도 도구, 아래 라이브러리 바로가기(패널), 왼쪽 아래 칸은 카메라. RTH는 RC RTH 버튼(Pilot 2와 같음)
    val mapMain = s.mapMain
    // 라이브러리 패널(10.5.8): 큰 지도 오른쪽. 열 때만 ViewModel을 만든다
    var library by rememberSaveable { mutableStateOf(false) }
    val lib = if (library && mapMain) libraryPanelViewModel(dao, projectId) else null
    val ls = lib?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(lib) { lib?.nav?.collect(onLibraryNav) }
    // 절차 화면(10.5.5: 비행 전 점검 → 체크리스트)은 카메라 뷰를 덮지 않고 바꿔 그린다(Pilot 2도 따로 된 화면). 밑에 FPV SurfaceView·지도 TextureView·
    // 계속 바뀌는 계기·소티 완료 창을 둔 채 위에 겹치면 RC에서 일부가 다시 그려지지 않았다(제목·✕·HMS 칸·상태 값 빈칸, 소티 완료 창 버튼 테두리 잔상, 11-06).
    // 홈에서 여는 같은 화면(밑에 아무것도 없음)은 바르게 그려졌다. 닫으면 FPV·미니맵을 다시 붙인다
    if (s.page != null) return ProcedurePages(s, vm, onHms = onHms)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // FPV 프레임 크기(격자·줌 영역 사각형을 영상 자리에, 10.5.2). 모의 기체는 모름(4:3으로 봄)
        var stream by remember { mutableStateOf<IntSize?>(null) }
        val fpv: @Composable (Modifier) -> Unit = { m -> FpvView(s.simulated, m) { stream = it } }
        if (mapMain) FlightMap(
            s, interactive = true, pad = listOf(220.dp, 90.dp, 60.dp, 120.dp), Modifier.fillMaxSize(), vm::setMapSource, vm::clearTrack, preview = ls?.previewRoute.orEmpty(),
            onSetHome = vm::setHome,
        )
        else {
            fpv(Modifier.fillMaxSize())
            // 탭 초점·초점 모드는 자동 비행 구간이 아닐 때만(지점 사진 초점을 건드리지 않게)
            CameraOverlay(s.lens, s.zoomRatio, focusEnabled = s.cameraFree, onFocus = vm::focusAt, Modifier.fillMaxSize(), stream, s.camera.ev, vm::setEv)
            if (s.ar) ArOverlay(s, stream, Modifier.fillMaxSize())
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            if (!mapMain) LensToggle(s.lens, vm::setLens, Modifier.padding(start = 8.dp, bottom = 6.dp))
            MiniMap(mapMain, onSwap = vm::swapView, Modifier) { m ->
                if (mapMain) fpv(m) else FlightMap(s, interactive = false, pad = List(4) { 8.dp }, m)
            }
        }

        // 소티 지금/전체(10.5.11, 홈·라이브러리와 같은 정의)
        TopBar(s, flightTitle(s.telemetry, "#$missionId · ${sortieLabel(s.sortieNumber, s.sortieTotal)}"), onExit, onOpenSettings, onTitleClick = vm::openPreflight)
        if (!mapMain) {
            ZoomBox(s.lens, s.zoomRatio, Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 9.dp))
            ExposureStrip(s.camera, s.videoMode, focusEnabled = s.cameraFree, onFocusMode = vm::cycleFocus, Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar + 8.dp, end = 48.dp))
            CameraLeftColumn(vm::setGimbal, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = Dimens.TopBar + 19.dp), s.telemetry, s.ar, vm::toggleAr)
            CameraColumn(
                s = s, shutterEnabled = s.shutterFree, onShutter = vm::shutter,
                cameraEnabled = s.cameraFree, onVideoMode = vm::setVideoMode, onZoom = vm::zoom, onCameraSettings = onCameraSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar + 8.dp, end = 6.dp), onAlbum = onAlbum,
                onOpenMenu = vm::openShootMenu, onCloseMenu = vm::closeShootMenu, onShootMode = vm::setShootMode, onTimer = vm::setTimer,
            )
        }
        val notices = listOfNotNull(ui.message?.let { it to Color.White }, s.shotMessage?.let { it to Color.White }) + ui.preflightFailures.map { "점검 실패: $it" to Error }
        // 모드 스위치가 N이 아니면 시작·재개가 거절된다: 시작 전·일시정지 중에 크게 알린다(2026-11-11 현장)
        val modeProblem = s.telemetry?.takeIf { it.connected && ui.state in MODE_CHECK_STATES }?.let { rcModeProblem(it.rcMode) }
        if (modeProblem != null) ActionCard(
            "모드 스위치를 N으로", modeProblem, Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 48.dp).widthIn(max = 380.dp),
        )
        NoticeLines(
            notices.filterNot { it.first == modeProblem },
            Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + if (modeProblem != null) 120.dp else 48.dp).widthIn(max = 380.dp),
        )
        if (ui.state != ExecState.SortieDone) {
            val summary = remember(ui.state, ui.sortieIndex, s.params) { vm.checklistSummary() }
            MissionChip(s, summary, vm, onShowResult, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = Dimens.TopBar + 19.dp))
            // 모의 RC 줄은 오른쪽 아래(마지막 사진 아래, 왼쪽 아래 ZOOM·미니맵과 겹치지 않게). 큰 지도면 [라이브러리] 위
            if (s.simulated) MockRcControls(
                vm, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (mapMain) 8.dp + Dimens.ButtonStd + 6.dp else 8.dp),
            )
        }
        // 마지막 사진(9.6, 11-07): 오른쪽 아래, 누르면 그 1장만 탐지. 모의 기체면 모의 RC 줄 위
        if (!mapMain) s.lastPhoto?.let {
            LastPhotoThumb(it, vm::detectLast, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (s.simulated) 8.dp + Dimens.ButtonStd + 6.dp else 8.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.telemetry?.landingConfirmNeeded == true) LandingConfirmButton(vm::confirmLanding)
            // Pilot 2 지도 뷰에는 내비 원판이 없다(10.5.4)
            if (!mapMain) s.telemetry?.let { NavDisplay(it, it.home ?: s.home, s.obstacles, s.nextWaypoint) }
        }
        if (mapMain) Box(Modifier.align(LibraryButtonAlignment).padding(8.dp)) { LibraryButton { library = !library } }
        // 떠 있으면 미리보기만(열기·편집·삭제는 막음)
        if (ls != null) LibraryPanel(ls, lib, missionId, canLeave = s.canExit, onClose = { library = false }, Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar).fillMaxHeight())

        if (!mapMain) CameraNoticeView(s.notice)
        if (ui.state == ExecState.SortieDone) SortieDonePanel(s, vm)
        if (ui.terrainFallbackAsk) TerrainFallbackDialog(ui.message, s.homeShiftM, vm)
    }
}

/** 지형 추적 시작 거절(5.6, 2026-10-30 사용자 결정): 이륙 지점 기준으로 다시 올릴지 묻는다. 그동안 기체는 멈춰 호버링한다. */
@Composable
private fun TerrainFallbackDialog(reason: String?, homeShiftM: Int?, vm: FlightViewModel) {
    FullscreenAlertDialog(
        onDismissRequest = vm::dismissTerrainFallback,
        title = { Text("지형 추적으로 시작하지 못했습니다") },
        // 기체가 준 이유(GPS 약함 등, 11-06)를 먼저 보인다
        text = {
            Text(
                listOfNotNull(
                    reason?.substringAfter("(", "")?.substringBeforeLast(")")?.takeIf { it.isNotBlank() }?.let { "기체 답: $it" },
                    // 이륙 지점 이동(6.7): 이륙 기준이면 땅 높이 차이만큼 촬영 고도가 달라진다
                    homeShiftM?.takeIf { it > Tuning.Preflight.HOME_SHIFT_WARN_M }?.let {
                        "이륙 지점이 계획과 ${it}m 다릅니다. 이륙 기준 고도라 땅 높이 차이만큼 촬영 고도가 달라집니다. 전주가 선 땅보다 낮은 곳이면 고도를 올리세요."
                    },
                    "이륙 지점 기준으로 다시 올리고 시작할까요? 비탈에서는 지면과의 거리가 달라지니 고도를 확인하세요. 취소하면 일시정지로 남습니다(수동 조종·RTH).",
                ).joinToString("\n\n"),
            )
        },
        confirmButton = { Button(onClick = vm::flyTakeoffHeight) { Text("이륙 기준으로 시작") } },
        dismissButton = { TextButton(onClick = vm::dismissTerrainFallback) { Text("취소") } },
    )
}

/**
 * 왼쪽 위 미션 칸(10.5.2, Pilot 2 p2s_2·p2_mission2·p2s_7): 한 줄 [초록 원 ⏸/▶ | 상태·진행률 | ˅]. 대기·업로드 뒤에는 Pilot 2 경로 열기처럼
 * [경로 유형 | 파란 ▶ | 경로 이름 | ˅]와 아래 경로 요약(거리·예상 시간·웨이포인트·사진, [summary] = 이번 소티). ▶는 대기: 비행 전 점검, 올림: 시작, 일시정지: 재개.
 * 아래에 우리 수(전주·사진, 10.5.11)와 지금 전주(한 줄 전체, 잘리지 않게). 일시정지면 [건너뛰기]·[완료 처리], 끝나면 [결과 보기].
 * 오른쪽 끝 ˅를 누르면 RC 버튼 안내를 펼친다. 큰 지도 위에서는 흰 칸에 검은 글자(Pilot 2 p2s_7), 영상 위에서는 어두운 칸에 흰 글자.
 */
@Composable
private fun MissionChip(s: FlightUiState, summary: RouteSummary?, vm: FlightViewModel, onShowResult: () -> Unit, modifier: Modifier) {
    val ui = s.exec
    val all = ui.visits
    // 미션 전체 수(홈·라이브러리와 같은 정의, [FlightUiState.missionPoles]): 이 화면을 열 때까지 + 이번 실행
    val done = s.finishedBefore + all.count { it.status == VisitStatus.DONE || it.status == VisitStatus.SKIPPED || it.status == VisitStatus.FAILED }
    val total = s.missionPoles
    // 사진은 지점(자동) 사진만(예상 수와 같은 셈, 10.5): 일시정지 중 수동·셔터·타이머 사진은 저장·목록에만(11-06 "33/32")
    val photos = s.photosBefore + all.sumOf { it.autoCaptureCount }
    val pct = if (total == 0) 0 else done * 100 / total
    val paused = ui.state == ExecState.Paused
    val canPause = ui.state.airborne && !paused && ui.state != ExecState.ReturningHome
    val idle = ui.state == ExecState.Idle || ui.state == ExecState.Uploaded
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
        idle -> s.routeName
        paused -> "일시정지 · 수동 비행"
        ui.state == ExecState.ReturningHome -> "RTH 진행 중"
        ui.state == ExecState.MissionDone -> stateLabel(ui.state, ui.remaining)
        canPause -> "비행 경로 수행 중…"
        else -> stateLabel(ui.state, ui.remaining)
    }
    val light = s.mapMain
    val fg = if (light) LightOnSurface else Color.White
    val soft = if (light) LightOnSurfaceVariant else TextSoft
    Column(
        modifier.width(if (idle) CHIP_IDLE_WIDTH else Dimens.CardWidth).background(if (light) LightSurface else CardBg, RoundedCornerShape(4.dp))
            .border(2.dp, Brand, RoundedCornerShape(4.dp)).padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (idle) {
                // 경로 유형(우리 유형은 전주 점검 하나, 10.5.8)과 칸 나눔 선
                Icon(painterResource(R.drawable.ic_route), contentDescription = "전주 점검 경로", modifier = Modifier.size(18.dp), tint = fg)
                Box(Modifier.width(1.dp).height(20.dp).background(soft.copy(alpha = 0.5f)))
            }
            action?.let { (play, label, onClick) -> ChipButton(play, label, if (idle) Accent else Tertiary, onClick) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = FontSize.Caption, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!idle) Text("$pct%", style = numberStyle(FontSize.Body), color = fg)
            }
            Icon(
                painterResource(R.drawable.ic_chevron_down), contentDescription = if (open) "안내 접기" else "안내 펼치기", tint = fg,
                modifier = Modifier.size(Dimens.TouchSmall).clickable(onClickLabel = if (open) "안내 접기" else "안내 펼치기") { open = !open }.padding(10.dp)
                    .rotate(if (open) 180f else 0f),
            )
        }
        if (idle) {
            Row(Modifier.padding(end = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(
                    "거리" to (summary?.let { "${it.distanceM.toInt()} m" } ?: "--"),
                    "예상 시간" to (summary?.let { durationLabel(it.durationSec) } ?: "--"),
                    "웨이포인트" to (summary?.waypoints?.toString() ?: "--"),
                    "사진" to (summary?.photos?.toString() ?: "--"),
                ).forEach { (label, value) ->
                    Column {
                        Text(label, fontSize = FontSize.Micro, color = soft, maxLines = 1)
                        Text(value, style = numberStyle(FontSize.Body), color = fg, maxLines = 1)
                    }
                }
            }
            if (ui.state == ExecState.Uploaded) Text("업로드 완료 · ▶ 시작", fontSize = FontSize.Caption, color = soft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            CardFigure("전주", "$done", "/$total", fg, soft)
            CardFigure("사진", "$photos", "/${s.expectedPhotos}", fg, soft)
        }
        // 지금 전주: 셋째 칸으로 두면 잘려서 한 줄 전체에
        ui.target?.pole?.displayName?.let {
            Text("→ $it", Modifier.padding(end = 6.dp), fontSize = FontSize.Caption, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (paused) Row(Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CardButton("건너뛰기", MaterialTheme.colorScheme.surfaceContainer, Color.White, { vm.skip() }, Modifier.weight(1f), height = Dimens.ButtonChip)
            CardButton("완료 처리", MaterialTheme.colorScheme.surfaceContainer, Color.White, { vm.markDone() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        }
        if (ui.state == ExecState.MissionDone) {
            CardButton("결과 보기", MaterialTheme.colorScheme.primary, Color.White, onShowResult, Modifier.padding(end = 6.dp).fillMaxWidth(), height = Dimens.ButtonChip)
        }
        if (open) {
            Text(
                buildAnnotatedString {
                    append("RC ")
                    withStyle(SpanStyle(color = fg, fontWeight = FontWeight.SemiBold)) { append("Ⅱ") }
                    append(" 일시정지 · ")
                    withStyle(SpanStyle(color = fg, fontWeight = FontWeight.SemiBold)) { append("▶") }
                    append(" 재개(일시정지 중 5D 누름) · C1 렌즈 · C2 지도 · 셔터 = 수동 사진")
                },
                Modifier.padding(end = 6.dp), fontSize = FontSize.Micro, color = soft,
            )
        }
    }
}

/** 대기·업로드 뒤 미션 칸 폭: 경로 요약 4칸(Pilot 2 p2s_2)이 들어가는 폭. */
private val CHIP_IDLE_WIDTH = 236.dp

/** 미션 칸 원 버튼(Pilot 2): [bg] 원에 흰 ▶([play]) 또는 ⏸. 대기는 파랑, 비행 중은 초록. */
@Composable
private fun ChipButton(play: Boolean, label: String, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(Dimens.TouchSmall).clip(CircleShape).background(bg).clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            if (play) drawPath(Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }, Color.White)
            else {
                drawRect(Color.White, Offset(size.width * 0.1f, 0f), Size(size.width * 0.3f, size.height))
                drawRect(Color.White, Offset(size.width * 0.6f, 0f), Size(size.width * 0.3f, size.height))
            }
        }
    }
}

@Composable
private fun CardFigure(label: String, value: String, suffix: String, fg: Color, soft: Color) {
    Text(
        buildAnnotatedString {
            append("$label ")
            withStyle(numberStyle(FontSize.Body).toSpanStyle().copy(color = fg)) { append(value) }
            append(suffix)
        },
        fontSize = FontSize.Caption, color = soft, maxLines = 1,
    )
}

@Composable
private fun CardButton(label: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), height: Dp = 34.dp) {
    Button(
        onClick = onClick, modifier = modifier.height(height), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 6.dp),
        colors = ButtonDefaults.buttonColors(bg, fg),
    ) { Text(label, fontSize = FontSize.Body, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/** FPV 영상. 실제 기체는 카메라 스트림(프레임 크기를 [onStreamSize]로), 모의 기체는 안내 문구(10장). */
@Composable
private fun FpvView(simulated: Boolean, modifier: Modifier, onStreamSize: (IntSize) -> Unit) {
    if (!simulated) return DjiFpvView(modifier.background(Color.Black), onStreamSize)
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

/** 모의 조작(모의 기체 전용): RC 일시정지 버튼, RC RTH 버튼(실제 기체는 펌웨어), 배터리 −30%, 장애물(없음 → 8m → 4m), HMS·GEO 모의 값 켜기. */
@Composable
private fun MockRcControls(vm: FlightViewModel, modifier: Modifier) {
    // [장애물]·[HMS]가 들어가게 카드보다 넓다
    Row(modifier.width(Dimens.CardWidth + 104.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("모의 RC", fontSize = FontSize.Caption, color = TextSoft)
        CardButton("Ⅱ", CardBg, Color.White, { vm.rcPause() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        CardButton("RTH", CardBg, Color.White, { vm.returnHome() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        CardButton("−30%", CardBg, Color.White, { vm.dropBattery() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        CardButton("장애물", CardBg, Color.White, { vm.cycleObstacle() }, Modifier.weight(1f), height = Dimens.ButtonChip)
        CardButton("HMS", CardBg, Color.White, { vm.toggleMockHealth() }, Modifier.weight(1f), height = Dimens.ButtonChip)
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
                Text("${sortieLabel(s.sortieNumber, s.sortieTotal)} 완료", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

/** 모드 스위치 안내를 보이는 상태: 시작 전(대기·올림)·구간 준비·일시정지. 비행 중에는 스위치를 옮기면 기체가 알아서 멈춘다. */
private val MODE_CHECK_STATES = setOf(ExecState.Idle, ExecState.Uploaded, ExecState.PreparingLeg, ExecState.Paused)
