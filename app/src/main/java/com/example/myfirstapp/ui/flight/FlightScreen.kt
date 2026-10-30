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
import androidx.compose.foundation.layout.widthIn
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
import com.example.myfirstapp.core.executor.PreflightCheck
import com.example.myfirstapp.core.model.Lens
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
internal val OverlayBg = Color.Black.copy(alpha = 0.5f)

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
) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: FlightViewModel = viewModel(factory = FlightViewModel.factory(
            dao, projectId, missionId, context.getExternalFilesDir(null), app.settings, app.rcButtons,
        ))
    val s by vm.state.collectAsStateWithLifecycle()
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
        else -> FlightContent(s, vm, onExit, onShowResult, onOpenSettings)
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
    vm: FlightViewModel,
    onExit: () -> Unit,
    onShowResult: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui = s.exec
    // DJI Pilot 2 카메라 뷰 배치(매뉴얼 54~55쪽): FPV 전체, 위쪽 상단 바, 왼쪽 위 경로 진행 알약, 오른쪽 카메라 버튼,
    // 아래 가운데 내비게이션 디스플레이, 왼쪽 아래 지도. 지도를 누르면 자리를 바꾼다.
    var mapMain by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fpv: @Composable (Modifier) -> Unit = { m -> FpvView(s.simulated, m) }
        if (mapMain) FlightMap(s, interactive = true, pad = listOf(200.dp, 90.dp, 90.dp, 130.dp), Modifier.fillMaxSize())
        else fpv(Modifier.fillMaxSize())
        MiniMap(mapMain, onSwap = { mapMain = !mapMain }, Modifier.align(Alignment.BottomStart)) { m ->
            if (mapMain) fpv(m) else FlightMap(s, interactive = false, pad = List(4) { 8.dp }, m)
        }

        Column(Modifier.fillMaxWidth()) {
            TopBar(s, onExit, onOpenSettings)
            if (ui.state != ExecState.SortieDone) RoutePill(s, vm, Modifier.padding(start = 8.dp, top = 6.dp))
        }

        // 오른쪽 위: 일시정지 중 [건너뛰기]·[완료 처리], 비행 중 [RTH 길게]
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = TOP_BAR_HEIGHT + 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { SideControls(s, vm) }
        CameraColumn(
            lens = s.lens, onLens = vm::setLens,
            shutterEnabled = ui.state == ExecState.Paused, onShutter = vm::manualCapture,
            onGimbal = vm::toggleGimbal,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp, top = TOP_BAR_HEIGHT),
        )

        if (s.simulated && ui.state != ExecState.SortieDone) MockRcControls(vm, Modifier.align(Alignment.CenterStart).padding(start = 8.dp, top = 60.dp))

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BottomAction(s, vm, onShowResult)
            s.telemetry?.let { NavDisplay(it, s.home, Modifier.padding(top = 6.dp)) }
        }

        if (ui.state == ExecState.SortieDone) SortieDonePanel(s, vm)
        s.preflight?.let { PreflightSheet(it, vm) }
    }
}

internal val TOP_BAR_HEIGHT = 40.dp

