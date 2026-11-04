package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.route.LocalProjection
import kotlin.math.abs

/** 지도 주석 종류(10.4.7, Pilot 2 "포인트·선·영역 주석"): 핀포인트, 선, 영역. */
enum class AnnotationKind(val title: String, val minPoints: Int) { PIN("핀포인트", 1), LINE("선", 2), AREA("영역", 3) }

/** 주석 색 5종(Pilot 2 순서: 파랑·초록·노랑·빨강·보라). [hex]는 지도와 내보내기(GeoJSON `color`)에 같이 쓴다. */
enum class AnnotationColor(val hex: String, val title: String) {
    BLUE("#2F8CF5", "파랑"), GREEN("#1EB464", "초록"), YELLOW("#F5B400", "노랑"), RED("#E8413A", "빨강"), PURPLE("#B43CF0", "보라"),
}

/**
 * 핀포인트·선·영역 주석 하나(Room `annotation`, 기기 안에만). [points]는 핀포인트 1점, 선 2점 이상, 영역 3점 이상(닫지 않은 꼭짓점).
 * [altitudeM]은 핀포인트 고도(이륙 지점 기준 ALT, 그때 기체 고도). 모르면 null.
 */
data class Annotation(
    val id: Long = 0,
    val kind: AnnotationKind,
    val name: String,
    val color: AnnotationColor = AnnotationColor.BLUE,
    val points: List<GeoPoint> = emptyList(),
    val altitudeM: Double? = null,
    val createdAt: Long = 0,
) {
    /** 저장할 만큼 점이 있다. */
    val complete: Boolean get() = points.size >= kind.minPoints
}

/** 주석 계산(10.4.7): 길이·넓이(WGS84 → 평면 근사), 새 이름 번호, 이름 찾기, 미션 둘레 고르기. */
object Annotations {
    /** 선 길이(m): 이웃한 점 사이 거리의 합. */
    fun lengthM(points: List<GeoPoint>): Double = points.zipWithNext { a, b -> LocalProjection.distanceM(a, b) }.sum()

    /** 영역 넓이(m²): 첫 점을 원점으로 한 평면 투영(수 km 안에서 충분, 5.2)에서 신발끈 공식. 점이 3개 미만이면 0. */
    fun areaM2(points: List<GeoPoint>): Double {
        if (points.size < 3) return 0.0
        val proj = LocalProjection(points.first())
        val e = points.map(proj::toEnu)
        return abs(e.indices.sumOf { i -> e[i].x * e[(i + 1) % e.size].y - e[(i + 1) % e.size].x * e[i].y }) / 2
    }

    /** 새 이름(Pilot 2 "M3T-1 8"): "[prefix] n". n은 같은 머리말 이름 중 가장 큰 번호 + 1(핀포인트·선·영역이 번호를 함께 쓴다). */
    fun nextName(prefix: String, names: Collection<String>): String {
        val re = Regex("^${Regex.escape(prefix)} (\\d+)$")
        val max = names.mapNotNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "$prefix ${max + 1}"
    }

    /** 이름으로 찾기(지도 검색 10.5.7): 같음 → 앞부분 → 포함 순. 띄어쓰기·대소문자는 무시한다([PoleSearch]와 같음). */
    fun search(items: List<Annotation>, query: String, limit: Int): List<Annotation> {
        fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        return items.mapNotNull { a ->
            val n = norm(a.name)
            when {
                n == q -> 0
                n.startsWith(q) -> 1
                q in n -> 2
                else -> null
            }?.let { a to it }
        }.sortedBy { it.second }.take(limit).map { it.first }
    }

    /** [around](미션 전주·이륙 지점)를 둘러싼 상자에서 [marginM] 안에 점이 하나라도 있는 주석(내보내기 4.3). [around]가 비면 없음. */
    fun near(items: List<Annotation>, around: List<GeoPoint>, marginM: Double): List<Annotation> {
        if (around.isEmpty()) return emptyList()
        val proj = LocalProjection(around.first())
        val e = around.map(proj::toEnu)
        val xs = e.minOf { it.x } - marginM..e.maxOf { it.x } + marginM
        val ys = e.minOf { it.y } - marginM..e.maxOf { it.y } + marginM
        return items.filter { a -> a.points.any { p -> proj.toEnu(p).let { it.x in xs && it.y in ys } } }
    }
}
