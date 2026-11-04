package com.example.myfirstapp.ui.flight

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.model.BatteryLevel
import com.example.myfirstapp.core.model.CameraStatus
import com.example.myfirstapp.core.model.FlightTitle
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.ObstacleLevel
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.model.batteryBand
import com.example.myfirstapp.core.model.batteryLevel
import com.example.myfirstapp.core.model.remainingFlightSec
import com.example.myfirstapp.core.model.videoContentRect
import com.example.myfirstapp.core.model.zoomFrameFraction
import com.example.myfirstapp.ui.PinpointButton
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.ControlBg
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Inactive
import com.example.myfirstapp.ui.theme.Muted
import com.example.myfirstapp.ui.theme.OverlayPlate
import com.example.myfirstapp.ui.theme.SurfaceContainerHigh
import com.example.myfirstapp.ui.theme.SurfaceContainerHighest
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 카메라 뷰 공통 틀(10.5.1~10.5.3, Pilot 2 카메라 뷰와 같은 배치, 시안 Flight3). 자동 비행([FlightScreen])과 수동 비행([ManualFlightScreen])이 같이 쓴다.
// 크기는 [Dimens](상단 바 높이 [Dimens.TopBar], 카드 폭 [Dimens.CardWidth]), 카드 바탕은 [CardBg](ui/theme).

/** 상단 상태 칸 경고 한 줄. [severe]면 빨강 바탕. */
internal data class TopWarning(val text: String, val severe: Boolean)

/** 상단 상태 칸 경고 목록(10.5.1): 연결 끊김·충돌·배터리·RTH 카운트다운·GNSS·점검 실패. 없으면 "정상". */
internal fun topWarnings(s: FlightUiState): List<TopWarning> {
    val t = s.telemetry
    val lost = s.exec.state == ExecState.LinkLost || t?.connected == false
    return buildList {
        if (lost) add(TopWarning("연결 끊김", true))
        s.obstacles.text?.let { add(TopWarning(it, s.obstacles.level == ObstacleLevel.DANGER)) }
        s.exec.rthCountdownSec?.let { add(TopWarning("배터리 부족 · ${it}초 뒤 RTH", true)) }
        if (t != null && t.connected) {
            when (batteryLevel(t.batteryPct, t.lowPct, t.criticalPct)) {
                BatteryLevel.CRITICAL -> add(TopWarning("배터리 심각 · ${t.batteryPct.toInt()}%", true))
                BatteryLevel.LOW -> add(TopWarning("배터리 낮음 · ${t.batteryPct.toInt()}%", false))
                BatteryLevel.OK -> Unit
            }
            if (t.gpsLevel < 3) add(TopWarning("GNSS 약함 · 주의해서 비행", false))
        }
        s.exec.preflightFailures.forEach { add(TopWarning("점검 실패: $it", false)) }
    }.sortedByDescending { it.severe }
}

/**
 * 상단 바(Pilot 2 순서, 10.5.1): 뒤로 · 표지 · 시스템 상태 칸(경고는 깜박임, 누르면 목록) · 가운데 비행 상태([title], 누르면 비행 전 점검) ·
 * 현재 시각 · 위성 수(0이면 빨강) · RC 신호(흰 3 / 노랑 2 / 빨강 1, 누르면 신호) · 기체 배터리 %·V(누르면 잔량·전압·온도) · RC 배터리 · ⋯ 설정.
 * 아래에 배터리 띠([BatteryBand]).
 */
