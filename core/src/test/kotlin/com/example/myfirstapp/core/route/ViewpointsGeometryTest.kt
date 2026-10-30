package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.csv.PoleCsvParser
import com.example.myfirstapp.core.csv.CsvImportResult
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 4방향 사선(5.6)을 기하로 검증한다: 지점 위치·이름이 선로 방향과 맞는지, 전선을 전주마다 한 번만 넘는지(꺾인 선로 포함).
 * 전선은 선로 순서대로 이은 전주 사이 선분으로 본다.
 */
class ViewpointsGeometryTest {
    private val home = GeoPoint(36.4800, 127.3300)
    private val proj = LocalProjection(home)
    private val diagonal = FlightParams(shotMode = ShotMode.DIAGONAL4)

    private fun pole(i: Int, x: Double, y: Double, bearing: Double? = null) = Pole("P$i", proj.toGeo(Enu(x, y)), lineBearingDeg = bearing)

    /** 촬영 지점 사이 이동이 전선(선로 순서 전주를 이은 선분)을 넘는 횟수. */
    private fun crossings(route: List<GeoPoint>, wire: List<Pair<GeoPoint, GeoPoint>>): Int {
        fun e(p: GeoPoint) = proj.toEnu(p)
        fun ccw(a: Enu, b: Enu, c: Enu) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        fun cross(a: Enu, b: Enu, c: Enu, d: Enu) = ccw(a, b, c) * ccw(a, b, d) < 0 && ccw(c, d, a) * ccw(c, d, b) < 0
        return route.zipWithNext().sumOf { (a, b) -> wire.count { (c, d) -> cross(e(a), e(b), e(c), e(d)) } }
    }

    /** 선로 순서대로 이은 전선. 실제 전선은 끝 전주 너머로도 이어지므로 양끝을 20m 늘린다. */
    private fun wireOf(poles: List<Pole>): List<Pair<GeoPoint, GeoPoint>> {
        fun beyond(from: Pole, to: Pole): GeoPoint {
            val a = proj.toEnu(from.position)
            val b = proj.toEnu(to.position)
            val k = 20.0 / LocalProjection.distanceM(from.position, to.position)
            return proj.toGeo(Enu(b.x + (b.x - a.x) * k, b.y + (b.y - a.y) * k))
        }
        val n = poles.size
        return listOf(beyond(poles[1], poles[0]) to poles[0].position) +
            poles.zipWithNext().map { (a, b) -> a.position to b.position } +
            (poles[n - 1].position to beyond(poles[n - 2], poles[n - 1]))
    }

    /** 지점 방위 = (선로번호 방향, 없으면 방문 앞뒤 방향) + 45° + 90°·번호. 거리는 설정값. */
    private fun assertCorners(v: List<Viewpoint>) = v.forEach { p ->
        assertEquals(diagonal.diagonalDistanceM, LocalProjection.distanceM(p.pole.position, p.position), 0.01)
        p.pole.lineBearingDeg?.let { theta ->
            val b = Viewpoints.bearing(p.pole.position, p.position)
            val d = ((b - (theta + 45.0 + 90.0 * p.index)).mod(360.0)).let { if (it > 180) 360 - it else it }
            assertEquals("${p.pole.poleId} 지점 ${p.index}", 0.0, d, 0.5)
        }
    }

    @Test
    fun `완만하게 꺾인 선로(30도)에서도 전선은 전주마다 한 번만 넘는다`() {
        val turn = Math.toRadians(30.0)
        val poles = (0..3).map { pole(it, 100.0 + 40.0 * it, 0.0) } +
            (1..3).map { pole(3 + it, 220.0 + 40.0 * it * kotlin.math.cos(turn), 40.0 * it * kotlin.math.sin(turn)) }
        val v = Viewpoints.expand(poles, diagonal, home)
        assertEquals(poles.size * 4, v.size)
        assertEquals(poles.size, crossings(v.map { it.position }, wireOf(poles)))
        assertCorners(v)
    }

