package com.example.myfirstapp.ui.flight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.ObstacleLevel
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.unit.TextUnit
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Inactive
import com.example.myfirstapp.ui.theme.Muted
import com.example.myfirstapp.ui.theme.SurfaceContainerHigh
import com.example.myfirstapp.ui.theme.SurfaceContainerHighest

// 카메라 뷰 공통 틀(10.4.3 자동·수동 비행 시안, Pilot 2 54~57쪽). 자동 비행([FlightScreen])과 수동 비행([ManualFlightScreen])이 같이 쓴다.
// 크기는 [Dimens](상단 바 높이 [Dimens.TopBar], 카드 폭 [Dimens.CardWidth]), 카드 바탕은 [CardBg](ui/theme).

/**
 * 상단 바(Pilot 2 56쪽 순서): 뒤로 · 시스템 상태 칸(196dp, 경고는 빨강) · 가운데 제목 · 현재 시각 · GNSS · RC · 배터리 · 설정.
 * 아래에 배터리 띠(빨강 0~10%, 노랑 10~20%, 초록 그 위, H는 복귀에 필요한 양).
 */
@Composable
internal fun TopBar(s: FlightUiState, onExit: () -> Unit, onOpenSettings: () -> Unit, label: String? = null, canExit: Boolean = s.canExit) {
    val t = s.telemetry
    val state = s.exec.state
    val lost = state == ExecState.LinkLost || t?.connected == false
    val weakGnss = t != null && t.connected && t.gpsLevel < 3
    val warn = s.exec.rthCountdownSec != null || weakGnss
    // 충돌 경고(13번): 위험은 빨강 바탕, 주의는 주황 점
    val obstacle = s.obstacles.text
    val (status, alarm) = when {
        lost -> "연결 끊김" to true
        obstacle != null && s.obstacles.level == ObstacleLevel.DANGER -> obstacle to true
        s.exec.rthCountdownSec != null -> "배터리 부족 · ${s.exec.rthCountdownSec}초 뒤 RTH" to true
        weakGnss -> "GNSS 약함 · 주의해서 비행" to true
        obstacle != null -> obstacle to false
        t == null -> "기체 없음" to false
        state == ExecState.Paused -> "일시정지 · 수동 비행" to false
        state == ExecState.ReturningHome -> "복귀 중" to false
        else -> "정상 · 비행 가능" to false
    }
    val dot = when {
        warn || lost -> Color.White
        t == null -> Muted
        obstacle != null -> Warning
        state == ExecState.Paused || state == ExecState.ReturningHome -> Warning
        else -> Tertiary
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(Dimens.HeaderCamera).background(Color.Black)) {
            Row(Modifier.fillMaxSize().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onExit, enabled = canExit, modifier = Modifier.size(Dimens.TouchSmall)) {
                    Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(14.dp), tint = if (canExit) Color.White else Inactive)
                }
                Row(
                    Modifier.width(Dimens.CardWidth).height(22.dp).background(if (alarm) Error else SurfaceContainerHigh, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (alarm) Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                    else Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                    Text(status, fontSize = FontSize.Caption, fontWeight = if (alarm) FontWeight.Bold else FontWeight.Normal, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.weight(1f))
                val now by produceState(clock()) { while (true) { delay(15_000); value = clock() } }
                Text(now, style = numberStyle(FontSize.Title), color = Color.White, modifier = Modifier.semantics { contentDescription = "현재 시각" })
                Box(Modifier.width(1.dp).height(16.dp).background(SurfaceContainerHighest))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(painterResource(R.drawable.ic_gnss), contentDescription = "GNSS", modifier = Modifier.size(13.dp), tint = if (weakGnss) Warning else Color.White)
                    Text(t?.satellites?.toString() ?: "--", style = numberStyle(FontSize.Label), color = if (weakGnss) Warning else Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("RC", fontSize = FontSize.Caption, fontWeight = FontWeight.SemiBold, color = Color.White)
                    SignalBars(t?.signalPct, t?.connected == true)
                }
                BatteryIcon(t?.batteryPct)
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(Dimens.TouchSmall)) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "설정", modifier = Modifier.size(15.dp), tint = Color.White)
                }
            }
            Text(
                listOfNotNull(label ?: stateLabel(state, s.exec.remaining), if (s.simulated) "모의 ×${FAKE_TIME_SCALE.toInt()}" else null).joinToString(" · "),
                Modifier.align(Alignment.Center), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
            )
        }
        BatteryBand(t?.batteryPct, s.returnNeedPct)
    }
}

private fun clock() = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

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
            drawRect(if (i < n) c else Inactive, Offset(i * 2 * w, size.height - h), androidx.compose.ui.geometry.Size(w, h))
        }
    }
}

