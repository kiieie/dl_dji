package com.example.myfirstapp.ui.result

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.ReviewVerdict
import com.example.myfirstapp.core.model.TargetLabels
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ResultRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 사진 위 데이터 표시 색(10.1 예외): 검출 대상은 빨강, 그 밖의 탐지는 노랑. */
private val DetectedBox = Color(0xFFFF5449)
private val OtherBox = Color(0xFFFFC53D)
private val VerdictNone = Color(0xFF4CD08A)

/** 사진 위 패널 바탕(10.1 DJI 톤): 사진이 비치게 반투명 검정. */
private val PanelBg = Color.Black.copy(alpha = 0.55f)

/** 결과 화면 ⑤와 1차 판독(F-19). 상태는 [ResultViewModel]이 갖고, 이 화면은 그리기와 입력 전달만 한다. */
@Composable
fun ResultScreen(projectId: Long, missionId: Long, dao: ProjectDao, initialFilter: ResultFilter? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ResultViewModel = viewModel(
        factory = ResultViewModel.factory(
            dao, projectId, missionId, context.getExternalFilesDir(null)!!, initialFilter, (context.applicationContext as InspectionApp).rcButtons,
        ) {
            context.contentResolver.openOutputStream(it)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::onSaveTarget)
    LaunchedEffect(vm) {
        vm.events.collect { e -> when (e) { is ResultEvent.PickSaveTarget -> saver.launch(e.fileName) } }
    }
    ResultContent(state, vm, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultContent(s: ResultUiState, vm: ResultViewModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("결과 — 미션 #${s.missionId}")
                        if (!s.loading) {
                            Text(
                                listOf("완료", "건너뜀", "실패", "미점검").joinToString(" · ") { "$it ${s.statusCounts[it] ?: 0}" } +
                                    " · 검출 ${s.count(ResultFilter.DETECTED)} · 판독 ${s.reviews.size}/${s.statusCounts["완료"] ?: 0}" +
                                    " · 모델 ${s.modelVersion ?: "없음"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "나가기") }
                },
                actions = { FilledTonalButton(onClick = vm::export, enabled = !s.loading, modifier = Modifier.padding(end = 16.dp)) { Text("내보내기") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        // RC Pro 5D 버튼은 SDK로 ViewModel이 받는다. 여기서는 같은 동작을 방향키로도 받는다(adb 시험, 키보드). 메모 입력 중에는 글상자가 먼저 받는다.
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        // 사진을 화면 전체에 깔고 목록·판정 패널은 반투명으로 얹는다(10장 ⑤). 사진을 누르면 패널을 숨긴다.
        var panels by rememberSaveable { mutableStateOf(true) }
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color.Black)
                .focusRequester(focus).focusable()
                .onKeyEvent { e -> e.type == KeyEventType.KeyDown && on5dKey(e.key, vm) },
        ) {
            val row = s.selected
            if (row == null) {
                Text("왼쪽에서 전주를 고르세요.", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val saved = s.reviews[row.visitId]?.let { ReviewVerdict.valueOf(it.verdict) }
                PhotoViewer(row, s.photos, s.draft.verdict, saved, onTap = { panels = !panels }, Modifier.fillMaxSize())
            }
            if (panels) {
                PoleList(s, vm, Modifier.align(Alignment.CenterStart).width(220.dp).fillMaxHeight().background(PanelBg).padding(8.dp))
                if (row != null) ReviewPanel(s, vm, Modifier.align(Alignment.CenterEnd).width(270.dp).fillMaxHeight().padding(8.dp))
            }
        }
    }
}

/** 방향키(adb·키보드). 실제 5D 가운데는 BUTTON_THUMBL로도 오지만 SDK와 겹쳐 두 번 저장되므로 받지 않는다. */
private fun on5dKey(key: Key, vm: ResultViewModel): Boolean {
    when (key) {
        Key.DirectionUp -> vm.moveSelection(-1)
        Key.DirectionDown -> vm.moveSelection(1)
        Key.DirectionLeft -> vm.moveVerdict(-1)
        Key.DirectionRight -> vm.moveVerdict(1)
        Key.DirectionCenter -> vm.saveReviewAndNext()
        else -> return false
    }
    return true
}

@Composable
private fun PoleList(s: ResultUiState, vm: ResultViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    // 5D로 고른 전주가 화면 밖이면 목록을 그쪽으로 옮긴다
    LaunchedEffect(s.selected?.visitId, s.filter) {
        val i = s.shown.indexOfFirst { it.visitId == s.selected?.visitId }
        if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.animateScrollToItem(i)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultFilter.entries.forEach { f ->
                FilterChip(s.filter == f, { vm.setFilter(f) }, label = { Text("${f.title} ${s.count(f)}") })
            }
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(s.shown, key = { it.visitId }) { r ->
                val selected = r.visitId == s.selected?.visitId
                val review = s.reviews[r.visitId]?.let { ReviewVerdict.valueOf(it.verdict).title }
                Card(
                    onClick = { vm.select(r.visitId) },
                    colors = CardDefaults.cardColors(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.08f),
                    ),
                ) {
                    ListItem(
                        headlineContent = { Text(r.poleId) },
                        overlineContent = { Text("${r.seq}-${r.order} · ${ResultUiState.STATUS_TITLE[r.status] ?: r.status} · 사진 ${r.photoCount}") },
                        supportingContent = review?.let { { Text("판독: $it") } },
                        trailingContent = if (r.detectCount > 0) {
                            { Text("검출 ${r.detectCount}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge) }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** 사진 한 장을 크게 보여 주고, 아래 가운데에 전주 정보·[광각]/[망원]·탐지 목록을 반투명으로 얹는다. */
@Composable
private fun PhotoViewer(
    r: ResultRow, photos: List<PhotoUi>, verdict: ReviewVerdict?, saved: ReviewVerdict?, onTap: () -> Unit, modifier: Modifier,
) {
    var index by rememberSaveable(r.visitId) { mutableIntStateOf(0) }
    val p = photos.getOrNull(index)
    Box(modifier.clickable(interactionSource = null, indication = null, onClick = onTap)) {
        if (p == null) Text("사진이 없습니다.", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Photo(p, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).padding(8.dp).widthIn(max = 280.dp).background(PanelBg, RoundedCornerShape(8.dp)).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            VerdictBadge(verdict, saved)
            Text(
                listOfNotNull(
                    r.poleId, ResultUiState.STATUS_TITLE[r.status] ?: r.status, r.lineName,
                    r.arrivedAt?.let { "도착 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }, r.skipReason,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEachIndexed { i, ph ->
                    FilterChip(i == index, { index = i }, label = { Text(when (ph.capture.lens) { "ZOOM" -> "망원"; "WIDE" -> "광각"; else -> ph.capture.lens }) })
                }
            }
            p?.boxes?.takeIf { it.isNotEmpty() }?.let { boxes ->
                Text(
                    boxes.sortedByDescending { it.alert }.joinToString(" · ") { "${TargetLabels.title(it.label)} ${(it.score * 100).toInt()}%" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (boxes.any { it.alert }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 판정 배지: 패널을 숨겨도 지금 고른 판정과 저장 여부를 보인다(데이터 표시 색, 10.1 예외). */
@Composable
private fun VerdictBadge(verdict: ReviewVerdict?, saved: ReviewVerdict?) {
    val color = when (verdict) {
        ReviewVerdict.CONFIRMED -> DetectedBox
        ReviewVerdict.NONE -> VerdictNone
        ReviewVerdict.RETAKE -> OtherBox
        null -> Color.Gray
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            verdict?.title ?: "판정 미선택",
            color = Color.Black,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Text(
            when {
                verdict == null -> "5D ←→로 고르기"
                verdict == saved -> "저장됨"
                else -> "저장 전 · 5D 누름"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (verdict != null && verdict == saved) VerdictNone else Color.White,
        )
    }
}

/** 사진 위에 탐지 상자(0~1 비율 좌표)를 그린다. 사진은 비율을 지켜 가운데 그리므로 상자도 그 영역에 맞춘다. */
@Composable
private fun Photo(p: PhotoUi, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, p.capture.filePath) {
        value = withContext(Dispatchers.IO) { p.capture.filePath?.let { BitmapFactory.decodeFile(it) }?.asImageBitmap() }
    }
    Box(modifier) {
        val b = bitmap
        if (b != null) Image(b, contentDescription = "${p.capture.lens} 사진", modifier = Modifier.fillMaxSize())
        else Text("사진 파일 없음", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxSize()) {
            val scale = b?.let { minOf(size.width / it.width, size.height / it.height) }
            val w = if (b != null) b.width * scale!! else size.width
            val h = if (b != null) b.height * scale!! else size.height
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            p.boxes.forEach { d ->
                drawRect(
                    if (d.alert) DetectedBox else OtherBox, Offset(left + d.x * w, top + d.y * h), Size(d.w * w, d.h * h),
                    style = Stroke(3.dp.toPx()),
                )
            }
        }
    }
}

/** 1차 판독 패널(F-19): 판정 3종, 대상 칩, 메모. */
@Composable
private fun ReviewPanel(s: ResultUiState, vm: ResultViewModel, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(PanelBg)) {
        // 저장 버튼은 스크롤 밖 아래에 고정한다(메모 입력 중에도 보이게)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(56.dp)) {
                ReviewVerdict.entries.forEachIndexed { i, v ->
                    SegmentedButton(
                        selected = s.draft.verdict == v,
                        onClick = { vm.setVerdict(v) },
                        shape = SegmentedButtonDefaults.itemShape(i, ReviewVerdict.entries.size),
                        modifier = Modifier.fillMaxHeight(),
                        icon = {},
                        label = { Text(v.title, maxLines = 1, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("대상", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.tagOptions.forEach { t -> FilterChip(t in s.draft.tags, { vm.toggleTag(t) }, label = { Text(TargetLabels.title(t)) }) }
            }
            OutlinedTextField(s.draft.memo, vm::setMemo, placeholder = { Text("메모") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "5D 버튼: ↑↓ 전주 · ←→ 판정 · 누름 저장",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Button(onClick = vm::saveReviewAndNext, modifier = Modifier.padding(12.dp).fillMaxWidth().heightIn(min = 56.dp)) { Text("1차 판독 저장 후 다음") }
    }
}
