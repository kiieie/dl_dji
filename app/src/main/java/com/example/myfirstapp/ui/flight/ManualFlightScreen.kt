package com.example.myfirstapp.ui.flight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.flightTitle
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.drone.DjiFpvView
import com.example.myfirstapp.ui.home.LatestMission
import com.example.myfirstapp.ui.mission.LibraryPanel
import com.example.myfirstapp.ui.mission.MissionNav
import com.example.myfirstapp.ui.mission.libraryPanelViewModel
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.TextSoft

/**
 * 수동 비행 카메라 뷰(10.5.2, Pilot 2 카메라 뷰): 홈의 [카메라 뷰 입력]. 미션 없이 FPV·상단 바·내비 원판·지도,
 * 자동 비행과 같은 왼쪽 열(짐벌 메뉴·ZOOM)·오른쪽 열(셔터 언제든)·노출 띠, 착륙 확인. 왼쪽 위 [미션 이어서 비행]을 누르면 최근 미션 비행 화면으로 간다.
 * RTH는 RC RTH 버튼(기체 펌웨어, Pilot 2와 같음).
 */
@Composable
fun ManualFlightScreen(
    dao: ProjectDao, onExit: () -> Unit, onOpenSettings: () -> Unit, onOpenMission: (LatestMission) -> Unit, onAlbum: () -> Unit = {},
    /** 큰 지도 라이브러리 패널(10.5.8)의 [열기]·편집: 이 화면을 닫고 그 미션·계획으로(착륙 상태에서만). */
    onLibraryNav: (MissionNav) -> Unit = {},
    /** 상단 바 비행 상태 글자(10.5.1): 비행 전 점검. */
    onPreflight: () -> Unit = {},
    /** 카메라 설정 아이콘(10.5.2): 기체 점검의 짐벌·카메라 탭. */
    onCameraSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: ManualFlightViewModel = viewModel(factory = ManualFlightViewModel.factory(dao, context.getExternalFilesDir(null), app.rcButtons, app.settings, app.precise, app.appScope))
    val s by vm.state.collectAsStateWithLifecycle()
    val f = s.flight
    val mapMain = f.mapMain
    // 라이브러리 패널(10.5.8): 큰 지도 오른쪽. 열 때만 ViewModel을 만든다
    var library by rememberSaveable { mutableStateOf(false) }
    val lib = if (library && mapMain) libraryPanelViewModel(dao, s.latest?.projectId) else null
    val ls = lib?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(lib) { lib?.nav?.collect(onLibraryNav) }
    // 날고 있으면 RC `<`(안드로이드 뒤로)로 카메라 뷰를 떠나지 않는다(10.3). 화면 ‹ 버튼은 그대로
    BackHandler(enabled = f.telemetry?.isFlying == true) {}
    LifecycleResumeEffect(vm) {
        vm.active = true
        onPauseOrDispose { vm.active = false }
    }

    // 자동 비행과 같은 틀(10.5.2). 왼쪽 위 [미션 이어서 비행](착륙 상태에서만)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // FPV 프레임 크기(격자·줌 영역 사각형을 영상 자리에, 10.5.2). 모르면 4:3
        var stream by remember { mutableStateOf<IntSize?>(null) }
        val fpv: @Composable (Modifier) -> Unit = { m ->
            if (s.connected) DjiFpvView(m) { stream = it } else Box(m.background(Color.Black), contentAlignment = Alignment.Center) {
                Text("기체 연결을 기다립니다", color = Color.White.copy(alpha = 0.5f), fontSize = FontSize.Body)
            }
        }
        if (mapMain) FlightMap(f, interactive = true, pad = List(4) { 90.dp }, Modifier.fillMaxSize(), vm::setMapSource, vm::clearTrack, preview = ls?.previewRoute.orEmpty(), onSetHome = vm::setHome)
        else {
            fpv(Modifier.fillMaxSize())
            CameraOverlay(f.lens, f.zoomRatio, focusEnabled = s.connected, onFocus = vm::focusAt, Modifier.fillMaxSize(), stream, f.camera.ev, vm::setEv)
            if (f.ar) ArOverlay(f, stream, Modifier.fillMaxSize())
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            // 마지막 사진(9.6): 미니맵 바로 위(미니맵을 키우면 그 위), 가장 최근 사진의 설비 상자
            f.lastPhoto?.let { LastPhotoThumb(it, Modifier) }
            MiniMap(mapMain, onSwap = vm::swapView, Modifier) { m ->
                if (mapMain) fpv(m) else FlightMap(f, interactive = false, pad = List(4) { 8.dp }, m)
            }
        }

        TopBar(f, flightTitle(f.telemetry), onExit, onOpenSettings, canExit = true, onTitleClick = onPreflight)
        if (!mapMain) {
            ZoomBox(f.lens, f.zoomRatio, Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 9.dp))
            ExposureStrip(f.camera, f.videoMode, focusEnabled = s.connected, onFocusMode = vm::cycleFocus, Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar + 8.dp, end = 48.dp))
            CameraLeftColumn(f.lens, vm::setLens, vm::setGimbal, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = Dimens.TopBar + 19.dp), f.telemetry, f.ar, vm::toggleAr)
            CameraColumn(
                s = f, shutterEnabled = s.connected && !f.capturing, onShutter = vm::shutter,
                cameraEnabled = s.connected, onVideoMode = vm::setVideoMode, onZoom = vm::zoom, onCameraSettings = onCameraSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar + 8.dp, end = 6.dp),
                onAlbum = onAlbum, shotsLabel = "${s.shots}장",
                onOpenMenu = vm::openShootMenu, onCloseMenu = vm::closeShootMenu, onShootMode = vm::setShootMode, onTimer = vm::setTimer,
            )
        }
        // 떠 있는 동안 비행 화면으로 바꾸면 이 화면의 착륙 확인을 잃으므로 착륙한 뒤에만 보인다
        s.latest?.takeIf { it.remaining > 0 && f.telemetry?.isFlying != true }?.let {
            MissionPill(it, onOpen = { onOpenMission(it) }, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = Dimens.TopBar + 19.dp))
        }
        NoticeLines(listOfNotNull(s.message?.let { it to Color.White }), Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 48.dp).widthIn(max = 300.dp))
        if (!mapMain) CameraNoticeView(f.notice)

        val t = f.telemetry
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (t?.landingConfirmNeeded == true) LandingConfirmButton(vm::confirmLanding)
            // Pilot 2 지도 뷰에는 내비 원판이 없다(10.5.4)
            if (!mapMain) t?.let { NavDisplay(it, f.home, f.obstacles) }
        }
        if (mapMain) Box(Modifier.align(LibraryButtonAlignment).padding(8.dp)) { LibraryButton { library = !library } }
        if (ls != null) LibraryPanel(
            ls, lib, current = null, canLeave = t?.isFlying != true, onClose = { library = false }, Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar).fillMaxHeight(),
        )
    }
}

/** 왼쪽 위 [미션 이어서 비행](10.4.3, 196×44): 남은 전주가 있는 최근 미션. 누르면 그 미션의 비행 화면(비행 전 점검)으로 간다. */
@Composable
private fun MissionPill(m: LatestMission, onOpen: () -> Unit, modifier: Modifier) {
    Row(
        modifier.width(Dimens.CardWidth).height(Dimens.ButtonPrimary).clip(RoundedCornerShape(4.dp)).background(CardBg)
            .clickable(onClickLabel = "미션 이어서 비행", onClick = onOpen).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_route), contentDescription = null, modifier = Modifier.size(16.dp), tint = Accent)
        Column(Modifier.weight(1f)) {
            Text(if (m.done == 0) "미션 비행 시작" else "미션 이어서 비행", fontSize = FontSize.Body, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text("#${m.missionId} · 남은 전주 ${m.remaining} · 착륙 상태에서", fontSize = FontSize.Caption, color = TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(12.dp), tint = TextSoft)
    }
}
