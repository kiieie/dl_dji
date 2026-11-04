package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FlightTrackTest {
    private val home = GeoPoint(36.47, 127.28)
    private fun at(e: Double) = LocalProjection(home).toGeo(Enu(e, 0.0))

    @Test
    fun `가까운 점·위치 없음은 더하지 않음`() {
        val t = listOf(at(0.0))
        assertSame(t, FlightTrack.append(t, at(1.0), minStepM = 2.0, maxPoints = 10))
        assertSame(t, FlightTrack.append(t, null, minStepM = 2.0, maxPoints = 10))
        assertEquals(2, FlightTrack.append(t, at(3.0), minStepM = 2.0, maxPoints = 10).size)
    }

    @Test
    fun `상한을 넘으면 오래된 점부터 버림`() {
        val t = (0 until 5).fold(emptyList<GeoPoint>()) { acc, i -> FlightTrack.append(acc, at(i * 10.0), minStepM = 2.0, maxPoints = 3) }
        assertEquals(listOf(at(20.0), at(30.0), at(40.0)), t)
    }
}
