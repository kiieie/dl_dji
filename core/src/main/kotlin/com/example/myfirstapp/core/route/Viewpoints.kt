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

    /**
     * 전주마다 4점(2026-10-30 사용자 요청, 4패스는 경로가 왔다 갔다 해서 바꿈): 한 전주에서 대각선 4곳을 다 찍고 다음 전주로 간다.
     * - 전주 i: 들어온 쪽(A)의 뒤 → A 앞 → 반대쪽(B) 앞 → B 뒤. 다음 전주는 B 쪽에서 시작해 거꾸로(B 뒤 → B 앞 → A 앞 → A 뒤).
     *   뒤·앞은 방문 방향 기준이다. 전선은 전주마다 앞쪽에서 한 번만 넘고, 다음 전주로는 같은 쪽을 따라 간다.
     * - 이륙 지점에서 가까운 끝·가까운 쪽부터 시작한다(고도는 전선보다 10m 이상 위, 5.6).
     * 지점 번호(`viewpoint`)는 선로 방향 θ(전원측 → 부하측) 기준 0: θ+45°(부하측 우), 1: θ+135°(전원측 우), 2: θ+225°(전원측 좌), 3: θ+315°(부하측 좌).
     * θ는 선로번호로 구한 [Pole.lineBearingDeg](4.5.6)을 먼저 쓰고, 없으면 방문 순서의 앞뒤 전주로 정한다.
     */
    private fun diagonal(poles: List<Pole>, p: FlightParams, start: GeoPoint): List<Viewpoint> {
        if (poles.isEmpty()) return emptyList()
        // 조준 높이: 설비 엑셀의 전주 길이로 구한 지상 높이(4.5), 없으면 설정값
        fun pitch(pole: Pole) = -Math.toDegrees(atan2(p.diagonalAltitudeM - aimHeight(pole, p), p.diagonalDistanceM))
        // 가까운 끝에서 출발
        val line = if (LocalProjection.distanceM(start, poles.last().position) < LocalProjection.distanceM(start, poles.first().position)) poles.reversed() else poles
        // k는 방문 방향 기준(0 앞-오른쪽, 1 뒤-오른쪽, 2 뒤-왼쪽, 3 앞-왼쪽). 실제 지점·번호는 선로번호 방향(있으면)의 가장 가까운 대각선으로 맞춘다
        fun corner(i: Int, k: Int): Viewpoint {
            val travel = lineBearing(line, i) + 45.0 + 90.0 * k
            val theta = line[i].lineBearingDeg
            val idx = if (theta == null) k else (0..3).minBy { j -> angleDiff(theta + 45.0 + 90.0 * j, travel) }
            val b = (theta ?: lineBearing(line, i)) + 45.0 + 90.0 * idx
            return Viewpoint(line[i], idx, offset(line[i].position, b, p.diagonalDistanceM), p.diagonalAltitudeM, (b + 180.0).mod(360.0), pitch(line[i]))
        }
        // 오른쪽(뒤 1, 앞 0)과 왼쪽(뒤 2, 앞 3) 중 첫 지점이 가까운 쪽부터
        val right = 1 to 0
        val left = 2 to 3
        var near = if (LocalProjection.distanceM(start, corner(0, 1).position) <= LocalProjection.distanceM(start, corner(0, 2).position)) right else left
        return line.indices.flatMap { i ->
            val far = if (near == right) left else right
            listOf(corner(i, near.first), corner(i, near.second), corner(i, far.second), corner(i, far.first)).also { near = far }
        }
    }

    /** 두 방위 사이 각(0~180°). */
    private fun angleDiff(a: Double, b: Double) = ((a - b).mod(360.0)).let { if (it > 180.0) 360.0 - it else it }

    /** 전주 머리 높이(m, 지상): 전주 길이를 알면 지상 높이, 모르면 [FlightParams.aimHeightM]. */
    fun aimHeight(pole: Pole, p: FlightParams) = pole.aboveGroundM ?: p.aimHeightM

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
