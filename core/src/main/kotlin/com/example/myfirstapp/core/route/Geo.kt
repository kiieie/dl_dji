package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** 로컬 ENU 평면의 점(미터). x는 동쪽, y는 북쪽. */
data class Enu(val x: Double, val y: Double) {
    fun distanceTo(o: Enu): Double = hypot(x - o.x, y - o.y)
}

/**
 * 이륙 지점을 원점으로 하는 등거리(equirectangular) 근사 투영(5.2).
 * 수 km 범위에서는 오차를 무시할 수 있다.
 */
class LocalProjection(val origin: GeoPoint) {
    private val cosLat = cos(Math.toRadians(origin.lat))

    fun toEnu(p: GeoPoint): Enu = Enu(
        x = Math.toRadians(p.lon - origin.lon) * EARTH_RADIUS_M * cosLat,
        y = Math.toRadians(p.lat - origin.lat) * EARTH_RADIUS_M,
    )

    fun toGeo(e: Enu): GeoPoint = GeoPoint(
        lat = origin.lat + Math.toDegrees(e.y / EARTH_RADIUS_M),
        lon = origin.lon + Math.toDegrees(e.x / (EARTH_RADIUS_M * cosLat)),
    )

    companion object {
        const val EARTH_RADIUS_M = 6_371_008.8

        fun distanceM(a: GeoPoint, b: GeoPoint): Double = LocalProjection(a).toEnu(b).distanceTo(Enu(0.0, 0.0))
    }
}

/** 점검 영역 다각형. 사각형도 꼭짓점 4개인 다각형으로 다룬다(5.2). */
class Polygon(val vertices: List<GeoPoint>) {
    init {
        require(vertices.size >= 3) { "다각형은 꼭짓점이 3개 이상이어야 합니다." }
    }

    /**
     * Ray casting 포함 판정. 등거리 투영은 위경도에 대한 선형 변환이므로 위경도에서 바로 계산해도 결과가 같다.
     * 경계 위의 점은 어느 쪽으로든 판정될 수 있다.
     */
    fun contains(p: GeoPoint): Boolean {
        var inside = false
        var j = vertices.lastIndex
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        /**
         * 누른 순서 때문에 변이 서로 꼬이면(예: 사각형 모서리를 대각선 순서로 누름) 중심 둘레 각도 순으로 다시 놓는다.
         * 꼬이지 않은 다각형은 오목해도 그대로 둔다.
         */
        fun untangle(vertices: List<GeoPoint>): List<GeoPoint> {
            if (vertices.size < 4 || !selfIntersects(vertices)) return vertices
            val lat = vertices.sumOf { it.lat } / vertices.size
            val lon = vertices.sumOf { it.lon } / vertices.size
            return vertices.sortedBy { atan2(it.lat - lat, it.lon - lon) }
        }

        private fun selfIntersects(v: List<GeoPoint>): Boolean {
            val n = v.size
            fun cross(o: GeoPoint, a: GeoPoint, b: GeoPoint) = (a.lon - o.lon) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lon - o.lon)
            fun crosses(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint) =
                cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0
            for (i in 0 until n) for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue // 이웃한 변
                if (crosses(v[i], v[(i + 1) % n], v[j], v[(j + 1) % n])) return true
            }
            return false
        }

        /** 두 대각 꼭짓점으로 사각형을 만든다. */
        fun rectangle(a: GeoPoint, b: GeoPoint) = Polygon(
            listOf(a, GeoPoint(a.lat, b.lon), b, GeoPoint(b.lat, a.lon)),
        )
    }
}