@Composable
internal fun TopBar(
    s: FlightUiState, title: FlightTitle, onExit: () -> Unit, onOpenSettings: () -> Unit, canExit: Boolean = s.canExit,
    /** 가운데 비행 상태 글자를 누르면(Pilot 2: 비행 전 점검을 엶, 10.5.1). */
    onTitleClick: (() -> Unit)? = null,
) {
    val t = s.telemetry
    val warnings = topWarnings(s)
    val alarm = warnings.any { it.severe }
    val rc by (LocalContext.current.applicationContext as InspectionApp).rcBattery.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.HeaderCamera).background(Color.Black).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Pilot 2처럼 비행 중에도 흰색(누르면 아무 일 없음, 떠나면 실행기가 멈추므로 막는 동작은 그대로)
            IconButton(onClick = onExit, enabled = canExit, modifier = Modifier.size(Dimens.TouchSmall)) {
                Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(14.dp), tint = Color.White)
            }
            com.example.myfirstapp.ui.AppBadge()
            StatusBox(warnings, alarm, connected = t != null)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    title.text + if (s.simulated) " · 모의 ×${FAKE_TIME_SCALE.toInt()}" else "",
                    Modifier.clickable(enabled = onTitleClick != null, onClickLabel = "비행 전 점검") { onTitleClick?.invoke() },
                    fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = if (title.warn) Warning else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val now by produceState(clock()) { while (true) { delay(15_000); value = clock() } }
            Text(now, style = numberStyle(FontSize.Title), color = Color.White, modifier = Modifier.semantics { contentDescription = "현재 시각" })
            Box(Modifier.width(1.dp).height(16.dp).background(SurfaceContainerHighest))
            val sats = t?.satellites
            val satColor = when {
                t == null -> Color.White
                sats == 0 -> Error
                t.gpsLevel < 3 -> Warning
                else -> Color.White
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(painterResource(R.drawable.ic_gnss), contentDescription = "GNSS 위성", modifier = Modifier.size(13.dp), tint = satColor)
                Text(sats?.toString() ?: "--", style = numberStyle(FontSize.Label), color = satColor)
            }
            PopupItem("RC 신호", listOf("신호 품질" to (t?.signalPct?.let { "$it%" } ?: if (t?.connected == true) "연결됨" else "연결 안 됨"))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("RC", fontSize = FontSize.Caption, fontWeight = FontWeight.SemiBold, color = Color.White)
                    SignalBars(t?.signalPct, t?.connected == true)
                }
            }
            AircraftBattery(t, s.returnNeedPct)
            // 조종기 자체 배터리(안드로이드 시스템 값). 기체 배터리와 같은 그림, 충전 중이면 ⚡
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("RC", fontSize = FontSize.Caption, fontWeight = FontWeight.SemiBold, color = Color.White)
                BatteryIcon(rc?.pct?.toDouble(), if ((rc?.pct ?: 100) < AppConfig.Checks.BATTERY_MIN_PCT) Error else Tertiary)
                // 값을 모르면 0이 아니라 "--"
                Text(rc?.let { (if (it.charging) "⚡" else "") + "${it.pct}%" } ?: "--", style = numberStyle(FontSize.Body), color = Color.White)
            }
            Box(
                Modifier.size(Dimens.TouchSmall).clickable(onClickLabel = "설정", onClick = onOpenSettings).semantics { contentDescription = "설정" },
                contentAlignment = Alignment.Center,
            ) { Text("⋯", fontSize = FontSize.Title, fontWeight = FontWeight.Bold, color = Color.White) }
        }
        BatteryBand(t, s.returnNeedPct)
    }
}

private fun clock() = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

/** 시스템 상태 칸(Pilot 2 "정상"): 경고가 있으면 첫 경고를 깜박이고(빨강 바탕은 심각), 누르면 경고 목록. */
@Composable
private fun StatusBox(warnings: List<TopWarning>, alarm: Boolean, connected: Boolean) {
    var open by remember { mutableStateOf(false) }
    // 경고가 있을 때만 깜박인다(없으면 애니메이션을 돌리지 않음)
    val blink = if (warnings.isEmpty()) 1f
    else rememberInfiniteTransition(label = "status").animateFloat(1f, 0.35f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink").value
    Box {
        Row(
            Modifier.width(Dimens.StatusBox).height(22.dp).clip(RoundedCornerShape(3.dp))
                .background(if (alarm) Error.copy(alpha = blink) else SurfaceContainerHigh)
                .clickable(onClickLabel = "경고 목록") { open = true }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val first = warnings.firstOrNull()
            if (first != null) Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(12.dp).alpha(blink), tint = if (alarm) Color.White else Warning)
            Text(
                first?.text ?: if (connected) "정상" else "기체 없음", Modifier.alpha(if (first != null && !alarm) blink else 1f),
                fontSize = FontSize.Caption, fontWeight = if (first != null) FontWeight.Bold else FontWeight.Normal,
                color = if (first == null && !connected) Muted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = CardBg) {
            if (warnings.isEmpty()) DropdownMenuItem({ Text("경고 없음", fontSize = FontSize.Body, color = TextSoft) }, onClick = { open = false })
            warnings.forEach { w ->
                DropdownMenuItem({ Text(w.text, fontSize = FontSize.Body, color = if (w.severe) Error else Warning) }, onClick = { open = false })
            }
        }
    }
}

/** 누르면 값 목록이 뜨는 상단 바 항목(배터리·RC 신호, 10.5.7). */
@Composable
private fun PopupItem(label: String, rows: List<Pair<String, String>>, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.clickable(onClickLabel = label) { open = true }) {
        content()
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = CardBg) {
            rows.forEach { (k, v) ->
                DropdownMenuItem({
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(k, Modifier.width(72.dp), fontSize = FontSize.Body, color = TextSoft)
                        Text(v, style = numberStyle(FontSize.Body), color = Color.White)
                    }
                }, onClick = { open = false })
            }
        }
    }
}

