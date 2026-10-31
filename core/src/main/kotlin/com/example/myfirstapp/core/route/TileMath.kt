package com.example.myfirstapp.core.route

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** 웹 메르카토르 타일 계산(7.4 배경 지도 미리 받기). Bing(`{quadkey}`)과 VWorld WMTS(`{z}/{y}/{x}`) 주소를 함께 다룬다. */
object TileMath {
    fun tileX(lon: Double, z: Int): Int = ((lon + 180) / 360 * (1 shl z)).toInt()

    fun tileY(lat: Double, z: Int): Int = ((1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2 * (1 shl z)).toInt()

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

    /** 영역(남·북·서·동)의 타일 수(확대 [minZ]~[maxZ]). */
    fun count(south: Double, north: Double, west: Double, east: Double, minZ: Int, maxZ: Int): Long = (minZ..maxZ).sumOf { z ->
        (tileX(east, z) - tileX(west, z) + 1).toLong() * (tileY(south, z) - tileY(north, z) + 1)
    }
}
