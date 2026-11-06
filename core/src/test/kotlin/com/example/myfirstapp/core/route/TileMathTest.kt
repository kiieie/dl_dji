package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TileMathTest {

    @Test
    fun `Bing quadkey는 문서 예제와 같다`() {
        assertEquals("213", TileMath.quadkey(3, 5, 3))
    }

    @Test
    fun `주소 틀을 채운다(VWorld WMTS는 z, y, x 순)`() {
        assertEquals("https://t/a213.jpeg", TileMath.expandUrl("https://t/a{quadkey}.jpeg", 3, 5, 3))
        assertEquals("https://api.vworld.kr/req/wmts/1.0.0/K/Satellite/16/25867/55856.jpeg",
            TileMath.expandUrl("https://api.vworld.kr/req/wmts/1.0.0/K/Satellite/{z}/{y}/{x}.jpeg", 55856, 25867, 16))
    }

    @Test
    fun `대평선 부근 z16 타일 번호와 영역 타일 수`() {
        // 웹 메르카토르 식으로 따로 계산한 값(36.48, 127.33)
        assertEquals(55947, TileMath.tileX(127.33, 16))
        assertEquals(25626, TileMath.tileY(36.48, 16))
        assertEquals(1L, TileMath.count(36.48, 36.48, 127.33, 127.33, 16, 16))
        assertEquals(2L, TileMath.count(36.4801, 36.4801, 127.3301, 127.3301, 15, 16)) // 한 점은 확대마다 1장
    }

    @Test
    fun `메르카토르 원점과 타일 번호·TMS 행이 맞물린다`() {
        assertEquals(0.5, TileMath.mercX(0.0), 1e-12)
        assertEquals(0.5, TileMath.mercY(0.0), 1e-12)
        assertEquals(TileMath.tileX(127.0, 17), (TileMath.mercX(127.0) * (1 shl 17)).toInt())
        assertEquals(0, TileMath.tmsRow(1, 1)) // z1: 위 줄(y 0)이 TMS 1
        assertEquals(1, TileMath.tmsRow(0, 1))
        assertEquals(7, TileMath.tmsRow(TileMath.tmsRow(7, 5), 5)) // 두 번 뒤집으면 제자리
    }

    // 합성 좌표(실제 전주 아님): 가로로 긴 두 점
    private val pts = listOf(GeoPoint(36.0, 127.0), GeoPoint(36.002, 127.01))

    @Test
    fun `범위를 여백 안 가운데에 맞추고 긴 쪽이 여백까지 찬다`() {
        val v = TileMath.ThumbView.fit(pts, 320, 200, 25, 150.0)!!
        val xs = pts.map { v.x(it.lon) }
        val ys = pts.map { v.y(it.lat) }
        assertEquals(25.0, xs.min(), 1e-6) // 가로가 길어 좌우 여백에 닿는다
        assertEquals(295.0, xs.max(), 1e-6)
        assertEquals(100.0, (ys.min() + ys.max()) / 2, 1e-6) // 세로는 가운데
        assertTrue(ys.min() > 25 && ys.max() < 175)
        assertTrue(ys[0] > ys[1]) // 북쪽이 위
    }

    @Test
    fun `점 하나는 최소 범위로 넓혀 가운데`() {
        val v = TileMath.ThumbView.fit(listOf(GeoPoint(36.0, 127.0)), 200, 200, 20, 150.0)!!
        assertEquals(100.0, v.x(127.0), 1e-6)
        assertEquals(100.0, v.y(36.0), 1e-6)
        // 160px가 150m: 세계 한 바퀴 픽셀 = 160 / (150 / (둘레 · cos 36°))
        val expected = 160 / (150 / (40_075_016.686 * kotlin.math.cos(Math.toRadians(36.0))))
        assertTrue(abs(v.scale - expected) / expected < 1e-9)
        assertNull(TileMath.ThumbView.fit(emptyList(), 200, 200, 20, 150.0))
    }

    @Test
    fun `확대는 타일이 그림에서 256px 이하가 되는 가장 작은 단계`() {
        assertEquals(15, TileMath.ThumbView(256.0 * (1 shl 15), 0.0, 0.0).zoom)
        assertEquals(16, TileMath.ThumbView(256.0 * (1 shl 15) * 1.01, 0.0, 0.0).zoom)
        val v = TileMath.ThumbView(256.0 * (1 shl 15) * 1.5, 0.0, 0.0)
        assertEquals(256.0 * 1.5 / 2, v.tileSize(16), 1e-9) // 한 단계 위 타일은 192px로 줄여 그림
    }

    @Test
    fun `타일 범위가 그림을 빈틈없이 덮고 놓는 자리가 메르카토르와 같다`() {
        val v = TileMath.ThumbView.fit(pts, 320, 200, 25, 150.0)!!
        val z = v.zoom
        val (xs, ys) = v.tiles(z, 320, 200)
        assertTrue(v.tileLeft(xs.first, z) <= 0 && v.tileLeft(xs.last, z) + v.tileSize(z) >= 320)
        assertTrue(v.tileTop(ys.first, z) <= 0 && v.tileTop(ys.last, z) + v.tileSize(z) >= 200)
        // 점이 든 타일의 칸 안에 점이 있다
        val p = pts[0]
        val tx = TileMath.tileX(p.lon, z)
        val ty = TileMath.tileY(p.lat, z)
        assertTrue(v.x(p.lon) in v.tileLeft(tx, z)..v.tileLeft(tx, z) + v.tileSize(z))
        assertTrue(v.y(p.lat) in v.tileTop(ty, z)..v.tileTop(ty, z) + v.tileSize(z))
        assertTrue(v.tileSize(z) > 128 && v.tileSize(z) <= 256)
    }

    @Test
    fun `빈 타일은 자기 자신, 그다음 조상 타일의 그 칸으로 메운다`() {
        val f = TileMath.fallbacks(13, 6, 4, 2)
        assertEquals(3, f.size) // z4, z3, z2
        assertEquals(TileMath.TileCrop(4, 13, 6, 0.0, 0.0, 1.0), f[0])
        // z3 조상 (6, 3): 13 = 2·6 + 1 → 오른쪽 반, 6 = 2·3 + 0 → 위쪽 반
        assertEquals(TileMath.TileCrop(3, 6, 3, 0.5, 0.0, 0.5), f[1])
        // z2 조상 (3, 1): 13 = 4·3 + 1 → 왼쪽에서 두 번째 1/4 칸, 6 = 4·1 + 2 → 위에서 세 번째
        assertEquals(TileMath.TileCrop(2, 3, 1, 0.25, 0.5, 0.25), f[2])
        // 조상 칸은 메르카토르로 같은 자리: 칸의 왼쪽 위 = 원래 타일의 왼쪽 위
        f.forEach { c ->
            val n = (1 shl c.z).toDouble()
            assertEquals(13.0 / 16, (c.x + c.left) / n, 1e-12)
            assertEquals(6.0 / 16, (c.y + c.top) / n, 1e-12)
            assertEquals(1.0 / 16, c.size / n, 1e-12)
        }
        assertEquals(1, TileMath.fallbacks(5, 5, 3, 3).size) // 최소 확대 = 자기 확대
        assertEquals(1, TileMath.fallbacks(0, 0, 0, -2).size) // 0 밑으로는 가지 않음
    }

    @Test
    fun `확대 차례는 알맞은 것, 아래, 위 순이고 상한·타일 수를 넘으면 뺀다`() {
        val v = TileMath.ThumbView.fit(pts, 320, 200, 25, 150.0)!!
        val z = v.zoom
        assertEquals(listOf(z, z - 1, z - 2, z + 1, z + 2), v.zoomOrder(320, 200, 2, 30, 1_000))
        assertEquals(listOf(z, z - 1, z - 2), v.zoomOrder(320, 200, 2, z, 1_000)) // 상한
        val few = v.zoomOrder(320, 200, 2, 30, 4)
        assertTrue(z + 2 !in few) // 두 단계 위는 타일이 32~64px라 5×4장 이상
        assertTrue(few.all { z2 -> v.tiles(z2, 320, 200).let { (a, b) -> a.count() * b.count() <= 4 } })
    }
}
