package com.example.myfirstapp.ui.result

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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

/** 결과 화면 ⑤와 1차 판독(F-19). 상태는 [ResultViewModel]이 갖고, 이 화면은 그리기와 입력 전달만 한다. */
@Composable
fun ResultScreen(projectId: Long, missionId: Long, dao: ProjectDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ResultViewModel = viewModel(
        key = "result-$missionId",
        factory = ResultViewModel.factory(dao, projectId, missionId, context.getExternalFilesDir(null)!!) {
            context.contentResolver.openOutputStream(it)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::onSaveTarget)
    LaunchedEffect(vm) {
        vm.events.collect { e -> when (e) { is ResultEvent.PickSaveTarget -> saver.launch(e.fileName) } }
    }
    BackHandler(onBack = onBack)
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
        Row(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PoleList(s, vm, Modifier.weight(0.25f))
            val row = s.selected
            if (row == null) {
                Box(Modifier.weight(0.75f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("왼쪽에서 전주를 고르세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                PoleDetail(row, s.photos, Modifier.weight(0.41f))
                ReviewPanel(s, vm, Modifier.weight(0.34f).padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun PoleList(s: ResultUiState, vm: ResultViewModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultFilter.entries.forEach { f ->
                FilterChip(s.filter == f, { vm.setFilter(f) }, label = { Text("${f.title} ${s.count(f)}") })
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(s.shown, key = { it.visitId }) { r ->
                val selected = r.visitId == s.selected?.visitId
                val review = s.reviews[r.visitId]?.let { ReviewVerdict.valueOf(it.verdict).title }
                Card(
                    onClick = { vm.select(r.visitId) },
                    colors = if (selected) CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
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

@Composable
private fun PoleDetail(r: ResultRow, photos: List<PhotoUi>, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${r.poleId} · ${ResultUiState.STATUS_TITLE[r.status] ?: r.status}", style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(
                r.lineName, "%.6f, %.6f".format(r.lat, r.lon),
                r.arrivedAt?.let { "도착 " + DateFormat.getTimeInstance().format(Date(it)) }, r.skipReason,
            ).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (photos.isEmpty()) Text("사진이 없습니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { p ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Photo(p)
                    Text(
                        "${when (p.capture.lens) { "ZOOM" -> "망원"; "WIDE" -> "광각"; else -> p.capture.lens }} · ${p.capture.source}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    p.boxes.forEach {
                        Text(
                            "${TargetLabels.title(it.label)} ${(it.score * 100).toInt()}%",
                            color = if (it.alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 사진 위에 탐지 상자(0~1 비율 좌표)를 그린다. 사진은 비율을 지켜 가운데 그리므로 상자도 그 영역에 맞춘다. */
@Composable
private fun Photo(p: PhotoUi) {
    val bitmap by produceState<ImageBitmap?>(null, p.capture.filePath) {
        value = withContext(Dispatchers.IO) { p.capture.filePath?.let { BitmapFactory.decodeFile(it) }?.asImageBitmap() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
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
    Card(modifier, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)) {
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
        Button(onClick = vm::saveReviewAndNext, modifier = Modifier.padding(12.dp).fillMaxWidth().heightIn(min = 56.dp)) { Text("1차 판독 저장 후 다음") }
    }
}
