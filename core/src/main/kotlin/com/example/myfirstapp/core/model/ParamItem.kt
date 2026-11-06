package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning

/**
 * 계획 패널의 미션 설정 항목(10.4.3). 화면의 [−][+]와 RC 5D ↔가 같은 [FlightParams.step]을 쓴다.
 * 짐벌 피치는 고도·거리·조준 높이로 정해지므로(5.6) 항목이 아니다.
 */
enum class ParamItem { SHOT_MODE, DIAGONAL_ALTITUDE, DIAGONAL_DISTANCE, INSPECT_ALTITUDE, ZOOM_RATIO, SPEED, TRANSIT_ALTITUDE, BATTERY_RESERVE, TERRAIN_FOLLOW }

/** 지금 촬영 방식에서 패널에 보이는 항목(위에서 아래 순서). */
fun FlightParams.items(): List<ParamItem> = buildList {
    add(ParamItem.SHOT_MODE)
    if (shotMode == ShotMode.DIAGONAL4) addAll(listOf(ParamItem.DIAGONAL_ALTITUDE, ParamItem.DIAGONAL_DISTANCE))
    else add(ParamItem.INSPECT_ALTITUDE)
    if (Lens.ZOOM in lenses) add(ParamItem.ZOOM_RATIO)
    addAll(listOf(ParamItem.SPEED, ParamItem.TRANSIT_ALTITUDE, ParamItem.BATTERY_RESERVE, ParamItem.TERRAIN_FOLLOW))
}

/** 항목 값을 한 단계 바꾼다([dir]는 −1 또는 +1). 촬영 방식·지형 추적은 바꿔 놓는다. 범위 검사는 [FlightParams.validate]. */
fun FlightParams.step(item: ParamItem, dir: Int): FlightParams = when (item) {
    ParamItem.SHOT_MODE -> copy(shotMode = if (shotMode == ShotMode.DIAGONAL4) ShotMode.OVERHEAD else ShotMode.DIAGONAL4)
    ParamItem.DIAGONAL_ALTITUDE -> copy(diagonalAltitudeM = diagonalAltitudeM + dir)
    ParamItem.DIAGONAL_DISTANCE -> copy(diagonalDistanceM = diagonalDistanceM + dir)
    ParamItem.INSPECT_ALTITUDE -> copy(inspectAltitudeM = inspectAltitudeM + dir * 5)
    ParamItem.ZOOM_RATIO -> copy(zoomRatio = (zoomRatio + dir).coerceIn(Tuning.Photo.ZOOM_RATIO_RANGE))
    ParamItem.SPEED -> copy(speedMps = speedMps + dir)
    ParamItem.TRANSIT_ALTITUDE -> copy(transitAltitudeM = transitAltitudeM + dir * 5)
    ParamItem.BATTERY_RESERVE -> copy(batteryReservePct = batteryReservePct + dir * 5)
    ParamItem.TERRAIN_FOLLOW -> copy(terrainFollow = !terrainFollow)
}

/** 5D ↕: 보이는 항목 사이에서 [cur]를 옮긴다. [cur]가 지금 방식에 없으면(방식을 바꿈) 첫 항목. */
fun FlightParams.moveItem(cur: ParamItem?, delta: Int): ParamItem {
    val all = items()
    val i = all.indexOf(cur)
    return if (i < 0) all.first() else all[(i + delta).coerceIn(0, all.lastIndex)]
}
