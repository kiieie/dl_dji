package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoutePlannerTest {
    private val home = GeoPoint(37.5, 127.0)
    private val proj = LocalProjection(home)

    private fun pole(id: String, east: Double, north: Double) = Pole(id, proj.toGeo(Enu(east, north)))

    private fun planner(params: FlightParams = FlightParams(), energy: EnergyParams = EnergyParams()) =
        RoutePlanner(EnergyModel(energy, params))

    @Test
    fun `가까운 전주 몇 개는 소티 1개`() {
        val poles = listOf(pole("A", 100.0, 0.0), pole("B", 100.0, 100.0), pole("C", 0.0, 100.0))
        val plan = planner().plan(home, poles)
        assertEquals(1, plan.sorties.size)
        assertEquals(3, plan.sorties[0].poles.size)
        assertTrue(plan.unreachable.isEmpty())
        // 정사각형 둘레 400m
        assertEquals(400.0, plan.sorties[0].estDistanceM, 1.0)
    }

    @Test
    fun `배터리 예산을 넘으면 여러 소티로 나누고 모든 전주를 정확히 한 번 넣는다`() {
        val rnd = Random(3)
        val poles = List(120) { pole("P$it", rnd.nextDouble(-450.0, 450.0), rnd.nextDouble(-200.0, 200.0)) }
        val model = EnergyModel(EnergyParams(), FlightParams())
        val plan = RoutePlanner(model).plan(home, poles)
        assertTrue(plan.sorties.size > 1)
        assertEquals(poles.map { it.poleId }.toSet(), plan.sorties.flatMap { s -> s.poles.map { it.poleId } }.toSet())
        assertEquals(poles.size, plan.sorties.sumOf { it.poles.size })
        plan.sorties.forEach { assertTrue(it.estBatteryPct <= model.budget() + 1e-9) }
    }

    @Test
    fun `가시권 밖과 배터리 초과 전주는 도달 불가로 분류한다`() {
        val params = FlightParams(vlosRadiusM = 500.0)
        val far = pole("FAR", 600.0, 0.0)
        val near = pole("NEAR", 100.0, 0.0)
        val plan = planner(params).plan(home, listOf(far, near))
        assertEquals(listOf("NEAR"), plan.sorties.flatMap { s -> s.poles.map { it.poleId } })
        assertEquals(UnreachablePole.Reason.VLOS_EXCEEDED, plan.unreachable.single().reason)

        // 소모율을 매우 크게 하면 단독 왕복도 예산 초과
        val hungry = planner(params, EnergyParams(cruisePctPerSec = 5.0)).plan(home, listOf(near))
        assertTrue(hungry.sorties.isEmpty())
        assertEquals(UnreachablePole.Reason.BATTERY_EXCEEDED, hungry.unreachable.single().reason)
    }

    @Test
    fun `영역 안 전주만 고른다`() {
        val area = Polygon.rectangle(proj.toGeo(Enu(-50.0, -50.0)), proj.toGeo(Enu(150.0, 150.0)))
        val poles = listOf(pole("IN", 100.0, 100.0), pole("OUT", 200.0, 0.0))
        assertEquals(listOf("IN"), planner().selectInArea(poles, area).map { it.poleId })
    }

    @Test
    fun `실시간 검증 - 계속 진행과 복귀 판단`() {
        val m = EnergyModel(EnergyParams(), FlightParams())
        assertTrue(m.canProceed(80.0, 100.0, 300.0))
        assertFalse(m.canProceed(31.0, 100.0, 300.0))
        assertFalse(m.mustReturn(40.0, 300.0))
        assertTrue(m.mustReturn(20.5, 300.0))
    }
}
