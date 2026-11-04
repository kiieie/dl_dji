package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 가져온 경로의 웨이포인트(5.7). [heightM]은 파일의 높이(WPML `executeHeight`·`height`, KML 고도), [gimbalPitchDeg]는 WPML `gimbalPitchAngle`,
 * [actions]는 WPML 동작 이름(`takePhoto`, `gimbalRotate` 등) 차례. 모르면 null·빈 목록.
 */
data class RouteWaypoint(val position: GeoPoint, val heightM: Double? = null, val gimbalPitchDeg: Double? = null, val actions: List<String> = emptyList()) {
    /** WPML 사진 동작이 있는 지점. */
    val photo get() = actions.any { it in RouteImport.PHOTO_ACTIONS }
}

/** 파일에서 읽은 경로: 웨이포인트(파일 차례)와 WPML 이륙 기준점(`takeOffRefPoint`, 없으면 null). */
data class ImportedRoute(val waypoints: List<RouteWaypoint>, val takeoff: GeoPoint? = null)

/** 경로를 전주에 맞춘 결과: [poles]는 처음 맞은 차례(겹치면 한 번), [unmatched]는 가까운 전주가 없는 웨이포인트, [used]는 맞춰 본 웨이포인트 수. */
data class RouteMatch(val poles: List<Pole>, val unmatched: List<RouteWaypoint>, val used: Int)

/**
 * Pilot 2·DJI 경로 가져오기(5.7, 10.5.8 라이브러리 `+` 경로 가져오기): KMZ(`wpmz/waylines.wpml`, 없으면 `wpmz/template.kml`, 그도 없으면 `*.kml`)나
 * KML에서 웨이포인트의 위치·높이·짐벌 피치·동작을 읽고, 웨이포인트마다 가까운 전주에 맞춘다. 비행에는 위치(전주 차례)만 쓰고
 * 높이·짐벌·동작·속도·요는 버린다(우리 촬영 방식 5.6으로 다시 만든다). XML 파서 대신 정규식으로 읽어 외부 엔티티(XXE)를 따르지 않는다.
 */
object RouteImport {
    /** 사진 동작(WPML 1.0.x). 하나라도 있으면 사진 지점만 전주에 맞춘다(나머지는 이동 지점). */
    val PHOTO_ACTIONS = setOf("takePhoto", "orientedShoot", "panoShot")

    private val PLACEMARK = Regex("<Placemark\\b[^>]*>(.*?)</Placemark>", RegexOption.DOT_MATCHES_ALL)
    private val COORDS = Regex("<coordinates>(.*?)</coordinates>", RegexOption.DOT_MATCHES_ALL)
    private val FUNC = Regex("<wpml:actionActuatorFunc>\\s*(\\w+)\\s*</wpml:actionActuatorFunc>")
    private val TAKEOFF = Regex("<wpml:takeOffRefPoint>\\s*([^<]+)</wpml:takeOffRefPoint>")
    private val SPACE = Regex("\\s+")

    /** KMZ(zip) 또는 KML 바이트를 읽는다. zip 안 파일 하나가 [maxBytes]를 넘으면 거절한다(압축 폭탄). */
    fun read(bytes: ByteArray, maxBytes: Long): ImportedRoute {
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return parse(String(bytes, Charsets.UTF_8))
        val texts = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("kml", "wpml") }.forEach { e ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    require(out.size() <= maxBytes) { "${e.name}가 너무 큽니다" }
                }
                texts[e.name] = out.toString(Charsets.UTF_8.name())
            }
        }
        val main = texts["wpmz/waylines.wpml"] ?: texts["wpmz/template.kml"] ?: texts.values.firstOrNull() ?: error("KMZ 안에 경로(kml·wpml)가 없습니다")
        // 이륙 기준점은 template.kml의 missionConfig에 있다
        return parse(main).let { r -> r.copy(takeoff = r.takeoff ?: texts.values.firstNotNullOfOrNull(::takeoff)) }
    }

    /** KML·WPML 글에서 Placemark 차례로 웨이포인트를 읽는다. 선(LineString)은 꼭짓점마다, 면(Polygon)은 건너뛴다. */
    fun parse(text: String): ImportedRoute {
        val wps = PLACEMARK.findAll(text).flatMap { m ->
            val body = m.groupValues[1]
            if ("<Polygon" in body) return@flatMap emptySequence()
            val points = COORDS.findAll(body).flatMap { c -> c.groupValues[1].trim().split(SPACE).asSequence() }.mapNotNull(::tuple).toList()
            if (points.size == 1) {
                val (at, alt) = points[0]
                sequenceOf(RouteWaypoint(at, num(body, "executeHeight") ?: num(body, "height") ?: alt, num(body, "gimbalPitchAngle"), FUNC.findAll(body).map { it.groupValues[1] }.toList()))
            } else points.asSequence().map { (at, alt) -> RouteWaypoint(at, alt) }
        }.toList()
        return ImportedRoute(wps, takeoff(text))
    }

    /**
     * 웨이포인트를 [radiusM] 안의 가장 가까운 전주에 맞춘다. 사진 동작이 있는 경로는 사진 지점만, 없으면(현장 KMZ처럼 손·타이머 촬영) 모든 지점을 본다.
     * 사선 경로는 한 전주에 지점이 여럿이라 전주는 처음 맞은 차례로 한 번만 넣는다.
     */
    fun match(route: ImportedRoute, poles: List<Pole>, radiusM: Double): RouteMatch {
        val used = route.waypoints.filter { it.photo }.ifEmpty { route.waypoints }
        val hits = used.map { w -> w to nearestWithin(w.position, poles, radiusM) { it.position } }
        return RouteMatch(hits.mapNotNull { it.second }.distinct(), hits.filter { it.second == null }.map { it.first }, used.size)
    }

    /** "경도,위도[,고도]" 하나. 숫자가 아니거나 범위 밖이면 null. */
    private fun tuple(t: String): Pair<GeoPoint, Double?>? {
        val v = t.split(',').map { it.trim().toDoubleOrNull() }
        val lon = v.getOrNull(0) ?: return null
        val lat = v.getOrNull(1) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon) to v.getOrNull(2)
    }

    private fun num(body: String, tag: String) = Regex("<wpml:$tag>\\s*([^<]+)</wpml:$tag>").find(body)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()

    /** WPML `takeOffRefPoint`는 "위도,경도,고도"(좌표와 차례가 반대). */
    private fun takeoff(text: String) = TAKEOFF.find(text)?.groupValues?.get(1)?.split(',')?.map { it.trim().toDoubleOrNull() }?.let { v ->
        val lat = v.getOrNull(0) ?: return@let null
        val lon = v.getOrNull(1) ?: return@let null
        GeoPoint(lat, lon).takeIf { lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0) }
    }
}
