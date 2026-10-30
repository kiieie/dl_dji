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
}
