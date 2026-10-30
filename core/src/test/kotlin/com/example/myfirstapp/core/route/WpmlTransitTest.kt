package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 방식 A 이동 고도 구간(5.6)과 지형 추적 고도 모드. */
class WpmlTransitTest {
    private val home = GeoPoint(36.48, 127.33)
    private val proj = LocalProjection(home)
    private fun pole(id: String, east: Double) = Pole(id, proj.toGeo(Enu(east, 0.0)))

    /** 첫 전주가 200m 멀고, P0→P1은 50m(짧음), P1→P2는 150m(김). */
    private val poles = listOf(pole("P0", 200.0), pole("P1", 250.0), pole("P2", 400.0))
    private val overhead = FlightParams(shotMode = ShotMode.OVERHEAD)

    private fun files(points: List<Viewpoint>, p: FlightParams, start: GeoPoint?): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { WpmlWriter.write(points, p, it, nowMs = 0, start = start) }.toByteArray()
        return buildMap { ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { put(it.name, z.readBytes().decodeToString()) } } }
    }

    @Test
    fun `먼 첫 전주·먼 구간·복귀는 이동 고도로 올라갔다 내려오고 짧은 구간은 그대로`() {
        val v = Viewpoints.expand(poles, overhead, home)
        val w = WpmlWriter.waypoints(v, overhead, home)
        assertEquals(listOf(null, null, 0, 1, null, null, 2, null), w.map { it.shot })
        assertEquals(listOf(50.0, 50.0, 30.0, 30.0, 50.0, 50.0, 30.0, 50.0), w.map { it.altitudeM })
        // 이동 고도 지점은 떠나는 곳·도착할 촬영 지점 바로 위
        assertEquals(home, w[0].position)
        assertEquals(v[0].position, w[1].position)
        assertEquals(v[1].position, w[4].position)
        assertEquals(v[2].position, w[5].position)
        assertEquals(v[2].position, w[7].position)
    }

    @Test
    fun `이동 고도 지점에는 촬영 동작이 없다`() {
        val v = Viewpoints.expand(poles, overhead, home)
        val wpml = files(v, overhead, home).getValue("wpmz/waylines.wpml")
        assertEquals(8, Regex("<Placemark>").findAll(wpml).count())
        assertEquals(3, Regex("takePhoto").findAll(wpml).count())
        assertEquals(3, Regex("<wpml:actionGroup>").findAll(wpml).count())
        // 동작 묶음은 촬영 웨이포인트 번호(2, 3, 6)에 붙고, 파일 이름은 전주 순서 그대로
        assertEquals(listOf(2, 3, 6), Regex("<wpml:actionGroupStartIndex>(\\d+)<").findAll(wpml).map { it.groupValues[1].toInt() }.toList())
        assertEquals(listOf("P0_0", "P1_0", "P2_0"), Regex("<wpml:fileSuffix>([^<]+)<").findAll(wpml).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `KMZ 번호를 촬영 지점 번호로 바꾼다(사진이 늦게 와도 지나온 지점)`() {
        val v = Viewpoints.expand(poles, overhead, home)
        val w = WpmlWriter.waypoints(v, overhead, home)
        assertEquals(listOf(null, null, 0, 1, 1, 1, 2, 2), w.indices.map { WpmlWriter.shotIndex(w, it) })
        assertEquals(null, WpmlWriter.shotIndex(w, -1))
    }

    @Test
    fun `경계 - 구간이 기준과 같으면 그대로, 넘으면 이동 고도`() {
        val two = listOf(pole("A", 0.0), pole("B", 60.0))
        val v = Viewpoints.expand(two, overhead, home)
        val d = LocalProjection.distanceM(v[0].position, v[1].position)
        assertEquals(2, WpmlWriter.waypoints(v, overhead.copy(transitLegThresholdM = d)).size)
        assertEquals(4, WpmlWriter.waypoints(v, overhead.copy(transitLegThresholdM = d - 0.5)).size)
    }

    @Test
    fun `4방향 사선 - 한 전주의 4점 사이는 이동 고도를 쓰지 않는다`() {
        val p = FlightParams(shotMode = ShotMode.DIAGONAL4, transitLegThresholdM = 5.0)
        val v = Viewpoints.expand(poles.take(1), p, home)
        val w = WpmlWriter.waypoints(v, p)
        assertEquals(listOf(0, 1, 2, 3), w.map { it.shot })
    }

    @Test
    fun `지형 추적이면 두 파일 모두 realTimeFollowSurface, 끄면 relativeToStartPoint`() {
        val v = Viewpoints.expand(poles, overhead, home)
        val on = files(v, overhead, null)
        assertTrue("<wpml:heightMode>realTimeFollowSurface</wpml:heightMode>" in on.getValue("wpmz/template.kml"))
        assertTrue("<wpml:executeHeightMode>realTimeFollowSurface</wpml:executeHeightMode>" in on.getValue("wpmz/waylines.wpml"))
        val off = files(v, overhead.copy(terrainFollow = false), null)
        assertTrue("<wpml:heightMode>relativeToStartPoint</wpml:heightMode>" in off.getValue("wpmz/template.kml"))
        assertTrue("<wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>" in off.getValue("wpmz/waylines.wpml"))
        assertTrue("realTimeFollowSurface" !in off.values.joinToString())
    }

    @Test
    fun `에너지 - 긴 구간마다 오르내림을 더한다`() {
        val e = EnergyModel(EnergyParams(), overhead)
        // (50 − 30) × 2 / 3m/s: 첫·복귀 한 번 + 긴 구간 2개
        assertEquals(3 * 40.0 / 3.0, e.transitClimbSec(2), 1e-9)
        assertEquals(2, e.longJumps(listOf(50.0, 60.0, 60.1, 150.0)))
        assertEquals(e.sortieCost(1000.0, 3) + 2 * 40.0 / 3.0 * EnergyParams().hoverPctPerSec, e.sortieCost(1000.0, 3, 2), 1e-9)
    }

    @Test
    fun `이동 고도가 촬영 고도보다 2m 넘게 높지 않으면 이동 지점을 끼우지 않는다(같은 자리 겹침 방지)`() {
        val p = FlightParams(shotMode = ShotMode.OVERHEAD, inspectAltitudeM = 50.0, transitAltitudeM = 50.0)
        val far = (0..2).map { i -> Pole("P$i", LocalProjection(GeoPoint(36.48, 127.33)).toGeo(Enu(200.0 * (i + 1), 0.0))) }
        val v = Viewpoints.expand(far, p, GeoPoint(36.48, 127.33))
        val wps = WpmlWriter.waypoints(v, p, GeoPoint(36.48, 127.33))
        assertTrue(wps.all { it.shot != null })
        assertTrue(wps.zipWithNext().none { (a, b) -> a.position == b.position && a.altitudeM == b.altitudeM })
    }
}
