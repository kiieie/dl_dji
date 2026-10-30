package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.ui.perPole
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
        else -> FlightContent(s, missionId, vm, onExit, onShowResult, onOpenSettings)
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
) {
    val ui = s.exec
    // 10.4.3 자동 비행 배치: FPV 전체, 위 상단 바, 가운데 위 줌 상자, 왼쪽 위 미션 진행 카드, 오른쪽 한 열(렌즈·촬영·앨범),
    // 아래 가운데 내비 원판, 왼쪽 아래 지도(누르면 자리 바꿈), 오른쪽 아래 RTH 길게
    var mapMain by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fpv: @Composable (Modifier) -> Unit = { m -> FpvView(s.simulated, m) }
        if (mapMain) FlightMap(s, interactive = true, pad = listOf(220.dp, 90.dp, 60.dp, 120.dp), Modifier.fillMaxSize())
        else fpv(Modifier.fillMaxSize())
        MiniMap(mapMain, onSwap = { mapMain = !mapMain }, Modifier.align(Alignment.BottomStart)) { m ->
            if (mapMain) fpv(m) else FlightMap(s, interactive = false, pad = List(4) { 8.dp }, m)
        }

        TopBar(s, onExit, onOpenSettings)
        ZoomBox(s.lens, s.params?.zoomRatio ?: 7.0, Modifier.align(Alignment.TopCenter).padding(top = TOP_BAR_HEIGHT + 9.dp))

        if (ui.state != ExecState.SortieDone) {
            Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = TOP_BAR_HEIGHT + 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MissionCard(s, missionId, vm, onShowResult)
                Notices(s)
                if (s.simulated) MockRcControls(vm)
            }
        }
        CameraColumn(
            lens = s.lens, onLens = vm::setLens,
            shutterEnabled = ui.state == ExecState.Paused, onShutter = vm::manualCapture,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = TOP_BAR_HEIGHT + 37.dp, end = 6.dp),
            onAlbum = if (s.canExit) onShowResult else null,
        )

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.telemetry?.landingConfirmNeeded == true) LandingConfirmButton(vm::confirmLanding)
            s.telemetry?.let { NavDisplay(it, s.home) }
        }
        // 실행기가 대기여도 기체가 떠 있으면(수동 비행 화면에서 넘어온 경우 등) RTH를 보인다
        if ((ui.state.airborne || s.telemetry?.isFlying == true) && ui.state != ExecState.ReturningHome) {
            RthButton(vm::returnHome, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }

        if (ui.state == ExecState.SortieDone) SortieDonePanel(s, vm)
        s.preflight?.let { PreflightSheet(it, vm) }
    }
}

/**
 * 왼쪽 위 미션 진행 카드(10.4.3, Pilot 2 55쪽): 미션·소티·진행률, 전주·사진 수, 지금 전주, 상태에 맞는 버튼 하나.
 * 대기 [▶ 비행 시작], 비행 중 노랑 [Ⅱ 일시정지 · 수동 비행](RC 일시정지와 같음), 일시정지 [▶ 재개](C1)와 [건너뛰기]·[완료 처리], 끝나면 [결과 보기].
 */
@Composable
private fun MissionCard(s: FlightUiState, missionId: Long, vm: FlightViewModel, onShowResult: () -> Unit) {
    val ui = s.exec
    val all = ui.visits
    val done = all.count { it.status == VisitStatus.DONE || it.status == VisitStatus.SKIPPED || it.status == VisitStatus.FAILED }
    val planned = all.size * (s.params?.let(::perPole) ?: 1)
    val photos = all.sumOf { it.captureCount }
    val pct = if (all.isEmpty()) 0 else done * 100 / all.size
    val paused = ui.state == ExecState.Paused
    val canPause = ui.state.airborne && !paused && ui.state != ExecState.ReturningHome
    Column(
        Modifier.width(CARD_WIDTH).background(CardBg, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#$missionId · 소티 ${ui.sortieIndex + 1}/${s.sortieCount}", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OnTertiaryContainer, maxLines = 1)
            Text("$pct%", style = numberStyle(13.sp), color = Color.White)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF333333))) {
            Box(Modifier.fillMaxWidth(pct / 100f).height(3.dp).background(Tertiary))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            CardFigure("전주", "$done", "/${all.size}")
            CardFigure("사진", "$photos", "/$planned")
            Text(
                ui.target?.pole?.displayName ?: stateLabel(ui.state), Modifier.weight(1f), fontSize = 11.sp, color = TextSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            )
        }
        when {
            ui.state == ExecState.Idle -> CardButton("▶ 비행 시작", MaterialTheme.colorScheme.primary, Color.White, vm::openPreflight)
            paused -> {
                CardButton("▶ 재개 (C1)", Tertiary, Color.Black, vm::resume)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CardButton("건너뛰기", MaterialTheme.colorScheme.surfaceContainer, Color.White, vm::skip, Modifier.weight(1f), height = 30.dp)
                    CardButton("완료 처리", MaterialTheme.colorScheme.surfaceContainer, Color.White, vm::markDone, Modifier.weight(1f), height = 30.dp)
                }
            }
            canPause -> CardButton("Ⅱ 일시정지 · 수동 비행", Caution, Color.Black, vm::pause)
            ui.state == ExecState.MissionDone -> CardButton("결과 보기", MaterialTheme.colorScheme.primary, Color.White, onShowResult)
            else -> Unit
        }
        if (ui.state.airborne) {
            Text(
                buildAnnotatedString {
                    append("RC ")
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("Ⅱ") }
                    append(" 일시정지 · ")
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("C1") }
                    append(" 재개")
                },
                fontSize = 11.sp, color = TextSoft,
            )
        }
    }
}

