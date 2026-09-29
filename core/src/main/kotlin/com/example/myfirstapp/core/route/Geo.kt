package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
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
        /** 두 대각 꼭짓점으로 사각형을 만든다. */
        fun rectangle(a: GeoPoint, b: GeoPoint) = Polygon(
            listOf(a, GeoPoint(a.lat, b.lon), b, GeoPoint(b.lat, a.lon)),
        )
    }
}