/** 기체 배터리(10.5.1): 그림 + %와 전압 두 줄. 낮음 문턱 이하 주황, 심각 이하 빨강. 누르면 잔량·전압·온도·RTH 필요·남은 시간. */
@Composable
private fun AircraftBattery(t: Telemetry?, returnNeedPct: Int) {
    val pct = t?.takeIf { it.connected }?.batteryPct
    val c = when (pct?.let { batteryLevel(it, t.lowPct, t.criticalPct) }) {
        null -> Inactive
        BatteryLevel.CRITICAL -> Error
        BatteryLevel.LOW -> Warning
        BatteryLevel.OK -> Tertiary
    }
    val need = t?.rthBatteryPct ?: returnNeedPct
    val rows = listOf(
        "잔량" to (pct?.let { "${it.toInt()}%" } ?: "--"),
        "전압" to (t?.batteryVoltageV?.let { "%.1fV".format(it) } ?: "--"),
        "온도" to (t?.batteryTempC?.let { "%.0f℃".format(it) } ?: "--"),
        "RTH 필요" to (if (pct != null && need > 0) "$need%" else "--"),
        "남은 비행" to (t?.let { remainingFlightSec(it, need) }?.let(::mmss) ?: "--"),
    )
    PopupItem("기체 배터리", rows) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BatteryIcon(pct, c)
            Column {
                Text(pct?.let { "${it.toInt()}%" } ?: "--%", style = numberStyle(FontSize.Body), color = if (c == Tertiary) Color.White else c)
                Text(t?.batteryVoltageV?.let { "%.1fV".format(it) } ?: "--V", style = numberStyle(FontSize.Micro), color = TextSoft)
            }
        }
    }
}

internal fun mmss(sec: Int) = "%02d:%02d".format(sec / 60, sec % 60)

/** 누를 수 없는 영상 위 흰 조작(줌·사진/영상)의 흐림. Pilot 2처럼 흰색을 유지한다. */
private const val DISABLED_ALPHA = 0.45f

/** 막힌 셔터의 가운데 원 흐림(고리는 흰색 그대로). 검은 바탕에서도 흰 원으로 읽히게 [DISABLED_ALPHA]보다 밝다. */
private const val SHUTTER_BLOCKED_ALPHA = 0.6f

/** 신호 막대 3개(Pilot 2 6번): 강함 흰 3개, 중간 노랑 2개, 약함 빨강 1개, 끊김 회색. 품질값이 없으면 연결 여부만. */
@Composable
private fun SignalBars(q: Int?, connected: Boolean) {
    val (n, c) = when {
        !connected -> 0 to Inactive
        q == null || q >= 60 -> 3 to Color.White
        q >= 30 -> 2 to Caution
        else -> 1 to Error
    }
    Canvas(Modifier.size(14.dp, 10.dp)) {
        val w = size.width / 5
        for (i in 0..2) {
            val h = size.height * (i + 1) / 3
            drawRect(if (i < n) c else Inactive, Offset(i * 2 * w, size.height - h), Size(w, h))
        }
    }
}

