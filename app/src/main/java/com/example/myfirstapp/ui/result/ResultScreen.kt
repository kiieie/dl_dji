package com.example.myfirstapp.ui.result

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import com.example.myfirstapp.ui.FullscreenAlertDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.example.myfirstapp.core.route.LineNumbers
import com.example.myfirstapp.ui.PoleSymbolRow
import com.example.myfirstapp.ui.poleSymbols
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.numberStyle
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.FilterChipDefaults
import com.example.myfirstapp.data.DetectionEntity
import androidx.compose.ui.unit.center
import androidx.compose.ui.geometry.center
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.myfirstapp.ui.theme.DetectedBox
import com.example.myfirstapp.ui.theme.DetectedText
import com.example.myfirstapp.ui.theme.FacilityBox
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.SurfaceContainerHighest

/** 판정 색(10.1 예외): 재촬영(보류)은 노랑. 사진 위 상자는 검출 대상 빨강([DetectedBox]), 설비 회색([FacilityBox], 9.6). */
private val OtherBox = Color(0xFFFFC53D)
private val VerdictNone = Color(0xFF4CD08A)

/** 사진 위 패널 바탕(10.1 DJI 톤): 사진이 비치게 반투명 검정. */
private val PanelBg = Color.Black.copy(alpha = 0.55f)

/** 어두운 바탕 위 빨간 글자(대비용, 10.4 시안 #FF6B61). */

