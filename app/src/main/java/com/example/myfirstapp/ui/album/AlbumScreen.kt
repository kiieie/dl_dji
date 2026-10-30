package com.example.myfirstapp.ui.album

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.R
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.input.fiveD
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.ui.mission.FiveDHints
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.PoleSymbol
import com.example.myfirstapp.ui.PoleSymbolIcon
import com.example.myfirstapp.ui.PoleSymbolRow
import com.example.myfirstapp.ui.poleSymbols
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.numberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Detected = Color(0xFFFF3B30)
private val DetectedText = Color(0xFFFF6B61)

/**
 * 앨범(10.4.3 ⑦, 사진 모아 보기): 왼쪽 미션 목록(최신순, 전주에 붙지 않은 수동 비행 하루치 포함), 오른쪽 4열 썸네일
 * (`대평선 36 · 1` + 설비 기호, 탐지 빨간 점, 수동 표시), 필터 전체/탐지만/수동 촬영, 아래 범례.
 * 미션 사진을 누르면 그 미션의 판독 화면으로, 수동 비행 사진은 크게 본다. 판독은 판독 화면에서만 한다.
 */
@Composable
fun AlbumScreen(dao: ProjectDao, onBack: () -> Unit, onReview: (projectId: Long, missionId: Long) -> Unit) {
    val context = LocalContext.current
    val vm: AlbumViewModel = viewModel(factory = AlbumViewModel.factory(dao, context.getExternalFilesDir(null)))
    val s by vm.state.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<File?>(null) }
    val group = s.group
    // RC 5D(10.4.3): ↕ 미션 고르기, ● 미션은 판독으로·수동 하루치는 첫 사진 크게. 사진을 크게 보는 동안은 받지 않는다(← 뒤로는 MainActivity 몫)
    val rc = (context.applicationContext as InspectionApp).rcButtons
    LaunchedEffect(vm, viewing == null) {
        if (viewing == null) rc.fiveD().collect { r ->
            r.actions.forEach { b ->
                when (b) {
                    RcButton.FIVE_D_UP -> vm.move(-1)
                    RcButton.FIVE_D_DOWN -> vm.move(1)
                    RcButton.FIVE_D_CENTER -> when (val g = vm.state.value.group) {
                        is AlbumGroup.Mission -> onReview(g.m.projectId, g.m.missionId)
                        is AlbumGroup.ManualDay -> viewing = g.files.firstOrNull()
                        null -> Unit
                    }
                    else -> Unit
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(40.dp).padding(start = 4.dp, end = 10.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp).align(Alignment.CenterStart)) {
                Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
            }
            Text("앨범", Modifier.align(Alignment.Center), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AlbumFilter.entries.forEach { f ->
                    val on = s.filter == f
                    Box(
                        Modifier.height(30.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (on) Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer)
                            .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { vm.setFilter(f) }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            f.title, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = when { on -> Color.White; f == AlbumFilter.DETECTED -> DetectedText; else -> TextSoft },
                        )
                    }
                }
            }
        }
        Row(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(216.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().height(30.dp).padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("미션 · 최신순", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("사진", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (!s.loading && s.groups.isEmpty()) Text("사진이 없습니다.", Modifier.padding(12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val list = rememberLazyListState()
                // 5D로 고른 줄이 화면 밖이면 따라간다
                LaunchedEffect(group?.key) {
                    val i = s.groups.indexOf(group)
                    val visible = list.layoutInfo.visibleItemsInfo
                    if (i >= 0 && visible.isNotEmpty() && (i <= visible.first().index || i >= visible.last().index)) list.animateScrollToItem(i)
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().scrollbar(list), state = list,
                    contentPadding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(s.groups, key = { it.key }) { g -> GroupRow(g, g.key == group?.key) { vm.select(g) } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FiveDHints(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp), listOf("5D ↕" to "고르기", "5D ●" to "열기", "5D ←" to "뒤로"))
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                val count = when (group) { is AlbumGroup.ManualDay -> group.files.size; else -> s.shown.size }
                Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (group) {
                            is AlbumGroup.Mission -> "${group.m.projectName} #${group.m.missionId} · ${count}장"
                            is AlbumGroup.ManualDay -> "수동 비행 ${dayLabel(group.day)} · 전주에 붙지 않은 사진 ${count}장"
                            null -> ""
                        },
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val grid = rememberLazyGridState()
                LazyVerticalGrid(
                    GridCells.Fixed(4), Modifier.weight(1f).fillMaxWidth(), state = grid,
                    contentPadding = PaddingValues(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (group) {
                        is AlbumGroup.Mission -> items(s.shown, key = { it.capture.id }) { p ->
                            val label = p.capture.viewpoint?.let { "${it + 1}" } ?: "M"
                            Thumb(
                                p.capture.filePath, "${p.displayName} · $label", poleSymbols(p.voltage, p.equipment),
                                detected = p.alertCount > 0, manual = p.capture.source == CaptureSource.MANUAL.name,
                            ) { onReview(group.m.projectId, group.m.missionId) }
                        }
                        is AlbumGroup.ManualDay -> if (s.filter != AlbumFilter.DETECTED) items(group.files, key = { it.path }) { f ->
                            Thumb(f.path, SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(f.lastModified())), emptyList(), detected = false, manual = true) { viewing = f }
                        }
                        null -> Unit
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Legend()
            }
        }
    }

    viewing?.let { f ->
        FullscreenAlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(f.name, fontSize = 13.sp) },
            text = { Photo(f.path, Modifier.fillMaxWidth().height(280.dp)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewing = null }) { Text("닫기") } },
        )
    }
}

private fun dayLabel(day: String) = if (day.length == 8) "${day.substring(4, 6)}-${day.substring(6)}" else day

/** 왼쪽 목록 한 줄 36dp. */
@Composable
private fun GroupRow(g: AlbumGroup, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) Accent else Color.Transparent))
        Text(
            when (g) { is AlbumGroup.Mission -> "#${g.m.missionId} ${g.m.projectName}"; is AlbumGroup.ManualDay -> "수동 비행 · ${dayLabel(g.day)}" },
            Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Color.White else TextSoft,
        )
        Text(
            "${when (g) { is AlbumGroup.Mission -> g.m.photoCount; is AlbumGroup.ManualDay -> g.files.size }}",
            Modifier.padding(horizontal = 8.dp), style = numberStyle(13.sp, FontWeight.Medium), color = if (selected) TextSoft else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 썸네일 칸(84dp 높이): 작게 디코딩한 사진, 왼쪽 위 탐지 점, 오른쪽 위 "수동", 아래 이름과 기호. */
@Composable
private fun Thumb(path: String?, label: String, symbols: List<PoleSymbol>, detected: Boolean, manual: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF333333)).clickable(onClick = onClick)) {
        Photo(path, Modifier.fillMaxSize(), sample = 16)
        if (detected) Box(Modifier.padding(4.dp).size(8.dp).clip(CircleShape).background(Detected).border(1.5.dp, Color.Black, CircleShape))
        if (manual) Text(
            "수동", Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
            fontSize = 10.sp, color = TextSoft,
        )
        Text(
            label, Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
            style = numberStyle(11.sp, FontWeight.Medium), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (symbols.isNotEmpty()) PoleSymbolRow(
            symbols, 10.dp, Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 2.dp),
        )
    }
}

/** 사진 파일(작게 [sample]분의 1로 디코딩). 없으면 빈 칸. */
@Composable
private fun Photo(path: String?, modifier: Modifier, sample: Int = 4) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { path?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap() }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier, contentScale = ContentScale.Crop) }
}

/** 아래 범례 26dp(10.4.3): 탐지 점과 설비 기호. */
@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Detected))
            Text("탐지", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PoleSymbol.entries.forEach { sym ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PoleSymbolIcon(sym, 11.dp)
                Text(sym.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
