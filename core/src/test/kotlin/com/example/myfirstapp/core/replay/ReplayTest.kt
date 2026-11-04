package com.example.myfirstapp.core.replay

import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private fun sample(at: Long, lat: Double, alt: Double, heading: Double, battery: Double, mode: FlightMode = FlightMode.MANUAL) =
        TrackSample(at, Telemetry(GeoPoint(lat, 127.0), alt, alt / 10, battery, mode, 5, true, null, headingDeg = heading, gimbalPitchDeg = -alt))

    private val track = ReplayTrack(
        listOf(
            sample(1_000, 36.0, 10.0, 350.0, 90.0),
            sample(2_000, 36.001, 20.0, 10.0, 89.0, FlightMode.GO_HOME),
            // 2~10초는 기록이 끊김(3초 넘음)
            sample(10_000, 36.002, 40.0, 90.0, 80.0),
        ),
        holdGapMs = 3_000,
    )

    @Test
    fun `사이는 선형 보간, 기수는 짧은 쪽, 배터리·모드·짐벌은 앞 점`() {
        val t = track.stateAt(1_500)!!
        assertEquals(36.0005, t.position!!.lat, 1e-9)
        assertEquals(15.0, t.altitudeM, 1e-9)
        assertEquals(1.5, t.speedMps, 1e-9)
        // 350° → 10°는 0°를 지나 20° 돈다
        assertEquals(0.0, t.headingDeg, 1e-9)
        assertEquals(90.0, t.batteryPct, 0.0)
        assertEquals(FlightMode.MANUAL, t.flightMode)
        assertEquals(-10.0, t.gimbalPitchDeg, 0.0)
        assertEquals(355.0, track.stateAt(1_250)!!.headingDeg, 1e-9)
    }

    @Test
    fun `범위 밖은 끝 점, 끊긴 구간은 앞 점에 머묾`() {
        assertEquals(10.0, track.stateAt(0)!!.altitudeM, 0.0)
        assertEquals(40.0, track.stateAt(99_000)!!.altitudeM, 0.0)
        val hold = track.stateAt(6_000)!!
        assertEquals(20.0, hold.altitudeM, 0.0)
        assertEquals(FlightMode.GO_HOME, hold.flightMode)
        assertNull(ReplayTrack(emptyList(), 3_000).stateAt(0))
    }

    @Test
    fun `재생 위치는 끊긴 구간을 줄여 셈`() {
        // 1초 + 끊긴 8초를 3초로 = 4초
        assertEquals(4_000L, track.durationMs)
        assertEquals(1_500L, track.wallAt(500))
        assertEquals(2_000L, track.wallAt(1_000))
        assertEquals(3_500L, track.wallAt(2_500))
        assertEquals(10_000L, track.wallAt(4_000))
        assertEquals(1_000L, track.wallAt(-1))
        assertEquals(0L, ReplayTrack(emptyList(), 3_000).durationMs)
    }

    @Test
    fun `각 보간은 양쪽 방향 모두 짧은 쪽`() {
        assertEquals(0.0, lerpAngle(10.0, 350.0, 0.5), 1e-9)
        assertEquals(85.0, lerpAngle(0.0, 170.0, 0.5), 1e-9)
        assertEquals(185.0, lerpAngle(-170.0, 180.0, 0.5), 1e-9)
    }

    @Test
    fun `그린 궤적은 t까지와 보간 위치, 지우개 뒤는 그때부터`() {
        assertEquals(listOf(36.0, 36.0005), track.pathBetween(0, 1_500).map { it.lat })
        assertEquals(listOf(36.0, 36.001), track.pathBetween(0, 2_000).map { it.lat })
        assertEquals(listOf(36.001, 36.002), track.pathBetween(1_500, 10_000).map { it.lat })
        assertTrue(track.pathBetween(0, 500).isEmpty())
        assertEquals(222.0, track.distanceM(), 1.0)
    }

    @Test
    fun `재생 시계는 배속만큼 가고 끝에서 멈추며 다시 누르면 처음부터`() {
        var p = Playback(durationMs = 10_000, speeds = listOf(1, 4, 16))
        assertEquals(p, p.tick(1_000))
        p = p.toggle().nextSpeed()
        assertEquals(4, p.speed)
        p = p.tick(1_000)
        assertEquals(4_000L, p.posMs)
        p = p.tick(5_000)
        assertEquals(10_000L, p.posMs)
        assertFalse(p.playing)
        p = p.toggle()
        assertEquals(0L, p.posMs)
        assertTrue(p.playing)
        assertEquals(10_000L, p.seek(20_000).posMs)
        assertEquals(1, p.nextSpeed().nextSpeed().speed)
        assertEquals("01:05", clockText(65_000))
        assertEquals("1:00:01", clockText(3_601_000))
    }
}
