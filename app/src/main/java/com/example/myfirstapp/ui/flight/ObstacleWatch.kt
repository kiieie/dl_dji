package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.ObstacleDirection
import com.example.myfirstapp.core.model.ObstacleLevel
import com.example.myfirstapp.core.model.ObstacleWarning
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.sound.ToneNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 충돌 경고(13번, 11장): 기체 장애물 거리를 등급으로 바꿔 [set]으로 상태에 넣는다. 땅에서도 보인다(10.5.3, Pilot 2와 같음).
 * 신호음은 모터가 돌거나 떠 있을 때만, 가장 높은 등급이 바뀔 때 반복을 시작·정지한다. 주의는 느린 삑(0.8초), 위험은 빠른 삑(0.25초).
 * 운용 로그(4.4): 어느 방향이든 [Tuning.Obstacle.LOG_M] 안이면 [AppConfig.Polling.OBSTACLE_LOG_MS]마다, 등급이 바뀌면 바로 `obstacle` 한 줄(방향별 거리, 위·아래 포함).
 * 땅에서는 아래 센서가 늘 땅을 보므로 아래 거리로는 남기지 않는다(다른 방향으로 남길 때 줄에는 넣는다).
 * 자동·수동 비행 화면이 같이 쓴다.
 */
internal fun CoroutineScope.watchObstacles(drone: DroneGateway, tone: ToneNotifier, set: (ObstacleWarning) -> Unit) = launch {
    var w = ObstacleWarning()
    var beep: Job? = null
    var beeping = ObstacleLevel.NONE
    var loggedAt = 0L
    val armed = drone.telemetry.map { it.isFlying || it.motorsOn }.distinctUntilChanged()
    combine(drone.obstacles, armed) { o, a -> o to a }.collect { (o, a) ->
        val next = w.next(o)
        val sound = if (a) next.level else ObstacleLevel.NONE
        if (sound != beeping) {
            beeping = sound
            beep?.cancel()
            beep = when (sound) {
                ObstacleLevel.NONE -> null
                else -> launch {
                    val gap = if (sound == ObstacleLevel.DANGER) AppConfig.Sound.OBSTACLE_DANGER_GAP_MS else AppConfig.Sound.OBSTACLE_CAUTION_GAP_MS
                    while (true) { tone.obstacleBeep(); delay(gap) }
                }
            }
        }
        val all = o?.distancesM.orEmpty()
        val now = System.currentTimeMillis()
        val flying = drone.telemetry.value.isFlying
        val near = all.any { (d, m) -> m < Tuning.Obstacle.LOG_M && (flying || d != ObstacleDirection.DOWN) }
        if (next.level != w.level || (near && now - loggedAt >= AppConfig.Polling.OBSTACLE_LOG_MS)) {
            loggedAt = now
            OpLog.log("obstacle", next.level.name, all.entries.associate { (d, m) -> d.name to Math.round(m * 10) / 10.0 } + ("armed" to a))
        }
        w = next
        set(w)
    }
}
