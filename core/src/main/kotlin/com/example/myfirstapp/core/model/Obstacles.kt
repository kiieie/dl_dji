package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning

/** 장애물 방향(기수 기준). 화면 글자는 [label]. */
enum class ObstacleDirection(val label: String) { FRONT("앞"), RIGHT("오른쪽"), BACK("뒤"), LEFT("왼쪽"), UP("위"), DOWN("아래") }

/**
 * 방향별 가장 가까운 장애물 거리(m). 감지한 것이 없는 방향은 빠진다.
 * [sensingOn]이 거짓이면 기체 장애물 감지가 꺼짐(내비 "OFF"). [warningM]·[brakingM]은 기체 수평 경고·제동 거리(모르면 null, [Tuning.Obstacle]).
 */
data class Obstacles(
    val distancesM: Map<ObstacleDirection, Double>,
    val sensingOn: Boolean = true,
    val warningM: Double? = null,
    val brakingM: Double? = null,
) {
    /**
     * 내비 원판 부채꼴·위아래 막대 색(10.5.3, Pilot 2): 제동 거리 안 빨강, 경고 거리 안 주황, 감지 범위([Tuning.Obstacle.SENSING_M]) 안 초록, 그 밖·모름은 null.
     * 땅에서도 그린다(신호음만 모터가 돌 때).
     */
    fun sector(dir: ObstacleDirection): ObstacleLevel? {
        val m = distancesM[dir] ?: return null
        return when {
            m <= (brakingM ?: Tuning.Obstacle.DANGER_M) -> ObstacleLevel.DANGER
            m <= (warningM ?: Tuning.Obstacle.CAUTION_M) -> ObstacleLevel.CAUTION
            m <= Tuning.Obstacle.SENSING_M -> ObstacleLevel.NONE
            else -> null
        }
    }

    companion object {
        /**
         * 기체가 주는 수평 거리 배열(기수부터 시계 방향, [intervalDeg]마다)을 앞·오른쪽·뒤·왼쪽 90° 칸의 최솟값으로 묶는다.
         * 0 이하는 값 없음으로 버린다.
         */
        fun fromHorizontal(horizontalM: List<Double>, intervalDeg: Int, upM: Double?, downM: Double?): Obstacles {
            val m = mutableMapOf<ObstacleDirection, Double>()
            fun put(d: ObstacleDirection, v: Double?) {
                if (v != null && v > 0) m[d] = minOf(v, m[d] ?: v)
            }
            if (intervalDeg > 0) horizontalM.forEachIndexed { i, v ->
                val a = (i * intervalDeg + 45).mod(360)
                put(ObstacleDirection.entries[a / 90], v)
            }
            put(ObstacleDirection.UP, upM)
            put(ObstacleDirection.DOWN, downM)
            return Obstacles(m)
        }
    }
}

/** 충돌 경고 등급(13번, 2026-10-31). 순서가 위험도다. */
enum class ObstacleLevel { NONE, CAUTION, DANGER }

/**
 * 방향별 경고 등급과 거리. [next]로 새 측정을 받아 갱신한다.
 * 거리는 DJI Pilot 2 기본과 비슷하게 주의 [Tuning.Obstacle.CAUTION_M], 위험 [Tuning.Obstacle.DANGER_M] 이하이고, 한 번 오른 등급은 [Tuning.Obstacle.HYSTERESIS_M] 더 멀어져야 내린다(깜박임 방지).
 * 아래는 땅이 늘 보이므로(이착륙, 낮은 고도) 등급을 매기지 않는다.
 */
data class ObstacleWarning(
    val levels: Map<ObstacleDirection, ObstacleLevel> = emptyMap(),
    val distancesM: Map<ObstacleDirection, Double> = emptyMap(),
    /** 마지막 측정 그대로(내비 원판 부채꼴·막대, 10.5.3). 기체가 값을 안 주면 null(내비 "NA"). */
    val sensed: Obstacles? = null,
) {
    val level: ObstacleLevel get() = levels.values.maxOrNull() ?: ObstacleLevel.NONE

    /** 경고 중인 방향 가운데 가장 가까운 것. */
    val nearest: ObstacleDirection? get() = levels.keys.minByOrNull { distancesM[it] ?: Double.MAX_VALUE }

    /** 상단 상태 칸 글자, 예: "앞 장애물 6.4m". 경고가 없으면 null. */
    val text: String? get() = nearest?.let { "${it.label} 장애물 %.1fm".format(distancesM[it]) }

    /** 새 측정 [o](없으면 null: 기체 없음, 착륙)로 등급을 다시 매긴다. */
    fun next(o: Obstacles?): ObstacleWarning {
        val d = o?.distancesM.orEmpty().filterKeys { it != ObstacleDirection.DOWN }
        val l = d.mapValues { (dir, m) -> grade(m, levels[dir] ?: ObstacleLevel.NONE) }.filterValues { it != ObstacleLevel.NONE }
        return ObstacleWarning(l, d.filterKeys { it in l }, o)
    }

    companion object {
        fun grade(m: Double, prev: ObstacleLevel): ObstacleLevel = when {
            m <= Tuning.Obstacle.DANGER_M || (prev == ObstacleLevel.DANGER && m <= Tuning.Obstacle.DANGER_M + Tuning.Obstacle.HYSTERESIS_M) -> ObstacleLevel.DANGER
            m <= Tuning.Obstacle.CAUTION_M || (prev != ObstacleLevel.NONE && m <= Tuning.Obstacle.CAUTION_M + Tuning.Obstacle.HYSTERESIS_M) -> ObstacleLevel.CAUTION
            else -> ObstacleLevel.NONE
        }
    }
}
