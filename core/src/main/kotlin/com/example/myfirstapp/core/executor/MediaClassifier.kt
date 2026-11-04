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
    /** 타이머(인터벌) 촬영: 끝날 때까지 오는 파일이 모두 앱 촬영 것이다(미션 일시정지 중 지점이 바뀌어 보여도). */
    private var screenContinuous = false
    private var screenUntil = Long.MIN_VALUE / 2
    /** 앱 촬영이 사진 알림 없이 끝났다(시간 초과): 늦게 오는 그 파일을 지점 사진으로 넘기지 않는다. 미션을 재개하면 버린다. */
    private var screenOwed = false
    /** 타이머 촬영이 끝났다: 미션을 재개(또는 새 소티)할 때까지 오는 파일은 앱 촬영 것(늦은 타이머 파일이 새 지점 0번 자리를 차지하지 않게). */
    private var continuousHeld = false
    private var cameraInterval = false
    private var lastShot: Int? = null
    private var actionsAt: (Int) -> List<PhotoAction> = { ONE }
    private val files = mutableMapOf<Int, Int>()
    private var reachedShot: Int? = null
    private var reachedAt = Long.MIN_VALUE / 2

    /** 앱 촬영 시작. [continuous]면 타이머 촬영(끝날 때까지 여러 장). */
    @Synchronized fun screenStarted(continuous: Boolean = false) {
        screenActive = true
        screenGot = false
        screenContinuous = continuous
    }

    /** [owed]: 카메라가 촬영을 거절하지 않았다(사진이 늦게 올 수 있음). */
    @Synchronized fun screenEnded(now: Long, owed: Boolean = false) {
        screenActive = false
        screenUntil = now + tailMs
        screenOwed = owed && !screenGot
        if (screenContinuous) continuousHeld = true
    }

    /** 미션 시작·재개: 일시정지 중 늦은 앱 촬영 파일을 더 기다리지 않는다(뒤 파일은 지점 사진). */
    @Synchronized fun missionResumed() {
        screenOwed = false
        continuousHeld = false
    }

    /** 카메라가 인터벌(타이머) 모드라고 알림(`KeyCameraMode`): 그 동안의 파일은 모두 앱 촬영 것(멈춤 실패·앱 재시작으로 타이머가 남아도). */
    @Synchronized fun cameraInterval(on: Boolean) {
        cameraInterval = on
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
        screenOwed = false
        continuousHeld = false
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
        screenActive && (screenContinuous || !screenGot) -> (MediaOwner.SCREEN to null).also { screenGot = true }
        cameraInterval || (continuousHeld && shot != null) -> MediaOwner.SCREEN to null
        screenOwed -> (MediaOwner.SCREEN to null).also { screenOwed = false }
        shot != null && shot != lastShot -> MediaOwner.WAYPOINT to waypointFile(now, shot, lens)
        screenActive || now < screenUntil -> MediaOwner.SCREEN to null
        shot != null -> MediaOwner.WAYPOINT to waypointFile(now, shot, lens)
        else -> MediaOwner.NONE to null
    }

    private data class Slot(val action: Int, val lens: Lens, val equipment: Boolean)

    /** 지점 [shot]에서 기대하는 파일 차례(동작 순서, 동작 안에서는 렌즈 순서). */
    private fun slots(shot: Int) = actionsAt(shot).flatMapIndexed { i, a -> a.lenses.ifEmpty { listOf(Lens.WIDE) }.map { Slot(i, it, a.equipment) } }

    /**
     * 지점 사진의 주인 지점(6.4 사진 순서): 보통은 지금 지점이다. 지금 지점에 막 도착했을 때([Tuning.Photo.LATE_FILE_MS] 안) 온 파일은
     * 렌즈를 알고(파일 종류) 그 렌즈 자리가 앞 지점에 비어 있으며 지금 지점이 다음에 기다리는 렌즈가 아닐 때만 앞 지점의 늦은 파일로 본다.
     * 렌즈를 모르면(`_V`) 지금 지점 것: 11-03 현장에서 지점 사진은 도착 0.5~0.8초 뒤에 왔고, 앞 지점 파일 하나가 빠지면 뒤 파일이 모두 한 칸씩 밀렸다.
     * 지점 안에서는 파일이 온 차례가 동작 차례다. 렌즈를 알면 그 렌즈의 다음 자리로 건너뛴다(앞 동작 파일이 빠졌을 때).
     */
    private fun waypointFile(now: Long, shot: Int, lens: Lens?): WaypointFile {
        val justArrived = shot == reachedShot && now - reachedAt < Tuning.Photo.LATE_FILE_MS
        val last = lastShot
        val owner = if (justArrived && lens != null && shot > 0 && lateFor(shot - 1, shot, lens)) shot - 1 else shot
        lastShot = maxOf(owner, last ?: owner)
        val s = slots(owner)
        val n = files.getOrDefault(owner, 0)
        val i = lens?.let { l -> (n until s.size).firstOrNull { s[it].lens == l } } ?: n
        files[owner] = i + 1
        val slot = s.getOrElse(i) { s.last() }
        return WaypointFile(owner, i, slot.action, lens ?: slot.lens, slot.equipment)
    }

    /** [lens] 파일이 앞 지점 [prev]의 남은 자리에 있고, 지금 지점 [shot]이 다음에 기다리는 렌즈가 아니다. */
    private fun lateFor(prev: Int, shot: Int, lens: Lens): Boolean {
        val p = slots(prev)
        val missing = (files.getOrDefault(prev, 0) until p.size).any { p[it].lens == lens }
        return missing && slots(shot).getOrNull(files.getOrDefault(shot, 0))?.lens != lens
    }

    private companion object {
        val ONE = listOf(PhotoAction(listOf(Lens.WIDE), false))
    }
}