/** 왼쪽 아래 지도 미리보기(매뉴얼 54쪽 15번). 누르면 큰 화면과 자리를 바꾼다. */
@Composable
internal fun MiniMap(mapMain: Boolean, onSwap: () -> Unit, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    Surface(
        modifier = modifier.padding(8.dp).size(180.dp, 112.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
    ) {
        content(Modifier.fillMaxSize())
        // 지도 뷰가 터치를 먹으므로 위에 투명한 누름 영역을 둔다
        Box(Modifier.fillMaxSize().clickable(onClickLabel = if (mapMain) "영상 크게" else "지도 크게", onClick = onSwap))
    }
}

/** 비행 전 점검(Pilot 2 체크리스트, 매뉴얼 68쪽 [수행]): 항목·값·통과를 보여 주고 확인하면 업로드·시작. */
@Composable
private fun PreflightSheet(items: List<PreflightCheck>, vm: FlightViewModel) {
    val ok = items.all { it.ok }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(0.7f), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("비행 전 점검", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // 화면 높이가 좁아 두 줄로 나눠 놓는다
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items.chunked((items.size + 1) / 2).forEach { col ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            col.forEach { c ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (c.ok) "●" else "▲", color = if (c.ok) PoleDone else PoleFailed, style = MaterialTheme.typography.bodyMedium)
                                    Text(c.label, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        c.value, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant else PoleFailed,
                                    )
                                }
                            }
                        }
                    }
                }
                val connected = items.firstOrNull()?.ok == true
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    if (ok) {
                        Button(onClick = { vm.confirmPreflight() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("업로드 후 시작") }
                    } else {
                        // Pilot 2처럼 경고가 있어도 조종사가 확인하고 시작할 수 있다. 기체 연결이 없으면 시작할 수 없다
                        Button(
                            onClick = { vm.confirmPreflight(ignoreWarnings = true) }, enabled = connected,
                            colors = ButtonDefaults.buttonColors(containerColor = PolePending, contentColor = Color.Black),
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("경고 확인하고 시작") }
                    }
                    TextButton(onClick = vm::closePreflight, modifier = Modifier.height(36.dp)) { Text("취소") }
                    if (!ok) Text("▲ ${items.count { !it.ok }}개 항목을 확인하세요", color = PoleFailed, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** FPV 영상. 실제 기체는 카메라 스트림, 모의 기체는 안내 문구(10장). */
@Composable
private fun FpvView(simulated: Boolean, modifier: Modifier) {
    if (!simulated) return DjiFpvView(modifier.background(Color.Black))
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Text("FPV 영상 (모의 기체: 영상 없음)", color = Color.White.copy(alpha = 0.35f), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 상단 바(Pilot 2 순서, 매뉴얼 56쪽): ← · 시스템 상태 표시줄(상태색) · 비행 상태 · GNSS 위성 · 신호 · 배터리 % · 설정 ⚙.
 * 그 아래 화면 폭 전체에 배터리 바(남은 양, 노란 눈금은 복귀에 필요한 양). 시스템 상태 표시줄은 녹(정상)·주(주의)·빨(경고).
 */
@Composable
internal fun TopBar(s: FlightUiState, onExit: () -> Unit, onOpenSettings: () -> Unit, label: String? = null, canExit: Boolean = s.canExit) {
    val t = s.telemetry
    val state = s.exec.state
    val lost = state == ExecState.LinkLost || t?.connected == false
    val warn = s.exec.rthCountdownSec != null || (t != null && t.connected && t.gpsLevel < 3)
    val statusColor = when {
        lost -> PoleFailed
        warn || state == ExecState.Paused || state == ExecState.ReturningHome -> PolePending
        else -> PoleDone
    }
    val status = when {
        lost -> "연결 끊김"
        warn -> s.exec.message ?: if (t != null && t.gpsLevel < 3) "GNSS 약함 · 주의해서 비행" else "주의"
        t == null -> "기체 없음"
        state == ExecState.Paused -> "일시정지"
        state == ExecState.ReturningHome -> "복귀 중"
        else -> "정상"
    }
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))) {
        Row(
            Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT - 3.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onExit, enabled = canExit, modifier = Modifier.size(48.dp, TOP_BAR_HEIGHT - 3.dp)) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
            }
            // 시스템 상태 표시줄(2번): 정상 또는 지금 경고
            Text(
                status,
                modifier = Modifier.widthIn(min = 72.dp, max = 220.dp).background(statusColor, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
                color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // 비행 상태(3번): 가운데
            Text(
                listOfNotNull(label ?: stateLabel(state), if (s.simulated) "모의 기체 ×${FAKE_TIME_SCALE.toInt()}" else null).joinToString(" · "),
                color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (t != null) {
                Text("GNSS ${t.satellites}", color = if (t.gpsLevel < 3) PolePending else Color.White, style = MaterialTheme.typography.labelLarge)
                Text("RC ${signalDots(t.signalPct, t.connected)}", color = signalColor(t.signalPct, t.connected), style = MaterialTheme.typography.labelLarge)
                Text("${t.batteryPct.toInt()}%", color = if (t.batteryPct < 30) PoleFailed else Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp, TOP_BAR_HEIGHT - 3.dp)) { Text("⚙", style = MaterialTheme.typography.titleLarge) }
        }
        BatteryBar(t?.batteryPct ?: 0.0, s.returnNeedPct, Modifier.fillMaxWidth())
    }
}

/** 배터리 바(Pilot 2 4번): 남은 양을 막대로, 복귀에 필요한 양을 노란 막대로. */
@Composable
private fun BatteryBar(pct: Double, returnNeed: Int, modifier: Modifier) {
    Box(modifier.height(3.dp).background(Color.White.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f)).height(3.dp).background(if (pct < 30) PoleFailed else PoleDone))
        Box(Modifier.fillMaxWidth((returnNeed / 100f).coerceIn(0f, 1f)).height(3.dp).background(Aircraft))
    }
}

