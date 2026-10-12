package com.example.myfirstapp.ui.flight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.data.ProjectDao

/** 위에서 본 그림의 전주 색(데이터 표시, 10.1 예외): 완료·건너뜀·실패·진행·대기, 기체. */
private val PoleDone = Color(0xFF4CD08A)
private val PoleSkipped = Color(0xFF80858E)
private val PoleFailed = Color(0xFFFF5449)
private val PoleActive = Color(0xFF5B9BFF)
private val PolePending = Color(0xFFFF9F43)
private val Aircraft = Color(0xFFFFC53D)

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
    val vm: FlightViewModel = viewModel(factory = FlightViewModel.factory(
            dao, projectId, missionId, context.getExternalFilesDir(null), (context.applicationContext as InspectionApp).settings,
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        // FPV 자리. 기체 연동 뒤 DJI UX SDK의 FPV 위젯으로 바꾼다(10장)
        TopDownView(s, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBar(s)
            if (ui.state != ExecState.SortieDone) ProgressPanel(s)
        }
        if (ui.state != ExecState.SortieDone) {
            Card(
                Modifier.align(Alignment.TopEnd).padding(12.dp).width(340.dp),
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Controls(s, vm, onExit, onShowResult)
                }
            }
        }
        if (ui.state == ExecState.Paused) {
            // 보조용 큰 [재개]: 화면 하단 중앙, 초록, 일시정지 상태에서만(10장)
            Button(
                onClick = vm::resume,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).width(320.dp).height(96.dp),
                colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary),
                shape = RoundedCornerShape(16.dp),
            ) { Text("▶ 재개", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        }
        if (ui.state == ExecState.SortieDone) SortieDonePanel(s, vm, onReview)
    }
}