/** 배터리 그림. 남은 양만큼 [fill] 색으로 채운다. */
@Composable
private fun BatteryIcon(pct: Double?, fill: Color) {
    Canvas(Modifier.size(9.dp, 15.dp)) {
        val u = size.width / 9
        drawRect(Color.White, Offset(2.5f * u, 0f), Size(4 * u, 2 * u))
        drawRect(Color.White, Offset(0.5f * u, 2 * u), Size(8 * u, size.height - 2.5f * u), style = Stroke(u))
        val full = size.height - 5 * u
        val h = full * ((pct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
        drawRect(fill, Offset(2 * u, 4 * u + full - h), Size(5 * u, h))
    }
}

/**
 * 배터리 띠 3dp(Pilot 2, 11-04 녹화 대조): 땅에서는 남은 양까지 초록과 끝에 "--:--". 떠 있으면 빨강(착륙 필요량)·주황(RTH 필요량, 끝에 노란 H)·초록(남은 양)과
 * 초록 끝 남은 비행 mm:ss([batteryBand]). RTH 필요량은 기체 값, 없으면 앱 추정([returnNeedPct]). 낮음·심각 문턱은 흰 점.
 * H 원과 시간 상자는 띠보다 큰 [BAND_MARK](12dp, Pilot 2 p2_mission2)로 띠에 걸쳐 그린다: 3dp 띠 높이에 눌려 잘리지 않게 크기를 고정한다(`requiredSize`).
 */
@Composable
private fun BatteryBand(t: Telemetry?, returnNeedPct: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp).background(SurfaceContainerHigh)) {
        if (t == null || !t.connected) return@BoxWithConstraints
        val flying = t.isFlying
        val need = (t.rthBatteryPct ?: returnNeedPct).takeIf { flying && it > 0 }
        batteryBand(t.batteryPct, flying, need, t.landBatteryPct, t.criticalPct).forEach { seg ->
            val c = when (seg.level) { BatteryLevel.CRITICAL -> Error; BatteryLevel.LOW -> Warning; BatteryLevel.OK -> Tertiary }
            Box(Modifier.offset(x = maxWidth * seg.from).width(maxWidth * (seg.to - seg.from)).fillMaxHeight().background(c))
        }
        listOf(t.criticalPct, t.lowPct).forEach { p ->
            Box(Modifier.offset(x = maxWidth * (p / 100f).coerceIn(0f, 1f) - 2.5.dp).requiredSize(5.dp).clip(CircleShape).background(Color.White))
        }
        need?.let {
            Box(
                Modifier.offset(x = maxWidth * (it / 100f).coerceIn(0f, 1f) - BAND_MARK / 2).requiredSize(BAND_MARK).clip(CircleShape).background(Caution)
                    .semantics { contentDescription = "RTH 필요 $it%" },
                contentAlignment = Alignment.Center,
            ) { Text("H", style = numberStyle(FontSize.Micro, FontWeight.Bold), color = Color.Black) }
        }
        val left = if (flying) remainingFlightSec(t, need ?: 0)?.let(::mmss) else null
        // 초록 끝에서 시작하는 초록 테두리 상자(화면 밖으로 나가지 않게 오른쪽 끝에서 멈춤)
        val boxW = 34.dp
        Box(
            Modifier.offset(x = (maxWidth * (t.batteryPct / 100).toFloat().coerceIn(0f, 1f)).coerceAtMost(maxWidth - boxW)).requiredSize(boxW, BAND_MARK)
                .background(Color.Black, RoundedCornerShape(2.dp)).border(1.dp, Tertiary, RoundedCornerShape(2.dp))
                .semantics { contentDescription = "남은 비행 시간" },
            contentAlignment = Alignment.Center,
        ) { Text(left ?: "--:--", style = numberStyle(FontSize.Micro), color = Tertiary, maxLines = 1) }
    }
}

/** 배터리 띠 위 H 원·시간 상자 높이(Pilot 2 약 30px = 12dp). */
private val BAND_MARK = 12.dp

/** 가운데 위 초록 줌 상자(WIDE 1.0X, ZOOM 7.0X). */
@Composable
internal fun ZoomBox(lens: Lens, zoomRatio: Double, modifier: Modifier) {
    Text(
        if (lens == Lens.WIDE) "WIDE 1.0X" else "ZOOM ${"%.1f".format(zoomRatio)}X",
        modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(2.dp)).border(1.dp, Tertiary, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp),
        style = numberStyle(FontSize.Body), color = Tertiary,
    )
}

/**
 * 영상 위 겹침(10.5.2): 3×3 격자와 가운데 ✕, 광각이면 지금 망원 배율이 찍을 범위(녹색 꺾쇠 사각형 + 배율, [zoomFrameFraction]),
 * 탭 초점([focusEnabled]이면 누른 자리를 영상 안 0~1로 [onFocus]에 넘기고 노란 사각형을 [AppConfig.CameraView.FOCUS_MARK_MS] 보인다).
 * 모두 실제 영상 자리([videoContentRect], [stream] 크기, 모르면 4:3) 안에 그린다: 4:3 영상이면 양옆 검은 띠에는 격자가 없고 줌 사각형도 4:3.
 */
