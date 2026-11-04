package com.example.myfirstapp.core.model

/**
 * 지도 검색의 좌표(10.5.7, 주소 검색 대신 오프라인): "36.48, 127.33" · "36.48 127.33" · "36.48,127.33"(위도, 경도, WGS84 도).
 * 경도를 먼저 쓴 것("127.33, 36.48")은 첫 수가 위도 범위 밖일 때만 바꿔 읽는다. 숫자가 둘이 아니거나 범위 밖이면 null.
 */
fun parseCoordinate(text: String): GeoPoint? {
    val nums = text.trim().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
    if (nums.size != 2) return null
    val (a, b) = nums.map { it.toDoubleOrNull() ?: return null }
    val (lat, lon) = if (a !in -90.0..90.0 && b in -90.0..90.0) b to a else a to b
    return GeoPoint(lat, lon).takeIf { lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0) }
}
