package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan

/** 웹 메르카토르 타일 계산(7.4 배경 지도 미리 받기). Bing(`{quadkey}`)과 VWorld WMTS(`{z}/{y}/{x}`) 주소를 함께 다룬다. */
object TileMath {
    /** 타일 한 장의 픽셀(Bing·VWorld·MBTiles 모두 256). */
    const val TILE_PX = 256

    /** 적도 둘레(m, WGS84). */
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    /** 경도 → 정규화 메르카토르 x(0~1, 서쪽 끝 0). */
    fun mercX(lon: Double): Double = (lon + 180) / 360

    /** 위도 → 정규화 메르카토르 y(0~1, 북쪽 끝 0). */
    fun mercY(lat: Double): Double = (1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2

    fun tileX(lon: Double, z: Int): Int = (mercX(lon) * (1 shl z)).toInt()

    fun tileY(lat: Double, z: Int): Int = (mercY(lat) * (1 shl z)).toInt()

    /** MBTiles 행 번호: TMS라 아래부터 센다(XYZ 타일 y를 뒤집음). */
    fun tmsRow(y: Int, z: Int): Int = (1 shl z) - 1 - y

    /** Bing quadkey(타일 x, y, 확대 z). */
    fun quadkey(x: Int, y: Int, z: Int): String = buildString {
        for (i in z downTo 1) {
            val mask = 1 shl (i - 1)
            append((if (x and mask != 0) 1 else 0) + (if (y and mask != 0) 2 else 0))
        }
    }

    /** 주소 틀의 `{quadkey}`, `{z}`, `{x}`, `{y}`를 채운다. */
    fun expandUrl(template: String, x: Int, y: Int, z: Int): String =
        template.replace("{quadkey}", quadkey(x, y, z)).replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")

    /**
     * 다른 확대의 타일 한 장에서 잘라 쓸 자리(경로 그림 빈칸 메우기, 10.4.10): 확대 [z]의 타일 ([x], [y]), 그 타일 안 왼쪽·위·한 변(타일 한 변 = 1).
     * 자기 자신이면 (0, 0, 1).
     */
    data class TileCrop(val z: Int, val x: Int, val y: Int, val left: Double, val top: Double, val size: Double)

    /**
     * 확대 [z]의 타일 ([x], [y])을 대신할 차례: 자기 자신, 그다음 한 단계씩 아래 확대의 조상 타일 중 이 타일이 차지하는 1/2·1/4·… 칸(늘려 그림),
     * 확대 [minZ]까지. 미리 받은 영역 밖이라 그 확대에 타일이 없을 때 넓은 조상 타일로 덮는다.
     */
    fun fallbacks(x: Int, y: Int, z: Int, minZ: Int): List<TileCrop> = (0..(z - minZ.coerceAtLeast(0)).coerceAtLeast(0)).map { d ->
        val n = 1 shl d
        TileCrop(z - d, x shr d, y shr d, (x and (n - 1)).toDouble() / n, (y and (n - 1)).toDouble() / n, 1.0 / n)
    }

    /** 영역(남·북·서·동)의 타일 수(확대 [minZ]~[maxZ]). */
    fun count(south: Double, north: Double, west: Double, east: Double, minZ: Int, maxZ: Int): Long = (minZ..maxZ).sumOf { z ->
        (tileX(east, z) - tileX(west, z) + 1).toLong() * (tileY(south, z) - tileY(north, z) + 1)
    }

    /**
     * 경로 그림 한 장의 배치(10.4.10): 정규화 메르카토르 좌표를 그림 픽셀로. 경로 선·점과 바탕 타일이 같은 것을 써서 어긋나지 않는다.
     * [scale]은 세계 한 바퀴의 픽셀 수, ([left], [top])은 그림 왼쪽 위의 메르카토르 좌표.
     */
    data class ThumbView(val scale: Double, val left: Double, val top: Double) {
        fun x(lon: Double): Double = (mercX(lon) - left) * scale

        fun y(lat: Double): Double = (mercY(lat) - top) * scale

        /** 바탕 확대: 타일 한 장(256px)이 그림에서 256px 이하(128~256px)가 되는 가장 작은 단계. 늘리지 않고 줄여 그려 선명하다. */
        val zoom: Int get() = ceil(log2(scale / TILE_PX)).toInt().coerceAtLeast(0)

        /** 확대 [z]에서 타일 한 장의 그림 위 한 변(px). */
        fun tileSize(z: Int): Double = scale / (1 shl z)

        /** 타일 x(또는 y) 번호의 그림 위 왼쪽(또는 위) 픽셀. */
        fun tileLeft(x: Int, z: Int): Double = (x.toDouble() / (1 shl z) - left) * scale

        fun tileTop(y: Int, z: Int): Double = (y.toDouble() / (1 shl z) - top) * scale

        /** 확대 [z]에서 [w]×[h] 그림을 덮는 타일 x 범위와 y 범위(세계 밖은 뺌). */
        fun tiles(z: Int, w: Int, h: Int): Pair<IntRange, IntRange> {
            val n = 1 shl z
            fun range(from: Double, px: Int) = floor(from * n).toInt().coerceIn(0, n - 1)..floor((from + px / scale) * n).toInt().coerceIn(0, n - 1)
            return range(left, w) to range(top, h)
        }

        /**
         * 바탕을 찾아볼 확대 차례: [zoom]부터 아래로 [steps]단계(늘려 그림), 그다음 위로 [steps]단계(줄여 그림).
         * [maxZoom]을 넘거나 [w]×[h] 그림에 타일이 [maxTiles]장보다 많이 드는 확대는 뺀다.
         */
        fun zoomOrder(w: Int, h: Int, steps: Int, maxZoom: Int, maxTiles: Int): List<Int> =
            ((zoom downTo zoom - steps) + (zoom + 1..zoom + steps)).filter { z ->
                z in 0..maxZoom && tiles(z, w, h).let { (xs, ys) -> xs.count() * ys.count() <= maxTiles }
            }

        companion object {
            /**
             * [points]의 범위를 [w]×[h] 그림의 [pad] 안쪽 가운데에 맞춘다. 범위가 [minSpanM](m)보다 좁으면 그만큼 넓힌다(점 하나).
             * 점이 없으면 null.
             */
            fun fit(points: List<GeoPoint>, w: Int, h: Int, pad: Int, minSpanM: Double): ThumbView? {
                if (points.isEmpty()) return null
                val xs = points.map { mercX(it.lon) }
                val ys = points.map { mercY(it.lat) }
                // 위도에서 지면 거리 m가 메르카토르로 m / (둘레 · cos 위도)
                val minSpan = minSpanM / (EARTH_CIRCUMFERENCE_M * cos(points.map { it.lat }.average() * PI / 180))
                val spanX = (xs.max() - xs.min()).coerceAtLeast(minSpan)
                val spanY = (ys.max() - ys.min()).coerceAtLeast(minSpan)
                val scale = min((w - 2 * pad).coerceAtLeast(1) / spanX, (h - 2 * pad).coerceAtLeast(1) / spanY)
                return ThumbView(scale, (xs.min() + xs.max()) / 2 - w / 2.0 / scale, (ys.min() + ys.max()) / 2 - h / 2.0 / scale)
            }
        }
    }
}
