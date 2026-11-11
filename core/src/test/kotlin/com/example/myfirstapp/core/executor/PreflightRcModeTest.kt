package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.port.PreflightReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-11 현장: RC 모드 스위치 T에서 시작 거절, RTH 고도 0m(못 읽음)을 OK로 보이던 것. */
class PreflightRcModeTest {
    private val home = GeoPoint(36.48, 127.33)
    private fun report(mode: Char?, rth: Double = 100.0) = PreflightReport(true, 20, 5, true, 90.0, 25.0, 50_000, true, true, rth, true, home, rcMode = mode)
    private fun item(r: PreflightReport, label: String) = PreflightEvaluator.checklist(r, FlightParams(), 10.0, null).single { it.label == label }

    @Test
    fun `N이 아니면 시작을 막고 스위치를 N으로 옮기라고 한다`() {
        val c = item(report('T'), PreflightEvaluator.FLIGHT_MODE)
        assertFalse(c.ok)
        assertTrue(c.blocking)
        assertTrue(c.problem, "N으로 옮긴" in c.problem && "T 모드" in c.problem)
    }

    @Test
    fun `N이거나 모르면 통과, 수동 비행 점검에는 모드 항목이 없다`() {
        assertTrue(item(report('N'), PreflightEvaluator.FLIGHT_MODE).ok)
        assertTrue(item(report(null), PreflightEvaluator.FLIGHT_MODE).ok)
        assertTrue(PreflightEvaluator.manual(report('T')).none { it.label == PreflightEvaluator.FLIGHT_MODE })
    }

    @Test
    fun `RTH 고도 0m는 못 읽은 것으로 본다`() {
        val c = item(report('N', rth = 0.0), "RTH 고도")
        assertFalse(c.ok)
        assertEquals("읽지 못함", c.value)
    }
}
