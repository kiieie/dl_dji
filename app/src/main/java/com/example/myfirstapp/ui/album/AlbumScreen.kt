package com.example.myfirstapp.ui.album

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import com.example.myfirstapp.core.model.storageUsedLabel
import com.example.myfirstapp.core.executor.sdFreeLabel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.input.fiveD
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.AlbumPhoto
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.AppBadge
import com.example.myfirstapp.ui.PoleSymbol
import com.example.myfirstapp.ui.PoleSymbolIcon
import com.example.myfirstapp.ui.PoleSymbolRow
import com.example.myfirstapp.ui.mission.FiveDHints
import com.example.myfirstapp.ui.poleSymbols
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.DetectedText
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FacilityBox
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.SurfaceContainerHighest
import com.example.myfirstapp.ui.theme.numberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Detected = Color(0xFFFF3B30)

/**
 * 앨범(10.4.3 ⑦, 사진 모아 보기). 위 [선로별 | 미션별] 보기(마지막 것을 기억), 필터 전체/탐지만/수동 촬영, 아래 범례.
 * 선로별(기본, Finder식): 왼쪽 나무 선로명 → 전주(선로번호 순) → 촬영 날짜, 맨 아래 "기타"(전주에 붙지 않은 수동 사진, 날짜별).
 * 미션별: 왼쪽 미션 목록(최신순, 수동 비행 하루치 포함). 오른쪽 4열 썸네일은 선로번호를 크게(없으면 전산화번호), 방향·설비·망원 표시와 설비 탐지 수.
 * 미션 사진을 누르면 그 미션의 판독 화면으로, 수동 비행 사진은 뷰어([PhotoViewer])로 크게 본다. 판독은 판독 화면에서만 한다.
 * 위 왼쪽 출처 탭 [기체 앨범 | 다운로드 완료](10.4.9): 기체 앨범은 기체 SD 사진([SdAlbumPane]), 다운로드 완료는 위 앨범.
 */