@Composable
internal fun CameraOverlay(lens: Lens, zoomRatio: Double, focusEnabled: Boolean, onFocus: (Double, Double) -> Unit, modifier: Modifier, stream: IntSize? = null) {
    var mark by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(mark) { if (mark != null) { delay(AppConfig.CameraView.FOCUS_MARK_MS); mark = null } }
    val focus by rememberUpdatedState(onFocus)
    val streamNow by rememberUpdatedState(stream)
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = FontSize.Body, fontWeight = FontWeight.Bold, color = Tertiary)
    Canvas(
        modifier.pointerInput(focusEnabled) {
            if (focusEnabled) detectTapGestures { p ->
                val v = videoContentRect(size.width.toDouble(), size.height.toDouble(), streamNow?.width, streamNow?.height)
                val x = (p.x - v.left) / v.width
                val y = (p.y - v.top) / v.height
                // 검은 띠를 누르면 무시
                if (x in 0.0..1.0 && y in 0.0..1.0) {
                    mark = p
                    focus(x, y)
                }
            }
        },
    ) {
        val v = videoContentRect(size.width.toDouble(), size.height.toDouble(), stream?.width, stream?.height)
        val left = v.left.toFloat()
        val top = v.top.toFloat()
        val vw = v.width.toFloat()
        val vh = v.height.toFloat()
        val mid = Offset(left + vw / 2, top + vh / 2)
        val thin = 1.dp.toPx()
        val grid = Color.White.copy(alpha = 0.45f)
        for (i in 1..2) {
            drawLine(grid, Offset(left + vw * i / 3, top), Offset(left + vw * i / 3, top + vh), thin)
            drawLine(grid, Offset(left, top + vh * i / 3), Offset(left + vw, top + vh * i / 3), thin)
        }
        val a = 4.dp.toPx()
        val b = 10.dp.toPx()
        listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f).forEach { (sx, sy) ->
            drawLine(Color.White, Offset(mid.x + sx * a, mid.y + sy * a), Offset(mid.x + sx * b, mid.y + sy * b), 1.5.dp.toPx())
        }
        val f = zoomFrameFraction(zoomRatio).toFloat()
        if (lens == Lens.WIDE && f < 1f) {
            // 영상과 같은 비율의 사각형(한 변 = 영상 / 배율)
            val w = vw * f
            val h = vh * f
            brackets(Offset(mid.x - w / 2, mid.y - h / 2), Size(w, h), Tertiary, minOf(12.dp.toPx(), w / 3), 2.dp.toPx())
            val text = measurer.measure("%.1fX".format(zoomRatio), label)
            drawText(text, topLeft = Offset(mid.x + w / 2 - text.size.width, mid.y + h / 2 + 2.dp.toPx()))
        }
        mark?.let { p ->
            val s = 48.dp.toPx()
            drawRect(Caution, Offset(p.x - s / 2, p.y - s / 2), Size(s, s), style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** 네 모서리 꺾쇠(줌 영역 사각형). */
private fun DrawScope.brackets(topLeft: Offset, sz: Size, c: Color, len: Float, w: Float) {
    val r = topLeft.x + sz.width
    val btm = topLeft.y + sz.height
    listOf(Offset(topLeft.x, topLeft.y) to (1f to 1f), Offset(r, topLeft.y) to (-1f to 1f), Offset(topLeft.x, btm) to (1f to -1f), Offset(r, btm) to (-1f to -1f))
        .forEach { (p, d) ->
            drawLine(c, p, Offset(p.x + d.first * len, p.y), w)
            drawLine(c, p, Offset(p.x, p.y + d.second * len), w)
        }
}

/**
 * 오른쪽 위 노출 띠(10.5.2): ISO · Shutter · Aperture · EV · AE(잠금) · AFS(누르면 초점 모드 차례로) · Storage(사진 남은 장수, 영상이면 남은 녹화 시간).
 * 모르는 값은 "--".
 */
@Composable
internal fun ExposureStrip(c: CameraStatus, videoMode: Boolean, focusEnabled: Boolean, onFocusMode: () -> Unit, modifier: Modifier) {
    Row(
        modifier.height(36.dp).background(OverlayPlate, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ExposureValue("ISO", c.iso)
        ExposureValue("Shutter", c.shutter)
        ExposureValue("Aperture", c.aperture)
        ExposureValue("EV", c.ev)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AE", fontSize = FontSize.Micro, color = TextSoft)
            LockGlyph(c.aeLocked)
        }
        Text(
            c.focus?.name ?: "AF", Modifier.clickable(enabled = focusEnabled, onClickLabel = "초점 모드 바꾸기", onClick = onFocusMode),
            style = numberStyle(FontSize.Title, FontWeight.Bold), color = if (focusEnabled) Color.White else TextSoft,
        )
        ExposureValue("Storage", if (videoMode) c.recordSecLeft?.let(::mmss) else c.photosLeft?.toString())
        AutoIcon()
    }
}

/** 노출 띠 끝 카메라 "AUTO" 아이콘(Pilot 2 자동 노출 표시): 흰 카메라 몸통에 검은 AUTO. 누르는 기능은 없다. */
@Composable
private fun AutoIcon() {
    Box(Modifier.size(28.dp, 19.dp).semantics { contentDescription = "자동 노출" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val u = 1.dp.toPx()
            drawRect(Color.White, Offset(4 * u, 0f), Size(7 * u, 3 * u))
            drawRoundRect(Color.White, Offset(0f, 2.5f * u), Size(size.width, size.height - 2.5f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u))
        }
        Text("AUTO", Modifier.offset(y = 1.dp), style = numberStyle(FontSize.Micro, FontWeight.Bold), color = Color.Black, maxLines = 1)
    }
}

@Composable
private fun ExposureValue(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = FontSize.Micro, color = TextSoft, maxLines = 1)
        Text(value ?: "--", style = numberStyle(FontSize.Body), color = Color.White, maxLines = 1)
    }
}

