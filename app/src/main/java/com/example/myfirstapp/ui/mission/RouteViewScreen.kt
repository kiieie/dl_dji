package com.example.myfirstapp.ui.mission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.data.MissionVisitRow
import com.example.myfirstapp.ui.AppBadge
import com.example.myfirstapp.ui.MapAttribution
import com.example.myfirstapp.ui.MapMarkers
import com.example.myfirstapp.ui.MapTool
import com.example.myfirstapp.ui.Overlay
import com.example.myfirstapp.ui.PoleMap
import com.example.myfirstapp.ui.PoleSymbolRow
import com.example.myfirstapp.ui.flight.poleLabel
import com.example.myfirstapp.ui.poleSymbols
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.LightSurface
import com.example.myfirstapp.ui.theme.numberStyle
import java.util.Locale

/**
 * 경로 보기(10.5.8, Pilot 2 경로 열기): 라이브러리 카드를 누르면 연다. 왼쪽 큰 지도(영역·소티 색 경로·구간 거리·H·전주 라벨),
 * 오른쪽 패널 요약·주 버튼·[다시 날기]·[결과]·[비행 다시 보기]와 방문 전주 목록(누르면 지도에서 고름). 뒤로는 화면의 BackHandler가 닫는다.
 */
@Composable
internal fun RouteViewScreen(v: RouteView, s: MissionListUiState, vm: MissionListViewModel) {
    val m = v.mission
    var fitRequest by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.HeaderList).background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = vm::closeView, modifier = Modifier.size(Dimens.TouchSmall)) {
                Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
            }
            AppBadge()
            Text("${m.projectName} #${m.id}", Modifier.weight(1f, fill = false), fontSize = FontSize.Title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val c = statusColor(m.status)
            Text(STATUS_TITLE[m.status] ?: m.status, fontSize = FontSize.Caption, color = c, modifier = Modifier.border(1.dp, c, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        }
        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                PoleMap(
                    poles = v.poles, mapFiles = s.mapFiles, background = s.background,
                    overlay = Overlay(v.area, v.plan, emptyList(), v.params, v.visits.firstOrNull { it.poleId == v.selected }?.let { GeoPoint(it.lat, it.lon) }),
                    markers = MapMarkers(home = v.plan.home), annotations = emptyList(), focus = v.focus, fitRequest = fitRequest, showBackground = true,
                    onPoleClick = { id -> vm.viewPole(id) }, onMapTap = null,
                )
                MapAttribution(s.background.attribution, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    MapTool("전체 보기", onClick = { fitRequest++ }) { tint -> Icon(painterResource(R.drawable.ic_fit), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) }
                }
            }
            RoutePanel(v, s, vm)
        }
    }
}

/** 오른쪽 패널 300dp: 요약 칸, 동작 버튼, 방문 전주 목록. */
@Composable
private fun RoutePanel(v: RouteView, s: MissionListUiState, vm: MissionListViewModel) {
    val m = v.mission
    Column(Modifier.width(300.dp).fillMaxHeight().background(LightSurface)) {
        val sum = s.summaries[m.id]
        val cells = listOf(
            "거리" to (sum?.let { "${it.distanceM.toInt()} m" } ?: "-"),
            "예상 시간" to (sum?.let { mmss(it.durationSec) } ?: "-"),
            "전주" to "${m.finishedCount}/${m.poleCount}",
            "사진" to "${m.photoCount}/${s.expectedPhotos[m.id] ?: 0}",
        )
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 8.dp, vertical = 8.dp)) {
            cells.forEachIndexed { i, (label, value) ->
                if (i > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(label, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(value, style = numberStyle(FontSize.Label), maxLines = 1)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            m.primaryAction()?.let { a ->
                Button(onClick = { vm.run(m) }, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(4.dp)) {
                    Text(a.label, fontSize = FontSize.Label, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction("다시 날기") { vm.copy(m) }
                if (m.doneCount > 0 && m.primaryAction() != MissionAction.RESULT) SmallAction("결과") { vm.result(m) }
                if (m.hasTrack) SmallAction("비행 다시 보기") { vm.replay(m) }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "전주 ${v.visits.size} · 소티 ${v.plan.sorties.size}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = rememberLazyListState()
        // 지도에서 고른 전주가 목록 밖이면 따라간다
        LaunchedEffect(v.selected) {
            val i = v.visits.indexOfFirst { it.poleId == v.selected }
            if (i >= 0) list.animateScrollToItem(i)
        }
        LazyColumn(Modifier.weight(1f), state = list) {
            itemsIndexed(v.visits, key = { _, r -> r.visitId }) { i, r -> PoleRow(i + 1, r, r.poleId == v.selected) { vm.viewPole(r.poleId, move = true) } }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick, modifier = Modifier.height(Dimens.ButtonChip), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 10.dp),
) { Text(label, fontSize = FontSize.Body, maxLines = 1) }

/**
 * 방문 전주 한 줄: 차례, 상태 점(끝남 초록·남음 회색), 선로번호(크게)·선로명(작게)·기호, 아래 줄 규격·설치년월·지선주·전산화번호.
 */
@Composable
private fun PoleRow(n: Int, r: MissionVisitRow, selected: Boolean, onClick: () -> Unit) {
    val done = r.status != "PENDING" && r.status != "IN_PROGRESS"
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else LightSurface)
            .clickable(onClickLabel = poleLabel(r.toPole()), onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$n", style = numberStyle(FontSize.Caption), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp), maxLines = 1)
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(poleLabel(r.toPole()), style = numberStyle(FontSize.Label), maxLines = 1)
                r.lineName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.weight(1f, fill = false), fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PoleSymbolRow(poleSymbols(r.voltage, r.equipment), ink = MaterialTheme.colorScheme.onSurface)
            }
            val attrs = listOfNotNull(
                r.lengthM?.let { "${it.toInt()}M" }, r.installedYm?.takeIf { it.length >= 6 }?.let { "${it.take(4)}-${it.substring(4, 6)}" },
                "지선주".takeIf { r.guy }, r.poleId,
            ).joinToString(" · ")
            Text(attrs, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun mmss(sec: Double) = sec.toInt().let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }
