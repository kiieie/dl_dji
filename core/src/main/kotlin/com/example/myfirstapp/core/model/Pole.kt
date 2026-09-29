package com.example.myfirstapp.core.model

/** WGS84 좌표(십진수 도). */
data class GeoPoint(val lat: Double, val lon: Double)

/** 전주 한 개. [poleId]는 전주번호로 프로젝트 안에서 유일하다(4.1). */
data class Pole(
    val poleId: String,
    val position: GeoPoint,
    val lineName: String? = null,
    val note: String? = null,
)

/** 방문 상태(4.2 `VISIT.status`). */
enum class VisitStatus { PENDING, IN_PROGRESS, DONE, SKIPPED, FAILED }

/** 사진 출처(4.2 `CAPTURE.source`). */
enum class CaptureSource { AUTO, MANUAL, PERIODIC }

enum class Lens { WIDE, ZOOM, THERMAL }

/** 탐지 결과 한 개. 좌표는 이미지 크기 대비 0~1 비율(좌상단 기준). */
data class Detection(
    val label: String,
    val score: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)
