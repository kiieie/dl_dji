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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.R
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.drone.DjiFpvView
import com.example.myfirstapp.ui.home.LatestMission
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.TextSoft

/**
 * 수동 비행 카메라 뷰(10.4.3 시안, Pilot 2 54쪽): 홈의 [수동 비행]. 미션 없이 FPV·상단 바·내비 원판·지도,
 * 오른쪽 한 열(렌즈·셔터 언제든), 왼쪽 짐벌, [RTH 길게], 착륙 확인. 왼쪽 위 [미션 이어서 비행]을 누르면 최근 미션 비행 화면으로 간다.
 */
@Composable
fun ManualFlightScreen(dao: ProjectDao, onExit: () -> Unit, onOpenSettings: () -> Unit, onOpenMission: (LatestMission) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: ManualFlightViewModel = viewModel(factory = ManualFlightViewModel.factory(dao, context.getExternalFilesDir(null), app.rcButtons))
    val s by vm.state.collectAsStateWithLifecycle()
    val f = s.flight
    var mapMain by rememberSaveable { mutableStateOf(false) }
    // 날고 있으면 RC `<`(안드로이드 뒤로)로 카메라 뷰를 떠나지 않는다(10.3). 화면 ‹ 버튼은 그대로
    BackHandler(enabled = f.telemetry?.isFlying == true) {}
    LifecycleResumeEffect(vm) {
        vm.active = true
        onPauseOrDispose { vm.active = false }
    }

    // 10.4.3 수동 비행 배치: 자동 비행과 같은 틀. 왼쪽 위 [미션 이어서 비행](착륙 상태에서만), 왼쪽 짐벌 버튼
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fpv: @Composable (Modifier) -> Unit = { m ->
            if (s.connected) DjiFpvView(m) else Box(m.background(Color.Black), contentAlignment = Alignment.Center) {
                Text("기체 연결을 기다립니다", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
        if (mapMain) FlightMap(f, interactive = true, pad = List(4) { 90.dp }, Modifier.fillMaxSize()) else fpv(Modifier.fillMaxSize())
        MiniMap(mapMain, onSwap = { mapMain = !mapMain }, Modifier.align(Alignment.BottomStart)) { m ->
            if (mapMain) fpv(m) else FlightMap(f, interactive = false, pad = List(4) { 8.dp }, m)
        }

        TopBar(f, onExit, onOpenSettings, label = "수동 비행", canExit = true)
        ZoomBox(f.lens, f.zoomRatio, Modifier.align(Alignment.TopCenter).padding(top = TOP_BAR_HEIGHT + 9.dp))

        Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = TOP_BAR_HEIGHT + 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 떠 있는 동안 비행 화면으로 바꾸면 이 화면의 RTH·착륙 확인을 잃으므로 착륙한 뒤에만 보인다
            s.latest?.takeIf { it.remaining > 0 && f.telemetry?.isFlying != true }?.let { MissionPill(it, onOpen = { onOpenMission(it) }) }
            s.message?.let {
                Text(
                    it, Modifier.width(CARD_WIDTH).background(CardBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 11.sp,
                )
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 150.dp)) { OverlayIconButton(R.drawable.ic_gimbal_center, "짐벌 중앙/아래", vm::toggleGimbal) }

        CameraColumn(
            s = f, onLens = vm::setLens,
            shutterEnabled = s.connected && !s.shooting, onShutter = vm::shutter,
            cameraEnabled = s.connected, onVideoMode = vm::setVideoMode, onZoom = vm::zoom,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = TOP_BAR_HEIGHT + 37.dp, end = 6.dp),
            shotsLabel = "${s.shots}장",
        )

        val t = f.telemetry
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (t?.landingConfirmNeeded == true) LandingConfirmButton(vm::confirmLanding)
            t?.let { NavDisplay(it, f.home) }
        }
        if (t != null && t.isFlying) RthButton(vm::returnHome, Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/** 왼쪽 위 [미션 이어서 비행](10.4.3, 196×44): 남은 전주가 있는 최근 미션. 누르면 그 미션의 비행 화면(비행 전 점검)으로 간다. */
@Composable
private fun MissionPill(m: LatestMission, onOpen: () -> Unit) {
    Row(
        Modifier.width(CARD_WIDTH).height(44.dp).clip(RoundedCornerShape(4.dp)).background(CardBg)
            .clickable(onClickLabel = "미션 이어서 비행", onClick = onOpen).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_route), contentDescription = null, modifier = Modifier.size(16.dp), tint = Accent)
        Column(Modifier.weight(1f)) {
            Text(if (m.done == 0) "미션 비행 시작" else "미션 이어서 비행", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text("#${m.missionId} · 남은 전주 ${m.remaining} · 착륙 상태에서", fontSize = 11.sp, color = TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(12.dp), tint = TextSoft)
    }
}
