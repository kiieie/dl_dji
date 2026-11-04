package com.example.myfirstapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.Annotation
import com.example.myfirstapp.core.model.Annotations
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.parseCoordinate
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.PoleSearch
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.vworldAvailable
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Inactive
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.TextSoft

// 지도 오른쪽 도구(10.5.4, Pilot 2 지도 뷰): 흰 둥근 버튼. 계획 지도(36dp)와 비행 큰 지도(32dp)가 같이 쓴다.

/**
 * 흰 지도 도구. 쓰는 중인 도구는 파랑 테두리와 파랑 아이콘. [dark]면 Pilot 2 N 잠금·라이브러리처럼 검은 바탕 흰 아이콘.
 */
@Composable
internal fun MapTool(
    label: String, active: Boolean = false, enabled: Boolean = true, size: Dp = Dimens.TouchMedium, dark: Boolean = false,
    onClick: () -> Unit, icon: @Composable (Color) -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled, color = if (dark) CardBg else Color.White, shape = RoundedCornerShape(4.dp),
        border = if (active) BorderStroke(2.dp, Accent) else null,
        modifier = Modifier.size(size).semantics { contentDescription = label },
    ) {
        val tint = when {
            !enabled -> if (dark) Inactive else Color(0xFFB0B0B0)
            active -> Accent
            dark -> Color.White
            else -> Color.Black
        }
        Box(contentAlignment = Alignment.Center) { icon(tint) }
    }
}

/**
 * N 잠금 그림(Pilot 2 p2s_7): 위에 "N", 아래 북쪽 화살촉(왼쪽 반 채움). [locked](북쪽 고정)면 오른쪽 아래 노란 자물쇠.
 * 기수 방향 모드는 자물쇠 없이 화살촉만.
 */
@Composable
internal fun NorthLockGlyph(locked: Boolean, tint: Color) {
    Box(Modifier.size(24.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("N", fontSize = FontSize.Micro, fontWeight = FontWeight.Bold, color = tint, lineHeight = FontSize.Micro)
            Canvas(Modifier.size(9.dp, 12.dp)) {
                val w = size.width
                val h = size.height
                val tip = Offset(w / 2, 0f)
                val notch = Offset(w / 2, h * 0.72f)
                drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(0f, h); lineTo(notch.x, notch.y); close() }, tint)
                drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(w, h); lineTo(notch.x, notch.y); close() }, tint, style = Stroke(1.dp.toPx()))
            }
        }
        if (locked) Canvas(Modifier.align(Alignment.BottomEnd).size(7.dp, 9.dp)) {
            val u = size.width / 7
            drawRect(Caution, Offset(0f, 4 * u), Size(size.width, size.height - 4 * u))
            drawArc(Caution, 180f, 180f, useCenter = false, topLeft = Offset(1.5f * u, u), size = Size(4 * u, 5 * u), style = Stroke(1.2f * u))
        }
    }
}

/**
 * 지도 출처(10.5.4): MapLibre 로고·ⓘ는 숨기고(Pilot 2에 없음) 배경 타일 출처("© …")만 작은 글자로 모서리에 둔다(타일 이용 조건).
 */
@Composable
internal fun MapAttribution(text: String, modifier: Modifier) {
    Text(
        text, modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        fontSize = FontSize.Micro, color = Color.White, maxLines = 1,
    )
}

/**
 * 레이어(10.5.7): 배경 지도 Bing 위성 / VWorld 위성 / VWorld 일반(7.4, 설정과 같은 값). VWorld 키가 없는 빌드는 VWorld를 끈다.
 * [background]가 있으면(계획 지도) 배경 끄기·켜기도 메뉴에 둔다. [geo]가 있으면(비행 지도) GEO 구역 보기 켬·끔(10.4.9, Pilot 2 레이어 → GEO 구역).
 */
