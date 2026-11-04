package com.example.myfirstapp.ui.mission

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.currentSortie
import com.example.myfirstapp.data.MissionSummary
import com.example.myfirstapp.data.ProjectSummary
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.MENU_PROPERTIES
import com.example.myfirstapp.ui.home.RoutePreview
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val STATUS_TITLE = mapOf("PLANNED" to "계획됨", "IN_PROGRESS" to "진행 중", "DONE" to "완료", "PARTIAL" to "부분 완료", "REPLACED" to "대체됨")

/** 상태 색(10.4.1): 계획됨 파랑, 진행 중·부분 완료 주황, 완료 초록, 새 계획으로 대체됨 회색. */
@Composable
internal fun statusColor(status: String) = when (status) {
    "DONE" -> MaterialTheme.colorScheme.tertiary
    "IN_PROGRESS", "PARTIAL" -> Warning
    "REPLACED" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Accent
}

private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(ms))

/** 필터 줄(Pilot 2 "모든 모델 유형 ˅ · 태그 ˅ · 업데이트 시간 내림차순 ˅"): 프로젝트(선로) · 상태 · 정렬. */
@Composable
internal fun LibraryFilters(s: MissionListUiState, projects: List<ProjectSummary>, vm: MissionListViewModel) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surfaceContainerLow), verticalAlignment = Alignment.CenterVertically) {
        FilterDrop(
            projects.firstOrNull { it.id == s.projectFilter }?.name ?: "모든 프로젝트(선로)",
            listOf<Pair<String, () -> Unit>>("모든 프로젝트(선로)" to { vm.setProject(null) }) + projects.map { p -> "${p.name} (${p.poleCount})" to { vm.setProject(p.id) } },
            Modifier.weight(1f),
        )
        FilterDivider()
        FilterDrop(s.filter.label, LibraryFilter.entries.map { f -> f.label to { vm.setFilter(f) } }, Modifier.weight(1f))
        FilterDivider()
        FilterDrop(
            if (s.newestFirst) "업데이트 시간 내림차순" else "업데이트 시간 오름차순",
            listOf("업데이트 시간 내림차순" to { vm.setNewestFirst(true) }, "업데이트 시간 오름차순" to { vm.setNewestFirst(false) }),
            Modifier.weight(1f),
        )
    }
}

/** 필터 칸 사이 세로 구분선(Pilot 2). */
@Composable
private fun FilterDivider() = Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))

@Composable
private fun FilterDrop(label: String, items: List<Pair<String, () -> Unit>>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clickable(onClickLabel = label) { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), fontSize = FontSize.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, properties = MENU_PROPERTIES) {
            items.forEach { (t, a) -> DropdownMenuItem(text = { Text(t) }, onClick = { open = false; a() }) }
        }
    }
}

