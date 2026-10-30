package com.example.myfirstapp.core.executor

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

/** 화면 수동 촬영 뒤 이 시간 안에 온 둘째 파일(광각·망원 동시 저장)은 그 촬영 것으로 본다. */
const val MULTI_FILE_MS = 1_500L

/**
 * 어댑터가 새 사진 알림마다 주인을 가른다(6.4 RC 셔터 규칙, 위에서부터). 시각은 조종기 시계 ms.
 * 어댑터는 DJI 콜백 스레드 여러 곳에서 부르므로 메서드를 잠근다.
 * ponytail: 촬영 지점 도착 직후 지점 사진보다 셔터 사진이 먼저 오면 둘이 바뀐다(둘 다 기록됨). 실기체에서 파일 이름(fileSuffix)으로 가를 수 있는지 확인한다.
 */
class MediaClassifier(private val pressWindowMs: Long = SHUTTER_SKEW_MS, private val tailMs: Long = MULTI_FILE_MS) {
    private var pressAt = Long.MIN_VALUE / 2
    private var screenActive = false
    private var screenGot = false
    private var screenUntil = Long.MIN_VALUE / 2
    private var lastShot: Int? = null

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

    /** 새 소티 미션: 지점 사진 기록을 비운다. */
    @Synchronized fun sortieStarted() {
        lastShot = null
    }

    /** [shot]: 미션 중이면 지금 촬영 지점 번호(이동 고도 지점은 앞 촬영 지점), 아니면 null. */
    @Synchronized fun classify(now: Long, shot: Int?): MediaOwner = when {
        screenActive && !screenGot -> MediaOwner.SCREEN.also { screenGot = true }
        shot != null && shot != lastShot -> MediaOwner.WAYPOINT.also { lastShot = shot }
        now - pressAt < pressWindowMs -> MediaOwner.SHUTTER
        screenActive || now < screenUntil -> MediaOwner.SCREEN
        shot != null -> MediaOwner.WAYPOINT
        else -> MediaOwner.NONE
    }
}
