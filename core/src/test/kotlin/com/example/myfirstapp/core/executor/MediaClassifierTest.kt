package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.executor.MediaOwner.NONE
import com.example.myfirstapp.core.executor.MediaOwner.SCREEN
import com.example.myfirstapp.core.executor.MediaOwner.SHUTTER
import com.example.myfirstapp.core.executor.MediaOwner.WAYPOINT
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaClassifierTest {

    @Test
    fun `셔터를 눌렀는데 안 찍혀도 뒤이은 지점 사진은 지점 사진이다`() {
        val c = MediaClassifier()
        c.shutterPressed(1_000)
        assertEquals(WAYPOINT, c.classify(1_500, shot = 0))
    }

    @Test
    fun `지점 사진 직후 셔터를 누르면 셔터 사진, 다음 지점 사진은 다시 지점 사진`() {
        val c = MediaClassifier()
        assertEquals(WAYPOINT, c.classify(1_000, shot = 0))
        c.shutterPressed(1_200)
        assertEquals(SHUTTER, c.classify(1_300, shot = 0))
        assertEquals(SHUTTER, c.classify(1_350, shot = 0)) // 광각·망원 두 파일
        assertEquals(WAYPOINT, c.classify(2_000, shot = 1)) // 4방향 사선: 바로 다음 지점
    }

    @Test
    fun `이동 고도 지점에서도 같은 촬영 지점으로 보고 셔터를 가른다`() {
        val c = MediaClassifier()
        assertEquals(WAYPOINT, c.classify(1_000, shot = 2))
        c.shutterPressed(5_000)
        assertEquals(SHUTTER, c.classify(5_100, shot = 2)) // shotIndex가 앞 촬영 지점 2를 돌려줌
        assertEquals(WAYPOINT, c.classify(9_000, shot = 2)) // 누른 지 3초 뒤: 같은 지점의 늦은 파일
    }

    @Test
    fun `미션 밖에서는 셔터를 누른 3초 안의 사진만 셔터 사진이다`() {
        val c = MediaClassifier()
        assertEquals(NONE, c.classify(1_000, shot = null))
        c.shutterPressed(2_000)
        assertEquals(SHUTTER, c.classify(2_100, shot = null))
        assertEquals(SHUTTER, c.classify(2_200, shot = null))
        assertEquals(NONE, c.classify(5_100, shot = null))
    }

    @Test
    fun `화면 수동 촬영의 파일은 지점·셔터로 새지 않는다`() {
        val c = MediaClassifier()
        assertEquals(WAYPOINT, c.classify(500, shot = 0))
        c.screenStarted()
        c.shutterPressed(1_000) // 화면 촬영 중 RC 셔터
        assertEquals(SCREEN, c.classify(1_100, shot = 0)) // 첫 파일은 화면 촬영 것
        assertEquals(SHUTTER, c.classify(1_200, shot = 0))
        c.screenEnded(1_300)
        assertEquals(SHUTTER, c.classify(1_400, shot = 0))
    }

    @Test
    fun `화면 수동 촬영 뒤 둘째 파일은 지점 사진이 아니다`() {
        val c = MediaClassifier()
        assertEquals(WAYPOINT, c.classify(500, shot = 0))
        c.screenStarted()
        assertEquals(SCREEN, c.classify(1_000, shot = 0))
        c.screenEnded(1_000)
        assertEquals(SCREEN, c.classify(1_500, shot = 0)) // 망원 파일
        assertEquals(WAYPOINT, c.classify(3_000, shot = 0))
    }

    @Test
    fun `새 소티는 지점 기록을 비운다`() {
        val c = MediaClassifier()
        assertEquals(WAYPOINT, c.classify(500, shot = 0))
        c.shutterPressed(550)
        c.sortieStarted()
        assertEquals(WAYPOINT, c.classify(600, shot = 0)) // 새 소티의 0번 지점 사진(비우지 않으면 셔터로 봄)
    }
}
