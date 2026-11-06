package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.PoleOverride
import com.example.myfirstapp.core.model.ShotMode
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경로 편집(5.7): 경로 역전, 순서 그대로 나누기, 전주별 설정, Pilot 2 경로 가져오기. 좌표는 모두 합성(실제 전주 좌표 아님). */
class RouteEditTest {
    private val home = GeoPoint(36.48, 127.33)
    private val proj = LocalProjection(home)
    private fun pole(id: String, east: Double, north: Double = 0.0) = Pole(id, proj.toGeo(Enu(east, north)))

    /** 정동쪽으로 50m 간격 전주 4개. */
    private val poles = (0..3).map { pole("P$it", 100.0 + 50.0 * it) }
    // 고도 값을 확인하는 테스트라 옛 기본값(27m, 이륙 지점 기준)으로 고정한다. 지형 추적 30m 하한은 FlightParamsTest
    private val diagonal = FlightParams(shotMode = ShotMode.DIAGONAL4, diagonalAltitudeM = 27.0, terrainFollow = false)
    private val energy = EnergyModel(EnergyParams(), diagonal)

    @Test
    fun `소티는 이륙 지점에서 가까운 끝부터 돈다`() {
        val east = proj.toGeo(Enu(400.0, 0.0))
        assertEquals("P3", RoutePlanner(energy).plan(east, poles).sorties.single().poles.first().poleId)
        assertEquals("P0", RoutePlanner(energy).plan(home, poles).sorties.single().poles.first().poleId)
    }

    @Test
    fun `경로 역전은 방문 순서를 뒤집고 촬영 지점도 그 순서를 따른다`() {
        val plan = RoutePlanner(energy).plan(home, poles)
        val back = plan.reverse(0, energy)
        assertEquals(plan.sorties[0].poles.reversed(), back.sorties[0].poles)
        // 왕복 경로라 거리·배터리는 같다
        assertEquals(plan.sorties[0].estDistanceM, back.sorties[0].estDistanceM, 1.0)
        assertEquals(plan.sorties[0].estBatteryPct, back.sorties[0].estBatteryPct, 0.1)
        assertEquals("P3", Viewpoints.expand(back.sorties[0].poles, diagonal, home).first().pole.poleId)
        assertEquals(plan.sorties[0].poles, back.reverse(0, energy).sorties[0].poles)
    }

    @Test
    fun `가져온 경로는 순서 그대로 나눈다`() {
        val zigzag = listOf(poles[2], poles[0], poles[3], poles[1])
        assertEquals(zigzag, RoutePlanner(energy).plan(home, zigzag, keepOrder = true).sorties.flatMap { it.poles })
        assertTrue(RoutePlanner(energy).plan(home, zigzag).sorties.flatMap { it.poles } != zigzag)
    }