/** AE 자물쇠: 잠금이면 닫힌 고리. */
@Composable
private fun LockGlyph(locked: Boolean) {
    Canvas(Modifier.size(10.dp, 11.dp).semantics { contentDescription = if (locked) "AE 잠금" else "AE 풀림" }) {
        val u = size.width / 10
        drawRect(Color.White, Offset(u, 5 * u), Size(8 * u, 6 * u))
        val shackle = Path().apply {
            moveTo(3 * u, 5 * u)
            lineTo(3 * u, 3 * u)
            cubicTo(3 * u, 0f, 7 * u, 0f, 7 * u, 3 * u)
            if (locked) lineTo(7 * u, 5 * u)
        }
        drawPath(shackle, Color.White, style = Stroke(1.3f * u))
    }
}

/**
 * 왼쪽 열(Pilot 2, 10.5.7): 핀포인트(누름 = 기체 위치, 길게 = 설정, 10.4.7 [PinpointButton]) · 짐벌 메뉴(짐벌 중앙 / 짐벌 아래 -90°) · AR(꺼 둠) · ZOOM/WIDE 전환.
 * 미션 칸·[미션 이어서 비행]은 화면이 핀포인트 오른쪽(x 48dp)에 따로 둔다.
 */
@Composable
internal fun CameraLeftColumn(lens: Lens, onLens: (Lens) -> Unit, onGimbal: (Boolean) -> Unit, modifier: Modifier, telemetry: Telemetry?) {
    Box(modifier) {
        PinpointButton(telemetry, Modifier.offset(y = 4.dp))
        var gimbal by remember { mutableStateOf(false) }
        Box(Modifier.offset(y = 68.dp)) {
            Box(
                Modifier.size(Dimens.TouchSmall).clip(RoundedCornerShape(3.dp)).clickable(onClickLabel = "짐벌 메뉴") { gimbal = true },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_gimbal_center), contentDescription = "짐벌 메뉴", modifier = Modifier.size(20.dp), tint = Color.White) }
            DropdownMenu(gimbal, onDismissRequest = { gimbal = false }, containerColor = CardBg) {
                DropdownMenuItem({ Text("짐벌 중앙", fontSize = FontSize.Body, color = Color.White) }, onClick = { gimbal = false; onGimbal(false) })
                DropdownMenuItem({ Text("짐벌 아래(-90°)", fontSize = FontSize.Body, color = Color.White) }, onClick = { gimbal = false; onGimbal(true) })
            }
        }
        Canvas(Modifier.offset(y = 116.dp).size(Dimens.TouchSmall).padding(6.dp).alpha(0.4f).semantics { contentDescription = "AR 표시(준비 중)" }) {
            val p = Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(size.width / 2, size.height * 0.75f); lineTo(0f, size.height); close() }
            drawPath(p, Color.White)
        }
        val other = if (lens == Lens.WIDE) Lens.ZOOM else Lens.WIDE
        Box(
            Modifier.offset(x = 42.dp, y = 112.dp).size(40.dp, 38.dp).clip(RoundedCornerShape(4.dp)).background(ControlBg)
                .clickable(onClickLabel = if (other == Lens.ZOOM) "망원으로" else "광각으로") { onLens(other) },
            contentAlignment = Alignment.Center,
        ) { Text(if (other == Lens.ZOOM) "ZOOM" else "WIDE", fontSize = FontSize.Caption, fontWeight = FontWeight.Bold, color = Color.White) }
    }
}

/**
 * 오른쪽 열(Pilot 2, 10.5.2): 카메라 설정(슬라이더) → 사진/영상 아이콘 → 셔터(녹화 중 빨강) → ▶ 재생(앨범). 왼쪽 옆에 세로 줌(+ / 눈금 / −).
 * 영상 모드에서 셔터는 녹화 시작·정지([FlightUiState.recordingSinceMs]가 있으면 빨간 네모와 경과 mm:ss).
 * [cameraEnabled]가 거짓이면 사진/영상·줌을 막는다(자동 비행은 일시정지 중에만). [onAlbum]이 null이면 재생을 숨긴다.
 */
