package com.example.myfirstapp.ui.flight

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.data.ProjectDao

/** 지도의 전주 색(데이터 표시, 10.1 예외): 완료·건너뜀·실패·진행·대기, 기체. */
internal val PoleDone = Color(0xFF4CD08A)
internal val PoleSkipped = Color(0xFF80858E)
internal val PoleFailed = Color(0xFFFF5449)
internal val PoleActive = Color(0xFF5B9BFF)
internal val PolePending = Color(0xFFFF9F43)
internal val Aircraft = Color(0xFFFFC53D)

/** 영상·지도 위 버튼과 글자 바탕(10.1 DJI 톤): 뒤가 비치도록 반투명 검정. */
private val OverlayBg = Color.Black.copy(alpha = 0.5f)

/**
 * 비행 화면 ④(10장). 상태와 명령은 [FlightViewModel]이 갖고, 이 화면은 그리기와 입력 전달만 한다.
 * [onReview]는 소티 완료 화면에서 보조자가 1차 판독으로 들어갈 때, [onShowResult]는 미션이 끝났을 때 부른다.
 */
@Composable
fun FlightScreen(
    projectId: Long,
    missionId: Long,
    dao: ProjectDao,
    onExit: () -> Unit,
    onShowResult: () -> Unit,
    onReview: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: FlightViewModel = viewModel(factory = FlightViewModel.factory(
            dao, projectId, missionId, context.getExternalFilesDir(null), app.settings, app.rcButtons,
        ))
    val s by vm.state.collectAsStateWithLifecycle()
    // 1차 판독 화면에서 돌아오면 판독 수를 다시 센다
    LaunchedEffect(Unit) { vm.refreshSortieSummary() }
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
        else -> FlightContent(s, vm, onExit, onShowResult, onReview)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun FlightContent(s: FlightUiState, vm: FlightViewModel, onExit: () -> Unit, onShowResult: () -> Unit, onReview: () -> Unit) {
    val ui = s.exec
    // DJI UX SDK 기본 화면 배치(10장): FPV 전체 화면, 위쪽 얇은 상태바, 오른쪽 아래 미니맵. 미니맵을 누르면 자리를 바꾼다.
    var mapMain by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 큰 지도는 손으로 움직일 수 있고, 처음에는 왼쪽 모의 RC와 오른쪽 버튼을 피해 맞춘다
        if (mapMain) FlightMap(s, interactive = true, pad = listOf(170.dp, 110.dp, 230.dp, 160.dp), Modifier.fillMaxSize())
        else FpvView(s.simulated, Modifier.fillMaxSize())

        // 미니맵은 버튼보다 먼저 그려 버튼을 가리지 않게 한다
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(240.dp, 135.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (mapMain) FpvView(s.simulated, Modifier.fillMaxSize())
            else FlightMap(s, interactive = false, pad = List(4) { 12.dp }, Modifier.fillMaxSize())
            // 지도 뷰가 터치를 먹으므로 위에 투명한 누름 영역을 둔다
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "지도와 영상 자리 바꾸기") { mapMain = !mapMain })
        }

        Column(Modifier.fillMaxWidth()) {
            TopBar(s, onExit)
            if (ui.state != ExecState.SortieDone) ProgressPanel(s, Modifier.padding(start = 12.dp, top = 8.dp))
        }

        // 오른쪽: 명령 버튼(자동 비행 중에는 [RTH 길게]뿐, 10장)
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { SideControls(s, vm) }

        if (s.simulated && ui.state != ExecState.SortieDone) MockRcControls(vm, Modifier.align(Alignment.BottomStart).padding(12.dp))

        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { BottomAction(s, vm, onShowResult) }

        if (ui.state == ExecState.SortieDone) SortieDonePanel(s, vm, onReview)
    }
}

