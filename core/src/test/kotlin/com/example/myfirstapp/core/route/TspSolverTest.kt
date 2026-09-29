package com.example.myfirstapp.core.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TspSolverTest {

    private fun tourLength(points: List<Enu>, order: List<Int>): Double {
        val seq = listOf(0) + order + 0
        return seq.zipWithNext { a, b -> points[a].distanceTo(points[b]) }.sum()
    }

    @Test
    fun `모든 점을 한 번씩 방문한다`() {
        val pts = List(50) { Enu(Random(it).nextDouble(-500.0, 500.0), Random(it + 100).nextDouble(-500.0, 500.0)) }
        val order = TspSolver().solve(pts)
        assertEquals((1 until 50).toSet(), order.toSet())
        assertEquals(49, order.size)
    }

    @Test
    fun `원 위의 점은 최적 순회(원 둘레 근사)를 찾는다`() {
        val rnd = Random(7)
        val ring = List(30) { i -> Enu(300 * cos(2 * PI * i / 30), 300 * sin(2 * PI * i / 30)) }.shuffled(rnd)
        val pts = listOf(ring.first()) + ring.drop(1)
        val optimal = 30 * 2 * 300 * sin(PI / 30)
        assertEquals(optimal, tourLength(pts, TspSolver().solve(pts)), 1e-6)
    }

    @Test
    fun `작은 문제에서 완전 탐색 대비 5퍼센트 이내`() {
        repeat(20) { seed ->
            val rnd = Random(seed)
            val pts = List(8) { Enu(rnd.nextDouble(0.0, 1000.0), rnd.nextDouble(0.0, 1000.0)) }
            val best = permutations((1 until 8).toList()).minOf { tourLength(pts, it) }
            val got = tourLength(pts, TspSolver().solve(pts))
            assertTrue("seed=$seed got=$got best=$best", got <= best * 1.05 + 1e-9)
        }
    }

    @Test
    fun `전주 300개를 3초 이내에 계산한다(N-04)`() {
        val rnd = Random(1)
        val pts = List(301) { Enu(rnd.nextDouble(-3000.0, 3000.0), rnd.nextDouble(-3000.0, 3000.0)) }
        val start = System.nanoTime()
        TspSolver().solve(pts)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("${ms}ms", ms < 3_000)
    }

    private fun permutations(list: List<Int>): Sequence<List<Int>> = sequence {
        if (list.size <= 1) {
            yield(list)
        } else {
            for (i in list.indices) {
                val rest = list.toMutableList().also { it.removeAt(i) }
                for (p in permutations(rest)) yield(listOf(list[i]) + p)
            }
        }
    }
}