@Composable
fun AlbumScreen(dao: ProjectDao, onBack: () -> Unit, onReview: (projectId: Long, missionId: Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: AlbumViewModel = viewModel(factory = AlbumViewModel.factory(dao, context.getExternalFilesDir(null), app.settings))
    val s by vm.state.collectAsStateWithLifecycle()
    val sdVm: SdAlbumViewModel = viewModel(factory = SdAlbumViewModel.factory(context.getExternalFilesDir(null)))
    val sd by sdVm.state.collectAsStateWithLifecycle()
    // Pilot 2 [기체 앨범 | 다운로드 완료](10.4.9). 처음은 다운로드 완료(우리 앨범)
    var aircraftTab by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sd.savedCount) { if (sd.savedCount > 0) vm.reload() }
    val group = s.group
    // RC 5D(10.4.3): ↕ 줄 고르기, ● 미션은 판독으로·수동 하루치는 첫 사진 크게, 나무는 펼치기·접기(날짜 칸은 열기).
    // 사진을 크게 보는 동안·기체 앨범 탭에서는 받지 않는다(← 뒤로는 MainActivity 몫)
    val listening = s.viewer == null && !aircraftTab
    LaunchedEffect(vm, listening) {
        if (listening) app.rcButtons.fiveD().collect { r ->
            r.actions.forEach { b ->
                when (b) {
                    RcButton.FIVE_D_UP -> vm.move(-1)
                    RcButton.FIVE_D_DOWN -> vm.move(1)
                    RcButton.FIVE_D_CENTER -> {
                        val st = vm.state.value
                        val n = st.node
                        if (st.byLine) when {
                            n == null -> Unit
                            n.children.isNotEmpty() -> vm.tapNode(n.key)
                            n.photos.isNotEmpty() -> n.photos.first().let { onReview(it.projectId, it.missionId) }
                            else -> n.files.firstOrNull()?.let(vm::open)
                        } else when (val g = st.group) {
                            is AlbumGroup.Mission -> onReview(g.m.projectId, g.m.missionId)
                            is AlbumGroup.ManualDay -> g.files.firstOrNull()?.let(vm::open)
                            null -> Unit
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(Dimens.HeaderList).padding(start = 4.dp, end = 10.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSmall)) {
                    Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
                }
                AppBadge()
                // Pilot 2 앨범 위 탭(기체 폴더는 두지 않음: SD 폴더 = 같은 사진, 10.4.9)
                Box(Modifier.width(12.dp))
                SourceTab("기체 앨범", aircraftTab) { aircraftTab = true; sdVm.refresh() }
                SourceTab("다운로드 완료", !aircraftTab) { aircraftTab = false }
            }
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (aircraftTab) {
                    // 기체 SD 저장소(Pilot 2 "M3E 사용됨 26.6GB / 238.0GB")와 남은 사진
                    val st = sd.storage
                    Text(
                        listOfNotNull(storageUsedLabel(st.totalMb, st.freeMb), st.photosLeft?.let { "남은 사진 ${it}장" }).joinToString(" · ").ifEmpty { "SD --" },
                        Modifier.padding(end = 8.dp), style = numberStyle(FontSize.Caption), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Chip("신형-구형", sd.newestFirst) { sdVm.setNewestFirst(true) }
                    Chip("구형-신형", !sd.newestFirst) { sdVm.setNewestFirst(false) }
                    Chip("새로 읽기", false) { sdVm.refresh() }
                } else {
                    Chip("선로별", s.byLine) { vm.setByLine(true) }
                    Chip("미션별", !s.byLine) { vm.setByLine(false) }
                    Box(Modifier.width(12.dp))
                    AlbumFilter.entries.forEach { f ->
                        Chip(f.title, s.filter == f, if (f == AlbumFilter.DETECTED) DetectedText else null) { vm.setFilter(f) }
                    }
                }
            }
        }
        if (aircraftTab) SdAlbumPane(sd, sdVm, Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
        else Row(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(Dimens.AlbumTree).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().height(Dimens.HeaderSection).padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.byLine) "선로 · 전주 · 날짜" else "미션 · 최신순", Modifier.weight(1f), fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("사진", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (!s.loading && s.groups.isEmpty() && s.tree.isEmpty()) {
                    Text("사진이 없습니다.", Modifier.padding(12.dp), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val list = rememberLazyListState()
                val rows = if (s.byLine) s.rows else emptyList()
                val selectedIndex = if (s.byLine) rows.indexOfFirst { it.key == s.node?.key } else s.groups.indexOf(group)
                // 5D로 고른 줄이 화면 밖이면 따라간다
                LaunchedEffect(selectedIndex, s.byLine) {
                    val visible = list.layoutInfo.visibleItemsInfo
                    if (selectedIndex >= 0 && visible.isNotEmpty() && (selectedIndex <= visible.first().index || selectedIndex >= visible.last().index)) {
                        list.animateScrollToItem(selectedIndex)
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().scrollbar(list), state = list,
                    contentPadding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (s.byLine) items(rows, key = { it.key }) { n -> TreeRow(n, n.key == s.node?.key, n.key in s.expanded) { vm.tapNode(n.key) } }
                    else items(s.groups, key = { it.key }) { g -> GroupRow(g, g.key == group?.key) { vm.select(g) } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FiveDHints(
                    Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp),
                    listOf("5D ↕" to "고르기", "5D ●" to if (s.byLine) "펼치기" else "열기", "5D ←" to "뒤로"),
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().height(Dimens.HeaderSection).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 설비(9.6)는 표시만 하는 탐지 수. 빨간 점(탐지)은 검출 대상만
                    fun facility(ps: List<AlbumPhoto>) = ps.sumOf { it.facilityCount }.let { if (it > 0) " · 설비 $it" else "" }
                    Text(
                        if (s.byLine) {
                            if (s.path.isEmpty()) "" else s.path.joinToString(" › ") { it.title } + " · ${s.nodePhotos.size + s.nodeFiles.size}장" + facility(s.nodePhotos)
                        } else when (group) {
                            is AlbumGroup.Mission -> "${group.m.projectName} #${group.m.missionId} · ${s.shown.size}장" + facility(s.shown)
                            is AlbumGroup.ManualDay -> "수동 비행 ${dayLabel(group.day)} · 전주에 붙지 않은 사진 ${group.files.size}장"
                            null -> ""
                        },
                        fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val grid = rememberLazyGridState()
                LazyVerticalGrid(
                    GridCells.Fixed(4), Modifier.weight(1f).fillMaxWidth().scrollbar(grid), state = grid,
                    contentPadding = PaddingValues(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (s.byLine) {
                        items(s.nodePhotos, key = { it.capture.id }) { p -> PhotoThumb(p, showLine = false) { onReview(p.projectId, p.missionId) } }
                        items(s.nodeFiles, key = { it.path }) { f -> FileThumb(f) { vm.open(f) } }
                    } else when (group) {
                        is AlbumGroup.Mission -> items(s.shown, key = { it.capture.id }) { p -> PhotoThumb(p, showLine = true) { onReview(group.m.projectId, group.m.missionId) } }
                        is AlbumGroup.ManualDay -> if (s.filter != AlbumFilter.DETECTED) items(group.files, key = { it.path }) { f -> FileThumb(f) { vm.open(f) } }
                        null -> Unit
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Legend(s.freeBytes)
            }
        }
    }

    // 뷰어(10.4.9): 파일 사진은 앱 사본만 지운다. 미션 사진은 판독 화면에서 본다(판독 기록이라 앨범에서 지우지 않음)
    s.viewer?.let { v ->
        PhotoViewer(
            v.file.path, viewerTime(v.takenAtMs), v.rows, lensTitle(lensOfAppFile(v.file.name)) + " · 단일 촬영",
            load = { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(v.file.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } },
            onBack = vm::closeViewer, deleteText = "조종기에 받은 이 사진(${v.file.name})을 지웁니다. 기체 SD의 원본은 그대로 남습니다.", onDelete = vm::deleteViewing,
        )
    }
    sd.viewing?.let { p ->
        val have = p.name in sd.downloaded
        PhotoViewer(
            p.name, viewerTime(p.takenAtMs),
            listOf(
                "렌즈" to lensTitle(p.lens), "파일" to p.name, "크기" to "%.1f MB".format(Locale.ROOT, p.sizeBytes / 1_048_576.0),
                "앱에 받음" to if (have) "예(다운로드 완료 › 기타)" else "아니오", "위치·전주" to "받은 뒤 다운로드 완료에서(EXIF)",
            ),
            lensTitle(p.lens) + " · 단일 촬영", load = { sdVm.preview(p) }, onBack = sdVm::close,
            deleteText = "기체 SD 카드에서 이 사진(${p.name})을 지웁니다. 되돌릴 수 없습니다.", onDelete = { sdVm.delete(p) },
            onDownload = if (have || p.name in sd.busy) null else ({ sdVm.download(listOf(p)) }),
        )
    }
    sd.notice?.let { n ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                n, Modifier.padding(bottom = 48.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = FontSize.Body, color = Color.White,
            )
        }
    }
}

/** 위 출처 탭(Pilot 2 [기체 앨범 | 다운로드 완료]): 고른 탭은 굵은 글자와 파랑 밑줄. */
@Composable
private fun SourceTab(title: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.height(Dimens.HeaderList).clickable(onClick = onClick).padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title, fontSize = FontSize.Label, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.padding(top = 3.dp).width(28.dp).height(2.dp).background(if (on) Accent else Color.Transparent))
    }
}

private fun dayLabel(day: String) = if (day.length == 8) "${day.substring(4, 6)}-${day.substring(6)}" else day

/** 머리글 칩 30dp(보기 전환·필터): 고른 것은 파랑 테두리와 굵은 글자. [offColor]는 고르지 않았을 때 글자색(탐지만은 빨강). */
@Composable
private fun Chip(title: String, on: Boolean, offColor: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.height(Dimens.ButtonChip).clip(RoundedCornerShape(4.dp))
            .background(if (on) Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title, fontSize = FontSize.Caption, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) MaterialTheme.colorScheme.onSurface else offColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 왼쪽 목록 한 줄 36dp. 고른 줄은 왼쪽 파랑 막대와 진한 바탕. */
@Composable
private fun SelectableRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.RowList).clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) Accent else Color.Transparent))
        content()
    }
}

/** 미션별 보기 한 줄: 미션 번호·프로젝트 또는 수동 비행 하루치, 사진 수. */
@Composable
private fun GroupRow(g: AlbumGroup, selected: Boolean, onClick: () -> Unit) = SelectableRow(selected, onClick) {
    Text(
        when (g) { is AlbumGroup.Mission -> "#${g.m.missionId} ${g.m.projectName}"; is AlbumGroup.ManualDay -> "수동 비행 · ${dayLabel(g.day)}" },
        Modifier.weight(1f).padding(start = 8.dp), fontSize = FontSize.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Count(when (g) { is AlbumGroup.Mission -> g.m.photoCount; is AlbumGroup.ManualDay -> g.files.size })
}

/** 선로별 보기 나무 한 줄: 깊이만큼 들여 쓰고 ▸/▾(아이가 있을 때), 이름(전주는 선로번호를 수치 글꼴로 크게), 사진 수. */
@Composable
private fun TreeRow(n: TreeNode, selected: Boolean, expanded: Boolean, onClick: () -> Unit) = SelectableRow(selected, onClick) {
    Text(
        if (n.children.isEmpty()) "" else if (expanded) "▾" else "▸",
        Modifier.padding(start = 4.dp + 14.dp * n.depth).width(14.dp), fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val pole = n.depth == 1 && n.key.startsWith("L|")
    Text(
        n.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        style = if (pole) numberStyle(FontSize.Number, if (selected) FontWeight.Bold else FontWeight.Medium)
        else TextStyle(fontSize = FontSize.Body, fontWeight = if (selected || n.depth == 0) FontWeight.SemiBold else FontWeight.Normal),
    )
    Count(n.count)
}

@Composable
private fun Count(n: Int) =
    Text("$n", Modifier.padding(horizontal = 8.dp), style = numberStyle(FontSize.Label, FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 미션 사진 썸네일: 선로번호(없으면 전산화번호) 크게, 아래 작게 선로명([showLine]일 때)·방향 번호·설비 조준·망원. */
@Composable
private fun PhotoThumb(p: AlbumPhoto, showLine: Boolean, onClick: () -> Unit) {
    val c = p.capture
    val marks = listOfNotNull(
        p.lineName?.takeIf { showLine && !p.lineNo.isNullOrBlank() }, c.viewpoint?.let { "${it + 1}" },
        "설비".takeIf { c.equipmentShot }, "망원".takeIf { c.lens == Lens.ZOOM.name },
    ).joinToString(" · ")
    Thumb(
        c.filePath, p.lineNo?.takeIf(String::isNotBlank) ?: p.poleId, marks, poleSymbols(p.voltage, p.equipment),
        detected = p.alertCount > 0, manual = c.source == CaptureSource.MANUAL.name, facility = p.facilityCount, onClick = onClick,
    )
}

/** 전주에 붙지 않은 수동 비행 사진 파일: 찍은 시각. */
@Composable
private fun FileThumb(f: File, onClick: () -> Unit) = Thumb(
    f.path, SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(f.lastModified())), "", emptyList(),
    detected = false, manual = true, facility = 0, onClick = onClick,
)

/** 썸네일 칸(84dp 높이): 작게 디코딩한 사진, 왼쪽 위 탐지 점, 오른쪽 위 "수동"·"설비 n", 왼쪽 아래 큰 [title]과 작은 [marks], 오른쪽 아래 기호. */
@Composable
private fun Thumb(
    path: String?, title: String, marks: String, symbols: List<PoleSymbol>, detected: Boolean, manual: Boolean, facility: Int, onClick: () -> Unit,
) {
    val tag = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp)
    Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceContainerHighest).clickable(onClick = onClick)) {
        Photo(path, Modifier.fillMaxSize(), sample = 16)
        if (detected) Box(Modifier.padding(4.dp).size(8.dp).clip(CircleShape).background(Detected).border(1.5.dp, Color.Black, CircleShape))
        Column(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (manual) Text("수동", tag, fontSize = FontSize.Micro, color = Color.White)
            // 설비(9.6): 표시만 하는 탐지 수(사진 위 회색 상자와 같은 색)
            if (facility > 0) Text("설비 $facility", tag, fontSize = FontSize.Micro, color = FacilityBox)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(4.dp).then(tag)) {
            Text(title, style = numberStyle(FontSize.Number), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (marks.isNotEmpty()) Text(marks, fontSize = FontSize.Micro, color = Color.White, maxLines = 1)
        }
        if (symbols.isNotEmpty()) PoleSymbolRow(symbols, 10.dp, Modifier.align(Alignment.BottomEnd).padding(4.dp).then(tag).padding(vertical = 2.dp))
    }
}

/** 사진 파일(작게 [sample]분의 1로 디코딩). 없으면 빈 칸. 홈 앨범 타일 바탕에도 쓴다. */
@Composable
internal fun Photo(path: String?, modifier: Modifier, sample: Int = 4) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { path?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap() }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier, contentScale = ContentScale.Crop) }
}

/** 아래 범례 26dp(10.4.3): 탐지 점과 설비 기호, 오른쪽 끝 조종기 남은 공간([freeBytes], 다운로드 완료 저장소). */
@Composable
private fun Legend(freeBytes: Long?) {
    Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Detected))
            Text("탐지", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PoleSymbol.entries.forEach { sym ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PoleSymbolIcon(sym, 11.dp)
                Text(sym.title, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        freeBytes?.let {
            Box(Modifier.weight(1f))
            Text("조종기 남은 공간 ${sdFreeLabel(it shr 20)}", style = numberStyle(FontSize.Caption), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
