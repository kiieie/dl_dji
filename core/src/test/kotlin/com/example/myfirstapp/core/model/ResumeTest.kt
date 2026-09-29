package com.example.myfirstapp.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ResumeTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, seoul).toInstant().toEpochMilli()

    @Test
    fun `같은 날이면 묻지 않고 날짜가 바뀌면 묻는다`() {
        assertFalse(needsResumeConfirm(at(29, 8), at(29, 23), seoul))
        // 자정을 넘기면 몇 시간 차이여도 다른 날이다(현지 날짜 기준)
        assertTrue(needsResumeConfirm(at(28, 23), at(29, 1), seoul))
        assertFalse(needsResumeConfirm(null, at(29, 1), seoul))
    }
}
