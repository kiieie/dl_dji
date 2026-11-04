package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.ShotMode

/**
 * 경로 요약(Pilot 2 경로 열기 칩·체크리스트 "거리 · 예상 시간 · 웨이포인트 · 사진", 10.5.5·10.5.8).
 * [waypoints]는 촬영 지점 수(4방향 사선은 전주마다 4, 전주 위는 1). 이동 고도 오르내림 지점은 세지 않는다.
 */
data class RouteSummary(val distanceM: Double, val durationSec: Double, val waypoints: Int, val photos: Int) {
    companion object {
        fun of(sorties: List<SortiePlan>, params: FlightParams) = RouteSummary(
            distanceM = sorties.sumOf { it.estDistanceM },
            durationSec = sorties.sumOf { it.estDurationSec },
            waypoints = sorties.sumOf { it.poles.size } * if (params.shotMode == ShotMode.DIAGONAL4) 4 else 1,
            photos = sorties.sumOf { s -> s.poles.sumOf(params::photosPerPole) },
        )
    }
}