/** 경로 카드 2열(Pilot 2 라이브러리). 5D로 고른 카드가 화면 밖이면 따라간다. */
@Composable
internal fun MissionGrid(s: MissionListUiState, vm: MissionListViewModel, modifier: Modifier) {
    val list = s.shown
    when {
        s.loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("경로가 없습니다. [+]로 전주 파일을 가져오고 새 계획을 세우세요.", fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val grid = rememberLazyGridState()
            val selected = s.selected
            LaunchedEffect(selected?.id) {
                val i = list.indexOf(selected)
                val visible = grid.layoutInfo.visibleItemsInfo
                if (i >= 0 && visible.isNotEmpty() && (i < visible.first().index || i > visible.last().index)) grid.animateScrollToItem(i)
            }
            LazyVerticalGrid(
                GridCells.Fixed(MissionListViewModel.COLUMNS), modifier.scrollbar(grid), state = grid,
                contentPadding = PaddingValues(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { m -> MissionCard(m, s.routes[m.id].orEmpty(), s.expectedPhotos[m.id] ?: 0, m.id == selected?.id, s, vm) }
            }
        }
    }
}

/**
 * 경로 카드 128dp(Pilot 2: 왼쪽 경로 그림, 유형 아이콘 + 이름, "기체 · 카메라 · 고도", 생성/업데이트 시간): 이름 "<프로젝트> #<미션>",
 * M3 · M3E · 촬영 고도, 우리 배지(상태 · 전주 끝난/전체 · 소티 · 사진 찍은/예상, 10.5 숫자 규칙)와 주 버튼([비행]/[이어하기]/[결과]),
 * ⋯(결과 · 다시 날기 · 즐겨찾기 · 삭제). 다중 선택 중이면 ⋯ 대신 체크.
 */
@Composable
private fun MissionCard(
    m: MissionSummary, route: List<List<com.example.myfirstapp.ui.home.RoutePoint>>, expectedPhotos: Int, selected: Boolean, s: MissionListUiState, vm: MissionListViewModel,
) {
    val action = m.primaryAction()
    val altitude = remember(m.flightParams) { flightParamsFromJson(m.flightParams).shotAltitudeM }
    val checked = m.id in s.checked
    val strong = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (selected || checked) 1.5.dp else 0.dp, if (selected || checked) Accent else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "${m.projectName} #${m.id}") { if (s.selecting) vm.toggleCheck(m) else vm.select(m) },
    ) {
        Box(Modifier.width(128.dp).fillMaxHeight()) {
            RoutePreview(route, GeoPoint(m.homeLat, m.homeLon), Modifier.fillMaxSize(), labels = false)
            if (m.favorite) Text("★", Modifier.align(Alignment.TopStart).padding(4.dp), color = Caution, fontSize = FontSize.Title)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 경로 유형 아이콘(Pilot 2 이름 앞): 우리 유형은 전주 점검 하나(10.5.8)
                Icon(painterResource(R.drawable.ic_route), contentDescription = "전주 점검 경로", modifier = Modifier.padding(end = 4.dp).size(14.dp))
                Text(
                    buildAnnotatedString {
                        append(m.projectName)
                        withStyle(SpanStyle(color = soft, fontWeight = FontWeight.Medium)) { append(" #${m.id}") }
                    },
                    Modifier.weight(1f), fontSize = FontSize.Title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (s.selecting) Checkbox(checked, onCheckedChange = { vm.toggleCheck(m) }, modifier = Modifier.size(Dimens.TouchSmall))
                else CardMenu(m, s.menuFor == m.id, vm)
            }
            Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Pilot 2 "기체 M3 · 카메라 M3E · 고도 0 m": 기체 · 페이로드 · 촬영 고도를 줄에 고르게 펼침(p2s_lib), 끝에 우리 상태 배지
                Box(Modifier.weight(1f)) { CardSpec(SpecIcon.AIRCRAFT, "M3") }
                Box(Modifier.weight(1f)) { CardSpec(SpecIcon.CAMERA, "M3E") }
                Box(Modifier.weight(1f)) { CardSpec(SpecIcon.ALTITUDE, "${altitude.toInt()} m") }
                val c = statusColor(m.status)
                Text(STATUS_TITLE[m.status] ?: m.status, fontSize = FontSize.Caption, color = c, modifier = Modifier.border(1.dp, c, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            }
            Text(
                buildAnnotatedString {
                    append("전주 ")
                    withStyle(numberStyle(FontSize.Label).toSpanStyle().copy(color = strong)) { append("${m.finishedCount}") }
                    // 소티 지금/전체(10.5.11, 홈·상단 바와 같은 정의)
                    append("/${m.poleCount} · 소티 ")
                    withStyle(numberStyle(FontSize.Label).toSpanStyle().copy(color = strong)) { append("${currentSortie(route.map { r -> r.count { !it.done } })}") }
                    append("/${m.sortieCount} · 사진 ")
                    withStyle(numberStyle(FontSize.Label).toSpanStyle().copy(color = strong)) { append("${m.photoCount}") }
                    append("/$expectedPhotos")
                },
                fontSize = FontSize.Caption, color = soft, maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("생성 시간: ${time(m.createdAt)}", fontSize = FontSize.Micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text("업데이트 시간: ${time(m.updatedAt)}", fontSize = FontSize.Micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (action != null && !s.selecting) Button(
                    onClick = { vm.run(m) }, modifier = Modifier.width(76.dp).height(Dimens.ButtonChip), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(0.dp),
                    colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surfaceContainerHigh, strong),
                ) { Text(action.label, fontSize = FontSize.Body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
            }
        }
    }
}

/** ⋯: 결과(사진이 있고 주 버튼이 결과가 아닐 때) · 다시 날기 · 즐겨찾기 · 삭제. 열림은 ViewModel이 들고 있어 열린 동안 5D를 받지 않는다. */
@Composable
private fun CardMenu(m: MissionSummary, open: Boolean, vm: MissionListViewModel) {
    Box {
        IconButton(onClick = { vm.setMenu(m) }, modifier = Modifier.size(Dimens.TouchSmall).semantics { contentDescription = "더 보기" }) { Text("⋯", fontSize = FontSize.Number, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(open, onDismissRequest = { vm.setMenu(null) }, properties = MENU_PROPERTIES) {
            if (m.doneCount > 0 && m.primaryAction() != MissionAction.RESULT) DropdownMenuItem(text = { Text("결과") }, onClick = { vm.result(m) })
            DropdownMenuItem(text = { Text("다시 날기") }, onClick = { vm.copy(m) })
            if (m.hasTrack) DropdownMenuItem(text = { Text("비행 다시 보기") }, onClick = { vm.replay(m) })
            DropdownMenuItem(text = { Text(if (m.favorite) "즐겨찾기 해제" else "즐겨찾기") }, onClick = { vm.toggleFavorite(m) })
            DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, onClick = { vm.askDelete(m) })
        }
    }
}

/** 다중 선택 아래 줄(Pilot 2 ☆ 🗑 ↗): 즐겨찾기 · 삭제 · 내보내기. */
@Composable
internal fun SelectionBar(s: MissionListUiState, vm: MissionListViewModel) {
    val any = s.checked.isNotEmpty()
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = vm::favoriteChecked, enabled = any) { Text("☆ 즐겨찾기") }
        TextButton(onClick = vm::askDeleteChecked, enabled = any, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
        TextButton(onClick = vm::exportChecked, enabled = any) {
            Icon(painterResource(R.drawable.ic_export), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("내보내기")
        }
    }
}

/** 삭제·이어하기 확인 대화상자. */
@Composable
internal fun LibraryDialogs(s: MissionListUiState, vm: MissionListViewModel) {
    if (s.deleting.isNotEmpty()) {
        val one = s.deleting.singleOrNull()
        FullscreenAlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text(one?.let { "${it.projectName} #${it.id} 삭제" } ?: "경로 ${s.deleting.size}개 삭제") },
            text = {
                Text(
                    (one?.let { "계획(소티 ${it.sortieCount}개, 전주 ${it.poleCount}개)과 사진 ${it.doneCount}개 전주의 기록·판독을 모두 지웁니다." }
                        ?: "고른 미션의 계획·사진 기록·판독을 모두 지웁니다.") + " 되돌릴 수 없습니다. 필요하면 먼저 내보내세요.",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDelete) { Text("취소") } },
        )
    }
    s.confirm?.let { m ->
        FullscreenAlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text("마지막 비행이 오늘이 아닙니다") },
            text = {
                Text(
                    "미션 #${m.id}의 마지막 비행은 ${time(m.lastFlightAt!!)}입니다. 남은 전주는 ${m.remainingCount}개입니다.\n" +
                        "다른 날·다른 장소라면 이륙 지점을 다시 정해 남은 전주로 새 계획을 세우세요.",
                )
            },
            confirmButton = { Button(onClick = vm::confirmContinue) { Text("이어서 비행") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::dismissConfirm) { Text("취소") }
                    OutlinedButton(onClick = vm::confirmReplan) { Text("남은 전주로 새 계획") }
                }
            },
        )
    }
    s.message?.let { msg ->
        FullscreenAlertDialog(onDismissRequest = vm::dismissMessage, text = { Text(msg) }, confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("확인") } })
    }
}