    @Test
    fun `직각으로 꺾이는 전주는 사선 지점 하나가 전선 바로 위에 온다(고도 여유로 안전)`() {
        // 꺾인 전주의 선로 방향은 앞뒤 전주를 이은 45°라 대각선 지점이 정북·정동·정남·정서에 놓인다
        val poles = (0..3).map { pole(it, 100.0 + 40.0 * it, 0.0) } + (1..3).map { pole(3 + it, 220.0, 40.0 * it) }
        val v = Viewpoints.expand(poles, diagonal, home).filter { it.pole.poleId == "P3" }
        val corner = proj.toEnu(poles[3].position)
        assertEquals(1, v.count { p -> proj.toEnu(p.position).let { kotlin.math.abs(it.x - corner.x) < 0.01 && it.y > corner.y } })
        // 그래도 전선(약 17m)보다 10m 위를 난다(5.6 검증)
        assertEquals(true, diagonal.diagonalAltitudeM - 17.0 >= 10.0)
    }

    @Test
    fun `지그재그 선로에서도 전선은 전주마다 한 번만 넘는다`() {
        val poles = (0..5).map { pole(it, 100.0 + 40.0 * it, if (it % 2 == 0) 0.0 else 25.0) }
        val v = Viewpoints.expand(poles, diagonal, home)
        assertEquals(poles.size, crossings(v.map { it.position }, wireOf(poles)))
    }

    @Test
    fun `방문 방향이 선로번호 방향과 반대여도 지점 이름은 선로 방향 기준이다`() {
        // 동쪽으로 날지만 전원측 → 부하측은 서쪽(θ=270°)
        val poles = (0..2).map { pole(it, 100.0 + 50.0 * it, 0.0, bearing = 270.0) }
        val v = Viewpoints.expand(poles, diagonal, home)
        assertCorners(v)
        assertEquals(3, crossings(v.map { it.position }, wireOf(poles)))
        // 첫 지점은 이륙 지점(서쪽) 가까운 남서: θ+315° → 3(부하측 좌, 서쪽을 보면 남쪽이 왼쪽)
        assertEquals(3, v.first().index)
    }

    /**
     * 실제 전주로 확인(선택, 현장 좌표는 저장소에 넣지 않는다). 환경 변수:
     * `POLES_CSV` 소티 하나의 방문 순서(전산화번호·위도·경도·선로명·선로번호), `ALL_POLES_CSV` 프로젝트 전체 전주(선로 방향·전선용),
     * `HOME` 이륙 지점 "위도,경도". 전선은 선로번호의 앞(전원측) 전주와 이은 선분(150m 이내)이다.
     * 넘는 횟수, 다른 전주와의 최소 수평 거리, 지점 이름이 선로 방향과 맞는지 본다.
     */
    @Test
    fun `실제 전주 순서(선택)`() {
        val path = System.getenv("POLES_CSV")
        assumeTrue(path != null && File(path).exists())
        fun read(f: String) = (PoleCsvParser.parse(File(f).readBytes()) as CsvImportResult.Success).poles
        val all = read(System.getenv("ALL_POLES_CSV"))
        val poles = LineNumbers.withBearings(read(path!!), all)
        val start = System.getenv("HOME_POINT").split(",").let { GeoPoint(it[0].toDouble(), it[1].toDouble()) }
        val v = Viewpoints.expand(poles, diagonal, start)
        val byNo = all.associateBy { it.lineName to it.lineNo }
        val wire = all.mapNotNull { p ->
            val prev = p.lineNo?.let(LineNumbers::previous)?.let { byNo[p.lineName to it] } ?: return@mapNotNull null
            (prev.position to p.position).takeIf { LocalProjection.distanceM(prev.position, p.position) <= 150.0 }
        }
        val near = v.minOf { p -> all.filter { it.poleId != p.pole.poleId }.minOf { LocalProjection.distanceM(it.position, p.position) } }
        val perPole = v.groupBy { it.pole.poleId }.mapValues { (_, l) -> crossings(l.map { it.position }, wire) }
        val between = v.zipWithNext().filter { (a, b) -> a.pole != b.pole }.sumOf { (a, b) -> crossings(listOf(a.position, b.position), wire) }
        println("실제 전주 ${poles.size}개(선로 방향 ${poles.count { it.lineBearingDeg != null }}개), 지점 ${v.size}개")
        println("전주 안 넘기: ${perPole.values.groupingBy { it }.eachCount()} (넘기 횟수→전주 수), 전주 사이 이동 넘기 ${between}번, 다른 전주와 최소 ${"%.1f".format(near)}m")
        perPole.filter { it.value != 1 }.forEach { (id, n) -> println("  확인: $id ${poles.first { it.poleId == id }.displayName} 넘기 $n") }
        assertCorners(v)
    }
}
