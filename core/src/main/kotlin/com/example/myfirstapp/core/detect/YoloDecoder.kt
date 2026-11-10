package com.example.myfirstapp.core.detect

import com.example.myfirstapp.core.model.Detection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 원본 이미지를 정사각 입력(한 변 [size])에 레터박스로 넣은 배치(9.3 2단계).
 * 비율을 지켜 긴 변이 [fit]이 되게 [scale]배 줄이고 남는 가장자리를 [padX], [padY]만큼 채운다. [fit]이 [size]보다 작으면 입력 가운데에
 * 작게 넣는다(망원 사진, 9.6: 같은 모델에 작은 입력처럼).
 */
data class Letterbox(val imageW: Int, val imageH: Int, val size: Int, val fit: Int = size) {
    val scale = min(fit.toFloat() / imageW, fit.toFloat() / imageH)
    val padX = (size - imageW * scale) / 2
    val padY = (size - imageH * scale) / 2
}

/**
 * JPEG 축소 디코딩 배율(`inSampleSize`, 2의 거듭제곱, 9.3 1단계): 원본 긴 변 [longSide] 중 가운데 [crop] 비율을 쓸 때
 * 그 영역의 긴 변이 모델 입력 [input] 이상으로 남는 가장 큰 배율. 20MP 원본을 다 풀지 않으려고 쓴다.
 */
fun sampleSize(longSide: Int, crop: Float, input: Int): Int {
    var s = 1
    while (longSide * crop / (s * 2) >= input) s *= 2
    return s
}

/** 이미지 안 직사각형(픽셀). */
data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

/** [w]×[h] 이미지 가운데 [fraction] 영역(가로·세로 같은 비율, 9.6 실시간 가운데 자르기). */
fun centerRegion(w: Int, h: Int, fraction: Float): Region {
    val cw = (w * fraction).toInt()
    val ch = (h * fraction).toInt()
    return Region((w - cw) / 2, (h - ch) / 2, cw, ch)
}

/**
 * [r] 영역(원본 [imageW]×[imageH] 픽셀 기준) 안에서 0~1 비율로 찾은 상자를 원본 대비 0~1 비율로 옮긴다.
 * 축소해 푼 전체에서 자른 영역(옛 방식)이든, 원본 좌표로 가운데만 푼 영역(9.7 O1)이든 같은 식이다.
 */
fun Detection.fromRegion(r: Region, imageW: Int, imageH: Int) = copy(
    x = (r.left + x * r.width) / imageW,
    y = (r.top + y * r.height) / imageH,
    w = w * r.width / imageW,
    h = h * r.height / imageH,
)

/** 두 상자의 IoU. 0~1 비율 좌표여도 픽셀 IoU와 같다(가로·세로 배율이 교집합·합집합에 똑같이 곱해진다). */
fun Detection.iou(o: Detection): Float {
    val iw = max(0f, min(x + w, o.x + o.w) - max(x, o.x))
    val ih = max(0f, min(y + h, o.y + o.h) - max(y, o.y))
    val inter = iw * ih
    val union = w * h + o.w * o.h - inter
    return if (union <= 0f) 0f else inter / union
}

/** [a]의 상자 중 [b]에 라벨이 같고 IoU [minIou] 이상, 점수 차 [maxScoreDiff] 이하인 짝이 있는 수(9.7 O1 디코딩 방식 대조). */
fun agreeing(a: List<Detection>, b: List<Detection>, minIou: Float, maxScoreDiff: Float): Int =
    a.count { d -> b.any { it.label == d.label && it.iou(d) >= minIou && abs(it.score - d.score) <= maxScoreDiff } }

/**
 * JPEG의 첫 SOF 마커(0xC0 기준, 0xC2 점진 등). JPEG가 아니거나 SOF 전에 끝나면 null.
 * 점진 JPEG는 영역 디코딩도 결국 전체를 풀어야 해서 옛 방식으로 간다(9.7 O1).
 */
fun jpegSof(b: ByteArray): Int? {
    if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
    var i = 2
    while (i + 3 < b.size) {
        if (b[i] != 0xFF.toByte()) return null
        val m = b[i + 1].toInt() and 0xFF
        if (m == 0xFF) { i++; continue } // 채움 바이트
        if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return m
        if (m == 0xDA || m == 0xD9) return null // SOF 없이 영상 시작·끝
        i += 2 + ((b[i + 2].toInt() and 0xFF) shl 8 or (b[i + 3].toInt() and 0xFF))
    }
    return null
}

/** 점진(progressive) JPEG이면 참(SOF2·6·10·14). */
fun isProgressiveJpeg(b: ByteArray) = jpegSof(b) in setOf(0xC2, 0xC6, 0xCA, 0xCE)

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
            // 회색 여백에서만 나온 상자는 잘리면 넓이가 0이다(망원 480처럼 여백이 넓을 때, 9.6)
            if (x1 <= x0 || y1 <= y0) return@mapNotNull null
            Detection(labels[best], score, x0, y0, x1 - x0, y1 - y0, labels[best] in alertLabels)
        }
        return nms(candidates)
    }

    /** 클래스별 NMS: 점수 순으로 고르고 같은 라벨에서 IoU가 기준을 넘는 상자를 버린다. */
    private fun nms(list: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (d in list.sortedByDescending { it.score }) {
            if (kept.none { it.label == d.label && it.iou(d) > iouThreshold }) kept += d
        }
        return kept
    }
}
