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
    private val diagonal = FlightParams(shotMode = ShotMode.DIAGONAL4)

    @Test
    fun `전주 위는 전주마다 1곳이고 다음 전주를 바라본다`() {
        val v = Viewpoints.expand(poles, FlightParams(), home)
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
    fun `4방향 사선은 직전 지점에서 가까운 대각선부터 시계 방향으로 돈다`() {
        val v = Viewpoints.expand(poles, diagonal, home)
        val first = v.take(4)
        // 선로 방향 90°에서 번호 0~3은 135°, 225°, 315°, 45°. 이륙 지점이 서쪽이므로 서쪽 두 지점(번호 1, 2) 중 하나로 들어간다
        assertTrue(first[0].index in setOf(1, 2))
        first.zipWithNext().forEach { (a, b) -> assertEquals((a.index + 1) % 4, b.index) }
    }

    @Test
    fun `4방향 사선 고도가 전선에 가까우면 검증에서 막는다`() {
        assertTrue(diagonal.validate().isEmpty())
        assertTrue(diagonal.copy(diagonalAltitudeM = 20.0).validate().any { "27m" in it })
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
        assertEquals(12, Regex("takePhoto").findAll(wpml).count())
        assertTrue("<wpml:fileSuffix>P0_0</wpml:fileSuffix>" in wpml)
        assertTrue("coordinateTurn" !in wpml) // 모든 지점에서 멈춘 뒤 돈다(오류 1546 방지)
        val headings = Regex("<wpml:waypointHeadingAngle>([-0-9.]+)<").findAll(wpml).map { it.groupValues[1].toDouble() }.toList()
        assertTrue(headings.all { it in -180.0..180.0 })
    }
}
