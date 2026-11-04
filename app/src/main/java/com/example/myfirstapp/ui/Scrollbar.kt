package com.example.myfirstapp.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 목록 오른쪽 4dp 스크롤바(10.4.2: 항상 보임). 줄 높이가 같다고 보고 보이는 줄 수로 막대 길이를 잡는다.
 * 목록이 한 화면에 다 들어가면 막대가 홈 전체를 채운다.
 */
fun Modifier.scrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val w = 4.dp.toPx()
    val inset = 4.dp.toPx()
    val track = size.height - 2 * inset
    val r = CornerRadius(w / 2)
    val x = size.width - inset - w
    drawRoundRect(Track, Offset(x, inset), Size(w, track), r)
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return@drawWithContent
    val first = visible.first()
    // 첫 줄이 가려진 비율까지 더해 부드럽게 움직이게 한다
    val start = first.index + (-first.offset).toFloat().coerceAtLeast(0f) / first.size.coerceAtLeast(1)
    val shown = visible.size.toFloat().coerceAtMost(total.toFloat())
    val len = (track * shown / total).coerceAtLeast(16.dp.toPx())
    val top = inset + ((track - len) * (start / (total - shown).coerceAtLeast(1f))).coerceIn(0f, track - len)
    drawRoundRect(Thumb, Offset(x, top), Size(w, len), r)
}

/** 격자 목록용(라이브러리 카드 2열, 10.5.8): 보이는 칸 수 비율로 막대 길이를 잡는다. */
fun Modifier.scrollbar(state: androidx.compose.foundation.lazy.grid.LazyGridState): Modifier = drawWithContent {
    drawContent()
    val w = 4.dp.toPx()
    val inset = 4.dp.toPx()
    val track = size.height - 2 * inset
    val r = CornerRadius(w / 2)
    val x = size.width - inset - w
    drawRoundRect(Track, Offset(x, inset), Size(w, track), r)
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return@drawWithContent
    val start = visible.first().index.toFloat()
    val shown = visible.size.toFloat().coerceAtMost(total.toFloat())
    val len = (track * shown / total).coerceAtLeast(16.dp.toPx())
    val top = inset + ((track - len) * (start / (total - shown).coerceAtLeast(1f))).coerceIn(0f, track - len)
    drawRoundRect(Thumb, Offset(x, top), Size(w, len), r)
}

private val Track = Color(0xFF262626)
private val Thumb = Color(0xFF6E6E6E)
