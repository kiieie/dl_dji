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

data class RoutePlan(val home: GeoPoint, val sorties: List<SortiePlan>, val unreachable: List<UnreachablePole>)

/** 경로 계획(5.1): 도달 불가 분류 → TSP → Split 소티 분할. */
class RoutePlanner(
    private val energy: EnergyModel,
    private val tsp: TspSolver = TspSolver(),
) {

    fun selectInArea(poles: List<Pole>, area: Polygon): List<Pole> = poles.filter { area.contains(it.position) }

    fun plan(home: GeoPoint, poles: List<Pole>, startBatteryPct: Double = 100.0): RoutePlan {
        val proj = LocalProjection(home)
        val origin = Enu(0.0, 0.0)
        val budget = energy.budget(startBatteryPct)

        // 1) 도달 불가 전주 분류(5.4): 가시권 밖이거나 단독 왕복도 예산을 넘는 전주
        val unreachable = mutableListOf<UnreachablePole>()
        val reachable = mutableListOf<Pole>()
        for (p in poles) {
            val dHome = proj.toEnu(p.position).distanceTo(origin)
            when {
                dHome > energy.flight.vlosRadiusM -> unreachable += UnreachablePole(p, UnreachablePole.Reason.VLOS_EXCEEDED)
                energy.sortieCost(2 * dHome, 1) > budget -> unreachable += UnreachablePole(p, UnreachablePole.Reason.BATTERY_EXCEEDED)
                else -> reachable += p
            }
        }
        if (reachable.isEmpty()) return RoutePlan(home, emptyList(), unreachable)

        // 2) 전체 순회 경로
        val points = listOf(origin) + reachable.map { proj.toEnu(it.position) }
        val order = tsp.solve(points).map { it - 1 }
        val ordered = order.map { reachable[it] }
        val enu = order.map { points[it + 1] }

        // 3) Split(동적 계획법): V[j] = min_i V[i-1] + cost(i, j), cost ≤ 예산
        val n = ordered.size
        val v = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }.also { it[0] = 0.0 }
        val from = IntArray(n + 1)
        for (i in 1..n) {
            if (v[i - 1].isInfinite()) continue
            var inner = 0.0
            for (j in i..n) {
                if (j > i) inner += enu[j - 2].distanceTo(enu[j - 1])
                val dist = enu[i - 1].distanceTo(origin) + inner + enu[j - 1].distanceTo(origin)
                val cost = energy.sortieCost(dist, j - i + 1)
                if (cost > budget) break // 전주를 더할수록 비용이 줄지 않으므로(삼각 부등식) 중단
                if (v[i - 1] + cost < v[j]) {
                    v[j] = v[i - 1] + cost
                    from[j] = i
                }
            }
        }

        val sorties = ArrayDeque<SortiePlan>()
        var j = n
        while (j > 0) {
            val i = from[j]
            val (seq, dist) = reoptimize((i - 1 until j).toList(), enu, origin)
            val count = j - i + 1
            sorties.addFirst(
                SortiePlan(
                    poles = seq.map { ordered[it] },
                    estDistanceM = dist,
                    estBatteryPct = energy.sortieCost(dist, count),
                    estDurationSec = energy.sortieDurationSec(dist, count),
                ),
            )
            j = i - 1
        }
        return RoutePlan(home, sorties.toList(), unreachable)
    }

    /** 소티 재최적화(5.4): 이륙 지점 + 소티 전주로 TSP를 다시 풀어 더 짧으면 그 순서를 쓴다. (순서, 닫힌 경로 길이)를 돌려준다. */
    private fun reoptimize(seg: List<Int>, enu: List<Enu>, origin: Enu): Pair<List<Int>, Double> {
        fun length(s: List<Int>) = (listOf(origin) + s.map { enu[it] } + origin).zipWithNext { a, b -> a.distanceTo(b) }.sum()
        val solved = tsp.solve(listOf(origin) + seg.map { enu[it] }).map { seg[it - 1] }
        return listOf(seg, solved).map { it to length(it) }.minBy { it.second }
    }
}