    @Test
    fun `전주별 설정은 그 전주의 고도와 짐벌 피치만 바꾸고 KMZ에 들어간다`() {
        val p = diagonal.withOverride("P1", PoleOverride(altitudeM = 35.0, gimbalPitchDeg = -60.0)).withOverride("P2", PoleOverride(altitudeM = 30.0))
        val v = Viewpoints.expand(poles, p, home)
        v.filter { it.pole.poleId == "P1" }.forEach { assertEquals(35.0, it.altitudeM, 1e-9); assertEquals(-60.0, it.gimbalPitchDeg, 1e-9) }
        // 피치를 따로 정하지 않으면 바뀐 고도로 다시 겨눈다: atan((30 − 17.5) / 10)
        v.filter { it.pole.poleId == "P2" }.forEach { assertEquals(-51.3, it.gimbalPitchDeg, 0.1) }
        v.filter { it.pole.poleId == "P0" }.forEach { assertEquals(27.0, it.altitudeM, 1e-9); assertEquals(-43.5, it.gimbalPitchDeg, 0.1) }
        val wpml = ByteArrayOutputStream().also { WpmlWriter.write(v, p, it, nowMs = 0) }.toByteArray().let { b ->
            ZipInputStream(b.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name.endsWith("waylines.wpml") }; z.readBytes().decodeToString() }
        }
        assertEquals(4, Regex("<wpml:executeHeight>35.0</wpml:executeHeight>").findAll(wpml).count())
        assertTrue("<wpml:gimbalPitchRotateAngle>-60.0</wpml:gimbalPitchRotateAngle>" in wpml)
        // 모두 경로 팔로우로 돌리면 지운다
        assertTrue(p.withOverride("P1", PoleOverride()).poleOverrides.keys == setOf("P2"))
    }

    @Test
    fun `전주별 설정도 미션 설정과 같은 안전 범위를 지킨다`() {
        assertTrue(diagonal.withOverride("P1", PoleOverride(altitudeM = 27.0, gimbalPitchDeg = -90.0)).validate().isEmpty())
        assertTrue(diagonal.withOverride("P1", PoleOverride(altitudeM = 20.0)).validate().any { "P1" in it && "27~50m" in it })
        assertTrue(diagonal.withOverride("P1", PoleOverride(gimbalPitchDeg = 10.0)).validate().any { "P1" in it })
        assertEquals(1.0..50.0, FlightParams(shotMode = ShotMode.OVERHEAD, terrainFollow = false).shotAltitudeRangeM)
        assertEquals(30.0..50.0, FlightParams(shotMode = ShotMode.OVERHEAD).shotAltitudeRangeM)
    }

    @Test
    fun `우리 KMZ를 다시 읽으면 사진 지점만 전주에 맞추고 이동 지점은 버린다`() {
        // 첫 전주가 200m 멀어 이동 고도 지점(사진 없음)이 끼어 있다
        val far = listOf(pole("A", 200.0), pole("B", 250.0))
        val p = diagonal.copy(transitLegThresholdM = 60.0)
        val v = Viewpoints.expand(far, p, home)
        val kmz = ByteArrayOutputStream().also { WpmlWriter.write(v, p, it, nowMs = 0, start = home) }.toByteArray()
        val route = RouteImport.read(kmz, 1_000_000)
        assertTrue(route.waypoints.size > v.size)
        assertEquals(v.size, route.waypoints.count { it.photo })
        assertEquals(27.0, route.waypoints.first { it.photo }.heightM!!, 1e-9)
        val m = RouteImport.match(route, far + pole("X", 1000.0), 20.0)
        assertEquals(listOf("A", "B"), m.poles.map { it.poleId })
        assertEquals(v.size, m.used)
        assertTrue(m.unmatched.isEmpty())
    }

    @Test
    fun `Pilot 2 KMZ - 사진 동작이 없으면 모든 지점을 맞추고 먼 지점은 맞지 않음으로 남긴다`() {
        val wps = listOf(poles[0], poles[1]).map { proj.toEnu(it.position) }.map { proj.toGeo(Enu(it.x + 7.0, it.y + 7.0)) } + proj.toGeo(Enu(100.0, 300.0))
        fun f(v: Double) = String.format(Locale.ROOT, "%.8f", v)
        val template = """<?xml version="1.0" encoding="UTF-8"?><kml xmlns:wpml="http://www.dji.com/wpmz/1.0.2"><Document><wpml:missionConfig><wpml:takeOffRefPoint>${f(home.lat)},${f(home.lon)},10.0</wpml:takeOffRefPoint></wpml:missionConfig><Folder>""" +
            wps.mapIndexed { i, g -> "<Placemark><Point><coordinates>${f(g.lon)},${f(g.lat)}</coordinates></Point><wpml:index>$i</wpml:index><wpml:height>27</wpml:height><wpml:gimbalPitchAngle>-55</wpml:gimbalPitchAngle>" +
                "<wpml:actionGroup><wpml:action><wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc></wpml:action></wpml:actionGroup></Placemark>" }.joinToString("") +
            "</Folder></Document></kml>"
        val kmz = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("wpmz/template.kml")); z.write(template.toByteArray()) } }.toByteArray()
        val route = RouteImport.read(kmz, 1_000_000)
        assertEquals(3, route.waypoints.size)
        assertEquals(-55.0, route.waypoints[0].gimbalPitchDeg!!, 1e-9)
        assertEquals(listOf("gimbalRotate"), route.waypoints[0].actions)
        assertEquals(home.lat, route.takeoff!!.lat, 1e-7)
        val m = RouteImport.match(route, poles, 20.0)
        assertEquals(listOf("P0", "P1"), m.poles.map { it.poleId })
        assertEquals(1, m.unmatched.size)
        // 압축을 푼 파일이 상한을 넘으면 거절
        assertTrue(runCatching { RouteImport.read(kmz, 100) }.isFailure)
    }

    /**
     * 현장 KMZ(git 제외, `docs/drone_dl_test`)를 `PILOT2_KMZ`(경로를 `;`로 여럿)로 주면 읽어 본다. `POLES_FILE`(전주 엑셀·CSV)도 주면 전주에 맞춰 본다.
     * 좌표는 출력하지 않는다.
     */
    @Test
    fun `현장 KMZ 읽기(선택)`() {
        val paths = System.getenv("PILOT2_KMZ")?.split(';')?.filter { java.io.File(it).exists() }.orEmpty()
        org.junit.Assume.assumeTrue(paths.isNotEmpty())
        val poles = System.getenv("POLES_FILE")?.let { java.io.File(it) }?.takeIf { it.exists() }
            ?.let { (com.example.myfirstapp.core.csv.PoleCsvParser.parse(it.readBytes()) as com.example.myfirstapp.core.csv.CsvImportResult.Success).poles }
        paths.forEach { path ->
            val r = RouteImport.read(java.io.File(path).readBytes(), 10_000_000)
            val m = poles?.let { RouteImport.match(r, it, 20.0) }
            println(
                "${java.io.File(path).name}: 웨이포인트 ${r.waypoints.size}, 사진 ${r.waypoints.count { it.photo }}, 높이 ${r.waypoints.mapNotNull { it.heightM }.distinct()}, " +
                    "이륙점 ${r.takeoff != null}" + (m?.let { ", 전주 ${it.poles.size}개, 맞지 않음 ${it.unmatched.size}" } ?: ""),
            )
            assertTrue(r.waypoints.isNotEmpty())
        }
    }

    @Test
    fun `구글 어스 KML 선은 꼭짓점마다 웨이포인트`() {
        val coords = poles.joinToString(" ") { "${it.position.lon},${it.position.lat},0" }
        val kml = "<kml><Document><Placemark><name>선</name><LineString><coordinates>\n$coords\n</coordinates></LineString></Placemark>" +
            "<Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>$coords</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>"
        val route = RouteImport.read(kml.toByteArray(), 1_000_000)
        assertEquals(4, route.waypoints.size)
        assertNull(route.takeoff)
        assertEquals(poles.map { it.poleId }, RouteImport.match(route, poles.reversed(), 20.0).poles.map { it.poleId })
    }
}
