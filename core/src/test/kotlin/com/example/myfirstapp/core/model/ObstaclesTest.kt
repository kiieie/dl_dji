package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.model.ObstacleDirection.BACK
import com.example.myfirstapp.core.model.ObstacleDirection.DOWN
import com.example.myfirstapp.core.model.ObstacleDirection.FRONT
import com.example.myfirstapp.core.model.ObstacleDirection.LEFT
import com.example.myfirstapp.core.model.ObstacleDirection.RIGHT
import com.example.myfirstapp.core.model.ObstacleDirection.UP
import com.example.myfirstapp.core.model.ObstacleLevel.CAUTION
import com.example.myfirstapp.core.model.ObstacleLevel.DANGER
import com.example.myfirstapp.core.model.ObstacleLevel.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObstaclesTest {

    @Test
    fun `수평 배열을 앞·오른쪽·뒤·왼쪽 칸의 최솟값으로 묶는다`() {
        // 45°마다 8칸: 0 앞, 45(경계)·90 오른쪽, 135·180 뒤, 225(값 0이라 버림)·270 왼쪽, 315 앞
        val o = Obstacles.fromHorizontal(listOf(20.0, 9.0, 30.0, 40.0, 8.0, 0.0, 7.0, 6.0), 45, upM = 3.0, downM = 1.0)
        assertEquals(mapOf(FRONT to 6.0, RIGHT to 9.0, BACK to 8.0, LEFT to 7.0, UP to 3.0, DOWN to 1.0), o.distancesM)
    }

    @Test
    fun `등급은 주의 10m, 위험 5m 이하이고 1m 더 멀어져야 내린다`() {
        assertEquals(NONE, ObstacleWarning.grade(10.5, NONE))
        assertEquals(CAUTION, ObstacleWarning.grade(10.0, NONE))
        assertEquals(DANGER, ObstacleWarning.grade(5.0, CAUTION))
        assertEquals(DANGER, ObstacleWarning.grade(5.8, DANGER))
        assertEquals(CAUTION, ObstacleWarning.grade(6.2, DANGER))
        assertEquals(CAUTION, ObstacleWarning.grade(10.8, CAUTION))
        assertEquals(NONE, ObstacleWarning.grade(11.2, CAUTION))
    }

    @Test
    fun `경고는 가장 가까운 방향을 보이고 아래는 매기지 않는다`() {
        var w = ObstacleWarning()
        w = w.next(Obstacles(mapOf(FRONT to 6.4, LEFT to 9.0, BACK to 30.0, DOWN to 1.0)))
        assertEquals(mapOf(FRONT to CAUTION, LEFT to CAUTION), w.levels)
        assertEquals(CAUTION, w.level)
        assertEquals("앞 장애물 6.4m", w.text)
        // 4.9m에서 위험으로 오른 뒤 5.9m로 멀어져도 위험 유지
        w = w.next(Obstacles(mapOf(FRONT to 4.9))).next(Obstacles(mapOf(FRONT to 5.9)))
        assertEquals(DANGER, w.level)
        w = w.next(null)
        assertEquals(NONE, w.level)
        assertNull(w.text)
    }
}