/** 배터리 그림과 %. 30% 아래는 빨강. */
@Composable
private fun BatteryIcon(pct: Double?) {
    val c = when {
        pct == null -> Inactive
        pct < 30 -> Error
        else -> Tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(9.dp, 15.dp)) {
            val u = size.width / 9
            drawRect(Color.White, Offset(2.5f * u, 0f), androidx.compose.ui.geometry.Size(4 * u, 2 * u))
            drawRect(Color.White, Offset(0.5f * u, 2 * u), androidx.compose.ui.geometry.Size(8 * u, size.height - 2.5f * u), style = androidx.compose.ui.graphics.drawscope.Stroke(u))
            val full = size.height - 5 * u
            val h = full * ((pct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
            drawRect(c, Offset(2 * u, 4 * u + full - h), androidx.compose.ui.geometry.Size(5 * u, h))
        }
        Text(pct?.let { "${it.toInt()}%" } ?: "--%", style = numberStyle(FontSize.Body), color = if (pct != null && pct < 30) Error else Color.White)
    }
}

/** 배터리 띠 3dp(Pilot 2 4번). 남은 양까지 빨강·노랑·초록, H 점은 복귀에 필요한 양. */
@Composable
private fun BatteryBand(pct: Double?, returnNeed: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp).background(SurfaceContainerHigh)) {
        val p = ((pct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
        listOf(Triple(0f, 0.1f, Error), Triple(0.1f, 0.2f, Caution), Triple(0.2f, 1f, Tertiary)).forEach { (a, b, c) ->
            val end = minOf(b, p)
            if (end > a) Box(Modifier.offset(x = maxWidth * a).width(maxWidth * (end - a)).fillMaxHeight().background(c))
        }
        if (pct != null && returnNeed > 0) {
            Box(
                Modifier.offset(x = maxWidth * (returnNeed / 100f).coerceIn(0f, 1f) - 4.5.dp, y = (-3).dp).size(9.dp).clip(CircleShape).background(Caution),
                contentAlignment = Alignment.Center,
            ) { Text("H", fontSize = 6.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
        }
    }
}

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
 * 오른쪽 한 열(10.4.3): 렌즈 W/Z → 사진/영상 → 촬영 → 앨범 → 줌 +/−(사이에 지금 배율). 짐벌은 RC 왼쪽 다이얼(10.4.4).
 * 영상 모드에서 셔터는 녹화 시작·정지([recordingSinceMs]가 있으면 빨간 네모와 경과 mm:ss).
 * [cameraEnabled]가 거짓이면 사진/영상·줌을 막는다(자동 비행은 일시정지 중에만). [onAlbum]이 null이면 앨범을 숨긴다.
 */
@Composable
internal fun CameraColumn(
    s: FlightUiState, onLens: (Lens) -> Unit, shutterEnabled: Boolean, onShutter: () -> Unit,
    cameraEnabled: Boolean, onVideoMode: (Boolean) -> Unit, onZoom: (Boolean) -> Unit,
    modifier: Modifier, onAlbum: (() -> Unit)? = null, shotsLabel: String? = null,
) {
    // 장갑 터치(10.4.2): 렌즈·줌 32dp, 사진/영상 28dp, 앨범 32dp. RTH와 겹치지 않게 사이는 4dp
    Column(modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Segments(listOf(Lens.WIDE to "W", Lens.ZOOM to "Z"), s.lens, enabled = true, height = Dimens.TouchSmall, fontSize = FontSize.Caption, onPick = onLens) { if (it == Lens.WIDE) "광각" else "망원" }
        Segments(listOf(false to "사진", true to "영상"), s.videoMode, enabled = cameraEnabled && s.recordingSinceMs == null, height = 28.dp, fontSize = FontSize.Micro, onPick = onVideoMode) { if (it) "영상 모드" else "사진 모드" }
        val since = s.recordingSinceMs
        val ring = if (shutterEnabled) Color.White else Inactive
        Box(
            Modifier.size(44.dp).clip(CircleShape).border(2.dp, ring, CircleShape)
                .clickable(enabled = shutterEnabled, onClickLabel = if (!s.videoMode) "촬영" else if (since == null) "녹화 시작" else "녹화 정지", onClick = onShutter),
            contentAlignment = Alignment.Center,
        ) {
            when {
                since != null -> Box(Modifier.size(16.dp).clip(RoundedCornerShape(2.dp)).background(Error))
                s.videoMode -> Box(Modifier.size(34.dp).clip(CircleShape).background(if (shutterEnabled) Error else Inactive))
                else -> Box(Modifier.size(34.dp).clip(CircleShape).background(ring))
            }
        }
        if (since != null) {
            val now by produceState(System.currentTimeMillis(), since) { while (true) { value = System.currentTimeMillis(); delay(500) } }
            val sec = ((now - since) / 1000).coerceAtLeast(0)
            Text("%02d:%02d".format(sec / 60, sec % 60), style = numberStyle(FontSize.Body), color = Error)
        } else shotsLabel?.let { Text(it, style = numberStyle(FontSize.Body), color = Color.White) }
        if (onAlbum != null) {
            Box(
                Modifier.size(Dimens.TouchSmall).clip(RoundedCornerShape(3.dp)).background(CardBg).clickable(onClickLabel = "앨범", onClick = onAlbum)
                    .semantics { contentDescription = "앨범" },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_album), contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White) }
        }
        Column(Modifier.width(40.dp).background(CardBg, RoundedCornerShape(4.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
            val c = if (cameraEnabled) Color.White else Inactive
            listOf(true to "+", false to "−").forEach { (up, t) ->
                if (!up) Text(if (s.lens == Lens.WIDE) "1.0X" else "%.1fX".format(s.zoomRatio), style = numberStyle(FontSize.Caption), color = Tertiary)
                Box(
                    Modifier.fillMaxWidth().height(Dimens.ButtonChip).clickable(enabled = cameraEnabled, onClickLabel = if (up) "줌 확대" else "줌 축소") { onZoom(up) },
                    contentAlignment = Alignment.Center,
                ) { Text(t, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c) }
            }
        }
    }
}

/** 세로 두 칸 고르기(렌즈 W/Z, 사진/영상). 고른 칸은 흰 바탕. */
@Composable
private fun <T> Segments(items: List<Pair<T, String>>, selected: T, enabled: Boolean, height: Dp, fontSize: TextUnit, onPick: (T) -> Unit, label: (T) -> String) {
    Column(Modifier.width(40.dp).background(CardBg, RoundedCornerShape(4.dp)).padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (v, t) ->
            val on = selected == v
            Box(
                Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(3.dp)).background(if (on) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                    .clickable(enabled = enabled, onClickLabel = label(v)) { onPick(v) },
                contentAlignment = Alignment.Center,
            ) { Text(t, fontSize = fontSize, fontWeight = FontWeight.Bold, color = if (on) Color.Black else if (enabled) Color.White else Color(0xFF777777)) }
        }
    }
}

/** RTH를 누르고 있어야 하는 시간(10장). 시스템 길게 누르기(약 0.5초)보다 길다. */
internal const val RTH_HOLD_MS = 1_000L

/** 오른쪽 아래 RTH(노랑 테두리, [RTH_HOLD_MS] 이상 눌러야 동작, 10장). 손을 떼기 전에 동작하고, 그 뒤 뗄 때까지는 다시 안 걸린다. */
@Composable
internal fun RthButton(onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val action by rememberUpdatedState(onLongPress)
    Row(
        modifier.height(Dimens.ButtonStd).clip(RoundedCornerShape(4.dp)).background(Color(0xE0121212)).border(1.dp, Caution, RoundedCornerShape(4.dp))
            .semantics {
                role = Role.Button
                contentDescription = "RTH, 1초 길게 누르기"
                onLongClick("RTH") { action(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var released = false
                    withTimeoutOrNull(RTH_HOLD_MS) { waitForUpOrCancellation(); released = true }
                    if (!released) {
                        action()
                        waitForUpOrCancellation()
                    }
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_rth), contentDescription = null, modifier = Modifier.size(15.dp), tint = Caution)
        Text("RTH", fontSize = FontSize.Body, fontWeight = FontWeight.Bold, color = Color.White)
        Text("길게", fontSize = FontSize.Caption, color = TextSoft)
    }
}

/** 왼쪽 아래 지도 미리보기 132×92(매뉴얼 54쪽 15번). 누르면 큰 화면과 자리를 바꾼다. */
@Composable
internal fun MiniMap(mapMain: Boolean, onSwap: () -> Unit, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    Surface(
        modifier = modifier.padding(8.dp).size(Dimens.MiniMap), shape = RoundedCornerShape(4.dp),
        color = Color.Black, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
    ) {
        content(Modifier.fillMaxSize())
        // 지도 뷰가 터치를 먹으므로 위에 투명한 누름 영역을 둔다
        Box(Modifier.fillMaxSize().clickable(onClickLabel = if (mapMain) "영상 크게" else "지도 크게", onClick = onSwap))
    }
}

/** 영상 위 작은 버튼(짐벌 등) 40×32. */
@Composable
internal fun OverlayIconButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp, 32.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xD9121212)).clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
}