/** 판독 화면(10.4.3 시안 ⑥, F-19 1차 판독). 상태는 [ResultViewModel]이 갖고, 이 화면은 그리기와 입력 전달만 한다. */
@Composable
fun ResultScreen(projectId: Long, missionId: Long, dao: ProjectDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ResultViewModel = viewModel(
        factory = ResultViewModel.factory(
            dao, projectId, missionId, context.getExternalFilesDir(null)!!, (context.applicationContext as InspectionApp).rcButtons,
            (context.applicationContext as InspectionApp).precise, (context.applicationContext as InspectionApp).settings,
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

@Composable
private fun ResultContent(s: ResultUiState, vm: ResultViewModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    var memoOpen by rememberSaveable { mutableStateOf(false) }
    // RC Pro 5D 버튼은 SDK로 ViewModel이 받는다. 여기서는 같은 동작을 방향키로도 받는다(adb 시험, 키보드)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onKeyEvent { e -> e.type == KeyEventType.KeyDown && on5dKey(e.key, vm) }) {
        Column(Modifier.fillMaxSize()) {
            // 머리글 40dp(10.4.2): 뒤로 · 미션 · 가운데 제목 · 필터 · 내보내기
            Box(Modifier.fillMaxWidth().height(Dimens.HeaderList).padding(start = 4.dp, end = 10.dp)) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSmall)) {
                        Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
                    }
                    Text(
                        "미션 #${s.missionId} · 판독 ${s.reviews.size}/${s.statusCounts["완료"] ?: 0}", fontSize = FontSize.Body, color = TextSoft, maxLines = 1,
                    )
                    // 착륙 뒤 정밀 탐지 진행(9.6). 끝나면 사라진다
                    s.precise?.let { Text(it, fontSize = FontSize.Caption, color = Accent, maxLines = 1) }
                }
                Text("판독", Modifier.align(Alignment.Center), fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 설비(9.6): 표시만 하는 탐지 수. "탐지"(검출 대상)에는 세지 않는다
                    if (s.facilityCount > 0) Text("설비 ${s.facilityCount}", Modifier.padding(horizontal = 6.dp), fontSize = FontSize.Caption, color = FacilityBox)
                    ResultFilter.entries.forEach { f -> FilterButton(f, s.filter == f, s.count(f)) { vm.setFilter(f) } }
                    Box(
                        Modifier.size(Dimens.ButtonChip).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable(enabled = !s.loading, onClickLabel = "내보내기", onClick = vm::export),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_export), contentDescription = "내보내기", modifier = Modifier.size(13.dp)) }
                }
            }
            if (s.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else Row(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                PoleList(s, vm, Modifier.width(s.listWidthDp.dp).fillMaxHeight())
                // 목록과 사진 사이 막대(10.4.3): 좌우로 끌어 목록 폭을 바꾼다. 놓으면 설정에 남는다
                val density = LocalDensity.current
                Box(
                    Modifier.width(Dimens.ResultDivider).fillMaxHeight().draggable(
                        rememberDraggableState { px -> vm.dragListWidth(with(density) { px.toDp().value }) }, Orientation.Horizontal,
                        onDragStopped = { vm.saveListWidth() },
                    ),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(3.dp, 32.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline)) }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val row = s.selected
                    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        if (row == null) Text("왼쪽에서 전주를 고르세요.", Modifier.align(Alignment.Center), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else PhotoViewer(row, s.photos, s.photoIndex, vm::showPhoto, s::labelTitle, Modifier.fillMaxSize())
                    }
                    VerdictRow(s, vm, onMemo = { memoOpen = true })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (memoOpen && s.selected != null) MemoDialog(s, vm) { memoOpen = false }
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

/** 머리글 필터 버튼 30dp. 고른 것은 파랑 테두리, 검출은 빨간 글자. */
@Composable
private fun FilterButton(f: ResultFilter, on: Boolean, count: Int, onClick: () -> Unit) {
    Box(
        Modifier.height(Dimens.ButtonChip).clip(RoundedCornerShape(4.dp))
            .background(if (on) Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${if (f == ResultFilter.DETECTED) "탐지" else f.title} $count", fontSize = FontSize.Caption, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = when { on -> Color.White; f == ResultFilter.DETECTED -> DetectedText; else -> TextSoft },
        )
    }
}

/** 판정 색 점: 판독했으면 판정 색, 안 했으면 검출 빨강 또는 회색. */
private fun dotColor(r: ResultRow, review: ReviewVerdict?) = when (review) {
    ReviewVerdict.CONFIRMED -> DetectedBox
    ReviewVerdict.NONE -> VerdictNone
    ReviewVerdict.RETAKE -> OtherBox
    null -> if (r.detectCount > 0) DetectedBox else Color(0xFF6E6E6E)
}

/** 판정 이름(10.4.3 판정 버튼과 같게): 정상·이상·보류. */
private val ReviewVerdict.short get() = when (this) { ReviewVerdict.NONE -> "정상"; ReviewVerdict.CONFIRMED -> "이상"; ReviewVerdict.RETAKE -> "보류" }

/** 전주 목록 열 폭: 상태(3글자), 설비 기호(2개), 검출 수(2자리). 머리글과 줄이 같이 쓴다. */
private val StatusCol = 36.dp
private val SymbolCol = 30.dp
private val DetectCol = 20.dp

/** 전주 목록 머리글과 줄의 오른쪽 세 칸(상태 · 설비 · 검출). */
@Composable
private fun RowScope.Cells(status: @Composable () -> Unit, symbols: @Composable () -> Unit, detect: @Composable () -> Unit) {
    Box(Modifier.width(StatusCol)) { status() }
    Box(Modifier.width(SymbolCol)) { symbols() }
    Box(Modifier.width(DetectCol).padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) { detect() }
}

/**
 * 왼쪽 전주 목록(10.4.3): 머리글 `전주 · 상태 · 설비 · 검출`과 한 줄 40dp. 전주 칸은 위에 작게 판정 점·선로명, 아래 크게 선로번호
 * (없으면 전산화번호), 오른쪽 칸은 상태(판정, 없으면 방문 상태 또는 미판독) · 설비 기호 · 검출 대상 수. 5D ↕로 고른다.
 */
@Composable
private fun PoleList(s: ResultUiState, vm: ResultViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    // 5D로 고른 전주가 화면 밖이면 목록을 그쪽으로 옮긴다
    LaunchedEffect(s.selected?.visitId, s.filter) {
        val i = s.shown.indexOfFirst { it.visitId == s.selected?.visitId }
        if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.animateScrollToItem(i)
    }
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        val head = @Composable { t: String -> Text(t, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        Row(Modifier.fillMaxWidth().height(Dimens.HeaderSection).padding(start = 13.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { head("전주") }
            Cells({ head("상태") }, { head("설비") }, { head("검출") })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().scrollbar(listState), state = listState,
            contentPadding = PaddingValues(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(s.shown, key = { it.visitId }) { r ->
                val selected = r.visitId == s.selected?.visitId
                val review = s.reviews[r.visitId]?.let { ReviewVerdict.valueOf(it.verdict) }
                val tag = when {
                    review != null -> review.short
                    r.status != "DONE" -> ResultUiState.STATUS_TITLE[r.status] ?: r.status
                    else -> "미판독"
                }
                Row(
                    Modifier.fillMaxWidth().height(Dimens.RowTable).clip(RoundedCornerShape(4.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .clickable { vm.select(r.visitId) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) Accent else Color.Transparent))
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor(r, review)))
                            Text(
                                r.lineName.orEmpty(), fontSize = FontSize.Micro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            r.lineNo?.takeIf(String::isNotBlank) ?: r.poleId, style = numberStyle(FontSize.Number, if (selected) FontWeight.Bold else FontWeight.Medium),
                            color = if (selected) Color.White else TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Cells(
                        {
                            Text(
                                tag, fontSize = FontSize.Caption, maxLines = 1,
                                color = if (review == ReviewVerdict.CONFIRMED) DetectedText else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        { PoleSymbolRow(poleSymbols(r.voltage, r.equipment), 10.dp) },
                        { if (r.detectCount > 0) Text("${r.detectCount}", style = numberStyle(FontSize.Label), color = DetectedText) },
                    )
                }
            }
        }
    }
}

/**
 * 사진 칸: 사진(확대·이동·상자 고르기), 왼쪽 위 전주·지점 정보와 렌즈, 오른쪽 위 검출 객체 칩, 아래 44dp 썸네일 줄(지점별 사진).
 */
@Composable
private fun PhotoViewer(r: ResultRow, photos: List<PhotoUi>, index: Int, onPhoto: (Int) -> Unit, title: (String) -> String, modifier: Modifier) {
    val p = photos.getOrNull(index)
    // 고른 검출 객체(상자를 누르거나 칩을 누름). 사진이 바뀌면 지운다
    var selected by remember(p?.capture?.id) { mutableStateOf<Int?>(null) }
    Box(modifier) {
        if (p == null) Text("사진이 없습니다.", Modifier.align(Alignment.Center), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Photo(p, selected, { selected = it }, onTapEmpty = {}, title, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val point = p?.capture?.viewpoint?.let { LineNumbers.SIDE_NAMES.getOrNull(it) }
            Text(
                listOfNotNull(r.displayName, r.poleId, r.lengthM?.let { "${it.toInt()}M" }, point?.let { "$it ${index + 1}/${photos.size}" }).joinToString(" · "),
                Modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = FontSize.Caption, color = Color.White, maxLines = 1,
            )
            p?.let {
                Text(
                    if (it.capture.equipmentShot) "${it.capture.lens} · 설비" else it.capture.lens, Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(3.dp)).border(1.dp, Tertiary, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp),
                    style = numberStyle(FontSize.Caption), color = Tertiary,
                )
            }
            }
            // 검출 객체가 있으면 정보 줄 아래 칩으로 고른다(가로로 밀어 봄). 고르면 그 상자로 확대된다(다시 누르면 전체 보기)
        p?.boxes?.takeIf { it.isNotEmpty() }?.let { boxes ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                boxes.forEachIndexed { i, d ->
                    Text(
                        "${title(d.label)} ${(d.score * 100).toInt()}%",
                        Modifier.clip(RoundedCornerShape(3.dp)).background(if (selected == i) (if (d.alert) DetectedBox else FacilityBox) else Color.Black.copy(alpha = 0.65f))
                            .clickable { selected = if (selected == i) null else i }.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = FontSize.Caption, color = if (selected == i) Color.Black else if (d.alert) DetectedText else Color.White,
                    )
                }
            }
            }
        }
        if (photos.size > 1) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(44.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                photos.forEachIndexed { i, ph ->
                    Thumbnail(ph, i == index, Modifier.size(52.dp, 32.dp)) { onPhoto(i) }
                }
            }
        }
    }
}

/** 썸네일 한 칸: 작게 디코딩(1/16)한 사진, 고른 것은 흰 테두리, 검출은 빨간 점. */
@Composable
internal fun Thumbnail(p: PhotoUi, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, p.capture.filePath) {
        value = withContext(Dispatchers.IO) {
            p.capture.filePath?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 16 }) }?.asImageBitmap()
        }
    }
    Box(modifier.clip(RoundedCornerShape(2.dp)).background(SurfaceContainerHighest).clickable(onClick = onClick)) {
        bitmap?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(2.dp)))
        if (p.boxes.any { it.alert }) Box(Modifier.padding(3.dp).size(6.dp).clip(CircleShape).background(DetectedBox))
    }
}

