package com.example.myfirstapp.core.port

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomTest {
    @Test
    fun 단계를_오르내리고_끝에서_멈춘다() {
        assertEquals(14.0, nextZoom(7.0, up = true), 0.0)
        assertEquals(4.0, nextZoom(7.0, up = false), 0.0)
        assertEquals(28.0, nextZoom(28.0, up = true), 0.0)
        assertEquals(1.0, nextZoom(1.0, up = false), 0.0)
        // 단계 사이 값(RC 다이얼 등)은 가까운 다음 단계로
        assertEquals(7.0, nextZoom(5.0, up = true), 0.0)
        assertEquals(4.0, nextZoom(5.0, up = false), 0.0)
    }

    @Test
    fun 기체_줌_범위_밖_단계는_범위_끝으로_모은다() {
        val steps = zoomSteps(2.0..14.0)
        assertEquals(listOf(2.0, 4.0, 7.0, 14.0), steps)
        assertEquals(14.0, nextZoom(14.0, up = true, steps), 0.0)
        assertEquals(2.0, nextZoom(4.0, up = false, steps), 0.0)
        assertEquals(ZOOM_STEPS, zoomSteps(null))
    }

    @Test
    fun 기체가_돌려준_배율은_가까운_단계로_맞춰_같은_단계를_되풀이하지_않는다() {
        assertEquals(7.0, snapZoom(6.98), 0.0)
        assertEquals(7.0, snapZoom(7.02), 0.0)
        assertEquals(5.0, snapZoom(5.0), 0.0) // 단계 사이 값은 그대로
        assertEquals(14.0, nextZoom(6.98, up = true), 0.0) // 7을 다시 고르지 않는다
        assertEquals(4.0, nextZoom(7.02, up = false), 0.0)
    }
}
