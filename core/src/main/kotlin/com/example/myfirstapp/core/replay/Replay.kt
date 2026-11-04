package com.example.myfirstapp.core.replay

import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.route.LocalProjection

// 지난 비행 다시 보기(10.4.6). 화면은 그리기만 하고, 시각 t의 기체 상태·그린 궤적·재생 시계는 여기서 정한다(단위 테스트 `ReplayTest`).

/** 기록한 궤적 한 점(1초 간격): 기록 시각과 그때의 텔레메트리(위치·고도·속도·기수·짐벌·배터리·비행 모드). */
data class TrackSample(val at: Long, val telemetry: Telemetry)

/**
 * 시각순으로 정렬한 궤적([samples])에서 시각 t의 기체 상태를 구한다.
 * 위치·고도·속도는 앞뒤 점 사이를 선형 보간, 기수는 짧은 쪽으로 돈다(359° → 1°는 0°를 지남). 배터리·비행 모드·짐벌은 앞 점 값을 쓴다.
 * 범위 밖은 처음·마지막 점, 두 점 사이가 [holdGapMs]보다 멀면(기록이 끊김: 착륙 뒤 다시 이륙, 다른 날 이어하기) 보간하지 않고 앞 점에 머문다.
 * 재생 위치(처음부터 ms)는 끊긴 구간을 [holdGapMs]로 줄여 센다([durationMs], [wallAt]): 하루 뒤 이어 난 소티도 시간 막대가 비지 않게.
 */
class ReplayTrack(val samples: List<TrackSample>, private val holdGapMs: Long) {
    private val ats = LongArray(samples.size) { samples[it].at }

    /** 점마다 재생 위치(끊긴 구간은 [holdGapMs]로 줄임). */
    private val playAt = LongArray(samples.size).also { p ->
        for (i in 1 until samples.size) p[i] = p[i - 1] + (ats[i] - ats[i - 1]).coerceAtMost(holdGapMs)
    }

    val startAt get() = samples.firstOrNull()?.at ?: 0L

    /** 재생 길이(끊긴 구간을 줄인 비행 시간). */
    val durationMs get() = playAt.lastOrNull() ?: 0L

    /** 재생 위치 [posMs]의 벽시계 시각. */
    fun wallAt(posMs: Long): Long {
        val i = playAt.lastAtOrBefore(posMs)
        return if (i < 0) startAt else ats[i] + (posMs - playAt[i]).coerceAtMost(if (i + 1 < ats.size) ats[i + 1] - ats[i] else 0L)
    }

    /** [at] 이하인 마지막 점 번호. 처음보다 앞이면 -1. */
    private fun indexAt(at: Long): Int = ats.lastAtOrBefore(at)

    /** 시각 [at](벽시계 ms)의 상태. 궤적이 비면 null. */
    fun stateAt(at: Long): Telemetry? {
        if (samples.isEmpty()) return null
        val i = indexAt(at)
        if (i < 0) return samples.first().telemetry
        if (i == samples.size - 1) return samples.last().telemetry
        val a = samples[i]
        val b = samples[i + 1]
        val gap = b.at - a.at
        if (gap > holdGapMs || gap <= 0) return a.telemetry
        val f = (at - a.at).toDouble() / gap
        val ta = a.telemetry
        val tb = b.telemetry
        val pos = if (ta.position != null && tb.position != null) {
            GeoPoint(lerp(ta.position.lat, tb.position.lat, f), lerp(ta.position.lon, tb.position.lon, f))
        } else ta.position
        return ta.copy(
            position = pos, altitudeM = lerp(ta.altitudeM, tb.altitudeM, f), speedMps = lerp(ta.speedMps, tb.speedMps, f),
            headingDeg = lerpAngle(ta.headingDeg, tb.headingDeg, f),
        )
    }

    /** 지도에 그릴 궤적: [from] ~ [to] 사이 기록 점과 [to]의 보간 위치(지우개 뒤에는 [from]부터). */
    fun pathBetween(from: Long, to: Long): List<GeoPoint> {
        val end = indexAt(to)
        if (end < 0) return emptyList()
        val start = (indexAt(from - 1) + 1).coerceAtLeast(0)
        val pts = (start..end).mapNotNull { samples[it].telemetry.position }
        return pts + listOfNotNull(stateAt(to)?.position?.takeIf { to > samples[end].at && it != pts.lastOrNull() })
    }

    /** 날아간 거리(m): 위치가 있는 점 사이 직선 거리 합. */
    fun distanceM(): Double = samples.mapNotNull { it.telemetry.position }.zipWithNext { a, b -> LocalProjection.distanceM(a, b) }.sum()
}

private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f

/** 정렬한 배열에서 [x] 이하인 마지막 번호. 없으면 -1. */
private fun LongArray.lastAtOrBefore(x: Long): Int {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid] <= x) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/** 짧은 쪽으로 도는 각 보간. 결과는 0~360°. */
fun lerpAngle(a: Double, b: Double, f: Double): Double {
    val d = ((b - a) % 360.0 + 540.0) % 360.0 - 180.0
    return ((a + d * f) % 360.0 + 360.0) % 360.0
}

/**
 * 재생 시계: 궤적 처음부터의 위치 [posMs], 재생 중 여부, 배속([speeds] 중 하나). 끝에 닿으면 멈추고, 끝에서 다시 재생하면 처음부터.
 */
data class Playback(val durationMs: Long, val speeds: List<Int>, val posMs: Long = 0, val playing: Boolean = false, val speed: Int = speeds.first()) {
    /** 실제로 [elapsedMs]가 지났을 때. */
    fun tick(elapsedMs: Long): Playback {
        if (!playing) return this
        val p = (posMs + elapsedMs * speed).coerceAtMost(durationMs)
        return copy(posMs = p, playing = p < durationMs)
    }

    fun seek(ms: Long) = copy(posMs = ms.coerceIn(0, durationMs))
    fun toggle() = if (playing) copy(playing = false) else copy(playing = true, posMs = if (posMs >= durationMs) 0 else posMs)
    fun nextSpeed() = copy(speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size])
}

/** "mm:ss"(한 시간이 넘으면 "h:mm:ss"). */
fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
