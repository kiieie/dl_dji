package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.drone.DjiFpvView

/**
 * 수동 비행 카메라 뷰(Pilot 2 카메라 뷰, 10.0): 홈의 [카메라 뷰로 들어가기]. 미션 없이 FPV·상단 바·내비 계기·지도,
 * 오른쪽에 렌즈·셔터(언제든)·짐벌, [RTH 길게], 착륙 확인. 자동 점검은 미션의 [비행 시작]에서.
 */
@Composable
fun ManualFlightScreen(onExit: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: ManualFlightViewModel = viewModel(factory = ManualFlightViewModel.factory(context.getExternalFilesDir(null), app.rcButtons))
    val s by vm.state.collectAsStateWithLifecycle()
    val f = s.flight
    var mapMain by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fpv: @Composable (Modifier) -> Unit = { m ->
            if (s.connected) DjiFpvView(m) else Box(m.background(Color.Black), contentAlignment = Alignment.Center) {
                Text("기체 연결을 기다립니다", color = Color.White.copy(alpha = 0.5f))
            }
        }
        if (mapMain) FlightMap(f, interactive = true, pad = List(4) { 120.dp }, Modifier.fillMaxSize()) else fpv(Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).size(240.dp, 135.dp),
            shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (mapMain) fpv(Modifier.fillMaxSize()) else FlightMap(f, interactive = false, pad = List(4) { 12.dp }, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "지도와 영상 자리 바꾸기") { mapMain = !mapMain })
        }

        Column(Modifier.fillMaxWidth()) {
            TopBar(f, onExit, onOpenSettings, label = "수동 비행", canExit = true)
            s.message?.let {
                Text(it, color = Color.White, modifier = Modifier.padding(start = 12.dp, top = 8.dp).background(OverlayBg, RoundedCornerShape(8.dp)).padding(8.dp))
            }
        }

        val t = f.telemetry
        if (t != null && t.isFlying) Box(Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp)) { RthButton(vm::returnHome) }

        // 오른쪽 카메라 버튼: 렌즈, 셔터(크게), 짐벌
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val colors = ButtonDefaults.filledTonalButtonColors(containerColor = OverlayBg, contentColor = Color.White)
            FilledTonalButton(onClick = { vm.setLens(if (f.lens == Lens.WIDE) Lens.ZOOM else Lens.WIDE) }, colors = colors) { Text(if (f.lens == Lens.WIDE) "광각" else "망원") }
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(38.dp)).background(Color.White)
                    .clickable(enabled = s.connected && !s.shooting, onClickLabel = "촬영") { vm.shoot() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(62.dp).clip(RoundedCornerShape(31.dp)).background(if (s.shooting) Color.Gray else PoleFailed)) }
            Text("${s.shots}장", color = Color.White)
            FilledTonalButton(onClick = vm::toggleGimbal, colors = colors) { Text("짐벌 ⇅") }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (t?.landingConfirmNeeded == true) {
                Button(onClick = vm::confirmLanding, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(240.dp, 72.dp)) {
                    Text("착륙 확인", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            t?.let { NavDisplay(it, f.home, Modifier.padding(top = 8.dp)) }
        }
    }
}
