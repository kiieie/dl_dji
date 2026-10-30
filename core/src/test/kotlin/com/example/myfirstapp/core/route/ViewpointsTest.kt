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
    fun `4방향 사선은 4패스 - 한쪽을 갔다 오고 반대쪽을 갔다 온다`() {
        val v = Viewpoints.expand(poles, diagonal, home)
        // 이륙 지점이 서쪽 끝이므로 P0부터, 갈 때 P0→P2, 올 때 P2→P0
        assertEquals(listOf("P0", "P1", "P2", "P2", "P1", "P0", "P0", "P1", "P2", "P2", "P1", "P0"), v.map { it.pole.poleId })
        // 선로 방향 90°: 1=뒤-오른쪽(225°), 0=앞-오른쪽(135°), 2=뒤-왼쪽(315°), 3=앞-왼쪽(45°). 이륙 지점(서쪽, 선로 위)에서 같은 거리면 오른쪽부터
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 2, 2, 2, 3, 3, 3), v.map { it.index })
        // 한 패스 안에서는 기수가 같다(선로 쪽 45°를 유지)
        v.chunked(3).forEach { pass -> assertEquals(1, pass.map { it.headingDeg.toInt() }.distinct().size) }
        // 전선(선로, y=0)을 넘는 것은 패스 2 → 3 한 번뿐
        val side = v.map { Math.signum(LocalProjection(home).toEnu(it.position).y) }
        assertEquals(1, side.zipWithNext().count { (a, b) -> a != b })
    }

    @Test
    fun `4방향 사선은 이륙 지점에서 가까운 끝부터 시작한다`() {
        val east = LocalProjection(home).toGeo(Enu(300.0, 0.0))
        assertEquals("P2", Viewpoints.expand(poles, diagonal, east).first().pole.poleId)
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

    @Test
    fun `지점이 하나면 촬영 없는 마무리 지점을 덧붙인다(웨이포인트 2개 이상)`() {
        val v = Viewpoints.expand(poles.take(1), FlightParams(), home)
        val bytes = ByteArrayOutputStream().also { WpmlWriter.write(v, FlightParams(), it, nowMs = 0) }.toByteArray()
        val wpml = ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name.endsWith("wpml") }.let { z.readBytes().decodeToString() } }
        assertEquals(2, Regex("<Placemark>").findAll(wpml).count())
        assertEquals(1, Regex("takePhoto").findAll(wpml).count())
    }

    @Test
    fun `4방향 사선은 배터리 예측을 4패스·4장으로 잡는다`() {
        val over = EnergyModel(EnergyParams(), FlightParams())
        val diag = EnergyModel(EnergyParams(), diagonal)
        assertTrue(diag.sortieCost(1000.0, 10) > 3 * over.sortieCost(1000.0, 10) - 3 * over.takeoffLanding)
    }
}
