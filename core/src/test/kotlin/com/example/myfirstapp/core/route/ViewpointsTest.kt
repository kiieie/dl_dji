package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ViewpointsTest {
    private val home = GeoPoint(36.4800, 127.3300)

    /** 정동쪽으로 50m 간격 전주 3개. */
    private val poles = (0..2).map { i -> Pole("P$i", LocalProjection(home).toGeo(Enu(100.0 + 50.0 * i, 0.0))) }
    private val diagonal = FlightParams(shotMode = ShotMode.DIAGONAL4, diagonalAltitudeM = 27.0)

    @Test
    fun `전주 위는 전주마다 1곳이고 다음 전주를 바라본다`() {
        val v = Viewpoints.expand(poles, FlightParams(shotMode = ShotMode.OVERHEAD), home)
        assertEquals(3, v.size)
        assertTrue(v.all { it.index == 0 && it.altitudeM == 30.0 && it.gimbalPitchDeg == -90.0 })
        assertEquals(90.0, v[0].headingDeg, 0.5)
    }

    @Test
    fun `4방향 사선은 전주마다 대각선 4곳에서 전주를 바라본다`() {
        val v = Viewpoints.expand(poles, diagonal, home)
        assertEquals(12, v.size)
        v.groupBy { it.pole }.forEach { (pole, list) ->
            assertEquals(setOf(0, 1, 2, 3), list.map { it.index }.toSet())
            list.forEach { p ->
                assertEquals(10.0, LocalProjection.distanceM(pole.position, p.position), 0.01)
                assertEquals(Viewpoints.bearing(p.position, pole.position), p.headingDeg, 0.01)
                // 선로가 동서 방향이므로 대각선 지점은 선로에서 남북으로 약 7m 떨어진다
                assertEquals(10.0 * Math.sqrt(0.5), abs(LocalProjection(pole.position).toEnu(p.position).y), 0.01)
            }
        }
        // 고도 27m, 거리 10m, 조준 17.5m → atan(9.5/10)
        assertEquals(-43.5, v[0].gimbalPitchDeg, 0.1)
    }

    @Test
    fun `4방향 사선은 전주마다 4점을 다 찍고 다음 전주로 간다`() {
        val v = Viewpoints.expand(poles, diagonal, home)
        // 이륙 지점이 서쪽 끝이므로 P0부터, 전주마다 4점
        assertEquals(listOf("P0", "P0", "P0", "P0", "P1", "P1", "P1", "P1", "P2", "P2", "P2", "P2"), v.map { it.pole.poleId })
        // 선로 방향 90°(동쪽): P0은 오른쪽(남) 뒤 1 → 앞 0 → 왼쪽(북) 앞 3 → 뒤 2, P1은 왼쪽에서 시작해 거꾸로
        assertEquals(listOf(1, 0, 3, 2, 2, 3, 0, 1, 1, 0, 3, 2), v.map { it.index })
        // 전선(선로, y=0)은 전주마다 한 번만 넘는다
        val side = v.map { Math.signum(LocalProjection(home).toEnu(it.position).y) }
        assertEquals(3, side.zipWithNext().count { (a, b) -> a != b })
        // 다음 전주로는 같은 쪽을 따라 간다(전주 사이 이동에서는 넘지 않음)
        assertTrue(side[3] == side[4] && side[7] == side[8])
    }

    @Test
    fun `4방향 사선은 받은 방문 순서를 따른다(가까운 끝은 계획이 정함, 5_7)`() {
        val east = LocalProjection(home).toGeo(Enu(300.0, 0.0))
        assertEquals("P0", Viewpoints.expand(poles, diagonal, east).first().pole.poleId)
        assertEquals("P2", Viewpoints.expand(poles.reversed(), diagonal, east).first().pole.poleId)
    }

    @Test
    fun `4방향 사선 고도가 전선에 가까우면 검증에서 막는다`() {
        assertTrue(diagonal.copy(diagonalAltitudeM = 30.0).validate().isEmpty())
        assertTrue(diagonal.copy(diagonalAltitudeM = 20.0).validate().any { "27m" in it }) // 지형 추적이어도 27m
        assertTrue(diagonal.copy(diagonalAltitudeM = 20.0, terrainFollow = false).validate().any { "27m" in it })
    }

    @Test
    fun `KMZ에는 촬영 지점마다 웨이포인트와 짐벌·촬영 동작이 들어간다`() {
        val v = Viewpoints.expand(poles, diagonal, home)
        val bytes = ByteArrayOutputStream().also { WpmlWriter.write(v, diagonal, it, nowMs = 0) }.toByteArray()
        val files = buildMap {
            ZipInputStream(bytes.inputStream()).use { z ->
                generateSequence { z.nextEntry }.forEach { put(it.name, z.readBytes().decodeToString()) }
            }
        }
        assertEquals(setOf("wpmz/template.kml", "wpmz/waylines.wpml"), files.keys)
        val wpml = files.getValue("wpmz/waylines.wpml")
        assertEquals(12, Regex("<Placemark>").findAll(wpml).count())
        assertEquals(24, Regex("takePhoto").findAll(wpml).count()) // 렌즈마다 동작(광각·망원)
        // 촬영마다 앞에 줌 동작(광각 24mm, 망원 배율 × 24mm), 지점 끝에 광각으로(2026-11-06 실내 시험: 없으면 망원도 광각 사진)
        val zooms = Regex("<wpml:focalLength>([0-9.]+)<").findAll(wpml).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(12 * 3, zooms.size)
        assertEquals(listOf(24.0, diagonal.zoomRatio * 24.0, 24.0), zooms.take(3))
        val firstPoint = wpml.substringAfter("<wpml:actionGroupId>").substringBefore("</wpml:actionGroup>")
        assertTrue(firstPoint.indexOf("<wpml:focalLength>${diagonal.zoomRatio * 24.0}") < firstPoint.indexOf("<wpml:payloadLensIndex>zoom<"))
        assertTrue("<wpml:fileSuffix>P0_0</wpml:fileSuffix>" in wpml)
        assertTrue("coordinateTurn" !in wpml) // 모든 지점에서 멈춘 뒤 돈다(오류 1546 방지)
        val headings = Regex("<wpml:waypointHeadingAngle>([-0-9.]+)<").findAll(wpml).map { it.groupValues[1].toDouble() }.toList()
        assertTrue(headings.all { it in -180.0..180.0 })
        // 이동 중에도 기수는 다음 촬영 전주(머리 높이)를 보고, 찍기 전에 이 전주로 돈다
        val pois = Regex("<wpml:waypointPoiPoint>([-0-9.]+),([-0-9.]+),([-0-9.]+)<").findAll(wpml).map { it.groupValues }.toList()
        assertEquals(12, Regex("towardPOI").findAll(wpml).count())
        assertEquals(v[1].pole.position.lat, pois[0][1].toDouble(), 1e-7)
        assertEquals(diagonal.aimHeightM, pois[0][3].toDouble(), 1e-9)
        assertEquals(12, Regex("rotateYaw").findAll(wpml).count())
    }

    @Test
    fun `지점이 하나면 촬영 없는 마무리 지점을 덧붙인다(웨이포인트 2개 이상)`() {
        val v = Viewpoints.expand(poles.take(1), FlightParams(shotMode = ShotMode.OVERHEAD), home)
        val bytes = ByteArrayOutputStream().also { WpmlWriter.write(v, FlightParams(shotMode = ShotMode.OVERHEAD), it, nowMs = 0) }.toByteArray()
        val wpml = ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name.endsWith("wpml") }.let { z.readBytes().decodeToString() } }
        assertEquals(2, Regex("<Placemark>").findAll(wpml).count())
        assertEquals(2, Regex("takePhoto").findAll(wpml).count())
    }

    @Test
    fun `4방향 사선은 배터리 예측에서 전주마다 도는 거리와 사진 4장을 더한다`() {
        val over = EnergyModel(EnergyParams(), FlightParams(shotMode = ShotMode.OVERHEAD))
        val diag = EnergyModel(EnergyParams(), diagonal)
        // 이륙 지점 왕복 200m + 전주 사이 1,000m, 전주 5개. 사선은 전주마다 d√2를 세 번
        assertEquals(1200.0, over.pathM(200.0, 1000.0, 5), 0.0)
        assertEquals(1200.0 + 5 * 3 * diagonal.diagonalDistanceM * Math.sqrt(2.0), diag.pathM(200.0, 1000.0, 5), 1e-9)
        assertEquals(4 * over.perVisit, diag.perVisit, 1e-9)
    }

    @Test
    fun `4방향 사선 피치는 전주 길이로 구한 머리 높이를 조준한다`() {
        val tall = poles.map { it.copy(lengthM = 16.0) } // 지상 13.5m
        val v = Viewpoints.expand(tall, diagonal, home)
        val expected = -Math.toDegrees(kotlin.math.atan2(diagonal.diagonalAltitudeM - 13.5, diagonal.diagonalDistanceM))
        assertTrue(v.all { kotlin.math.abs(it.gimbalPitchDeg - expected) < 1e-9 })
        // 길이를 모르면 설정의 조준 높이
        val plain = Viewpoints.expand(poles, diagonal, home)
        assertEquals(-Math.toDegrees(kotlin.math.atan2(diagonal.diagonalAltitudeM - diagonal.aimHeightM, diagonal.diagonalDistanceM)), plain[0].gimbalPitchDeg, 1e-9)
    }
}
