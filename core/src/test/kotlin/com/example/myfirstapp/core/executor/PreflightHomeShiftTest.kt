package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.port.PreflightReport
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.LocalProjection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이륙 지점 이동(6.7, 이어하기): 이륙 기준 고도인데 기체 홈이 계획 홈에서 100m 넘게 멀면 경고(막지는 않음). */
class PreflightHomeShiftTest {
    private val plan = GeoPoint(36.48, 127.33)
    private val proj = LocalProjection(plan)
    private fun report(home: GeoPoint) = PreflightReport(true, 20, 5, true, 90.0, 25.0, 50_000, true, true, 100.0, true, home)
    private fun check(home: GeoPoint, terrain: Boolean) =
        PreflightEvaluator.checklist(report(home), FlightParams().copy(terrainFollow = terrain), 10.0, null, plan).single { it.label == "이륙 지점" }

    @Test
    fun `이륙 기준이면 350m 옮긴 이륙 지점은 경고다`() {
        val c = check(proj.toGeo(Enu(350.0, 0.0)), terrain = false)
        assertFalse(c.ok)
        assertFalse(c.blocking)
        assertTrue(c.problem, "350m" in c.problem)
    }

    @Test
    fun `지형 추적이거나 가까우면 통과다`() {
        assertTrue(check(proj.toGeo(Enu(350.0, 0.0)), terrain = true).ok)
        assertTrue(check(proj.toGeo(Enu(60.0, 0.0)), terrain = false).ok)
    }
}
