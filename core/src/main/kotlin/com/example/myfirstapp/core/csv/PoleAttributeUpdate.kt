package com.example.myfirstapp.core.csv

import com.example.myfirstapp.core.model.Pole

/**
 * 전주 속성 갱신 미리보기(4.5.7). [changed]는 속성이 바뀌는 기존 전주(바뀐 값을 넣은 것),
 * [newInFile]은 파일에만 있는 전주 수(추가하지 않음), [missingInFile]은 파일에 없는 기존 전주 수(그대로 둠).
 */
data class PoleAttrDiff(val changed: List<Pole>, val newInFile: Int, val missingInFile: Int)

/**
 * 파일 전주를 기존 전주와 전산화번호([Pole.poleId])로 맞춰 속성만 바꾼다. 좌표는 기존 값을 둔다.
 * 파일 칸이 비어 있으면(null) 기존 값을 두고, 지선주([Pole.guy])는 파일 값을 따른다.
 */
fun diffPoleAttributes(existing: List<Pole>, file: List<Pole>): PoleAttrDiff {
    val byId = file.associateBy { it.poleId }
    val changed = existing.mapNotNull { old ->
        val f = byId[old.poleId] ?: return@mapNotNull null
        old.copy(
            lineName = f.lineName ?: old.lineName,
            note = f.note ?: old.note,
            lineNo = f.lineNo ?: old.lineNo,
            lengthM = f.lengthM ?: old.lengthM,
            equipment = f.equipment ?: old.equipment,
            guy = f.guy,
            installedYm = f.installedYm ?: old.installedYm,
            voltage = f.voltage ?: old.voltage,
        ).takeIf { it != old }
    }
    val ids = existing.mapTo(HashSet()) { it.poleId }
    return PoleAttrDiff(changed, file.count { it.poleId !in ids }, existing.count { it.poleId !in byId })
}
