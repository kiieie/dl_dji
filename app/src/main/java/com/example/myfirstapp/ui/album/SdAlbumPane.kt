package com.example.myfirstapp.ui.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.core.model.SdPhoto
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.SurfaceContainerHighest
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.numberStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기체 앨범 탭 본문(10.4.9, Pilot 2 [기체 앨범]): 날짜 머리(최신 먼저 또는 오래된 먼저) + 6열 썸네일. 썸네일 왼쪽 아래 시각·렌즈,
 * 오른쪽 위 "받음"(앱에 있음) 또는 받는 중 고리. 날짜 머리 [이 날짜 모두 받기]. 누르면 뷰어([SdAlbumViewModel.open]).
 */
@Composable
internal fun SdAlbumPane(s: SdAlbumUiState, vm: SdAlbumViewModel, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        val message = when {
            s.blocked != null -> s.blocked
            s.loading -> "기체 SD 목록을 읽는 중…"
            s.photos.isEmpty() -> "기체 SD 카드에 사진이 없습니다."
            else -> null
        }
        if (message != null) {
            Text(message, Modifier.align(Alignment.Center), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        val grid = rememberLazyGridState()
        LazyVerticalGrid(
            GridCells.Fixed(6), Modifier.fillMaxSize().scrollbar(grid), state = grid,
            contentPadding = PaddingValues(start = 8.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            s.days.forEach { (day, photos) ->
                item(key = "d$day", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${dayTitle(day)} · ${photos.size}장", Modifier.weight(1f), fontSize = FontSize.Caption, fontWeight = FontWeight.SemiBold)
                        val left = photos.count { it.name !in s.downloaded }
                        if (left > 0) TextButton(onClick = { vm.download(photos) }) { Text("이 날짜 모두 받기($left)", fontSize = FontSize.Caption) }
                    }
                }
                items(photos, key = { it.name }) { p -> SdThumb(p, p.name in s.downloaded, p.name in s.busy, vm) { vm.open(p) } }
            }
        }
    }
}

@Composable
private fun SdThumb(p: SdPhoto, downloaded: Boolean, busy: Boolean, vm: SdAlbumViewModel, onClick: () -> Unit) {
    val tag = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp)
    val image by produceState<ImageBitmap?>(null, p.name) { value = vm.thumbnail(p) }
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceContainerHighest).clickable(onClick = onClick)) {
        image?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Column(Modifier.align(Alignment.BottomStart).padding(3.dp).then(tag)) {
            Text(SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(p.takenAtMs)), style = numberStyle(FontSize.Micro), color = Color.White)
            p.lens?.let { Text(lensTitle(it), fontSize = FontSize.Micro, color = Color.White) }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp), color = Color.White, strokeWidth = 2.dp)
            downloaded -> Text("받음", Modifier.align(Alignment.TopEnd).padding(3.dp).then(tag), fontSize = FontSize.Micro, color = Tertiary)
        }
    }
}
