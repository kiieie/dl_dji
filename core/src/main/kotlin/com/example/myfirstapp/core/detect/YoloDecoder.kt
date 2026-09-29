package com.example.myfirstapp.core.detect

import com.example.myfirstapp.core.model.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * 원본 이미지를 정사각 입력(한 변 [size])에 레터박스로 넣은 배치(9.3 2단계).
 * 비율을 지켜 [scale]배 줄이고 남는 가장자리를 [padX], [padY]만큼 채운다.
 */
data class Letterbox(val imageW: Int, val imageH: Int, val size: Int) {
    val scale = min(size.toFloat() / imageW, size.toFloat() / imageH)
    val padX = (size - imageW * scale) / 2
    val padY = (size - imageH * scale) / 2
}

/**
 * YOLO(v8/11) 출력 `1×(4+C)×N`을 탐지 목록으로 바꾼다(9.3 4단계).
 * 상자는 `cx, cy, w, h`(입력 대비 0~1, [pixelBoxes]면 입력 픽셀)이고, 결과는 원본 대비 0~1 비율이다.
 */
class YoloDecoder(
    private val labels: List<String>,
    private val alertLabels: Set<String>,
    private val scoreThreshold: Float,
    private val iouThreshold: Float,
    private val pixelBoxes: Boolean = false,
) {
    /** [out]은 채널 우선 배열이다: 채널 k, 후보 i의 값은 `out[k * n + i]`. */
    fun decode(out: FloatArray, n: Int, box: Letterbox): List<Detection> {
        val c = labels.size
        require(out.size == (4 + c) * n) { "출력 크기 ${out.size} ≠ (4+$c)×$n" }
        val unit = if (pixelBoxes) 1f else box.size.toFloat()
        val candidates = (0 until n).mapNotNull { i ->
            var best = 0
            for (k in 1 until c) if (out[(4 + k) * n + i] > out[(4 + best) * n + i]) best = k
            val score = out[(4 + best) * n + i]
            if (score < scoreThreshold) return@mapNotNull null
            val cx = out[i] * unit
            val cy = out[n + i] * unit
            val w = out[2 * n + i] * unit
            val h = out[3 * n + i] * unit
            // 입력 좌표에서 패딩을 빼고 배율로 나눠 원본 비율로 바꾼다
            val x0 = ((cx - w / 2 - box.padX) / box.scale / box.imageW).coerceIn(0f, 1f)
            val y0 = ((cy - h / 2 - box.padY) / box.scale / box.imageH).coerceIn(0f, 1f)
            val x1 = ((cx + w / 2 - box.padX) / box.scale / box.imageW).coerceIn(0f, 1f)
            val y1 = ((cy + h / 2 - box.padY) / box.scale / box.imageH).coerceIn(0f, 1f)
            Detection(labels[best], score, x0, y0, x1 - x0, y1 - y0, labels[best] in alertLabels)
        }
        return nms(candidates, box)
    }

    /** 클래스별 NMS: 점수 순으로 고르고 같은 라벨에서 IoU가 기준을 넘는 상자를 버린다. */
    private fun nms(list: List<Detection>, box: Letterbox): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (d in list.sortedByDescending { it.score }) {
            if (kept.none { it.label == d.label && iou(it, d, box) > iouThreshold }) kept += d
        }
        return kept
    }

    /** 픽셀 기준 IoU(비율 좌표는 가로세로 배율이 달라 원본 크기를 곱한다). */
    private fun iou(a: Detection, b: Detection, box: Letterbox): Float {
        val iw = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x)) * box.imageW
        val ih = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y)) * box.imageH
        val inter = iw * ih
        val union = (a.w * a.h + b.w * b.h) * box.imageW * box.imageH - inter
        return if (union <= 0f) 0f else inter / union
    }
}