@Composable
internal fun MapLayerButton(
    current: MapSource, size: Dp, onPick: (MapSource) -> Unit, background: Boolean? = null, onToggleBackground: () -> Unit = {},
    geo: Boolean? = null, onToggleGeo: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        MapTool("레이어", active = open || background == false, size = size, onClick = { open = true }) { tint ->
            Icon(painterResource(R.drawable.ic_layers), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, properties = MENU_PROPERTIES) {
            MapSource.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text((if (s == current) "✓ " else "    ") + s.title, fontSize = FontSize.Body) },
                    enabled = s == MapSource.BING || vworldAvailable,
                    onClick = { open = false; onPick(s) },
                )
            }
            if (background != null) DropdownMenuItem(text = { Text(if (background) "배경 끄기" else "배경 켜기", fontSize = FontSize.Body) }, onClick = { open = false; onToggleBackground() })
            if (geo != null) DropdownMenuItem(text = { Text((if (geo) "✓ " else "    ") + "GEO 구역", fontSize = FontSize.Body) }, onClick = { open = false; onToggleGeo() })
        }
    }
}

/** 과녁(10.5.7): 기체 / 조종기를 골라 그 위치로 지도를 옮긴다. 위치를 모르는 쪽은 끈다. */
@Composable
internal fun MapTargetButton(size: Dp, aircraftKnown: Boolean, rcKnown: Boolean, onAircraft: () -> Unit, onRc: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        MapTool("위치로 이동", active = open, enabled = aircraftKnown || rcKnown, size = size, onClick = { open = true }) { tint ->
            Icon(painterResource(R.drawable.ic_target), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, properties = MENU_PROPERTIES) {
            DropdownMenuItem(text = { Text("기체", fontSize = FontSize.Body) }, enabled = aircraftKnown, onClick = { open = false; onAircraft() })
            DropdownMenuItem(text = { Text("조종기", fontSize = FontSize.Body) }, enabled = rcKnown, onClick = { open = false; onRc() })
        }
    }
}

/**
 * 검색(10.5.7): 이 지도의 전주를 전주번호·선로번호로([PoleSearch]), 핀포인트·선·영역을 이름으로([Annotations.search]) 찾아(앱 DB만)
 * 고르면 [onPick]·[onPickNote]. [onPickPoint]가 있으면 좌표("36.48, 127.33", [parseCoordinate])도 받아 그 자리로 간다(주소 검색은 온라인이라 없음).
 * 찾을 것이 없으면 끈다.
 */
@Composable
internal fun MapSearchButton(
    poles: List<Pole>, size: Dp, notes: List<Annotation> = emptyList(), onPickNote: (Annotation) -> Unit = {},
    onPickPoint: ((GeoPoint) -> Unit)? = null, onPick: (Pole) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val any = poles.isNotEmpty() || notes.isNotEmpty() || onPickPoint != null
    MapTool(if (any) "검색" else "검색(전주·주석 없음)", active = open, enabled = any, size = size, onClick = { open = true }) { tint ->
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
    if (!open) return
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(poles, query) { PoleSearch.search(poles, query, AppConfig.Map.SEARCH_LIMIT) }
    // 전주와 합쳐 [AppConfig.Map.SEARCH_LIMIT]개까지(대화상자 높이)
    val foundNotes = remember(notes, query, found) { Annotations.search(notes, query, AppConfig.Map.SEARCH_LIMIT - found.size) }
    val point = remember(query) { onPickPoint?.let { parseCoordinate(query) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    FullscreenAlertDialog(
        onDismissRequest = { open = false },
        title = { Text("검색") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    label = { Text(if (onPickPoint != null) "전주번호·선로번호, 주석 이름 또는 좌표(36.48, 127.33)" else "전주번호·선로번호 또는 핀포인트·선·영역 이름") },
                )
                if (query.isNotBlank() && found.isEmpty() && foundNotes.isEmpty() && point == null) Text("찾는 전주·주석이 없습니다", fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                point?.let { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.RowList).clickable(onClickLabel = "좌표로 이동") { open = false; onPickPoint?.invoke(p) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_target), contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("%.6f, %.6f".format(java.util.Locale.ROOT, p.lat, p.lon), Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("좌표로 이동", fontSize = FontSize.Caption, color = TextSoft, maxLines = 1)
                    }
                }
                found.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.RowList).clickable(onClickLabel = "${p.displayName}로 이동") { open = false; onPick(p) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(p.displayName, Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.poleId, fontSize = FontSize.Caption, color = TextSoft, maxLines = 1)
                    }
                }
                foundNotes.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.RowList).clickable(onClickLabel = "${a.name}로 이동") { open = false; onPickNote(a) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Diamond(a.color.ui, 14.dp)
                        Text(a.name, Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.kind.title, fontSize = FontSize.Caption, color = TextSoft, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("닫기") } },
    )
}
