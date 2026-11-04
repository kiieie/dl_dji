package com.example.myfirstapp.core.detect

import com.example.myfirstapp.core.model.Detection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 두 단계 탐지(9.6): 최근 1장 일꾼, 착륙 뒤 정밀 일꾼, 모델 고르기, 가운데 자르기·축소 디코딩. */
class TwoStageTest {
    @Test
    fun `최근 1장 일꾼은 처리 중에 온 사진 중 마지막 것만 남긴다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Int>()
        val worker = LatestOnly<Int>(backgroundScope) { x ->
            if (x == 1) gate.await()
            seen += x
        }
        worker.offer(1)
        runCurrent() // 1을 처리하는 중
        worker.offer(2)
        worker.offer(3)
        worker.offer(4)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 4), seen)
    }

    /** 가짜 DB: 남은 사진 목록. 처리(1초)가 끝나야 빠진다. */
    private class Db(vararg ids: Long) {
        val pending = ids.toMutableList()
        val processed = mutableListOf<Long>()
        val started = mutableListOf<Long>()
        var fail: Long? = null
        suspend fun process(id: Long) {
            started += id
            delay(1_000)
            if (id == fail) error("깨진 사진")
            pending -= id
            processed += id
        }
    }

    @Test
    fun `정밀 일꾼은 이륙하면 멈추고 착륙하면 남은 것부터 이어 한다`() = runTest {
        val db = Db(1, 2, 3, 4)
        val canRun = MutableStateFlow(true)
        var idle = 0
        val backfill = PreciseBackfill(canRun, { db.pending.toList() }, db::process, onIdle = { idle++ })
        backgroundScope.launch { backfill.run() }
        delay(1_500) // 1은 끝났고 2를 처리하는 중
        assertEquals(listOf(1L), db.processed)
        assertEquals(BackfillProgress(1, 4, running = true), backfill.progress.value)

        canRun.value = false // 소티 시작
        delay(10_000)
        assertEquals(listOf(1L), db.processed) // 2는 취소되어 DB에 남음
        assertFalse(backfill.progress.value.running)
        assertEquals(3, backfill.progress.value.remaining)
        assertTrue(idle >= 1) // 쉬는 동안 모델을 닫는다

        canRun.value = true // 착륙
        delay(3_500)
        assertEquals(listOf(1L, 2L, 3L, 4L), db.processed)
        assertEquals(listOf(1L, 2L, 2L, 3L, 4L), db.started) // 2만 다시, 끝낸 1은 다시 안 함
        assertEquals(BackfillProgress(), backfill.progress.value)
    }

    @Test
    fun `정밀 일꾼은 다 끝나면 새 사진이 올 때까지 기다리고, 실패한 사진은 건너뛴다`() = runTest {
        val db = Db(1, 2).apply { fail = 1L }
        val backfill = PreciseBackfill(MutableStateFlow(true), { db.pending.toList() }, db::process)
        backgroundScope.launch { backfill.run() }
        delay(5_000)
        assertEquals(listOf(2L), db.processed)
        assertEquals(listOf(1L, 2L), db.started) // 1은 실패 뒤 다시 잡지 않음

        db.pending += 5L // 착륙 뒤 받은 사진
        delay(5_000)
        assertEquals(listOf(2L), db.processed) // 알림 전에는 다시 보지 않는다
        backfill.kick()
        delay(1_500)
        assertEquals(listOf(2L, 5L), db.processed)
    }

    @Test
    fun `정밀 일꾼은 다 끝나도 잠시 모델을 열어 두고, 그 사이 온 사진은 다시 열지 않고 한다`() = runTest {
        val db = Db(1)
        val canRun = MutableStateFlow(true)
        var idle = 0
        val backfill = PreciseBackfill(canRun, { db.pending.toList() }, db::process, onIdle = { idle++ }, idleCloseMs = 60_000)
        backgroundScope.launch { backfill.run() }
        delay(30_000) // 1은 끝났고 새 사진을 기다리는 중
        assertEquals(listOf(1L), db.processed)
        assertEquals(0, idle)

        db.pending += 2L // 착륙 뒤 한 장 더 받음
        backfill.kick()
        delay(1_500)
        assertEquals(listOf(1L, 2L), db.processed)
        assertEquals(0, idle) // 모델을 닫지 않고 이어 했다

        canRun.value = false // 기다리는 중 이륙: 바로 닫는다
        runCurrent()
        assertEquals(1, idle)

        canRun.value = true // 착륙: 할 것이 없으면 기다린 뒤 닫는다
        delay(59_000)
        assertEquals(1, idle)
        delay(2_000)
        assertEquals(2, idle)
    }

    @Test
    fun `비행 중 모델은 live 중 입력이 가장 작은 것, 정밀은 full, full이 없으면 비행 중 모델`() {
        val models = listOf(
            ModelChoice("f-1280", ModelPick.FULL, 1280), ModelChoice("f-960", ModelPick.LIVE, 960),
            ModelChoice("f-640", ModelPick.LIVE, 640), ModelChoice("coco", null, 640),
        )
        assertEquals("f-640", ModelPick.liveOrder(models, null).first())
        assertEquals(listOf("f-640", "f-960", "coco", "f-1280"), ModelPick.liveOrder(models, null))
        assertEquals("f-960", ModelPick.liveOrder(models, "f-960").first())
        assertEquals("f-640", ModelPick.liveOrder(models, "없는 폴더").first())
        assertEquals("f-1280", ModelPick.fullOrder(models, null, null).first())
        assertEquals("f-960", ModelPick.fullOrder(models, "f-960", null).first())
        val noFull = models.filter { it.role != ModelPick.FULL }
        assertEquals("f-960", ModelPick.fullOrder(noFull, null, "f-960").first())
        // 역할이 없는 옛 모델만 있으면 예전처럼 이름이 가장 뒤인 것
        assertEquals("v2", ModelPick.liveOrder(listOf(ModelChoice("v1", null, 640), ModelChoice("v2", null, 640)), null).first())
    }

    @Test
    fun `가운데 자르기 상자는 원본 비율로, 축소 디코딩은 입력 이상만 남긴다`() {
        // M3E 광각 5280×3956, 가운데 0.5
        val full = centerRegion(5280, 3956, 0.5f)
        assertEquals(Region(1320, 989, 2640, 1978), full)
        val d = Detection("LP", 0.8f, 0f, 0.5f, 1f, 0.5f).fromRegion(full, 5280, 3956)
        assertEquals(0.25f, d.x, 1e-6f)
        assertEquals(0.5f, d.y, 1e-6f)
        assertEquals(0.5f, d.w, 1e-6f)
        assertEquals(0.25f, d.h, 1e-6f)
        // 9.7 O1: 원본 좌표로 가운데만 푼 상자(새)와 1/4로 전체를 풀어 자른 상자(옛)가 같은 자리로 간다(옛 쪽은 축소 픽셀 반올림만큼 어긋남)
        val sampled = centerRegion(5280 / 4, 3956 / 4, 0.5f)
        for (b in listOf(Detection("LP", 0.8f, 0.1f, 0.2f, 0.05f, 0.08f), Detection("SUS", 0.6f, 0.7f, 0.6f, 0.2f, 0.3f), d)) {
            val new = b.fromRegion(full, 5280, 3956)
            val old = b.fromRegion(sampled, 5280 / 4, 3956 / 4)
            assertTrue(new.iou(old) >= 0.95f)
        }
        assertEquals(1, agreeing(listOf(d), listOf(d.copy(score = 0.81f), d.copy(label = "SUS")), 0.9f, 0.02f))
        assertEquals(0, agreeing(listOf(d), listOf(d.copy(score = 0.85f)), 0.9f, 0.02f))
        // M3E 광각 5280: 1280 전체 → 4배(1320), 640 + 가운데 0.5 → 4배(자른 영역 660), 640 전체 → 8배(660)
        assertEquals(4, sampleSize(5280, 1f, 1280))
        assertEquals(4, sampleSize(5280, 0.5f, 640))
        assertEquals(8, sampleSize(5280, 1f, 640))
        assertEquals(1, sampleSize(640, 1f, 640))
    }

    @Test
    fun `JPEG 기준·점진 구분은 첫 SOF 마커로`() {
        fun jpeg(sof: Int) = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0, 4, 1, 2, // APP1(EXIF 자리) 길이 4
            0xFF.toByte(), 0xC4.toByte(), 0, 2, // DHT는 SOF가 아니다
            0xFF.toByte(), sof.toByte(), 0, 2,
        )
        assertEquals(0xC0, jpegSof(jpeg(0xC0)))
        assertFalse(isProgressiveJpeg(jpeg(0xC0)))
        assertTrue(isProgressiveJpeg(jpeg(0xC2)))
        assertEquals(null, jpegSof(byteArrayOf(1, 2, 3, 4, 5)))
        assertFalse(isProgressiveJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}
