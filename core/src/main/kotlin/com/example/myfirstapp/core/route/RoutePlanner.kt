package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole

/** 소티 하나의 계획. [poles]는 방문 순서대로다. */
data class SortiePlan(
    val poles: List<Pole>,
    val estDistanceM: Double,
    val estBatteryPct: Double,
    val estDurationSec: Double,
)

data class UnreachablePole(val pole: Pole, val reason: Reason) {
    enum class Reason { VLOS_EXCEEDED, BATTERY_EXCEEDED }
}

data class RoutePlan(val home: GeoPoint, val sorties: List<SortiePlan>, val unreachable: List<UnreachablePole>) {
    /** 소티 [i]의 방문 순서를 뒤집는다(Pilot 2 경로 역전, 5.7). 거리·배터리·시간은 [energy]로 다시 센다(왕복 경로라 같지만 지점 배치가 달라질 수 있음). */
    fun reverse(i: Int, energy: EnergyModel) = copy(sorties = sorties.mapIndexed { j, s -> if (j == i) energy.sortiePlan(home, s.poles.reversed()) else s })
}

/** 경로 계획(5.1): 도달 불가 분류 → TSP → Split 소티 분할. */
class RoutePlanner(
    private val energy: EnergyModel,
    private val tsp: TspSolver = TspSolver(),
) {

    fun selectInArea(poles: List<Pole>, area: Polygon): List<Pole> = poles.filter { area.contains(it.position) }

    /**
     * [keepOrder]면 [poles] 순서 그대로 나눈다(가져온 Pilot 2 경로, 5.7): TSP·소티 재최적화·끝 맞추기를 하지 않는다.
     * 아니면 소티마다 이륙 지점에서 가까운 끝부터 돈다(5.4, 경로 역전으로 바꿀 수 있음).
     */
    fun plan(home: GeoPoint, poles: List<Pole>, startBatteryPct: Double = 100.0, keepOrder: Boolean = false): RoutePlan {
        val proj = LocalProjection(home)
        val origin = Enu(0.0, 0.0)
        val budget = energy.budget(startBatteryPct)
        // 전체 순회는 한도의 절반, 소티별 재최적화는 나머지를 나눠 쓴다(소티마다 따로 쓰면 최악 (1 + 소티 수) × 한도, 성능 2026-11-07)
        val start = System.nanoTime()
        val deadline = start + tsp.timeLimitMs * 1_000_000

        // 1) 도달 불가 전주 분류(5.4): 가시권 밖이거나 단독 소티도 예산을 넘는 전주. 단독 소티 비용은 분할(3)과 같은 셈이다
        //    (이륙 지점 왕복 + 사선 전주 둘레 + 설비 조준 촬영). 왕복만 보면 혼자서도 예산을 넘는 소티가 계획되었다(11-04 리뷰).
        val unreachable = mutableListOf<UnreachablePole>()
        val reachable = mutableListOf<Pole>()
        for (p in poles) {
            val dHome = proj.toEnu(p.position).distanceTo(origin)
            when {
                dHome > energy.flight.vlosRadiusM -> unreachable += UnreachablePole(p, UnreachablePole.Reason.VLOS_EXCEEDED)
                energy.sortieCost(energy.pathM(dHome + dHome, 0.0, 1), 1, 0, energy.equipmentShots(listOf(p))) > budget ->
                    unreachable += UnreachablePole(p, UnreachablePole.Reason.BATTERY_EXCEEDED)
                else -> reachable += p
            }
        }
        if (reachable.isEmpty()) return RoutePlan(home, emptyList(), unreachable)

        // 2) 전체 순회 경로
        val points = listOf(origin) + reachable.map { proj.toEnu(it.position) }
        val order = if (keepOrder) reachable.indices.toList() else tsp.solve(points, start + tsp.timeLimitMs * 500_000).map { it - 1 }
        val ordered = order.map { reachable[it] }
        val enu = order.map { points[it + 1] }

        // 3) Split(동적 계획법, 2026-11-04 균형): 소티 수를 가장 적게 하고, 그 수 안에서 소티 비용 제곱합이 가장 작게(고르게) 나눈다.
        //    비용 합 최소만 보면 이륙 지점 옆 전주 1개를 따로 떼는 1 + 25 같은 분할이 나왔다(11-03 현장).
        val n = ordered.size
        // 설비 조준 추가 촬영 누적(앞에서 k개 전주까지)
        val extra = IntArray(n + 1).also { e -> for (k in 1..n) e[k] = e[k - 1] + energy.equipmentShots(listOf(ordered[k - 1])) }
        // cost[i][j]: i..j번째 전주(1부터)를 한 소티로 돌 때 비용, 예산을 넘으면 없음
        val cost = Array(n + 1) { DoubleArray(n + 1) { Double.POSITIVE_INFINITY } }
        for (i in 1..n) {
            var inner = 0.0
            var jumps = 0
            for (j in i..n) {
                if (j > i) {
                    val d = enu[j - 2].distanceTo(enu[j - 1])
                    inner += d
                    if (d > energy.flight.transitLegThresholdM) jumps++
                }
                val dist = energy.pathM(enu[i - 1].distanceTo(origin) + enu[j - 1].distanceTo(origin), inner, j - i + 1)
                val c = energy.sortieCost(dist, j - i + 1, jumps, extra[j] - extra[i - 1])
                // 전주를 더할수록 비용이 줄지 않으므로(삼각 부등식) 중단. 전주 1개 소티는 도달 불가 판정과 같은 셈이라 늘 예산 안이다(j > i는 분할이 끊기지 않게 하는 안전장치).
                if (c > budget && j > i) break
                cost[i][j] = c
            }
        }
        // v[j]: 소티 s개로 앞 j개 전주를 돌 때 비용 제곱합의 최소. s를 하나씩 늘려 v[n]이 처음 생기면 멈춘다.
        var v = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }.also { it[0] = 0.0 }
        val from = mutableListOf<IntArray>()
        while (v[n].isInfinite()) {
            val next = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val back = IntArray(n + 1)
            for (j in 1..n) for (i in 1..j) {
                val c = cost[i][j]
                if (c.isInfinite() || v[i - 1].isInfinite()) continue
                if (v[i - 1] + c * c < next[j]) {
                    next[j] = v[i - 1] + c * c
                    back[j] = i
                }
            }
            from += back
            v = next
        }

        val sorties = ArrayDeque<SortiePlan>()
        var j = n
        for (back in from.asReversed()) {
            val i = back[j]
            val seg = (i - 1 until j).toList()
            val (seq, dist) = if (keepOrder) seg to length(seg, enu, origin) else reoptimize(seg, enu, origin, deadline)
            val count = j - i + 1
            val jumps = energy.longJumps(seq.zipWithNext { a, b -> enu[a].distanceTo(enu[b]) })
            val shots = extra[j] - extra[i - 1]
            sorties.addFirst(
                SortiePlan(
                    poles = seq.map { ordered[it] },
                    estDistanceM = dist,
                    estBatteryPct = energy.sortieCost(dist, count, jumps, shots),
                    estDurationSec = energy.sortieDurationSec(dist, count, jumps, shots),
                ),
            )
            j = i - 1
        }
        return RoutePlan(home, sorties.toList(), unreachable)
    }

    /** 경로 길이 [EnergyModel.pathM]: 이륙 지점 왕복 + 전주 사이 + 전주 둘레. */
    private fun length(s: List<Int>, enu: List<Enu>, origin: Enu) = energy.pathM(
        enu[s.first()].distanceTo(origin) + enu[s.last()].distanceTo(origin),
        s.zipWithNext { a, b -> enu[a].distanceTo(enu[b]) }.sum(),
        s.size,
    )

    /**
     * 소티 재최적화(5.4): 이륙 지점 + 소티 전주로 TSP를 다시 풀어 더 짧으면 그 순서를 쓴다. (순서, 경로 길이)를 돌려준다.
     * 왕복 경로라 방향은 길이와 상관없으므로 이륙 지점에서 가까운 끝부터 돌게 맞춘다(전에는 사선 지점 펼치기가 했음, 5.7).
     */
    private fun reoptimize(seg: List<Int>, enu: List<Enu>, origin: Enu, deadline: Long): Pair<List<Int>, Double> {
        val solved = tsp.solve(listOf(origin) + seg.map { enu[it] }, deadline).map { seg[it - 1] }
        val (best, dist) = listOf(seg, solved).map { it to length(it, enu, origin) }.minBy { it.second }
        return (if (enu[best.last()].distanceTo(origin) < enu[best.first()].distanceTo(origin)) best.reversed() else best) to dist
    }
}
