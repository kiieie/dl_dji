package com.example.myfirstapp.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPU 캐시 두 단계 열기(9.7): 토큰·옛 캐시 고르기, 만들 차례 판단, 준비 표시. */
class GpuCacheTest {
    private val fast = GpuCacheNames.token("f-640", "2.16.1|fp|100|1|640|0")
    private val slow = GpuCacheNames.token("f-640", "2.16.1|fp|100|1|640|1")

    @Test
    fun `토큰은 16자리이고 모델 몫이 같다`() {
        assertEquals(16, fast.length)
        assertEquals(fast.take(8), slow.take(8))
        assertTrue(fast != slow)
        assertTrue(GpuCacheNames.token("f-1280", "x").take(8) != fast.take(8))
    }

    @Test
    fun `다 쓴 캐시 파일만 있는 것으로 센다`() {
        assertTrue(GpuCacheNames.has(listOf("${slow}_123.bin"), slow))
        assertFalse(GpuCacheNames.has(listOf("${slow}1231700000000"), slow)) // 쓰는 중인 임시 파일
        assertFalse(GpuCacheNames.has(listOf("${fast}_123.bin"), slow))
    }

    @Test
    fun `같은 모델의 옛 토큰 파일만 지운다`() {
        val old = GpuCacheNames.token("f-640", "2.16.0|fp|100|1|640|1")
        val other = GpuCacheNames.token("f-1280", "2.16.1|fp|100|1|1280|1")
        val names = listOf("${fast}_1.bin", "${slow}_2.bin", "${slow}21700000000", "${old}_3.bin", "${old}31700000000", "${other}_4.bin", "readme.txt")
        assertEquals(listOf("${old}_3.bin", "${old}31700000000"), GpuCacheNames.stale(names, listOf(fast, slow)))
    }

    @Test
    fun `쉴 이유가 없을 때 캐시 없는 첫 모델부터 만든다`() {
        assertEquals("비행 중", gpuBuildPause("비행 중", 0))
        assertEquals("탐지기 사용 중", gpuBuildPause(null, 1))
        assertNull(gpuBuildPause(null, 0))
        val targets = listOf("f-640", "f-1280")
        assertEquals("f-640", nextGpuBuild(targets, emptySet(), emptySet(), null))
        assertEquals("f-1280", nextGpuBuild(targets, setOf("f-640"), emptySet(), null))
        assertEquals("f-1280", nextGpuBuild(targets, emptySet(), setOf("f-640"), null))
        assertNull(nextGpuBuild(targets, emptySet(), emptySet(), "비행 중"))
        assertNull(nextGpuBuild(targets, setOf("f-640", "f-1280"), emptySet(), null))
    }

    @Test
    fun `준비 표시`() {
        assertNull(GpuPrep().text)
        assertEquals("모델 GPU 준비 완료", GpuPrep(2, 2).text)
        assertEquals("모델 GPU 준비 중 1/2", GpuPrep(1, 2, building = true).text)
        assertEquals("모델 GPU 준비 1/2 · 대기(비행 중)", GpuPrep(1, 2, pause = "비행 중").text)
        assertEquals("모델 GPU 준비 1/2 · 실패 1", GpuPrep(1, 2, failed = 1).text)
    }

    @Test
    fun `쓰는 모델은 비행 중 다음 정밀, 같으면 하나`() {
        val models = listOf(ModelChoice("f-1280", ModelPick.FULL, 1280), ModelChoice("f-960", ModelPick.LIVE, 960), ModelChoice("f-640", ModelPick.LIVE, 640))
        assertEquals(listOf("f-640", "f-1280"), ModelPick.inUse(models, null, null))
        assertEquals(listOf("f-960", "f-1280"), ModelPick.inUse(models, "f-960", null))
        assertEquals(listOf("f-640"), ModelPick.inUse(listOf(ModelChoice("f-640", ModelPick.LIVE, 640)), null, null))
    }
}
