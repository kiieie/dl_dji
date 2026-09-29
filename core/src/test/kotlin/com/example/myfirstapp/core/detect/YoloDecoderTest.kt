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
}
