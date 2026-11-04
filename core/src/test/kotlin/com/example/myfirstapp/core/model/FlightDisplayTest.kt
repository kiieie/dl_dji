package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Enu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightDisplayTest {
    private val home = GeoPoint(35.0, 127.0)
    private val ground = Telemetry(home, 0.0, 0.0, 80.0, FlightMode.ON_GROUND, 5, true, home)

    @Test
    fun `상단 바 제목은 Pilot 2 문구와 미션 번호`() {
        assertEquals(FlightTitle("N 모드 - 대기"), flightTitle(ground))
        assertEquals("N 모드 - 수동 비행", flightTitle(ground.copy(flightMode = FlightMode.MANUAL)).text)
        assertEquals("S 모드 - 수동 비행", flightTitle(ground.copy(flightMode = FlightMode.MISSION_PAUSED, rcMode = 'S')).text)
        assertEquals(FlightTitle("N 모드 - 비전 포지셔닝", warn = true), flightTitle(ground.copy(gpsLevel = 0)))
        assertEquals("비행 경로 수행 중 · #20 1/2", flightTitle(ground.copy(flightMode = FlightMode.MISSION), "#20 1/2").text)
        assertEquals("RTH 진행 중", flightTitle(ground.copy(flightMode = FlightMode.GO_HOME)).text)
        assertEquals("착륙", flightTitle(ground.copy(flightMode = FlightMode.LANDING)).text)
        assertTrue(flightTitle(null).warn)
        assertTrue(flightTitle(ground.copy(connected = false)).warn)
    }

    @Test
    fun `배터리 띠는 심각 빨강 낮음 주황 그 위 초록을 남은 양까지`() {
        val full = batteryBand(70.0, lowPct = 30, criticalPct = 10)
        assertEquals(listOf(BatteryLevel.CRITICAL, BatteryLevel.LOW, BatteryLevel.OK), full.map { it.level })
        assertEquals(0.1f, full[0].to, 1e-6f)
        assertEquals(0.3f, full[1].to, 1e-6f)
        assertEquals(0.7f, full[2].to, 1e-6f)
        val low = batteryBand(20.0, lowPct = 30, criticalPct = 10)
        assertEquals(listOf(BatteryLevel.CRITICAL, BatteryLevel.LOW), low.map { it.level })
        assertEquals(0.2f, low.last().to, 1e-6f)
        assertTrue(batteryBand(0.0, 30, 10).isEmpty())
        assertEquals(BatteryLevel.LOW, batteryLevel(43.0, 43, 22))
        assertEquals(BatteryLevel.CRITICAL, batteryLevel(22.0, 43, 22))
        assertEquals(BatteryLevel.OK, batteryLevel(44.0, 43, 22))
    }

    @Test
    fun `남은 비행 시간은 기체 값 우선, 없으면 RTH 필요량을 뺀 추정`() {
        assertEquals(600, remainingFlightSec(ground.copy(remainingFlightSec = 600), 10))
        val est = remainingFlightSec(ground, 20)!!
        assertTrue(est in 1_100..1_130) // (80-20)% × 호버 31분(1860초)/100% ≈ 1116초
        assertNull(remainingFlightSec(ground.copy(connected = false), 0))
    }

    @Test
    fun `줌 영역 사각형은 배율에 반비례`() {
        assertEquals(0.5, zoomFrameFraction(2.0), 1e-9)
        assertEquals(1.0 / 7, zoomFrameFraction(7.0), 1e-9)
        assertEquals(1.0, zoomFrameFraction(1.0), 1e-9)
    }

    @Test
    fun `내비 원판은 기수가 위, 16m 넘으면 가장자리`() {
        val p = LocalProjection(home)
        val north8 = p.toGeo(Enu(0.0, 8.0))
        // 기수 북: 북쪽 8m는 위로 반
        val (x, y) = navPoint(home, north8, 0.0)!!
        assertEquals(0.0, x, 0.01)
        assertEquals(-0.5, y, 0.01)
        // 기수 동(90°): 북쪽은 왼쪽
        val (x2, y2) = navPoint(home, north8, 90.0)!!
        assertEquals(-0.5, x2, 0.01)
        assertEquals(0.0, y2, 0.01)
        // 100m 동쪽은 가장자리 오른쪽(기수 북)
        val (x3, _) = navPoint(home, p.toGeo(Enu(100.0, 0.0)), 0.0)!!
        assertEquals(1.0, x3, 0.01)
        assertNull(navPoint(home, home, 0.0))
        assertNull(navPoint(null, home, 0.0))
        assertEquals(270.0, navAngle(0.0, 90.0), 1e-9)
        // 조종기 점은 5m 넘을 때만
        assertFalse(showRcDot(home, p.toGeo(Enu(3.0, 0.0))))
        assertTrue(showRcDot(home, p.toGeo(Enu(6.0, 0.0))))
    }

    @Test
    fun `장애물 부채꼴은 제동 빨강 경고 주황 16m 안 초록`() {
        val o = Obstacles(
            mapOf(ObstacleDirection.FRONT to 2.0, ObstacleDirection.RIGHT to 9.0, ObstacleDirection.BACK to 15.0, ObstacleDirection.LEFT to 30.0),
            warningM = 10.0, brakingM = 3.0,
        )
        assertEquals(ObstacleLevel.DANGER, o.sector(ObstacleDirection.FRONT))
        assertEquals(ObstacleLevel.CAUTION, o.sector(ObstacleDirection.RIGHT))
        assertEquals(ObstacleLevel.NONE, o.sector(ObstacleDirection.BACK))
        assertNull(o.sector(ObstacleDirection.LEFT))
        assertNull(o.sector(ObstacleDirection.UP))
    }
}
