package com.example.myfirstapp.core.model

/** WGS84 좌표(십진수 도). */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * 전주 한 개. [poleId]는 전주번호(설비 엑셀의 전산화번호)로 프로젝트 안에서 유일하다(4.1).
 * 설비 엑셀(4.5)에서 오는 값: [lineNo] 선로번호, [lengthM] 전주 길이(규격), [equipment] 설치기기, [guy] 지선주, [installedYm] 설치년월.
 */
data class Pole(
    val poleId: String,
    val position: GeoPoint,
    val lineName: String? = null,
    val note: String? = null,
    val lineNo: String? = null,
    val lengthM: Double? = null,
    val equipment: String? = null,
    val guy: Boolean = false,
    val installedYm: String? = null,
) {
    val displayName: String get() = poleDisplayName(poleId, lineName, lineNo)

    /** 지상 높이(m): 길이 − 근입(15m 이하 길이/6, 넘으면 2.5m). 길이를 모르면 null. */
    val aboveGroundM: Double? get() = lengthM?.let { it - if (it <= 15.0) it / 6.0 else 2.5 }
}

/** 전주 화면 이름(4.5): 선로번호가 있으면 "대평선 13", 없으면 전주번호. */
fun poleDisplayName(poleId: String, lineName: String?, lineNo: String?) =
    if (lineNo.isNullOrBlank()) poleId else listOfNotNull(lineName, lineNo).joinToString(" ")

/** 방문 상태(4.2 `VISIT.status`). */
enum class VisitStatus { PENDING, IN_PROGRESS, DONE, SKIPPED, FAILED }

/** 사진 출처(4.2 `CAPTURE.source`). */
enum class CaptureSource { AUTO, MANUAL, PERIODIC }

enum class Lens { WIDE, ZOOM, THERMAL }

/** 탐지 결과 한 개. 좌표는 이미지 크기 대비 0~1 비율(좌상단 기준). [alert]는 경고 라벨(9.1 `alertLabels`)인지다. */
data class Detection(
    val label: String,
    val score: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val alert: Boolean = false,
)

/** 1차 판독 판정(F-19). 앱은 이상 유무가 아니라 AI 검출이 맞는지를 판정한다(9.2). */
enum class ReviewVerdict(val title: String) {
    CONFIRMED("검출 맞음"),
    NONE("검출 없음"),
    RETAKE("재촬영"),
}

/** 검출 대상 라벨의 화면 이름(9.2). 목록에 없는 라벨은 그대로 보여 준다. */
object TargetLabels {
    val names = linkedMapOf("bird_nest" to "조류 둥지", "foreign_object" to "이물질")

    fun title(label: String) = names[label] ?: label
}