@Composable
internal fun CameraColumn(
    s: FlightUiState, shutterEnabled: Boolean, onShutter: () -> Unit,
    cameraEnabled: Boolean, onVideoMode: (Boolean) -> Unit, onZoom: (Boolean) -> Unit,
    onCameraSettings: () -> Unit, modifier: Modifier, onAlbum: (() -> Unit)? = null, shotsLabel: String? = null,
) {
    Box(modifier) {
        ZoomControl(s.zoomRatio, cameraEnabled, onZoom, Modifier.padding(top = 50.dp, end = 48.dp).align(Alignment.TopEnd))
        Column(Modifier.align(Alignment.TopEnd).width(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(Dimens.TouchSmall).clickable(onClickLabel = "카메라 설정", onClick = onCameraSettings).padding(6.dp).semantics { contentDescription = "카메라 설정" }) {
                val w = 2.dp.toPx()
                listOf(0.15f to 0.7f, 0.5f to 0.3f, 0.85f to 0.8f).forEach { (y, knob) ->
                    drawLine(Color.White, Offset(0f, size.height * y), Offset(size.width, size.height * y), w)
                    drawCircle(Color.Black, 3.dp.toPx(), Offset(size.width * knob, size.height * y))
                    drawCircle(Color.White, 3.dp.toPx(), Offset(size.width * knob, size.height * y), style = Stroke(w))
                }
            }
            val modeEnabled = cameraEnabled && s.recordingSinceMs == null
            Canvas(
                Modifier.size(Dimens.TouchSmall, 28.dp).clickable(enabled = modeEnabled, onClickLabel = if (s.videoMode) "사진 모드로" else "영상 모드로") { onVideoMode(!s.videoMode) }
                    .padding(horizontal = 3.dp, vertical = 4.dp).semantics { contentDescription = if (s.videoMode) "영상 모드" else "사진 모드" },
            ) {
                val c = Color.White.copy(alpha = if (modeEnabled) 1f else DISABLED_ALPHA)
                val w = 2.dp.toPx()
                drawRect(c, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                if (s.videoMode) drawCircle(Error, size.height / 4, center)
                else drawRect(c, Offset(size.width * 0.18f, size.height * 0.25f), Size(size.width * 0.64f, size.height * 0.5f))
            }
            val since = s.recordingSinceMs
            // Pilot 2 흰 셔터: 흰 고리 + 흰 원. 누를 수 없을 때(자동 비행 중)는 고리는 흰색 그대로, 원만 흐린 흰색(회색 원이 아님)
            Box(
                Modifier.size(44.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape)
                    .clickable(enabled = shutterEnabled, onClickLabel = if (!s.videoMode) "촬영" else if (since == null) "녹화 시작" else "녹화 정지", onClick = onShutter),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    since != null -> Box(Modifier.size(16.dp).clip(RoundedCornerShape(2.dp)).background(Error))
                    s.videoMode -> Box(Modifier.size(34.dp).clip(CircleShape).background(Error.copy(alpha = if (shutterEnabled) 1f else SHUTTER_BLOCKED_ALPHA)))
                    else -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = if (shutterEnabled) 1f else SHUTTER_BLOCKED_ALPHA)))
                }
            }
            if (since != null) {
                val now by produceState(System.currentTimeMillis(), since) { while (true) { value = System.currentTimeMillis(); delay(500) } }
                Text(mmss(((now - since) / 1000).coerceAtLeast(0).toInt()), style = numberStyle(FontSize.Body), color = Error)
            } else shotsLabel?.let { Text(it, style = numberStyle(FontSize.Body), color = Color.White) }
            if (onAlbum != null) {
                Box(
                    Modifier.size(Dimens.TouchSmall).clickable(onClickLabel = "재생(앨범)", onClick = onAlbum).semantics { contentDescription = "재생(앨범)" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(26.dp, 20.dp)) {
                        drawRoundRect(Color.White, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                        val p = Path().apply { moveTo(size.width * 0.4f, size.height * 0.28f); lineTo(size.width * 0.66f, size.height / 2); lineTo(size.width * 0.4f, size.height * 0.72f); close() }
                        drawPath(p, Color.Black)
                    }
                }
            }
        }
    }
}

/**
 * 세로 줌(Pilot 2): + / 눈금 / − 와 가운데 "Zoom 7.0X". 눈금을 위로 끌면 확대, [Dimens.ZoomDragStep]마다 한 단계(1·2·4·7·14·28배, 기체 범위 안).
 * 광각 보기에서도 망원 배율을 바꾸며 광각 화면에는 줌 영역 사각형으로 보인다(10.5.2).
 */
