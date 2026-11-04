package com.example.myfirstapp.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.PoleSearch
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.vworldAvailable
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.TextSoft

// 지도 오른쪽 도구(10.5.4, Pilot 2 지도 뷰): 흰 둥근 버튼. 계획 지도(36dp)와 비행 큰 지도(32dp)가 같이 쓴다.

/** 흰 지도 도구. 쓰는 중인 도구는 파랑 테두리와 파랑 아이콘. */
@Composable
internal fun MapTool(
    label: String, active: Boolean = false, enabled: Boolean = true, size: Dp = Dimens.TouchMedium, onClick: () -> Unit, icon: @Composable (Color) -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled, color = Color.White, shape = RoundedCornerShape(4.dp),
        border = if (active) BorderStroke(2.dp, Accent) else null,
        modifier = Modifier.size(size).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { icon(if (!enabled) Color(0xFFB0B0B0) else if (active) Accent else Color.Black) }
    }
}

/** 도구 안 그림 글자(N 잠금 등). */
@Composable
internal fun MapToolGlyph(text: String, tint: Color) = Text(text, fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = tint)

/**
 * 레이어(10.5.7): 배경 지도 Bing 위성 / VWorld 위성 / VWorld 일반(7.4, 설정과 같은 값). VWorld 키가 없는 빌드는 VWorld를 끈다.
 * [background]가 있으면(계획 지도) 배경 끄기·켜기도 메뉴에 둔다.
 */
@Composable
internal fun MapLayerButton(current: MapSource, size: Dp, onPick: (MapSource) -> Unit, background: Boolean? = null, onToggleBackground: () -> Unit = {}) {
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

/** 검색(10.5.7): 이 지도의 전주를 전주번호·선로번호로 찾아([PoleSearch], 앱 DB만) 고르면 [onPick]. 전주가 없으면 끈다. */
@Composable
internal fun MapSearchButton(poles: List<Pole>, size: Dp, onPick: (Pole) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    MapTool(if (poles.isEmpty()) "검색(전주 없음)" else "전주 검색", active = open, enabled = poles.isNotEmpty(), size = size, onClick = { open = true }) { tint ->
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
    if (!open) return
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(poles, query) { PoleSearch.search(poles, query, AppConfig.Map.SEARCH_LIMIT) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    FullscreenAlertDialog(
        onDismissRequest = { open = false },
        title = { Text("전주 검색") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    label = { Text("전주번호 또는 선로번호") },
                )
                if (query.isNotBlank() && found.isEmpty()) Text("찾는 전주가 없습니다", fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                found.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.RowList).clickable(onClickLabel = "${p.displayName}로 이동") { open = false; onPick(p) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(p.displayName, Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.poleId, fontSize = FontSize.Caption, color = TextSoft, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("닫기") } },
    )
}
