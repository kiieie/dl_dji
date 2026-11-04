package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Polygon

// 기체 건강(HMS)·GEO 구역·기체가 알려 주는 설정 범위(10.5.5). SDK 값을 앱 모양으로 바꾼 뒤의 판단만 둔다(DJI 의존 없음).

/** HMS 등급(Pilot 2 홈·비행 전 점검 칸 색): 표준 녹 · 경고 주황 · 심각 빨강. 알림(NOTICE)은 표준으로 본다. */
enum class HmsLevel { NORMAL, CAUTION, SERIOUS }

/** HMS 항목 하나. [code]는 SDK 정보 코드("0x16100001"), [title]·[description]은 SDK 글(한국어가 있으면 한국어). */
data class HmsIssue(val code: String, val title: String, val description: String, val level: HmsLevel)

/** 전체 등급: 가장 높은 항목. 항목이 없으면 표준. */
fun hmsLevel(issues: List<HmsIssue>): HmsLevel = issues.maxOfOrNull { it.level } ?: HmsLevel.NORMAL

/** HMS 목록의 모듈 줄(위에서 아래 순서). 항목이 없어도 "정상"으로 보인다. */
val HMS_MODULES = listOf("비행 컨트롤러", "배터리", "비전 시스템", "짐벌 카메라", "이미지 전송", "지능형 비행", "시스템", "기타")

/**
 * 정보 코드의 모듈: 앞 두 자리. 묶음은 SDK 한국어 글(values-ko `hms_title_*`)로 확인했다(2026-11-04):
 * 0x11 배터리·전원, 0x14 페이로드, 0x16·0x20 비행 컨트롤러·이륙, 0x17 이미지 전송, 0x19 시스템, 0x1A 비전, 0x1B RTH·스마트 트랙,
 * 0x1C 카메라, 0x1D 짐벌. 배터리 스테이션(0x12)·레이더(0x15)·액세서리(0x1E)·LTE(0x1F)는 M3E 기본 구성에 없어 기타.
 */
fun hmsModule(code: String): String = when (code.drop(2).take(2).uppercase()) {
    "16", "20" -> "비행 컨트롤러"
    "11" -> "배터리"
    "1A" -> "비전 시스템"
    "14", "1C", "1D" -> "짐벌 카메라"
    "17" -> "이미지 전송"
    "1B" -> "지능형 비행"
    "19" -> "시스템"
    else -> "기타"
}

/** GEO 구역의 영역 하나: 원([center]·[radiusM]) 또는 다각형([points]). [limitM]은 그 영역 높이 제한(m, 0이면 없음). */
data class GeoArea(val center: GeoPoint? = null, val radiusM: Double = 0.0, val points: List<GeoPoint> = emptyList(), val limitM: Int = 0)

/**
 * GEO 구역 종류(DJI Fly Safe, SDK `FlyZoneCategory`). [ALTITUDE]는 SDK 종류가 아니라 제한 구역 안 높이 제한 영역(지도에서 회색)이다.
 * [REGULATORY]는 SDK `UTMISS_REGULATION`(규제 제한), [APPROVED]는 `UTMISS_LAW_ALLOW`(허가 구역).
 */
enum class GeoCategory(val title: String) {
    RESTRICTED("제한 구역"), AUTHORIZATION("승인 구역"), ENHANCED_WARNING("강화 경고 구역"), WARNING("경고 구역"),
    ALTITUDE("고도 제한 구역"), REGULATORY("규제 제한 구역"), APPROVED("허가 구역"), UNKNOWN("기타 구역"),
}

/** GEO 구역(DJI 비행 제한 구역) 하나. */
data class GeoZone(val name: String, val areas: List<GeoArea>, val category: GeoCategory = GeoCategory.UNKNOWN) {
    /** 지도에 그릴 종류: 제한 구역 안 높이 제한 영역(높이 > 0)은 Pilot 2처럼 고도 제한 구역으로 본다. */
    fun displayCategory(area: GeoArea): GeoCategory = if (category == GeoCategory.RESTRICTED && area.limitM > 0) GeoCategory.ALTITUDE else category
}

/** 원 영역을 지도에 그릴 [n]각형 꼭짓점(닫지 않음, 정북에서 시계 방향). 평면 근사(5.2)라 수 km 원도 충분하다. */
fun circleRing(center: GeoPoint, radiusM: Double, n: Int = 48): List<GeoPoint> {
    val p = LocalProjection(center)
    return List(n) { i ->
        val a = 2 * Math.PI * i / n
        p.toGeo(Enu(radiusM * Math.sin(a), radiusM * Math.cos(a)))
    }
}

/** 홈 GEO 구역 카드: 지금 자리의 최고 고도(m), 주변 구역 수, 가장 가까운 구역 이름과 거리(m, 안이면 0). */
data class GeoSummary(val maxAltitudeM: Int, val zones: Int, val nearest: String?, val nearestM: Double?)

/**
 * [at]에서 본 GEO 구역 요약. 최고 고도는 [at]을 품은 높이 제한 영역 중 가장 낮은 값, 없으면 [defaultMaxM](Pilot 2 홈 "최고 고도: 1500m").
 * ponytail: 다각형 밖 거리는 가장 가까운 꼭짓점까지(변까지가 아님). 카드 글용이라 수백 m 오차는 둔다.
 */
fun geoSummary(zones: List<GeoZone>, at: GeoPoint, defaultMaxM: Int): GeoSummary {
    fun GeoArea.distanceM(): Double = when {
        center != null && radiusM > 0 -> (LocalProjection.distanceM(at, center) - radiusM).coerceAtLeast(0.0)
        points.size >= 3 -> if (Polygon(points).contains(at)) 0.0 else points.minOf { LocalProjection.distanceM(at, it) }
        else -> Double.POSITIVE_INFINITY
    }
    val near = zones.map { z -> z to (z.areas.minOfOrNull { it.distanceM() } ?: Double.POSITIVE_INFINITY) }.filter { it.second.isFinite() }.minByOrNull { it.second }
    val limit = zones.flatMap { it.areas }.filter { it.limitM > 0 && it.distanceM() == 0.0 }.minOfOrNull { it.limitM }
    return GeoSummary(limit ?: defaultMaxM, zones.size, near?.first?.name, near?.second)
}

/** 기체가 알려 준 설정 범위(`IntValueConfig` 최소·최대). 못 읽었거나 뒤집혔으면 [fallback](모의 기체·읽기 실패). */
fun settingRange(min: Int?, max: Int?, fallback: IntRange): IntRange = if (min != null && max != null && min <= max) min..max else fallback