/**
 * 아래 판정 줄 40dp(10.4.3): 5D 안내, [⋯](대상·메모), [정상] [이상] [보류]. 누르면 그 판정으로 저장하고 다음 전주로 간다.
 * 5D ↔로 고른 판정은 채운 색으로 보인다(● 저장).
 */
@Composable
private fun VerdictRow(s: ResultUiState, vm: ResultViewModel, onMemo: () -> Unit) {
    val row = s.selected
    val saved = row?.let { s.reviews[it.visitId] }?.let { ReviewVerdict.valueOf(it.verdict) }
    Row(Modifier.fillMaxWidth().height(Dimens.RowTable), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            buildAnnotatedString {
                append("1차 판정 · ")
                listOf("5D ↕" to " 전주 ", "5D ↔" to " 판정 ", "●" to " 저장 ", "C1" to " 사진").forEach { (k, v) ->
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(k) }
                    append(v)
                }
            },
            Modifier.weight(1f), fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (saved != null) Text("저장됨 ${saved.short}", fontSize = FontSize.Caption, color = VerdictNone)
        Box(
            Modifier.size(Dimens.TouchMedium).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(enabled = row != null, onClickLabel = "대상·메모", onClick = onMemo),
            contentAlignment = Alignment.Center,
        ) { Text("⋯", fontSize = FontSize.Number, color = TextSoft) }
        listOf(ReviewVerdict.NONE, ReviewVerdict.CONFIRMED, ReviewVerdict.RETAKE).forEach { v ->
            val on = s.draft.verdict == v
            val bg = when {
                !on -> MaterialTheme.colorScheme.surfaceContainer
                v == ReviewVerdict.CONFIRMED -> MaterialTheme.colorScheme.errorContainer
                v == ReviewVerdict.NONE -> MaterialTheme.colorScheme.primary
                else -> OtherBox
            }
            Button(
                onClick = { vm.setVerdict(v); vm.saveReviewAndNext() }, enabled = row != null && row.status == "DONE",
                modifier = Modifier.size(72.dp, Dimens.ButtonStd), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(bg, if (on && v == ReviewVerdict.RETAKE) Color.Black else Color.White),
            ) { Text(v.short, fontSize = FontSize.Body, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
        }
    }
}

/** 대상·메모(F-19): 판정과 함께 저장할 검출 대상 칩과 한 줄 메모. 판정 버튼을 누를 때 같이 저장된다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoDialog(s: ResultUiState, vm: ResultViewModel, onClose: () -> Unit) {
    FullscreenAlertDialog(
        onDismissRequest = onClose,
        title = { Text("${s.selected?.displayName} · 대상·메모") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.tagOptions.forEach { t -> FilterChip(t in s.draft.tags, { vm.toggleTag(t) }, label = { Text(TargetLabels.title(t)) }) }
                }
                OutlinedTextField(s.draft.memo, vm::setMemo, placeholder = { Text("메모") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("판정 버튼을 누르면 함께 저장됩니다.", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

/** 판독용으로 읽기: 원본(20MP)을 그대로 올리면 수십 MB라, 긴 변이 3000px 아래가 되게 2의 배수로 줄인다(8배 확대에 충분). */
private fun decodeForReview(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * 사진과 검출 상자(0~1 비율 좌표, 사진은 비율을 지켜 가운데). 두 손가락으로 확대·이동, 두 번 탭하면 2.5배 ↔ 전체.
 * 상자를 누르면 그 객체를 고르고 그 상자로 확대한다. 고른 상자를 다시 누르면 풀고 전체 보기로. 상자 밖을 한 번 누르면 [onTapEmpty](패널 숨기기).
 */
@Composable
private fun Photo(p: PhotoUi, selected: Int?, onSelect: (Int?) -> Unit, onTapEmpty: () -> Unit, title: (String) -> String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, p.capture.filePath) {
        value = withContext(Dispatchers.IO) { p.capture.filePath?.let(::decodeForReview)?.asImageBitmap() }
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Color.Black, fontWeight = FontWeight.Bold)
    var scale by remember(p.capture.id) { mutableFloatStateOf(1f) }
    var pan by remember(p.capture.id) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(Size.Zero) }
    // 탭 처리는 사진이 바뀔 때만 다시 만드므로 고른 상자·콜백은 최신 값을 읽는다
    val sel by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val tapEmpty by rememberUpdatedState(onTapEmpty)
    /** 사진이 화면에 맞춰 들어간 영역(확대 전 좌표). */
    fun imageRect(): Rect {
        val b = bitmap ?: return Rect(Offset.Zero, viewSize)
        val k = minOf(viewSize.width / b.width, viewSize.height / b.height)
        val w = b.width * k
        val h = b.height * k
        return Rect(Offset((viewSize.width - w) / 2, (viewSize.height - h) / 2), Size(w, h))
    }
    fun boxRect(d: DetectionEntity) = imageRect().let { r -> Rect(Offset(r.left + d.x * r.width, r.top + d.y * r.height), Size(d.w * r.width, d.h * r.height)) }
    // 고른 상자가 화면 절반쯤 차도록 확대해 가운데로
    LaunchedEffect(selected, viewSize, bitmap) {
        val d = selected?.let { p.boxes.getOrNull(it) } ?: return@LaunchedEffect
        val r = boxRect(d)
        if (r.width <= 0f || r.height <= 0f) return@LaunchedEffect
        scale = minOf(viewSize.width * 0.5f / r.width, viewSize.height * 0.5f / r.height).coerceIn(1f, 8f)
        pan = (viewSize.center - r.center) * scale
    }
    Box(
        modifier.clipToBounds()
            .onSizeChanged { viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(p.capture.id) {
                detectTransformGestures { _, dPan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    pan = if (scale == 1f) Offset.Zero else pan + dPan
                }
            }
            .pointerInput(p.capture.id, p.boxes) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        if (scale > 1f) { scale = 1f; pan = Offset.Zero } else { scale = 2.5f; pan = (viewSize.center - at) * 1.5f }
                    },
                    onTap = { at ->
                        // 화면 좌표 → 확대 전 좌표(가운데 기준 확대 + 이동)
                        val c = viewSize.center + (at - viewSize.center - pan) / scale
                        val hit = p.boxes.indices.lastOrNull { boxRect(p.boxes[it]).contains(c) }
                        when (hit) {
                            null -> tapEmpty()
                            sel -> { select(null); scale = 1f; pan = Offset.Zero }
                            else -> select(hit)
                        }
                    },
                )
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) {
            val b = bitmap
            if (b != null) Image(b, contentDescription = "${p.capture.lens} 사진", modifier = Modifier.fillMaxSize())
            else Text("사진 파일 없음", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(Modifier.fillMaxSize()) {
                p.boxes.forEachIndexed { i, d ->
                    val color = if (d.alert) DetectedBox else FacilityBox
                    val r = boxRect(d)
                    // 확대해도 선·글자 굵기는 화면에서 같게
                    val stroke = (if (i == selected) 5.dp else 3.dp).toPx() / scale
                    drawRect(if (selected == null || i == selected) color else color.copy(alpha = 0.45f), r.topLeft, r.size, style = Stroke(stroke))
                    val text = measurer.measure("${title(d.label)} ${(d.score * 100).toInt()}%", labelStyle)
                    val k = 1f / scale
                    val pad = 4.dp.toPx() * k
                    val lw = text.size.width * k + pad * 2
                    val lh = text.size.height * k
                    val lx = r.left.coerceIn(0f, (size.width - lw).coerceAtLeast(0f))
                    val ly = if (r.top - lh >= imageRect().top) r.top - lh else r.top
                    drawRect(color, Offset(lx, ly), Size(lw, lh))
                    withTransform({ translate(lx + pad, ly); scale(k, k, Offset.Zero) }) { drawText(text) }
                }
            }
        }
    }
}
