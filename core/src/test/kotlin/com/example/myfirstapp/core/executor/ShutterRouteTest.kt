package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.model.PhotoModes
import com.example.myfirstapp.core.model.ShootMode
import com.example.myfirstapp.core.model.shootModeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShutterRouteTest {

    @Test
    fun `땅 위 셔터는 수동 사진 경로(Pilot 2처럼 찍음), 실행기 상태와 무관`() {
        listOf(ExecState.Idle, ExecState.Uploaded, ExecState.PreflightCheck, ExecState.SortieDone, ExecState.MissionDone).forEach {
            assertEquals(it.name, ShutterRoute.MANUAL, shutterRoute(it, flying = false))
        }
    }

    @Test
    fun `자동 비행 중에만 막고, 일시정지는 실행기`() {
        listOf(ExecState.Takeoff, ExecState.FlyingLeg, ExecState.Arrived, ExecState.Capturing, ExecState.Waiting).forEach {
            assertEquals(it.name, ShutterRoute.BLOCKED, shutterRoute(it, flying = true))
        }
        assertEquals(ShutterRoute.EXECUTOR, shutterRoute(ExecState.Paused, flying = true))
    }

    @Test
    fun `떠 있는데 실행기가 쉬면 실행기(수동 비행에서 넘어옴), RTH 중은 수동 사진 경로`() {
        assertEquals(ShutterRoute.EXECUTOR, shutterRoute(ExecState.Idle, flying = true))
        assertEquals(ShutterRoute.EXECUTOR, shutterRoute(ExecState.SortieDone, flying = true))
        assertEquals(ShutterRoute.MANUAL, shutterRoute(ExecState.ReturningHome, flying = true))
    }

    @Test
    fun `촬영 모드 - 파노라마는 늘 막고 땅에서는 Pilot 2 문구, 기체가 모르는 모드도 막음`() {
        val basic = PhotoModes.BASIC.modes
        assertEquals("기체 비행 전. 파노라마 촬영 불가", shootModeBlock(ShootMode.PANORAMA, basic + ShootMode.PANORAMA, flying = false))
        assertNotNull(shootModeBlock(ShootMode.PANORAMA, basic + ShootMode.PANORAMA, flying = true))
        assertNotNull(shootModeBlock(ShootMode.SMART, basic, flying = true))
        assertNull(shootModeBlock(ShootMode.SMART, basic + ShootMode.SMART, flying = false))
        assertNull(shootModeBlock(ShootMode.TIMER, basic, flying = false))
    }
}