/** 목록 아래 5D 안내(10.4.2). */
@Composable
internal fun FiveDHints(modifier: Modifier, hints: List<Pair<String, String>> = listOf("5D ↕" to "고르기", "5D ●" to "실행", "5D ←" to "뒤로")) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        hints.forEach { (k, v) ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)) { append(k) }
                    append(" $v")
                },
                fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** 카드 사양 아이콘(Pilot 2 라이브러리 카드): 기체 · 카메라 · 고도. */
private enum class SpecIcon { AIRCRAFT, CAMERA, ALTITUDE }

/** 카드 사양 한 칸: 작은 회색 선 아이콘 + 글. */
@Composable
private fun CardSpec(icon: SpecIcon, text: String) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.size(12.dp)) {
            val w = 1.3.dp.toPx()
            when (icon) {
                SpecIcon.AIRCRAFT -> {
                    // 위에서 본 쿼드콥터: X 팔과 프로펠러 원 4개
                    drawLine(c, Offset(size.width * 0.2f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.8f), w)
                    drawLine(c, Offset(size.width * 0.8f, size.height * 0.2f), Offset(size.width * 0.2f, size.height * 0.8f), w)
                    listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.2f to 0.8f, 0.8f to 0.8f).forEach { (x, y) ->
                        drawCircle(c, size.width * 0.17f, Offset(size.width * x, size.height * y), style = Stroke(w))
                    }
                }
                SpecIcon.CAMERA -> {
                    drawRoundRect(c, Offset(0f, size.height * 0.25f), Size(size.width, size.height * 0.65f), CornerRadius(w), style = Stroke(w))
                    drawCircle(c, size.width * 0.18f, Offset(size.width / 2, size.height * 0.58f), style = Stroke(w))
                    drawRect(c, Offset(size.width * 0.3f, size.height * 0.1f), Size(size.width * 0.4f, size.height * 0.15f))
                }
                SpecIcon.ALTITUDE -> {
                    // 땅 위에 뜬 기체(Pilot 2 고도 아이콘): 아래 땅 선, 위에 옆에서 본 기체(몸통 + 양쪽 프로펠러), 사이 점선
                    drawLine(c, Offset(0f, size.height * 0.95f), Offset(size.width, size.height * 0.95f), w)
                    drawRect(c, Offset(size.width * 0.35f, size.height * 0.18f), Size(size.width * 0.3f, size.height * 0.2f))
                    drawLine(c, Offset(size.width * 0.05f, size.height * 0.12f), Offset(size.width * 0.35f, size.height * 0.12f), w)
                    drawLine(c, Offset(size.width * 0.65f, size.height * 0.12f), Offset(size.width * 0.95f, size.height * 0.12f), w)
                    drawLine(c, Offset(size.width / 2, size.height * 0.5f), Offset(size.width / 2, size.height * 0.62f), w)
                    drawLine(c, Offset(size.width / 2, size.height * 0.72f), Offset(size.width / 2, size.height * 0.84f), w)
                }
            }
        }
        Text(text, fontSize = FontSize.Caption, color = c, maxLines = 1)
    }
}
