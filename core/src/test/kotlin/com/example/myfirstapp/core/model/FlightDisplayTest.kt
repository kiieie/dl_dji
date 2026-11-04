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
        assertEquals("비행 경로 수행 중 · #20 · 소티 2/5", flightTitle(ground.copy(flightMode = FlightMode.MISSION), "#20 · ${sortieLabel(2, 5)}").text)
        assertEquals("RTH 진행 중", flightTitle(ground.copy(flightMode = FlightMode.GO_HOME)).text)
        assertEquals("착륙", flightTitle(ground.copy(flightMode = FlightMode.LANDING)).text)
        assertTrue(flightTitle(null).warn)
        assertTrue(flightTitle(ground.copy(connected = false)).warn)
    }

    @Test
    fun `비행 모드 글자는 다중 비행 모드와 F 자리 설정을 따른다`() {
        assertEquals('N', flightModeLetter('S', multiMode = false, fMode = 'T'))
        assertEquals('N', flightModeLetter('F', multiMode = false, fMode = 'A'))
        assertEquals('S', flightModeLetter('S', multiMode = true, fMode = 'T'))
        assertEquals('T', flightModeLetter('F', multiMode = true, fMode = 'T'))
        assertEquals('A', flightModeLetter('F', multiMode = true, fMode = 'A'))
        assertEquals('N', flightModeLetter('N', multiMode = true, fMode = 'A'))
        // 설정을 못 읽으면 스위치 글자 그대로
        assertEquals('F', flightModeLetter('F', multiMode = null, fMode = null))
        assertEquals('T', flightModeLetter('T', multiMode = null, fMode = null))
        assertNull(flightModeLetter(null, multiMode = true, fMode = 'T'))
    }

    @Test
    fun `신호 강도 글과 대역폭 글`() {
        assertEquals("강함", signalWord(60))
        assertEquals("보통", signalWord(59))
        assertEquals("보통", signalWord(30))
        assertEquals("약함", signalWord(29))
        assertEquals("40MHz", bandwidthLabel("BANDWIDTH_40MHZ"))
        assertEquals("5MHz", bandwidthLabel("BANDWIDTH_5MHZ"))
        assertNull(bandwidthLabel("UNKNOWN"))
    }

    @Test
    fun `배터리 띠는 땅에서 초록 하나, 떠 있으면 착륙 빨강 RTH 주황 그 위 초록`() {
        val ground = batteryBand(70.0, flying = false, rthPct = 18, landPct = 8, criticalPct = 22)
        assertEquals(listOf(BandSegment(0f, 0.7f, BatteryLevel.OK)), ground)
        val air = batteryBand(70.0, flying = true, rthPct = 18, landPct = 8, criticalPct = 22)
        assertEquals(listOf(BatteryLevel.CRITICAL, BatteryLevel.LOW, BatteryLevel.OK), air.map { it.level })
        assertEquals(0.08f, air[0].to, 1e-6f)
        assertEquals(0.18f, air[1].to, 1e-6f)
        assertEquals(0.7f, air[2].to, 1e-6f)
        // 착륙 필요량을 모르면 심각 문턱까지, 단 RTH 필요량을 넘지 않음
        assertEquals(0.1f, batteryBand(70.0, true, rthPct = 10, landPct = null, criticalPct = 22)[0].to, 1e-6f)
        // 남은 양이 RTH 필요량보다 적으면 거기서 자름
        val low = batteryBand(12.0, true, rthPct = 18, landPct = 8, criticalPct = 22)
        assertEquals(listOf(BatteryLevel.CRITICAL, BatteryLevel.LOW), low.map { it.level })
        assertEquals(0.12f, low.last().to, 1e-6f)
        // 떠 있어도 RTH 필요량을 모르면 초록 하나
        assertEquals(1, batteryBand(50.0, true, null, null, 22).size)
        assertTrue(batteryBand(0.0, false, null, null, 22).isEmpty())
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
    fun `영상 자리는 비율을 지켜 가운데, 모르면 4대3`() {
        // 16:9 화면(1920×1080)에 4:3 영상: 양옆 240px 검은 띠
        assertEquals(ContentRect(240.0, 0.0, 1440.0, 1080.0), videoContentRect(1920.0, 1080.0, 1440, 1080))
        assertEquals(ContentRect(240.0, 0.0, 1440.0, 1080.0), videoContentRect(1920.0, 1080.0, null, null))
        // 16:9 영상은 화면을 채운다
        assertEquals(ContentRect(0.0, 0.0, 1920.0, 1080.0), videoContentRect(1920.0, 1080.0, 1280, 720))
        // 화면보다 넓은 영상은 위아래 띠
        val wide = videoContentRect(800.0, 600.0, 1920, 1080)
        assertEquals(0.0, wide.left, 1e-9)
        assertEquals(450.0, wide.height, 1e-9)
        assertEquals(75.0, wide.top, 1e-9)
        assertEquals(ContentRect(0.0, 0.0, 0.0, 0.0), videoContentRect(0.0, 0.0, 4, 3))
    }

    @Test
    fun `소티 번호는 남은 전주가 있는 첫 소티, 다 끝나면 전체`() {
        assertEquals(1, currentSortie(listOf(3, 4, 2)))
        assertEquals(2, currentSortie(listOf(0, 4, 2)))
        // 건너뛴 소티가 있어도 남은 것이 있는 첫 소티
        assertEquals(1, currentSortie(listOf(1, 0, 2)))
        assertEquals(3, currentSortie(listOf(0, 0, 0)))
        assertEquals(0, currentSortie(emptyList()))
        assertEquals("소티 2/5", sortieLabel(2, 5))
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
