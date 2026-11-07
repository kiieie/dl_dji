package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 계획 시간(N-04, 성능 리포트 B-TSP): 소티가 여럿이어도 TSP 시간 한도 하나 안에 끝난다. */
class RoutePlannerPerfTest {
    private val home = GeoPoint(37.5, 127.0)
    private val proj = LocalProjection(home)

    @Test
    fun `전주 150·300·1000개 계획 시간`() {
        for (n in listOf(150, 300, 1000)) {
            val rnd = Random(n)
            val poles = List(n) { Pole("P$it", proj.toGeo(Enu(rnd.nextDouble(-450.0, 450.0), rnd.nextDouble(-450.0, 450.0)))) }
            val planner = RoutePlanner(EnergyModel(EnergyParams(), FlightParams()))
            val t0 = System.nanoTime()
            val plan = planner.plan(home, poles)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("B-TSP n=$n sorties=${plan.sorties.size} ms=$ms")
            // 거리 행렬·분할 시간 여유 1초
            assertTrue("n=$n ${ms}ms", ms < Tuning.Route.TSP_TIME_LIMIT_MS + 1_000)
        }
    }
}
