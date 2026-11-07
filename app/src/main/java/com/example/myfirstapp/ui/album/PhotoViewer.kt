package com.example.myfirstapp.ui.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.R
import com.example.myfirstapp.detect.LastPhoto
import com.example.myfirstapp.ui.flight.PhotoWithBoxes
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.numberStyle

/**
 * 앨범 뷰어(10.4.9, Pilot 2 앨범 사진 보기): 검은 전체 화면, 위 ‹ · 촬영 시각 ⓘ(누르면 오른쪽 정보 칸), 아래 왼쪽 🗑(확인 뒤 [onDelete]),
 * 오른쪽 ⬇ 받기([onDownload], 기체 앨범만), 가운데 [chip]. Pilot 2 오른쪽 위 공유는 **두지 않는다**(데이터 주권: 다른 앱·클라우드로 사진이 나감).
 * [load]는 [key]가 바뀔 때마다 그림을 읽는다.
 */
@Composable
internal fun PhotoViewer(
    key: Any, title: String, info: List<Pair<String, String>>, chip: String?, load: suspend () -> ImageBitmap?,
    onBack: () -> Unit, deleteText: String, onDelete: () -> Unit, onDownload: (() -> Unit)? = null, downloadLabel: String = "받기",
    /** [탐지](9.6, 11-07): 앱에 받은 사진만. 결과([detect])는 상자로 겹쳐 보인다. */
    onDetect: (() -> Unit)? = null, detect: LastPhoto? = null, detecting: Boolean = false,
) {
    BackHandler(onBack = onBack)
    var showInfo by remember { mutableStateOf(false) }
    var askDelete by remember(key) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, key) { value = load() }
    // 빈 곳 누름은 아래 앨범으로 넘기지 않는다
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = null, indication = null) {}) {
        image?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White)
        detect?.let { PhotoWithBoxes(it, longSidePx = 1280, labels = true, Modifier.fillMaxSize()) }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = numberStyle(FontSize.Label), color = Color.White)
            Text(
                "ⓘ", Modifier.clickable(onClickLabel = "정보") { showInfo = !showInfo }.padding(horizontal = 8.dp),
                fontSize = FontSize.Title, color = if (showInfo) Accent else Color.White,
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).size(Dimens.TouchMedium)) {
            Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { askDelete = true }, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(Dimens.TouchMedium)) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "삭제", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        onDetect?.let { d ->
            Button(onClick = d, enabled = !detecting, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                Text(if (detecting) "탐지 중…" else detect?.summary ?: "탐지", fontSize = FontSize.Body)
            }
        }
        onDownload?.let { d ->
            IconButton(onClick = d, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(Dimens.TouchMedium)) {
                Icon(painterResource(R.drawable.ic_import), contentDescription = downloadLabel, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        chip?.let {
            Text(
                it, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = FontSize.Caption, color = Color.Black,
            )
        }
        if (showInfo) Column(
            Modifier.align(Alignment.CenterEnd).width(Dimens.CardWidth).fillMaxHeight().padding(top = Dimens.TouchMedium, bottom = Dimens.TouchMedium)
                .background(CardBg, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("정보", fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.White)
            info.forEach { (label, value) ->
                Column {
                    Text(label, fontSize = FontSize.Caption, color = TextSoft)
                    Text(value, fontSize = FontSize.Body, color = Color.White)
                }
            }
        }
    }
    if (askDelete) FullscreenAlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("삭제") },
        text = { Text(deleteText) },
        confirmButton = { TextButton(onClick = { askDelete = false; onDelete() }) { Text("삭제", color = Error) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("취소") } },
    )
}