/** 신호 3점(Pilot 2 6번): 흰 3점 강함, 노랑 2점 중간, 빨강 1점 약함. 품질값이 없으면 연결 여부만. */
private fun signalDots(q: Int?, connected: Boolean) = when {
    !connected -> "✕"
    q == null || q >= 60 -> "●●●"
    q >= 30 -> "●●○"
    else -> "●○○"
}

private fun signalColor(q: Int?, connected: Boolean) = when {
    !connected -> PoleFailed
    q == null || q >= 60 -> Color.White
    q >= 30 -> Aircraft
    else -> PoleFailed
}

/**
 * 오른쪽 카메라 버튼(매뉴얼 54쪽 16·10·17번): 렌즈 전환, 셔터, 짐벌 중앙/아래(C2). 영상 양옆 검은 띠 위에 바탕 없이 둔다.
 * [shotsLabel]은 셔터 아래 촬영 수(수동 비행).
 */
@Composable
internal fun CameraColumn(
    lens: Lens, onLens: (Lens) -> Unit, shutterEnabled: Boolean, onShutter: () -> Unit, onGimbal: () -> Unit,
    modifier: Modifier, shotsLabel: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CameraTextButton(if (lens == Lens.WIDE) "광각" else "망원", "렌즈 전환") { onLens(if (lens == Lens.WIDE) Lens.ZOOM else Lens.WIDE) }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (shutterEnabled) Color.White else Color.White.copy(alpha = 0.3f))
                .clickable(enabled = shutterEnabled, onClickLabel = "촬영", onClick = onShutter),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(52.dp).clip(CircleShape).background(if (shutterEnabled) PoleFailed else Color.Gray)) }
        shotsLabel?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelLarge) }
        CameraTextButton("짐벌 ⇅", "짐벌 중앙/아래", onGimbal)
    }
}

