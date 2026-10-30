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
        val vertical = (60.0 - 27.0 + 60.0) / 3.0 * (100.0 / (28 * 60))
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
}
