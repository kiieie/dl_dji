package com.example.myfirstapp.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightParamsTest {
    @Test
    fun `기본값은 유효하다`() {
        assertTrue(FlightParams().validate().isEmpty())
        assertEquals(60.0, FlightParams().rthAltitudeM, 0.0)
    }

    @Test
    fun `150m 상한과 범위를 검증한다`() {
        assertTrue(FlightParams(transitAltitudeM = 145.0).validate().any { it.contains("이동 고도") })
        assertTrue(FlightParams(transitAltitudeM = 120.0).validate().isEmpty())
        assertTrue(FlightParams(inspectAltitudeM = 60.0).validate().any { it.contains("점검 고도") })
        assertTrue(FlightParams(batteryReservePct = 19.0).validate().any { it.contains("예비율") })
        assertTrue(FlightParams(speedMps = 20.0).validate().any { it.contains("속도") })
    }

    @Test
    fun `사선 고도 하한은 지형 추적이면 24_5m, 아니면 27m`() {
        val d = FlightParams(shotMode = ShotMode.DIAGONAL4, diagonalAltitudeM = 25.0)
        assertTrue(d.validate().isEmpty())
        assertTrue(d.copy(diagonalAltitudeM = 24.0).validate().any { it.contains("사선 고도") })
        assertTrue(d.copy(terrainFollow = false).validate().any { it.contains("사선 고도") })
        assertTrue(d.copy(terrainFollow = false, diagonalAltitudeM = 27.0).validate().isEmpty())
    }
}
