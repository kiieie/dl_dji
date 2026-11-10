package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning

/** WGS84 좌표(십진수 도). */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * 전주 한 개. [poleId]는 전주번호(설비 엑셀의 전산화번호)로 프로젝트 안에서 유일하다(4.1).
 * 설비 엑셀(4.5)에서 오는 값: [lineNo] 선로번호, [lengthM] 전주 길이(규격), [equipment] 설치기기, [guy] 지선주, [installedYm] 설치년월,
 * [voltage] 고/저압(고압·저압·고저압). [lineBearingDeg]는 저장하지 않고 계획 때 선로번호로 구한다(4.5.6).
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
    val voltage: String? = null,
    val lineBearingDeg: Double? = null,
) {
    val displayName: String get() = poleDisplayName(poleId, lineName, lineNo)

    /** 지상 높이(m): 길이 − 근입([PoleCodes.embedmentM]). 길이를 모르면 null. */
    val aboveGroundM: Double? get() = lengthM?.let { it - PoleCodes.embedmentM(it) }
}

/** 전주 화면 이름(4.5): 선로번호가 있으면 "대평선 13", 없으면 전주번호. */
fun poleDisplayName(poleId: String, lineName: String?, lineNo: String?) =
    if (lineNo.isNullOrBlank()) poleId else listOfNotNull(lineName, lineNo).joinToString(" ")

/** 방문 상태(4.2 `VISIT.status`). */
enum class VisitStatus { PENDING, IN_PROGRESS, DONE, SKIPPED, FAILED }

/** 사진 출처(4.2 `CAPTURE.source`). */
enum class CaptureSource { AUTO, MANUAL, PERIODIC }

enum class Lens { WIDE, ZOOM, THERMAL }

/** 받은 사진의 실제 렌즈(5.6): EXIF 35mm 환산 초점([focal35], 모르면 0 이하)이 [Tuning.Photo.TELE_FOCAL35_MIN_MM] 이상이면 망원, 아니면 광각. 모르거나 열화상이면 [claimed]. */
fun lensOfFocal35(focal35: Int, claimed: Lens): Lens = when {
    focal35 <= 0 || claimed == Lens.THERMAL -> claimed
    focal35 >= Tuning.Photo.TELE_FOCAL35_MIN_MM -> Lens.ZOOM
    else -> Lens.WIDE
}

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
