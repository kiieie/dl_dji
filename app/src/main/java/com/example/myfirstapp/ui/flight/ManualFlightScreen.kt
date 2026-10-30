package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.drone.DjiFpvView
import com.example.myfirstapp.ui.home.LatestMission

/**
 * 수동 비행 카메라 뷰(Pilot 2 카메라 뷰, 매뉴얼 54쪽, 10.0): 홈의 [수동 비행]. 미션 없이 FPV·상단 바·내비 계기·지도,
 * 오른쪽에 렌즈·셔터(언제든)·짐벌, [RTH 길게], 착륙 확인. 왼쪽 위 경로 알약(55쪽)을 누르면 최근 미션을 이어서 비행한다.
 */
@Composable
fun ManualFlightScreen(dao: ProjectDao, onExit: () -> Unit, onOpenSettings: () -> Unit, onOpenMission: (LatestMission) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: ManualFlightViewModel = viewModel(factory = ManualFlightViewModel.factory(dao, context.getExternalFilesDir(null), app.rcButtons))
    val s by vm.state.collectAsStateWithLifecycle()
    val f = s.flight
    var mapMain by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fpv: @Composable (Modifier) -> Unit = { m ->
            if (s.connected) DjiFpvView(m) else Box(m.background(Color.Black), contentAlignment = Alignment.Center) {
                Text("기체 연결을 기다립니다", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (mapMain) FlightMap(f, interactive = true, pad = List(4) { 90.dp }, Modifier.fillMaxSize()) else fpv(Modifier.fillMaxSize())
        MiniMap(mapMain, onSwap = { mapMain = !mapMain }, Modifier.align(Alignment.BottomStart)) { m ->
            if (mapMain) fpv(m) else FlightMap(f, interactive = false, pad = List(4) { 8.dp }, m)
        }

        Column(Modifier.fillMaxWidth()) {
            TopBar(f, onExit, onOpenSettings, label = "수동 비행", canExit = true)
            // 떠 있는 동안 비행 화면으로 바꾸면 이 화면의 RTH·착륙 확인을 잃으므로 착륙한 뒤에만 보인다
            s.latest?.takeIf { it.remaining > 0 && f.telemetry?.isFlying != true }?.let { MissionPill(it, onOpen = { onOpenMission(it) }, Modifier.padding(start = 8.dp, top = 6.dp)) }
            s.message?.let {
                Text(
                    it, color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp).background(OverlayBg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        val t = f.telemetry
        if (t != null && t.isFlying) Box(Modifier.align(Alignment.TopEnd).padding(top = TOP_BAR_HEIGHT + 8.dp, end = 8.dp)) { RthButton(vm::returnHome) }

        CameraColumn(
            lens = f.lens, onLens = vm::setLens,
            shutterEnabled = s.connected && !s.shooting, onShutter = vm::shoot,
            onGimbal = vm::toggleGimbal,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp, top = TOP_BAR_HEIGHT),
            shotsLabel = "${s.shots}장",
        )

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (t?.landingConfirmNeeded == true) LandingConfirmButton(vm::confirmLanding)
            t?.let { NavDisplay(it, f.home, Modifier.padding(top = 6.dp)) }
        }
    }
}

/** 왼쪽 위 경로 알약(매뉴얼 55쪽 22번 "Mapping1 ▶"): 최근 미션 이름과 진행. 누르면 그 미션의 비행 화면(비행 전 점검)으로 간다. */
@Composable
private fun MissionPill(m: LatestMission, onOpen: () -> Unit, modifier: Modifier) {
    Surface(color = OverlayBg, shape = RoundedCornerShape(24.dp), modifier = modifier.widthIn(max = 320.dp)) {
        Row(Modifier.clickable(onClickLabel = "이어서 비행", onClick = onOpen).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("▶", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 8.dp, end = 12.dp)) {
                Text("이어서 비행 · ${m.projectName} #${m.missionId}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("완료 ${m.done}/${m.total} · 남은 전주 ${m.remaining}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
