package com.example.myfirstapp.core.route

/**
 * 방문 순서 근사(5.3): 최근접 이웃 초기해 → 2-opt·Or-opt 반복 개선.
 * 0번 점(이륙 지점)에서 출발해 0번으로 돌아오는 순회를 최소화한다.
 */
class TspSolver(private val timeLimitMs: Long = 2_000) {

    /** [points]의 0번은 이륙 지점이다. 0번을 뺀 방문 순서(점 번호)를 돌려준다. */
    fun solve(points: List<Enu>): List<Int> {
        val n = points.size
        if (n <= 1) return emptyList()
        val d = Array(n) { i -> DoubleArray(n) { j -> points[i].distanceTo(points[j]) } }
        val deadline = System.nanoTime() + timeLimitMs * 1_000_000
        // tour[0] = 0(이륙 지점), 끝에서 0으로 돌아온다.
        val tour = nearestNeighbor(d)
        var improved = true
        while (improved && System.nanoTime() < deadline) {
            improved = twoOpt(tour, d, deadline)
            improved = orOpt(tour, d) || improved
        }
        return tour.drop(1)
    }

    private fun nearestNeighbor(d: Array<DoubleArray>): MutableList<Int> {
        val n = d.size
        val visited = BooleanArray(n).also { it[0] = true }
        val tour = mutableListOf(0)
        repeat(n - 1) {
            val last = tour.last()
            var best = -1
            for (j in 0 until n) if (!visited[j] && (best < 0 || d[last][j] < d[last][best])) best = j
            visited[best] = true
            tour += best
        }
        return tour
    }

    private fun next(tour: List<Int>, i: Int) = if (i + 1 < tour.size) tour[i + 1] else tour[0]

    private fun twoOpt(tour: MutableList<Int>, d: Array<DoubleArray>, deadline: Long): Boolean {
        var any = false
        var improved = true
        while (improved && System.nanoTime() < deadline) {
            improved = false
            for (i in 1 until tour.size - 1) {
                for (j in i + 1 until tour.size) {
                    val a = tour[i - 1]
                    val b = tour[i]
                    val c = tour[j]
                    val e = next(tour, j)
                    val delta = d[a][c] + d[b][e] - d[a][b] - d[c][e]
                    if (delta < -EPS) {
                        tour.subList(i, j + 1).reverse()
                        improved = true
                        any = true
                    }
                }
            }
        }
        return any
    }

    /** 길이 1~3인 연속 구간을 다른 위치로 옮긴다(방향 유지). */
    private fun orOpt(tour: MutableList<Int>, d: Array<DoubleArray>): Boolean {
        var any = false
        for (len in 1..3) {
            var i = 1
            while (i + len <= tour.size) {
                val prev = tour[i - 1]
                val first = tour[i]
                val last = tour[i + len - 1]
                val after = next(tour, i + len - 1)
                val removeGain = d[prev][first] + d[last][after] - d[prev][after]
                var bestK = -1
                var bestAdd = removeGain - EPS
                for (k in tour.indices) {
                    if (k in (i - 1) until (i + len)) continue
                    val u = tour[k]
                    val v = next(tour, k)
                    val add = d[u][first] + d[last][v] - d[u][v]
                    if (add < bestAdd) { bestAdd = add; bestK = k }
                }
                if (bestK >= 0) {
                    val segment = tour.subList(i, i + len).toList()
                    repeat(len) { tour.removeAt(i) }
                    val insertAt = (if (bestK > i) bestK - len else bestK) + 1
                    tour.addAll(insertAt, segment)
                    any = true
                } else {
                    i++
                }
            }
        }
        return any
    }

    private companion object {
        const val EPS = 1e-9
    }
}