@Composable
private fun CameraTextButton(text: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp, 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/**
 * 경로 진행 알약(매뉴얼 55쪽 "Executing flight route"): [Ⅱ 수동 비행] / [▶ 재개]와 소티·전주·처리 수.
 * Ⅱ는 RC 일시정지 버튼과 같다(기체가 멈춰 호버링, 스틱으로 조종). 재개는 C1 또는 ▶.
 */
@Composable
private fun RoutePill(s: FlightUiState, vm: FlightViewModel, modifier: Modifier) {
    val ui = s.exec
    val sortieVisits = ui.visits.filter { it.sortieIndex == ui.sortieIndex }
    val done = sortieVisits.count { it.status == VisitStatus.DONE || it.status == VisitStatus.SKIPPED || it.status == VisitStatus.FAILED }
    val target = ui.target?.let { "${it.pole.poleId} (${it.order + 1}/${sortieVisits.size})" } ?: "전주 ${sortieVisits.size}개"
    val paused = ui.state == ExecState.Paused
    val canPause = ui.state.airborne && !paused && ui.state != ExecState.ReturningHome
    Column(modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(color = OverlayBg, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    paused -> PillButton("▶", "재개", PoleDone, vm::resume)
                    canPause -> PillButton("Ⅱ", "수동 비행", Color.White, vm::pause)
                    else -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("✈", color = Color.White) }
                }
                Column(Modifier.padding(start = 8.dp, end = 12.dp)) {
                    Text(
                        if (paused) "수동 비행 중 · C1로 계속" else "소티 ${ui.sortieIndex + 1}/${s.sortieCount} · ${stateLabel(ui.state)}",
                        color = if (paused) Aircraft else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                    Text("$target · 처리 $done/${sortieVisits.size}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // 경고·알림 줄(필요할 때만)
        val lines = listOfNotNull(
            ui.rthCountdownSec?.let { "배터리 부족 — ${it}초 뒤 자동 RTH" to MaterialTheme.colorScheme.error },
            ui.message?.let { it to Color.White },
        ) + ui.preflightFailures.map { "점검 실패: $it" to MaterialTheme.colorScheme.error }
        lines.forEach { (text, color) ->
            Text(
                text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                modifier = Modifier.background(OverlayBg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun PillButton(glyph: String, description: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).semantics { contentDescription = description }
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SideControls(s: FlightUiState, vm: FlightViewModel) {
    val state = s.exec.state
    if (state == ExecState.Paused) {
        // 화면 높이(약 430dp)가 좁아 가로 한 줄로 둔다. 촬영은 오른쪽 셔터로
        val colors = ButtonDefaults.outlinedButtonColors(containerColor = OverlayBg, contentColor = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::skip, modifier = Modifier.heightIn(min = 48.dp), colors = colors) { Text("건너뛰기") }
            OutlinedButton(onClick = vm::markDone, modifier = Modifier.heightIn(min = 48.dp), colors = colors) { Text("완료 처리") }
        }
    }
    // 실행기가 대기여도 기체가 떠 있으면(수동 비행 화면에서 넘어온 경우 등) RTH를 보인다
    if ((state.airborne || s.telemetry?.isFlying == true) && state != ExecState.ReturningHome) RthButton(vm::returnHome)
}

/** 화면 아래 가운데의 큰 버튼: 비행 시작, 보조용 [재개](일시정지 때만, 초록), 미션 완료(10장). */
@Composable
private fun BottomAction(s: FlightUiState, vm: FlightViewModel, onShowResult: () -> Unit) {
    if (s.telemetry?.landingConfirmNeeded == true) return LandingConfirmButton(vm::confirmLanding)
    when (s.exec.state) {
        ExecState.Idle -> Button(onClick = vm::openPreflight, modifier = Modifier.size(200.dp, 56.dp)) {
            Text("비행 시작", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        ExecState.Paused -> Button(
            onClick = vm::resume,
            modifier = Modifier.size(220.dp, 64.dp),
            colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f), MaterialTheme.colorScheme.onTertiary),
            shape = RoundedCornerShape(16.dp),
        ) { Text("▶ 재개 (C1)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        ExecState.MissionDone -> Button(onClick = onShowResult, modifier = Modifier.size(240.dp, 56.dp)) {
            Text("미션 완료 · 결과·판독", fontWeight = FontWeight.Bold)
        }
        else -> Unit
    }
}

/** 착륙 보호: 지면 바로 위에서 기체가 확인을 기다린다(Pilot 2 [착륙 확인]). RC 왼쪽 스틱을 내려도 된다. */
@Composable
internal fun LandingConfirmButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.size(220.dp, 64.dp), shape = RoundedCornerShape(16.dp)) {
        Text("착륙 확인", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

/** 모의 조작(기체 연동 전 전용). RC Pro의 일시정지·C1·RTH 버튼으로도 같은 동작을 한다(10장). */
@Composable
private fun MockRcControls(vm: FlightViewModel, modifier: Modifier) {
    val colors = ButtonDefaults.outlinedButtonColors(containerColor = OverlayBg, contentColor = Color.White)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("모의 RC", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        OutlinedButton(onClick = { vm.rcPause() }, colors = colors) { Text("RC 일시정지") }
        OutlinedButton(onClick = { vm.rcResume() }, colors = colors) { Text("RC 재개") }
        OutlinedButton(onClick = { vm.dropBattery() }, colors = colors) { Text("배터리 −30%") }
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
        Card(Modifier.widthIn(max = 420.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("소티 ${ui.sortieIndex + 1}/${s.sortieCount} 완료", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("남은 전주 $remaining", color = MaterialTheme.colorScheme.onSurfaceVariant)
                CheckRow("배터리 교체", battery) { battery = it }
                CheckRow("프로펠러·기체 확인", props) { props = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = vm::nextSortie, enabled = battery && props, modifier = Modifier.heightIn(min = 56.dp)) { Text("다음 소티 시작") }
                    OutlinedButton(onClick = vm::endMission, modifier = Modifier.heightIn(min = 56.dp)) { Text("미션 종료") }
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

/** 잘못 누르지 않도록 1초 이상 눌러야 동작하는 RTH 버튼(10장). */
@Composable
internal fun RthButton(onLongPress: () -> Unit) {
    Box(
        Modifier.size(width = 132.dp, height = 48.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
            .semantics { contentDescription = "RTH 길게 누르기" }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
        contentAlignment = Alignment.Center,
    ) { Text("RTH (길게)", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold) }
}

internal fun stateLabel(s: ExecState) = when (s) {
    ExecState.Idle -> "비행 대기"
    ExecState.PreflightCheck -> "비행 전 점검"
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
    ExecState.MissionDone -> "미션 완료"
}
