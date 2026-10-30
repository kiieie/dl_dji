package com.example.myfirstapp.core.input

import com.example.myfirstapp.core.port.RcButton.FIVE_D_CENTER
import com.example.myfirstapp.core.port.RcButton.FIVE_D_DOWN
import com.example.myfirstapp.core.port.RcButton.FIVE_D_RIGHT
import com.example.myfirstapp.core.port.RcButton.FIVE_D_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시각은 2026-10-29 RC Pro 로그(`rckey.log`)의 실측 간격을 쓴다. */
class FiveDGateTest {

    @Test
    fun `방향 하나는 기다린 뒤 내보낸다`() {
        val g = FiveDGate()
        assertEquals(emptyList<Any>(), g.press(FIVE_D_DOWN, 0).actions)
        assertEquals(emptyList<Any>(), g.tick(100).actions)
        assertEquals(listOf(FIVE_D_DOWN), g.tick(150).actions)
    }

    @Test
    fun `가운데 98ms 전에 기운 방향이 오면 둘 다 버리고 겹침을 알린다`() {
        val g = FiveDGate()
        g.press(FIVE_D_UP, 0)
        val r = g.press(FIVE_D_CENTER, 98)
        assertTrue(r.conflict)
        assertEquals(emptyList<Any>(), r.actions + g.tick(1000).actions)
    }

    @Test
    fun `방향을 세게 눌러 가운데가 따라와도 저장하지 않는다`() {
        val g = FiveDGate()
        g.press(FIVE_D_RIGHT, 0)
        assertTrue(g.press(FIVE_D_CENTER, 1).conflict)
        assertEquals(emptyList<Any>(), g.tick(1000).actions)
    }

    @Test
    fun `가운데를 뗄 때 210ms 뒤 기운 방향은 버린다`() {
        val g = FiveDGate()
        g.press(FIVE_D_CENTER, 0)
        assertEquals(listOf(FIVE_D_CENTER), g.tick(150).actions)
        g.press(FIVE_D_UP, 210)
        assertEquals(emptyList<Any>(), g.tick(1000).actions)
    }

    @Test
    fun `겹친 뒤 잠시 지나 다시 누르면 받는다`() {
        val g = FiveDGate()
        g.press(FIVE_D_UP, 0)
        g.press(FIVE_D_CENTER, 98)
        g.press(FIVE_D_CENTER, 1000)
        assertEquals(listOf(FIVE_D_CENTER), g.tick(1150).actions)
    }

    @Test
    fun `방향을 빠르게 두 번 누르면 둘 다 받는다`() {
        val g = FiveDGate()
        g.press(FIVE_D_DOWN, 0)
        assertEquals(listOf(FIVE_D_DOWN), g.press(FIVE_D_DOWN, 100).actions)
        assertEquals(listOf(FIVE_D_DOWN), g.tick(250).actions)
    }
}

class FiveDFlowTest {
    @Test
    fun `흐름은 5D만 기다렸다 내보내고 다른 버튼은 버린다`() = kotlinx.coroutines.test.runTest {
        val got = mutableListOf<FiveDGate.Result>()
        kotlinx.coroutines.flow.flowOf(com.example.myfirstapp.core.port.RcButton.C1, FIVE_D_DOWN)
            .fiveD(now = { testScheduler.currentTime })
            .collect { got += it }
        assertEquals(listOf(FiveDGate.Result(listOf(FIVE_D_DOWN))), got)
    }

    @Test
    fun `줄 이동은 끝에서 멈춘다`() {
        assertEquals(-1, moveIndex(0, 1, 0))
        assertEquals(0, moveIndex(-1, 1, 3))
        assertEquals(2, moveIndex(2, 1, 3))
        assertEquals(0, moveIndex(0, -1, 3))
    }
}