/** FPV 영상. 실제 기체는 카메라 스트림, 모의 기체는 안내 문구(10장). */
@Composable
private fun FpvView(simulated: Boolean, modifier: Modifier) {
    if (!simulated) return DjiFpvView(modifier.background(Color.Black))
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Text(
            if (simulated) "FPV 영상 (모의 기체: 영상 없음)" else "FPV 영상",
            color = Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 위쪽 상태바(DJI 기본 화면처럼 한 줄): 상태, 배터리와 복귀 필요량, 고도, GPS. */
@Composable
private fun TopBar(s: FlightUiState, onExit: () -> Unit) {
    val t = s.telemetry
    val state = s.exec.state
    val stateColor = when (state) {
        ExecState.Paused, ExecState.ReturningHome -> Aircraft
        ExecState.LinkLost -> PoleFailed
        else -> PoleDone
    }
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (s.canExit) TextButton(onClick = onExit) { Text("← 나가기") }
        Text(stateLabel(state), color = stateColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (t != null) {
            Text("배터리 ${t.batteryPct.toInt()}% (복귀 ${s.returnNeedPct}%)", color = Color.White)
            Text("${t.altitudeM.toInt()}m", color = Color.White)
            Text("GPS ${"●".repeat(t.gpsLevel)}${"○".repeat((5 - t.gpsLevel).coerceAtLeast(0))}", color = Color.White)
        }
        if (s.simulated) Text("모의 기체 ×${FAKE_TIME_SCALE.toInt()}", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ProgressPanel(s: FlightUiState, modifier: Modifier) {
    val ui = s.exec
    val sortieVisits = ui.visits.filter { it.sortieIndex == ui.sortieIndex }
    val done = sortieVisits.count { it.status == VisitStatus.DONE || it.status == VisitStatus.SKIPPED || it.status == VisitStatus.FAILED }
    val target = ui.target?.let { "${it.pole.poleId} (${it.order + 1}/${sortieVisits.size})" } ?: "전주 ${sortieVisits.size}개"
    Column(modifier.background(OverlayBg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("소티 ${ui.sortieIndex + 1}/${s.sortieCount} · $target · 처리 $done/${sortieVisits.size}", color = Color.White)
        ui.countdownSec?.let { Text("다음 전주까지 ${it}s", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        if (ui.state == ExecState.Paused) Text("수동 조종 가능", color = Aircraft)
        ui.rthCountdownSec?.let { Text("배터리 부족 — ${it}초 뒤 자동 RTH", color = MaterialTheme.colorScheme.error) }
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        ui.preflightFailures.forEach { Text("점검 실패: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SideControls(s: FlightUiState, vm: FlightViewModel) {
    val state = s.exec.state
    if (state == ExecState.Paused) {
        // 화면 높이(약 400dp)가 좁아 가로 한 줄로 둔다. 뒤 영상이 비치게 반투명
        val colors = ButtonDefaults.filledTonalButtonColors(containerColor = OverlayBg, contentColor = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = vm::manualCapture, modifier = Modifier.heightIn(min = 48.dp), colors = colors) { Text("촬영") }
            FilledTonalButton(onClick = vm::skip, modifier = Modifier.heightIn(min = 48.dp), colors = colors) { Text("건너뛰기") }
            FilledTonalButton(onClick = vm::markDone, modifier = Modifier.heightIn(min = 48.dp), colors = colors) { Text("완료 처리") }
        }
    }
    if (state.airborne && state != ExecState.ReturningHome) RthButton(vm::returnHome)
}

/** 화면 아래 가운데의 큰 버튼: 비행 시작, 보조용 [재개](일시정지 때만, 초록), 미션 완료(10장). */
@Composable
private fun BottomAction(s: FlightUiState, vm: FlightViewModel, onShowResult: () -> Unit) {
    if (s.telemetry?.landingConfirmNeeded == true) {
        // 착륙 보호: 지면 바로 위에서 기체가 확인을 기다린다(Pilot 2 [착륙 확인]). RC 왼쪽 스틱을 내려도 된다
        return Button(onClick = vm::confirmLanding, modifier = Modifier.size(240.dp, 80.dp), shape = RoundedCornerShape(16.dp)) {
            Text("착륙 확인", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
    when (s.exec.state) {
        ExecState.Idle -> Button(onClick = vm::start, modifier = Modifier.size(240.dp, 64.dp)) {
            Text("비행 시작", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        ExecState.Paused -> Button(
            onClick = vm::resume,
            modifier = Modifier.size(240.dp, 80.dp),
            colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f), MaterialTheme.colorScheme.onTertiary),
            shape = RoundedCornerShape(16.dp),
        ) { Text("▶ 재개 (C1)", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        ExecState.MissionDone -> Button(onClick = onShowResult, modifier = Modifier.size(240.dp, 64.dp)) {
            Text("미션 완료 · 결과 보기", fontWeight = FontWeight.Bold)
        }
        else -> Unit
    }
}

/** 모의 조작(기체 연동 전 전용). RC Pro의 일시정지·C1·RTH 버튼으로도 같은 동작을 한다(10장). */
@Composable
private fun MockRcControls(vm: FlightViewModel, modifier: Modifier) {
    val colors = ButtonDefaults.outlinedButtonColors(containerColor = OverlayBg, contentColor = Color.White)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("모의 RC", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        OutlinedButton(onClick = { vm.rcPause() }, colors = colors) { Text("RC 일시정지") }
        OutlinedButton(onClick = { vm.rcResume() }, colors = colors) { Text("RC 재개") }
        OutlinedButton(onClick = { vm.dropBattery() }, colors = colors) { Text("배터리 −30%") }
    }
}

/**
 * 소티 완료 화면(10장 2인 운용). 왼쪽은 조종사(배터리 교체 확인 → 다음 소티), 오른쪽은 보조자(1차 판독).
 * 확인 항목을 모두 체크해야 [다음 소티 시작]이 켜진다. 판독은 비행과 독립이라 다음 소티를 막지 않는다.
 */
@Composable
private fun SortieDonePanel(s: FlightUiState, vm: FlightViewModel, onReview: () -> Unit) {
    val ui = s.exec
    val remaining = ui.visits.count { it.status == VisitStatus.PENDING }
    var battery by rememberSaveable(ui.sortieIndex) { mutableStateOf(false) }
    var props by rememberSaveable(ui.sortieIndex) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("소티 ${ui.sortieIndex + 1}/${s.sortieCount} 완료", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("조종사", style = MaterialTheme.typography.titleMedium)
                        CheckRow("배터리 교체", battery) { battery = it }
                        CheckRow("프로펠러·기체 확인", props) { props = it }
                        Text("남은 전주 $remaining", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = vm::nextSortie, enabled = battery && props, modifier = Modifier.heightIn(min = 56.dp)) {
                                Text("다음 소티 시작")
                            }
                            OutlinedButton(onClick = vm::endMission, modifier = Modifier.heightIn(min = 56.dp)) { Text("미션 종료") }
                        }
                    }
                }
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("보조자 · 1차 판독", style = MaterialTheme.typography.titleMedium)
                        val sum = s.sortieSummary
                        if (sum == null) Text("집계 중입니다")
                        else {
                            Text("이번 소티 전주 ${sum.poles} · 검출 ${sum.detected} · 판독 ${sum.reviewed}/${sum.poles}")
                            Text("배터리를 바꾸는 동안 검출 전주부터 판독합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = onReview, modifier = Modifier.padding(top = 8.dp).heightIn(min = 56.dp)) { Text("1차 판독 시작") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // 줄 전체를 눌러 체크한다(장갑 낀 손가락, MD3 체크박스 목록)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/** 잘못 누르지 않도록 1초 이상 눌러야 동작하는 RTH 버튼(10장). */
@Composable
private fun RthButton(onLongPress: () -> Unit) {
    Box(
        Modifier.size(width = 180.dp, height = 56.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
            .semantics { contentDescription = "RTH 길게 누르기" }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
        contentAlignment = Alignment.Center,
    ) { Text("RTH (길게)", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold) }
}

private fun stateLabel(s: ExecState) = when (s) {
    ExecState.Idle -> "비행 대기"
    ExecState.PreflightCheck -> "비행 전 점검"
    ExecState.Takeoff -> "이륙 중"
    ExecState.PreparingLeg -> "구간 준비"
    ExecState.FlyingLeg -> "이동 중"
    ExecState.Arrived -> "도착"
    ExecState.Capturing -> "촬영 중"
    ExecState.Downloading -> "다운로드 중"
    ExecState.Analyzing -> "분석 중"
    ExecState.Waiting -> "대기"
    ExecState.Paused -> "일시정지됨"
    ExecState.LinkLost -> "연결 끊김"
    ExecState.ReturningHome -> "복귀 중"
    ExecState.SortieDone -> "소티 완료"
    ExecState.MissionDone -> "미션 완료"
}
