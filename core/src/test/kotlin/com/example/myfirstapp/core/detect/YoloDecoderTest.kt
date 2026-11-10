package com.example.myfirstapp.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloDecoderTest {
    private val labels = listOf("person", "bus")

    /** 후보 목록(cx, cy, w, h, 클래스별 점수)을 채널 우선 배열로 만든다. */
    private fun output(vararg boxes: FloatArray): FloatArray {
        val n = boxes.size
        return FloatArray(boxes[0].size * n) { idx -> boxes[idx % n][idx / n] }
    }

    @Test
    fun `레터박스 좌표를 원본 비율로 되돌린다`() {
        // 1280×960 원본 → 640 입력: 배율 0.5, 위아래 패딩 80
        val box = Letterbox(1280, 960, 640)
        assertEquals(0.5f, box.scale)
        assertEquals(80f, box.padY)
        // 원본 (320,240)-(640,480) 상자는 입력에서 (160,200)-(320,320), 중심 (240,260) 크기 160×120
        val out = output(floatArrayOf(240 / 640f, 260 / 640f, 160 / 640f, 120 / 640f, 0.9f, 0.1f))
        val d = YoloDecoder(labels, setOf("person"), 0.35f, 0.5f).decode(out, 1, box).single()
        assertEquals("person", d.label)
        assertTrue(d.alert)
        assertEquals(0.25f, d.x, 1e-5f)
        assertEquals(0.25f, d.y, 1e-5f)
        assertEquals(0.25f, d.w, 1e-5f)
        assertEquals(0.25f, d.h, 1e-5f)
    }

    @Test
    fun `작게 넣은 레터박스(망원 9_6)도 원본 비율로 되돌린다`() {
        // 4000×3000 원본 → 1280 입력 가운데 긴 변 480: 배율 0.12, 패딩 가로 400 세로 460
        val box = Letterbox(4000, 3000, 1280, fit = 480)
        assertEquals(0.12f, box.scale, 1e-6f)
        assertEquals(400f, box.padX, 1e-3f)
        assertEquals(460f, box.padY, 1e-3f)
        // 원본 (1000,750)-(2000,1500) → 입력 (520,550)-(640,640), 중심 (580,595) 크기 120×90
        val out = output(floatArrayOf(580 / 1280f, 595 / 1280f, 120 / 1280f, 90 / 1280f, 0.9f, 0.1f))
        val d = YoloDecoder(labels, setOf("person"), 0.35f, 0.5f).decode(out, 1, box).single()
        assertEquals(0.25f, d.x, 1e-4f)
        assertEquals(0.25f, d.y, 1e-4f)
        assertEquals(0.25f, d.w, 1e-4f)
        assertEquals(0.25f, d.h, 1e-4f)
        // 여백(왼쪽 위 0~400)에서만 나온 상자는 버린다
        val pad = output(floatArrayOf(100 / 1280f, 100 / 1280f, 80 / 1280f, 80 / 1280f, 0.9f, 0.1f))
        assertTrue(YoloDecoder(labels, setOf("person"), 0.35f, 0.5f).decode(pad, 1, box).isEmpty())
    }

    @Test
    fun `점수 필터와 클래스별 NMS`() {
        val box = Letterbox(640, 640, 640)
        val out = output(
            floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, 0.9f, 0f), // person
            floatArrayOf(0.51f, 0.5f, 0.2f, 0.2f, 0.8f, 0f), // 같은 person과 겹침 → 버림
            floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, 0f, 0.7f), // bus: 라벨이 달라 남김
            floatArrayOf(0.2f, 0.2f, 0.1f, 0.1f, 0.2f, 0.1f), // 점수 미달
        )
        val list = YoloDecoder(labels, setOf("person"), 0.35f, 0.5f).decode(out, 4, box)
        assertEquals(listOf("person" to 0.9f, "bus" to 0.7f), list.map { it.label to it.score })
        assertFalse(list[1].alert)
    }

    @Test
    fun `픽셀 단위 상자와 가장자리 자르기`() {
        val box = Letterbox(640, 640, 640)
        val out = output(floatArrayOf(620f, 320f, 80f, 64f, 0f, 0.6f))
        val d = YoloDecoder(labels, emptySet(), 0.35f, 0.5f, pixelBoxes = true).decode(out, 1, box).single()
        assertEquals(580 / 640f, d.x, 1e-5f)
        assertEquals(1f, d.x + d.w, 1e-5f)
    }

    @Test
    fun `경고 라벨이 비면(설비 모델) 모두 표시만 하고 검출로 세지 않는다`() {
        val out = output(floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, 0.9f, 0f), floatArrayOf(0.2f, 0.2f, 0.1f, 0.1f, 0f, 0.8f))
        val list = YoloDecoder(labels, emptySet(), 0.25f, 0.45f).decode(out, 2, Letterbox(640, 640, 640))
        assertEquals(2, list.size)
        assertEquals(0, list.count { it.alert })
    }
}
