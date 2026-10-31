package com.example.myfirstapp.core.model

// 전문가 확인 대기(2026-10-31): 설비 엑셀(4.5) 값 → 의미 규칙을 이 파일에 모았다. 규칙이 바뀌면 여기만 고친다.

/** 전주 고/저압(4.5 엑셀 `고/저압`). */
enum class Voltage { HIGH, LOW, BOTH }

/** 설비 엑셀 값의 판정 규칙(전압, 설치기기, 지선주, 근입 깊이). */
object PoleCodes {

    /** `고/저압` 또는 `형태` 열의 이 값은 지선주다(전압 값이 아님). */
    const val GUY = "지선주"

    /** `고/저압` 값 → 전압. "고저" → 고저압, "고" → 고압, "저" → 저압(앞에서부터). 모르면 null. */
    fun voltageOf(text: String?): Voltage? = when {
        text == null -> null
        "고저" in text -> Voltage.BOTH
        "고" in text -> Voltage.HIGH
        "저" in text -> Voltage.LOW
        else -> null
    }

    /** `설치기기` 값 → 기기. "가공뱅크/피뢰기"처럼 여러 개일 수 있다. 뱅크·변압 → 변압기, 개폐 → 개폐기, 피뢰 → 피뢰기. */
    fun equipmentsOf(text: String?): Set<Equipment> = buildSet {
        val e = text.orEmpty()
        if ("뱅크" in e || "변압" in e) add(Equipment.TRANSFORMER)
        if ("개폐" in e) add(Equipment.SWITCH)
        if ("피뢰" in e) add(Equipment.ARRESTER)
    }

    /** 근입 깊이(m): 전주 길이 15m 이하는 길이/6, 넘으면 2.5m. */
    fun embedmentM(lengthM: Double): Double = if (lengthM <= 15.0) lengthM / 6.0 else 2.5
}