@Composable
private fun CardFigure(label: String, value: String, suffix: String) {
    Text(
        buildAnnotatedString {
            append("$label ")
            withStyle(numberStyle(12.sp).toSpanStyle().copy(color = Color.White)) { append(value) }
            append(suffix)
        },
        fontSize = 11.sp, color = TextSoft, maxLines = 1,
    )
}

@Composable
private fun CardButton(label: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), height: Dp = 34.dp) {
    Button(
        onClick = onClick, modifier = modifier.height(height), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 6.dp),
        colors = ButtonDefaults.buttonColors(bg, fg),
    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/** 카드 아래 안내·점검 실패 줄(있을 때만). 배터리 부족 카운트다운은 상단 상태 칸에 보인다. */
@Composable
private fun Notices(s: FlightUiState) {
    val lines = listOfNotNull(s.exec.message?.let { it to Color.White }) + s.exec.preflightFailures.map { "점검 실패: $it" to Error }
    lines.forEach { (text, color) ->
        Text(
            text, Modifier.width(CARD_WIDTH).background(CardBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            color = color, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 비행 전 점검(Pilot 2 체크리스트, 매뉴얼 68쪽 [수행]): 항목·값·통과를 보여 주고 확인하면 업로드·시작. */
@Composable
private fun PreflightSheet(items: List<PreflightCheck>, vm: FlightViewModel) {
    val ok = items.all { it.ok }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth(0.7f), shape = RoundedCornerShape(6.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
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
                        Button(onClick = { vm.confirmPreflight() }, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(4.dp)) { Text("업로드 후 시작") }
                    } else {
                        // Pilot 2처럼 경고가 있어도 조종사가 확인하고 시작할 수 있다. 기체 연결이 없으면 시작할 수 없다
                        Button(
                            onClick = { vm.confirmPreflight(ignoreWarnings = true) }, enabled = connected,
                            colors = ButtonDefaults.buttonColors(containerColor = PolePending, contentColor = Color.Black),
                            modifier = Modifier.height(44.dp), shape = RoundedCornerShape(4.dp),
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

/** 착륙 보호: 지면 바로 위에서 기체가 확인을 기다린다(Pilot 2 [착륙 확인]). RC 왼쪽 스틱을 내려도 된다. */
@Composable
internal fun LandingConfirmButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(bottom = 6.dp).size(200.dp, 44.dp), shape = RoundedCornerShape(4.dp)) {
        Text("착륙 확인", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 모의 조작(기체 연동 전 전용). RC Pro의 일시정지·C1 버튼으로도 같은 동작을 한다(10장). */
@Composable
private fun MockRcControls(vm: FlightViewModel) {
    Row(Modifier.width(CARD_WIDTH), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("모의 RC", fontSize = 11.sp, color = TextSoft)
        CardButton("Ⅱ", CardBg, Color.White, { vm.rcPause() }, Modifier.weight(1f), height = 30.dp)
        CardButton("C1", CardBg, Color.White, { vm.rcResume() }, Modifier.weight(1f), height = 30.dp)
        CardButton("−30%", CardBg, Color.White, { vm.dropBattery() }, Modifier.weight(1f), height = 30.dp)
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
                    Button(onClick = vm::nextSortie, enabled = battery && props, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(4.dp)) { Text("다음 소티 시작") }
                    OutlinedButton(onClick = vm::endMission, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(4.dp)) { Text("미션 종료") }
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