@Composable
private fun ZoomControl(ratio: Double, enabled: Boolean, onZoom: (Boolean) -> Unit, modifier: Modifier) {
    val zoom by rememberUpdatedState(onZoom)
    val stepPx = with(LocalDensity.current) { Dimens.ZoomDragStep.toPx() }
    val c = Color.White.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
    // Pilot 2: 흰 + · 눈금과 흰 "Zoom 7.0X" · 흰 − 를 따로 떨어진 어두운 칸 셋으로
    Column(modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZoomKey(true, enabled, c, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)) { onZoom(true) }
        Column(
            Modifier.fillMaxWidth().background(ControlBg).padding(vertical = 3.dp).pointerInput(enabled, stepPx) {
                if (!enabled) return@pointerInput
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, dy ->
                    change.consume()
                    acc += dy
                    while (acc <= -stepPx) { acc += stepPx; zoom(true) }
                    while (acc >= stepPx) { acc -= stepPx; zoom(false) }
                }
            }.semantics { contentDescription = "줌 %.1f배, 위아래로 끌어 바꾸기".format(ratio) },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Ticks(c)
            Text(
                "Zoom\n%.1fX".format(ratio), Modifier.background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                style = numberStyle(FontSize.Micro), color = Color.Black, textAlign = TextAlign.Center,
            )
            Ticks(c)
        }
        ZoomKey(false, enabled, c, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)) { onZoom(false) }
    }
}

/** 세로 줌 + / − 칸. */
@Composable
private fun ZoomKey(up: Boolean, enabled: Boolean, c: Color, shape: RoundedCornerShape, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(Dimens.TouchSmall).clip(shape).background(ControlBg)
            .clickable(enabled = enabled, onClickLabel = if (up) "줌 확대" else "줌 축소", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(if (up) "+" else "−", fontSize = FontSize.Number, fontWeight = FontWeight.Bold, color = c) }
}

@Composable
private fun Ticks(c: Color) {
    Canvas(Modifier.size(18.dp, 32.dp)) {
        var y = 0f
        val gap = 5.dp.toPx()
        while (y <= size.height) {
            drawLine(c.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            y += gap
        }
    }
}

/**
 * 왼쪽 아래 미리보기 132×92(매뉴얼 54쪽 15번): 카메라 뷰에서는 지도, 큰 지도에서는 카메라. 누르면 큰 화면과 자리를 바꾼다(RC C2와 같음).
 * 왼쪽 아래 L 꺾쇠로 크게([Dimens.MiniMapLarge])/작게(10.5.4). 카메라 칸이면 오른쪽 위에 기체 이름 "M3E"(Pilot 2).
 */
@Composable
internal fun MiniMap(mapMain: Boolean, onSwap: () -> Unit, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    var large by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.padding(8.dp).size(if (large) Dimens.MiniMapLarge else Dimens.MiniMap), shape = RoundedCornerShape(4.dp),
        color = Color.Black, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
    ) {
        content(Modifier.fillMaxSize())
        // 지도 뷰가 터치를 먹으므로 위에 투명한 누름 영역을 둔다
        Box(Modifier.fillMaxSize().clickable(onClickLabel = if (mapMain) "영상 크게" else "지도 크게", onClick = onSwap))
        // 큰 화면이 지도면 이 칸은 카메라(Pilot 2 "M3E")
        if (mapMain) Box(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 3.dp), contentAlignment = Alignment.TopEnd) {
            Text("M3E", fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.White)
        }
        // 왼쪽 아래 L 꺾쇠(Pilot 2 p2s_7): 작은 어두운 판 위, 크게/작게
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Canvas(
                Modifier.size(Dimens.TouchSmall).clickable(onClickLabel = if (large) "미리보기 작게" else "미리보기 크게") { large = !large }.padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp)).padding(5.dp)
                    .semantics { contentDescription = if (large) "미리보기 작게" else "미리보기 크게" },
            ) {
                val w = 2.dp.toPx()
                drawLine(Color.White, Offset(w / 2, 0f), Offset(w / 2, size.height - w / 2), w)
                drawLine(Color.White, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
            }
        }
    }
}

/** 영상 위 안내 줄(Pilot 2 안내문 자리, 가운데 위 줌 상자 아래): 미션 안내·점검 실패·수동 비행 안내. */
@Composable
internal fun NoticeLines(lines: List<Pair<String, Color>>, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { (text, color) ->
            Text(
                text, Modifier.background(CardBg, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = color, fontSize = FontSize.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}
