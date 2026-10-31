package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.ObstacleLevel
import com.example.myfirstapp.core.model.ObstacleWarning
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.sound.ToneNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 충돌 경고(13번, 11장): 기체 장애물 거리를 등급으로 바꿔 [set]으로 상태에 넣고, 가장 높은 등급이 바뀔 때만 신호음 반복을 시작·정지한다.
 * 주의는 느린 삑(0.8초), 위험은 빠른 삑(0.25초). 땅에 있을 때는 경고하지 않는다. 자동·수동 비행 화면이 같이 쓴다.
 */
internal fun CoroutineScope.watchObstacles(drone: DroneGateway, tone: ToneNotifier, set: (ObstacleWarning) -> Unit) = launch {
    var w = ObstacleWarning()
    var beep: Job? = null
    val flying = drone.telemetry.map { it.isFlying }.distinctUntilChanged()
    combine(drone.obstacles, flying) { o, f -> o.takeIf { f } }.collect { o ->
        val next = w.next(o)
        if (next.level != w.level) {
            beep?.cancel()
            beep = when (next.level) {
                ObstacleLevel.NONE -> null
                else -> launch {
                    val gap = if (next.level == ObstacleLevel.DANGER) AppConfig.Sound.OBSTACLE_DANGER_GAP_MS else AppConfig.Sound.OBSTACLE_CAUTION_GAP_MS
                    while (true) { tone.obstacleBeep(); delay(gap) }
                }
            }
        }
        w = next
        set(w)
    }
}
