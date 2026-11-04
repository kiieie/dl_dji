package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.ShotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyModelTest {
    private val overhead = EnergyModel(EnergyParams(), FlightParams(shotMode = ShotMode.OVERHEAD))
    private val diagonal = EnergyModel(EnergyParams(), FlightParams(shotMode = ShotMode.DIAGONAL4))

    @Test
    fun `복귀 필요량은 수평 이동에 RTH 고도까지 오르기와 내려앉기를 더한다`() {
        // 이동 50m → RTH 60m, 사선 촬영 27m: (60 − 27 + 60)m / 3m/s를 호버로
        val vertical = (60.0 - 27.0 + 60.0) / 3.0 * (100.0 / (31 * 60))
        assertEquals(diagonal.move(500.0) + vertical, diagonal.returnPct(500.0), 1e-9)
    }

    @Test
    fun `사선은 전주 둘레만큼 더 써서 같은 배터리로 못 가는 경우가 있다`() {
        // 다음 전주 400m, 거기서 복귀 400m. 두 방식 필요량 사이의 배터리
        fun need(e: EnergyModel) = e.move(400.0 + e.aroundPoleM) + e.perVisit + e.returnPct(400.0) + e.flight.batteryReservePct
        val pct = (need(overhead) + need(diagonal)) / 2
        assertTrue(overhead.canProceed(pct, 400.0, 400.0))
        assertFalse(diagonal.canProceed(pct, 400.0, 400.0))
    }

    @Test
    fun `설치기기 전주는 설비 조준 촬영만큼 더 쓴다`() {
        val bank = com.example.myfirstapp.core.model.Pole("P", com.example.myfirstapp.core.model.GeoPoint(36.0, 127.0), equipment = "개폐기")
        assertEquals(4, diagonal.equipmentShots(listOf(bank)))
        assertEquals(0, overhead.equipmentShots(listOf(bank)))
        assertTrue(diagonal.sortieCost(1000.0, 1, 0, 4) > diagonal.sortieCost(1000.0, 1))
    }

    @Test
    fun `2026-11-03 현장 미션 두 번을 실측보다 조금 많게(0~15%) 추정한다`() {
        // 실제로 난 거리(미션 + 복귀)와 사진 수로 셈(tools/energy_fit.py, docs/evidence/2026-11-03-field/energy.md)
        val m19 = EnergyModel(EnergyParams(), FlightParams(speedMps = 5.0, transitAltitudeM = 50.0, diagonalAltitudeM = 27.0, shotMode = ShotMode.DIAGONAL4))
        val m20 = EnergyModel(EnergyParams(), FlightParams(speedMps = 8.0, transitAltitudeM = 30.0, diagonalAltitudeM = 30.0, shotMode = ShotMode.DIAGONAL4))
        listOf(m19.sortieCost(452.0, 4, longJumps = 1) to 13.0, m20.sortieCost(1394.0, 15, extraShots = 4) to 29.0).forEach { (est, used) ->
            assertTrue("예상 $est%, 실측 $used%", est in used..used * 1.15)
        }
    }

    @Test
    fun `순서가 정해진 전주의 소티 계획은 이륙 지점 왕복과 전주 사이 거리`() {
        val home = com.example.myfirstapp.core.model.GeoPoint(35.0, 127.0)
        val proj = LocalProjection(home)
        val poles = listOf(Enu(0.0, 300.0), Enu(400.0, 300.0)).mapIndexed { i, e -> com.example.myfirstapp.core.model.Pole("P$i", proj.toGeo(e)) }
        val plan = overhead.sortiePlan(home, poles)
        assertEquals(300.0 + 400.0 + 500.0, plan.estDistanceM, 0.5)
        assertEquals(overhead.sortieDurationSec(plan.estDistanceM, 2, overhead.longJumps(listOf(400.0))), plan.estDurationSec, 1e-6)
    }
}
