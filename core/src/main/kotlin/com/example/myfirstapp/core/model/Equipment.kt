package com.example.myfirstapp.core.model

/** 전주 설치기기(4.5 엑셀 `설치기기`). 설비 조준 사진(5.6)과 판독·앨범 기호에 쓴다. */
enum class Equipment(val title: String) { TRANSFORMER("변압기"), SWITCH("개폐기"), ARRESTER("피뢰기") }

/** 엑셀 `설치기기` 값 → 기기. "가공뱅크/피뢰기"처럼 여러 개일 수 있다. */
fun equipmentsOf(text: String?): Set<Equipment> = buildSet {
    val e = text.orEmpty()
    if ("뱅크" in e || "변압" in e) add(Equipment.TRANSFORMER)
    if ("개폐" in e) add(Equipment.SWITCH)
    if ("피뢰" in e) add(Equipment.ARRESTER)
}
