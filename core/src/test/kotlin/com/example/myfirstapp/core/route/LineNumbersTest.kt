package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LineNumbersTest {

    private val home = GeoPoint(36.47, 127.28)
    private fun at(e: Double, n: Double) = LocalProjection(home).toGeo(Enu(e, n))
    private fun pole(no: String, e: Double, n: Double) = Pole("ID$no", at(e, n), "대평선", lineNo = no)

    @Test
    fun `앞(전원측)과 뒤(부하측) 번호`() {
        assertEquals("112", LineNumbers.previous("113"))
        assertNull(LineNumbers.previous("1"))
        assertEquals("113R9", LineNumbers.previous("113R10"))
        assertEquals("113R10", LineNumbers.previous("113R10L1")) // 분기 1번의 앞은 분기 시작 전주
        assertEquals("113R10L2", LineNumbers.next("113R10L1"))
        assertEquals("114", LineNumbers.next("113"))
        assertNull(LineNumbers.previous("가나"))
    }

    @Test
    fun `선로 방위는 전원측에서 부하측으로, 멀리 떨어진 번호는 잇지 않는다`() {
        // 본선 1→2→3이 동쪽으로, 2에서 북쪽으로 분기 2R1→2R2
        val all = listOf(pole("1", 0.0, 0.0), pole("2", 40.0, 0.0), pole("3", 80.0, 0.0), pole("2R1", 40.0, 40.0), pole("2R2", 40.0, 80.0), pole("4", 900.0, 0.0))
        val b = LineNumbers.bearings(all)
        assertEquals(90.0, b.getValue("ID2"), 0.5)
        assertEquals(0.0, b.getValue("ID2R1").let { if (it > 180) it - 360 else it }, 0.5) // 분기는 북쪽
        assertEquals(90.0, b.getValue("ID3"), 0.5) // 4는 820m 떨어져 이웃이 아님 → 2→3 방향
        assertNull(b["ID4"]) // 이웃 없음
    }

    @Test
    fun `4방향 사선 번호는 선로번호 방향 기준이고, 방문이 거꾸로여도 전선은 전주마다 한 번만 넘는다`() {
        val all = (1..4).map { pole("$it", 40.0 * it, 0.0) }
        val withB = LineNumbers.withBearings(all, all)
        // 이륙 지점이 동쪽 끝이라 방문은 부하측 → 전원측(거꾸로)
        val start = at(400.0, -30.0)
        val v = Viewpoints.expand(withB, FlightParams(shotMode = ShotMode.DIAGONAL4), start)
        assertEquals(16, v.size)
        // 전선(y=0)은 전주마다 한 번만 넘고, 전주 사이 이동에서는 넘지 않는다
        val side = v.map { Math.signum(LocalProjection(home).toEnu(it.position).y) }
        assertEquals(4, side.zipWithNext().count { (a, b) -> a != b })
        assertTrue((1..3).all { side[4 * it - 1] == side[4 * it] })
        // 번호는 동쪽(부하측) 기준: 남쪽-동쪽 대각선 = θ(90°)+45° = 부하측 우(0)
        v.forEach { vp ->
            val enu = LocalProjection(vp.pole.position).toEnu(vp.position)
            val expected = when { enu.x > 0 && enu.y < 0 -> 0; enu.x < 0 && enu.y < 0 -> 1; enu.x < 0 && enu.y > 0 -> 2; else -> 3 }
            assertEquals(expected, vp.index)
            assertTrue(abs(enu.x) > 1 && abs(enu.y) > 1)
        }
        assertEquals("부하측 우", LineNumbers.SIDE_NAMES[0])
    }
}