/** 영상 위 반투명 패널(10.1: DJI 조종 앱 톤). */
@Composable
private fun OverlayPanel(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f), shape = RoundedCornerShape(8.dp)) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun StatusBar(s: FlightUiState) {
    val t = s.telemetry ?: return
    OverlayPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("배터리 ${t.batteryPct.toInt()}% (복귀 필요 ${s.returnNeedPct}%)")
            Text("고도 ${t.altitudeM.toInt()}m")
            Text("GPS ${"●".repeat(t.gpsLevel)}${"○".repeat((5 - t.gpsLevel).coerceAtLeast(0))}")
            if (s.simulated) Text("모의 기체 ×${FAKE_TIME_SCALE.toInt()}", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProgressPanel(s: FlightUiState) {
    val ui = s.exec
    val sortieVisits = ui.visits.filter { it.sortieIndex == ui.sortieIndex }
    val done = sortieVisits.count { it.status == VisitStatus.DONE || it.status == VisitStatus.SKIPPED || it.status == VisitStatus.FAILED }
    val target = ui.target?.let { "현재 ${it.pole.poleId} (${it.order + 1}/${sortieVisits.size})" } ?: "전주 ${sortieVisits.size}개"
    val wait = ui.countdownSec?.let { " ${it}s" }.orEmpty()
    OverlayPanel {
        Column {
            Text("${stateLabel(ui.state)}$wait", style = MaterialTheme.typography.titleLarge)
            Text("소티 ${ui.sortieIndex + 1}/${s.sortieCount} · $target · 처리 $done/${sortieVisits.size}")
            ui.rthCountdownSec?.let { Text("배터리 부족 — ${it}초 뒤 자동 RTH", color = MaterialTheme.colorScheme.error) }
            ui.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            ui.preflightFailures.forEach { Text("점검 실패: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Controls(s: FlightUiState, vm: FlightViewModel, onExit: () -> Unit, onShowResult: () -> Unit) {
    when (s.exec.state) {
        ExecState.Idle -> Button(onClick = vm::start, modifier = Modifier.heightIn(min = 56.dp)) { Text("비행 시작") }
        ExecState.MissionDone -> {
            Text("미션 완료", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onShowResult) { Text("결과 보기") }
        }
        ExecState.Paused -> {
            Text("일시정지됨 — 수동 조종 가능", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::manualCapture) { Text("촬영") }
                FilledTonalButton(onClick = vm::skip) { Text("건너뛰기") }
                FilledTonalButton(onClick = vm::markDone) { Text("완료 처리") }
            }
            RthButton(vm::returnHome)
        }
        else -> if (s.exec.state.airborne && s.exec.state != ExecState.ReturningHome) {
            // 자동 비행 중 버튼은 [RTH](길게)뿐이다(10장).
            RthButton(vm::returnHome)
        }
    }
    if (s.canExit) OutlinedButton(onClick = onExit) { Text("나가기") }
    if (s.simulated) {
        // RC 하드웨어 버튼을 대신하는 모의 조작(기체 연동 전 전용)
        Text("모의 RC", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.rcPause() }) { Text("RC 일시정지") }
            OutlinedButton(onClick = { vm.rcResume() }) { Text("RC 재개") }
        }
        OutlinedButton(onClick = { vm.dropBattery() }) { Text("배터리 −30%") }
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
        Modifier.size(width = 200.dp, height = 56.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.error)
            .semantics { contentDescription = "RTH 길게 누르기" }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
        contentAlignment = Alignment.Center,
    ) { Text("RTH (길게)", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold) }
}

/** FPV 자리에 그리는 위에서 본 그림: 이륙 지점(흰 사각형), 소티 경로(회색 선), 전주(상태별 색), 기체(노랑). */
@Composable
private fun TopDownView(s: FlightUiState, modifier: Modifier) {
    val home = s.home ?: return
    val proj = remember(home) { LocalProjection(home) }
    val points = remember(s.poles) { s.poles.map { proj.toEnu(it.position) } }
    val status = s.exec.visits.associate { it.pole.poleId to it.status }
    val enuOf = s.poles.indices.associate { s.poles[it].poleId to points[it] }
    val route = MaterialTheme.colorScheme.outline
    val homeColor = MaterialTheme.colorScheme.onSurface
    // 상태 패널(왼쪽 위)과 조작 카드(오른쪽)를 피한 영역에 가운데 맞춰 그린다.
    Canvas(modifier.padding(start = 24.dp, end = 380.dp, top = 170.dp, bottom = 24.dp)) {
        val all = points + Enu(0.0, 0.0)
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1.0); val spanY = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf(size.width / spanX, size.height / spanY)
        val offX = (size.width - spanX * scale) / 2; val offY = (size.height - spanY * scale) / 2
        fun at(x: Double, y: Double) = Offset((offX + (x - minX) * scale).toFloat(), (size.height - offY - (y - minY) * scale).toFloat())
        fun at(e: Enu) = at(e.x, e.y)
        val homeAt = at(0.0, 0.0)
        s.exec.visits.groupBy { it.sortieIndex }.values.forEach { v ->
            val line = listOf(homeAt) + v.sortedBy { it.order }.mapNotNull { enuOf[it.pole.poleId]?.let(::at) } + homeAt
            line.zipWithNext { a, b -> drawLine(route, a, b, strokeWidth = 3f) }
        }
        drawRect(homeColor, topLeft = homeAt - Offset(10f, 10f), size = Size(20f, 20f))
        s.poles.forEachIndexed { i, p ->
            val c = when (status[p.poleId]) {
                VisitStatus.DONE -> PoleDone
                VisitStatus.SKIPPED -> PoleSkipped
                VisitStatus.FAILED -> PoleFailed
                VisitStatus.IN_PROGRESS -> PoleActive
                else -> PolePending
            }
            drawCircle(c, 12f, at(points[i].x, points[i].y))
        }
        s.telemetry?.position?.let { proj.toEnu(it) }?.let { drawCircle(Aircraft, 16f, at(it.x, it.y)) }
    }
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
