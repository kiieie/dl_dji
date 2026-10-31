package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.config.Tuning

/** 새로 생긴 사진 파일의 주인(6.4 RC 셔터). */
enum class MediaOwner {
    /** 화면 수동 촬영(`capture`)이 기다리는 사진. */
    SCREEN,

    /** 미션 동작이 찍은 지점 사진(`PhotoTaken`). */
    WAYPOINT,

    /** RC 셔터 사진(`ShutterPhoto`). */
    SHUTTER,

    /** 넘기지 않는다(SD에만 남음). */
    NONE,
}

/**
 * 지점 사진 파일: [shot] 촬영 지점, [ordinal] 그 지점에서 몇 번째 파일(0부터),
 * [action] 그 지점의 몇 번째 촬영 동작(0 지점 사진, 1 설비 조준 사진), [lensSlot] 그 동작 안에서 몇 번째 파일(KMZ 렌즈 순서).
 */
data class WaypointFile(val shot: Int, val ordinal: Int, val action: Int = 0, val lensSlot: Int = ordinal)

/**
 * 어댑터가 새 사진 알림마다 주인을 가른다(6.4 RC 셔터 규칙, 위에서부터). 시각은 조종기 시계 ms.
 * 어댑터는 DJI 콜백 스레드 여러 곳에서 부르므로 메서드를 잠근다.
 * ponytail: 촬영 지점 도착 직후 지점 사진보다 셔터 사진이 먼저 오면 둘이 바뀐다(둘 다 기록됨). 실기체에서 파일 이름(fileSuffix)으로 가를 수 있는지 확인한다.
 */
class MediaClassifier(private val pressWindowMs: Long = Tuning.Photo.SHUTTER_SKEW_MS, private val tailMs: Long = Tuning.Photo.MULTI_FILE_MS) {
    private var pressAt = Long.MIN_VALUE / 2
    private var screenActive = false
    private var screenGot = false
    private var screenUntil = Long.MIN_VALUE / 2
    private var lastShot: Int? = null
    private var filesPerShot: (Int) -> Int = { 1 }
    private val files = mutableMapOf<Int, Int>()
    private val lastFileAt = mutableMapOf<Int, Long>()
    private val actions = mutableMapOf<Int, Int>()
    private val slots = mutableMapOf<Int, Int>()
    private var reachedShot: Int? = null
    private var reachedAt = Long.MIN_VALUE / 2

    @Synchronized fun shutterPressed(now: Long) {
        pressAt = now
    }

    @Synchronized fun screenStarted() {
        screenActive = true
        screenGot = false
    }

    @Synchronized fun screenEnded(now: Long) {
        screenActive = false
        screenUntil = now + tailMs
    }

    /** 새 소티 미션: 지점 사진 기록을 비운다. 모든 지점에서 파일이 [filesPerShot]개(렌즈 수). */
    fun sortieStarted(filesPerShot: Int = 1) = sortieStarted { filesPerShot }

    /** 새 소티 미션: [filesPerShot]은 지점마다 오는 파일 수(렌즈 수 × 촬영 횟수, 설비 사진이 있으면 2배). */
    @Synchronized fun sortieStarted(filesPerShot: (Int) -> Int) {
        lastShot = null
        this.filesPerShot = { filesPerShot(it).coerceAtLeast(1) }
        files.clear()
        lastFileAt.clear()
        actions.clear()
        slots.clear()
        reachedShot = null
    }

    /** 촬영 지점 [shot]에 도착(웨이포인트 알림). */
    @Synchronized fun shotReached(shot: Int, now: Long) {
        if (shot == reachedShot) return
        reachedShot = shot
        reachedAt = now
    }

    /** [shot]: 미션 중이면 지금 촬영 지점 번호(이동 고도 지점은 앞 촬영 지점), 아니면 null. */
    @Synchronized fun classify(now: Long, shot: Int?): MediaOwner = classifyFile(now, shot).first

    /** [classify]와 같고, 지점 사진이면 어느 지점의 몇 번째 파일인지도 돌려준다. */
    @Synchronized fun classifyFile(now: Long, shot: Int?): Pair<MediaOwner, WaypointFile?> = when {
        screenActive && !screenGot -> (MediaOwner.SCREEN to null).also { screenGot = true }
        shot != null && shot != lastShot -> MediaOwner.WAYPOINT to waypointFile(now, shot)
        now - pressAt < pressWindowMs -> MediaOwner.SHUTTER to null
        screenActive || now < screenUntil -> MediaOwner.SCREEN to null
        shot != null -> MediaOwner.WAYPOINT to waypointFile(now, shot)
        else -> MediaOwner.NONE to null
    }

    /**
     * 지점 사진의 주인 지점(6.4 사진 순서): 지금 지점에 막 도착했는데([Tuning.Photo.LATE_FILE_MS] 안) 앞 지점 파일이 덜 왔으면 앞 지점의 늦은 파일,
     * 아직 아무 지점도 없었으면 그 앞 번호 지점. 그 밖에는 지금 지점. 한 지점이 통째로 안 찍혀도 뒤 지점이 밀리지 않는다.
     */
    private fun waypointFile(now: Long, shot: Int): WaypointFile {
        val justArrived = shot == reachedShot && now - reachedAt < Tuning.Photo.LATE_FILE_MS
        val last = lastShot
        val owner = when {
            !justArrived -> shot
            last != null && last < shot && files.getOrDefault(last, 0) < filesPerShot(last) -> last
            last == null && shot > 0 -> shot - 1
            else -> shot
        }
        lastShot = maxOf(owner, last ?: owner)
        val n = files.getOrDefault(owner, 0)
        files[owner] = n + 1
        // 앞 파일과 떨어져 왔으면 다음 촬영 동작(설비 조준), 붙어 왔으면 같은 동작의 다음 렌즈
        val prev = lastFileAt[owner]
        val action = actions.getOrDefault(owner, 0) + if (prev != null && now - prev > Tuning.Photo.BURST_MS) 1 else 0
        val slot = if (prev == null || now - prev > Tuning.Photo.BURST_MS) 0 else slots.getOrDefault(owner, 0) + 1
        lastFileAt[owner] = now
        actions[owner] = action
        slots[owner] = slot
        return WaypointFile(owner, n, action, slot)
    }
}
