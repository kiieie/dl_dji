package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.route.WpmlWriter.PhotoAction

/** 새로 생긴 사진 파일의 주인(6.4 사진 주인 가르기). */
enum class MediaOwner {
    /** 앱이 찍는 촬영(`capture`: 화면 셔터·RC 셔터)이 기다리는 사진. */
    SCREEN,

    /** 미션 동작이 찍은 지점 사진(`PhotoTaken`). */
    WAYPOINT,

    /** 넘기지 않는다(SD에만 남음). */
    NONE,
}

/**
 * 지점 사진 파일: [shot] 촬영 지점, [ordinal] 그 지점에서 기대한 파일 중 몇 번째(0부터),
 * [action] 그 지점의 몇 번째 촬영 동작(KMZ 순서), [lens] 렌즈(파일 종류를 알면 그것, 모르면 동작의 렌즈), [equipment] 설비 조준 사진.
 */
data class WaypointFile(val shot: Int, val ordinal: Int, val action: Int = 0, val lens: Lens = Lens.WIDE, val equipment: Boolean = false)

/**
 * 어댑터가 새 사진 알림마다 주인을 가른다(6.4, 위에서부터). 시각은 조종기 시계 ms.
 * 어댑터는 DJI 콜백 스레드 여러 곳에서 부르므로 메서드를 잠근다.
 */
class MediaClassifier(private val tailMs: Long = Tuning.Photo.MULTI_FILE_MS) {
    private var screenActive = false
    private var screenGot = false
    private var screenUntil = Long.MIN_VALUE / 2
    private var lastShot: Int? = null
    private var actionsAt: (Int) -> List<PhotoAction> = { ONE }
    private val files = mutableMapOf<Int, Int>()
    private var reachedShot: Int? = null
    private var reachedAt = Long.MIN_VALUE / 2

    @Synchronized fun screenStarted() {
        screenActive = true
        screenGot = false
    }

    @Synchronized fun screenEnded(now: Long) {
        screenActive = false
        screenUntil = now + tailMs
    }

    /** 새 소티 미션(테스트·단순 경우): 모든 지점에서 광각 동작 [filesPerShot]개, 동작마다 파일 1장. */
    fun sortieStarted(filesPerShot: Int = 1) = sortieStarted { List(filesPerShot) { ONE[0] } }

    /**
     * 새 소티 미션: [actions]는 지점마다의 촬영 동작(KMZ 순서). 동작 하나에서 렌즈마다 파일 1장을 기대한다.
     * 렌즈 동시 저장이 거절되어 한 동작에 1장만 오는 소티면 어댑터가 동작의 렌즈를 하나로 줄여 넘긴다.
     */
    @Synchronized fun sortieStarted(actions: (Int) -> List<PhotoAction>) {
        lastShot = null
        actionsAt = { actions(it).ifEmpty { ONE } }
        files.clear()
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

    /** [classify]와 같고, 지점 사진이면 어느 지점의 몇 번째 파일인지도 돌려준다. [lens]는 파일 종류로 안 렌즈(모르면 null). */
    @Synchronized fun classifyFile(now: Long, shot: Int?, lens: Lens? = null): Pair<MediaOwner, WaypointFile?> = when {
        screenActive && !screenGot -> (MediaOwner.SCREEN to null).also { screenGot = true }
        shot != null && shot != lastShot -> MediaOwner.WAYPOINT to waypointFile(now, shot, lens)
        screenActive || now < screenUntil -> MediaOwner.SCREEN to null
        shot != null -> MediaOwner.WAYPOINT to waypointFile(now, shot, lens)
        else -> MediaOwner.NONE to null
    }

    private data class Slot(val action: Int, val lens: Lens, val equipment: Boolean)

    /** 지점 [shot]에서 기대하는 파일 차례(동작 순서, 동작 안에서는 렌즈 순서). */
    private fun slots(shot: Int) = actionsAt(shot).flatMapIndexed { i, a -> a.lenses.ifEmpty { listOf(Lens.WIDE) }.map { Slot(i, it, a.equipment) } }

    /**
     * 지점 사진의 주인 지점(6.4 사진 순서): 지금 지점에 막 도착했는데([Tuning.Photo.LATE_FILE_MS] 안) 앞 지점 파일이 덜 왔으면 앞 지점의 늦은 파일,
     * 아직 아무 지점도 없었으면 그 앞 번호 지점. 그 밖에는 지금 지점. 한 지점이 통째로 안 찍혀도 뒤 지점이 밀리지 않는다.
     * 지점 안에서는 파일이 온 차례가 동작 차례다. 렌즈를 알면 그 렌즈의 다음 자리로 건너뛴다(앞 동작 파일이 빠졌을 때).
     */
    private fun waypointFile(now: Long, shot: Int, lens: Lens?): WaypointFile {
        val justArrived = shot == reachedShot && now - reachedAt < Tuning.Photo.LATE_FILE_MS
        val last = lastShot
        val owner = when {
            !justArrived -> shot
            last != null && last < shot && files.getOrDefault(last, 0) < slots(last).size -> last
            last == null && shot > 0 -> shot - 1
            else -> shot
        }
        lastShot = maxOf(owner, last ?: owner)
        val s = slots(owner)
        val n = files.getOrDefault(owner, 0)
        val i = lens?.let { l -> (n until s.size).firstOrNull { s[it].lens == l } } ?: n
        files[owner] = i + 1
        val slot = s.getOrElse(i) { s.last() }
        return WaypointFile(owner, i, slot.action, lens ?: slot.lens, slot.equipment)
    }

    private companion object {
        val ONE = listOf(PhotoAction(listOf(Lens.WIDE), false))
    }
}
