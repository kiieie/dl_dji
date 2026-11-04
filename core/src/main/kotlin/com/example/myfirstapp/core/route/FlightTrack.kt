package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint

/**
 * 지도에 그리는 비행 궤적(10.5.4 주황 선). 기록용 궤적(TELEMETRY_SAMPLE, 4.2)과 따로, 화면에 보일 만큼만 둔다.
 * 앞 점에서 [minStepM] 이상 움직였을 때만 더하고(제자리 호버링은 점을 늘리지 않음), [maxPoints]를 넘으면 오래된 점부터 버린다.
 * [at]이 null이면(위치 모름, 땅 위) 그대로 돌려준다.
 */
object FlightTrack {
    fun append(track: List<GeoPoint>, at: GeoPoint?, minStepM: Double, maxPoints: Int): List<GeoPoint> {
        if (at == null) return track
        val last = track.lastOrNull()
        if (last != null && LocalProjection.distanceM(last, at) < minStepM) return track
        return (track + at).takeLast(maxPoints)
    }
}
