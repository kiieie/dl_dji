package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 촬영 지점 하나(5.6). [index]는 `CAPTURE.viewpoint`: 전주 위는 0, 4방향 사선은 선로 방향 기준 45°부터 시계 방향 0~3.
 * [headingDeg]는 기수(정북 0°, 시계 방향 0~360), [altitudeM]은 이륙 지점 기준이다.
 */
data class Viewpoint(
    val pole: Pole,
    val index: Int,
    val position: GeoPoint,
    val altitudeM: Double,
    val headingDeg: Double,
    val gimbalPitchDeg: Double,
)

/** 방문 순서대로 놓인 전주를 촬영 방식(5.6)에 따라 촬영 지점으로 펼친다. */
object Viewpoints {
    fun expand(poles: List<Pole>, params: FlightParams, start: GeoPoint): List<Viewpoint> = when (params.shotMode) {
        ShotMode.OVERHEAD -> overhead(poles, params, start)
        ShotMode.DIAGONAL4 -> diagonal(poles, params, start)
    }

    /** 전주 상공 1곳(오프셋이 있으면 옮김). 기수는 다음 전주 쪽, 짐벌은 설정값. */
    private fun overhead(poles: List<Pole>, p: FlightParams, start: GeoPoint): List<Viewpoint> {
        val spots = poles.map { offset(it.position, p.offsetBearingDeg, p.offsetDistanceM) }
        return poles.mapIndexed { i, pole ->
            val from = spots.getOrNull(i - 1) ?: start
            val to = spots.getOrNull(i + 1)
            val heading = if (to != null) bearing(spots[i], to) else bearing(from, spots[i])
            Viewpoint(pole, 0, spots[i], p.inspectAltitudeM, heading, p.gimbalPitchDeg)
        }
    }

    /** 전주마다 대각선 4곳. 들어가는 지점은 직전 지점에서 가장 가까운 곳이고, 거기서 시계 방향으로 돈다. */
    private fun diagonal(poles: List<Pole>, p: FlightParams, start: GeoPoint): List<Viewpoint> {
        val pitch = -Math.toDegrees(atan2(p.diagonalAltitudeM - p.aimHeightM, p.diagonalDistanceM))
        var last = start
        return poles.flatMapIndexed { i, pole ->
            val line = lineBearing(poles, i)
            val corners = (0 until 4).map { k ->
                val b = line + 45.0 + 90.0 * k
                val at = offset(pole.position, b, p.diagonalDistanceM)
                Viewpoint(pole, k, at, p.diagonalAltitudeM, (b + 180.0).mod(360.0), pitch)
            }
            val first = corners.indices.minBy { LocalProjection.distanceM(last, corners[it].position) }
            (0 until 4).map { corners[(first + it) % 4] }.also { last = it.last().position }
        }
    }

    /** 선로 방향: 앞뒤 전주를 이은 방위. 이웃이 하나면 그쪽, 없으면 정북. */
    private fun lineBearing(poles: List<Pole>, i: Int): Double {
        val a = poles.getOrNull(i - 1)?.position ?: poles[i].position
        val b = poles.getOrNull(i + 1)?.position ?: poles[i].position
        return if (a == b) 0.0 else bearing(a, b)
    }

    /** a → b 방위(정북 0°, 시계 방향 0~360). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val e = LocalProjection(a).toEnu(b)
        return Math.toDegrees(atan2(e.x, e.y)).mod(360.0)
    }

    private fun offset(p: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        if (distanceM == 0.0) return p
        val r = Math.toRadians(bearingDeg)
        return LocalProjection(p).toGeo(Enu(distanceM * sin(r), distanceM * cos(r)))
    }
}
