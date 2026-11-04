package com.example.myfirstapp.ui.flight

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.core.detect.sampleSize
import com.example.myfirstapp.detect.LastPhoto
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.DetectedBox
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FacilityBox
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.TextSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 미니맵 위 "마지막 사진"(9.6 실시간 1단계, 10.5.2 추가 항목): 가장 최근 사진에 설비 상자(회색)·검출 상자(빨강), 탐지한 가운데 영역(흐린 테두리),
 * 아래에 클래스별 수("LP 2 · SUS 4"). 썸네일이 작아 이름은 누르면 여는 큰 보기에서 보인다.
 */
@Composable
internal fun LastPhotoThumb(p: LastPhoto, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.padding(start = 8.dp).width(Dimens.LastPhoto.width).clip(RoundedCornerShape(4.dp)).background(CardBg)
            .clickable(onClickLabel = "마지막 사진 크게 보기") { open = true },
    ) {
        PhotoWithBoxes(p, longSidePx = 320, labels = false, Modifier.size(Dimens.LastPhoto))
        Text(
            p.summary, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = FontSize.Caption, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    if (open) FullscreenAlertDialog(
        onDismissRequest = { open = false },
        title = { Text("마지막 사진 · ${p.summary}", fontSize = FontSize.Label) },
        text = {
            Column {
                PhotoWithBoxes(p, longSidePx = 1280, labels = true, Modifier.fillMaxWidth().height(280.dp))
                if (p.crop < 1f) Text("흰 테두리 안만 비행 중에 탐지했습니다. 전체 사진은 착륙 뒤 정밀 탐지합니다.", fontSize = FontSize.Caption, color = TextSoft)
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("닫기") } },
    )
}

/** 사진(비율을 지켜 가운데, 긴 변이 [longSidePx] 이상 남게 줄여 읽음)과 상자. [labels]면 상자 위에 화면 이름(model.json `displayNames`)을 쓴다. */
@Composable
private fun PhotoWithBoxes(p: LastPhoto, longSidePx: Int, labels: Boolean, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, p.path, longSidePx) {
        value = withContext(Dispatchers.IO) {
            p.path?.let { path ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
                val sample = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), 1f, longSidePx)
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }?.asImageBitmap()
        }
    }
    val measurer = rememberTextMeasurer()
    Box(modifier.background(Color.Black)) {
        bitmap?.let { Image(it, contentDescription = "마지막 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        Canvas(Modifier.fillMaxSize()) {
            val b = bitmap ?: return@Canvas
            val k = minOf(size.width / b.width, size.height / b.height)
            val w = b.width * k
            val h = b.height * k
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            if (p.crop < 1f) {
                val o = (1 - p.crop) / 2
                drawRect(Color.White.copy(alpha = 0.35f), Offset(left + o * w, top + o * h), Size(p.crop * w, p.crop * h), style = Stroke(1.dp.toPx()))
            }
            p.detections.forEach { d ->
                val c = if (d.alert) DetectedBox else FacilityBox
                val at = Offset(left + d.x * w, top + d.y * h)
                drawRect(c, at, Size(d.w * w, d.h * h), style = Stroke((if (labels) 2 else 1).dp.toPx()))
                if (labels) {
                    val text = measurer.measure(p.names[d.label] ?: d.label, TextStyle(color = Color.Black, fontSize = FontSize.Caption, fontWeight = FontWeight.Bold))
                    val ty = (at.y - text.size.height).coerceAtLeast(top)
                    drawRect(c, Offset(at.x, ty), Size(text.size.width.toFloat(), text.size.height.toFloat()))
                    drawText(text, topLeft = Offset(at.x, ty))
                }
            }
        }
    }
}
