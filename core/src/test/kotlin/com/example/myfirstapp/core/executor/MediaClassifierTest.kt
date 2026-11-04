package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.executor.MediaOwner.NONE
import com.example.myfirstapp.core.executor.MediaOwner.SCREEN
import com.example.myfirstapp.core.executor.MediaOwner.WAYPOINT
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.route.WpmlWriter.PhotoAction
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaClassifierTest {

    @Test
    fun `미션 밖에서 앱이 찍지 않은 사진은 넘기지 않는다(RC 셔터도 앱이 찍는다)`() {
        val c = MediaClassifier()
        assertEquals(NONE, c.classify(1_000, shot = null))
    }

    @Test
    fun `화면 수동 촬영의 첫 파일은 그 촬영 것, 지점 사진으로 새지 않는다`() {
        val c = MediaClassifier()
        assertEquals(WAYPOINT, c.classify(500, shot = 0))
        c.screenStarted()
        assertEquals(SCREEN, c.classify(1_100, shot = 0))
        assertEquals(SCREEN, c.classify(1_200, shot = 0)) // 같은 촬영의 둘째 파일
        c.screenEnded(1_300)
        assertEquals(SCREEN, c.classify(1_400, shot = 0))
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
        assertEquals(WaypointFile(0, 0), c.classifyFile(500, shot = 0).second)
        c.sortieStarted()
        assertEquals(WaypointFile(0, 0), c.classifyFile(600, shot = 0).second) // 새 소티의 0번 지점 첫 파일
    }

    @Test
    fun `렌즈를 모르면 다음 지점에 막 도착했을 때 온 파일도 그 지점 것(앞 지점 파일이 덜 왔어도)`() {
        val c = MediaClassifier().apply { sortieStarted(filesPerShot = 2) }
        c.shotReached(0, 0)
        c.shotReached(1, 10_000) // 0번 사진이 아직 안 옴
        assertEquals(WaypointFile(1, 0), c.classifyFile(10_300, shot = 1).second)
        assertEquals(WaypointFile(1, 1, 1), c.classifyFile(10_400, shot = 1).second)
    }

    @Test
    fun `한 지점이 통째로 안 찍혀도 뒤 지점은 밀리지 않는다`() {
        val c = MediaClassifier().apply { sortieStarted(filesPerShot = 1) }
        c.shotReached(0, 0)
        assertEquals(WaypointFile(0, 0), c.classifyFile(3_000, shot = 0).second)
        c.shotReached(1, 10_000) // 1번 실패
        c.shotReached(2, 20_000)
        assertEquals(WaypointFile(2, 0), c.classifyFile(23_000, shot = 2).second)
        c.shotReached(3, 30_000)
        assertEquals(WaypointFile(3, 0), c.classifyFile(33_000, shot = 3).second)
    }

    private val wide = PhotoAction(listOf(Lens.WIDE), false)
    private val zoom = PhotoAction(listOf(Lens.ZOOM), false)

    @Test
    fun `11-03 현장 - 동작마다 1장, 다음 지점 도착 1초 안에 온 그 지점 사진은 그 지점 것(방향 0·1·2·3 한 번씩)`() {
        // 렌즈 동시 저장이 거절된 소티: 한 동작(wide,zoom)에 1장만 온다 → 어댑터가 동작의 렌즈를 하나로 줄여 넘김
        val c = MediaClassifier().apply { sortieStarted { listOf(wide) } }
        val got = (0 until 4).map { k ->
            val at = 10_000L * (k + 1)
            c.shotReached(k, at)
            c.classifyFile(at + 500 + 100L * k, shot = k).second!! // 0.5~0.8초 뒤(LATE_FILE_MS 안)
        }
        assertEquals(listOf(0, 1, 2, 3), got.map { it.shot })
        assertEquals(listOf(0, 0, 0, 0), got.map { it.ordinal })
    }

    @Test
    fun `렌즈별 동작 - 렌즈를 모르는 파일 하나가 빠져도 뒤 지점 파일이 밀리지 않는다`() {
        // 11-04 리뷰: 1번 지점 망원 파일이 빠지면 옛 규칙은 2번 지점 광각을 1번 망원 자리로 당겨 소티 끝까지 한 칸씩 밀렸다
        val c = MediaClassifier().apply { sortieStarted { listOf(wide, zoom) } }
        val got = (0 until 4).flatMap { k ->
            val at = 10_000L * (k + 1)
            c.shotReached(k, at)
            listOfNotNull(
                c.classifyFile(at + 600, shot = k).second!!,
                if (k == 1) null else c.classifyFile(at + 1_500, shot = k).second!!, // 1번 망원 없음
            )
        }
        assertEquals(listOf(0, 0, 1, 2, 2, 3, 3), got.map { it.shot })
        assertEquals(listOf(0, 1, 0, 0, 1, 0, 1), got.map { it.ordinal })
    }

    @Test
    fun `렌즈를 알면 앞 지점의 빈 망원 자리를 늦게 온 망원 파일로 채운다`() {
        val c = MediaClassifier().apply { sortieStarted { listOf(wide, zoom) } }
        c.shotReached(0, 0)
        assertEquals(WaypointFile(0, 0, 0, Lens.WIDE), c.classifyFile(600, shot = 0, lens = Lens.WIDE).second)
        c.shotReached(1, 3_000)
        // 0번 망원이 1번 도착 뒤에 옴: 1번은 다음에 광각을 기다리므로 0번 것
        assertEquals(WaypointFile(0, 1, 1, Lens.ZOOM), c.classifyFile(3_300, shot = 1, lens = Lens.ZOOM).second)
        assertEquals(WaypointFile(1, 0, 0, Lens.WIDE), c.classifyFile(3_600, shot = 1, lens = Lens.WIDE).second)
        assertEquals(WaypointFile(1, 1, 1, Lens.ZOOM), c.classifyFile(3_900, shot = 1, lens = Lens.ZOOM).second)
    }

    @Test
    fun `렌즈를 알면 앞 지점 망원이 끝내 안 와도 지금 지점 파일은 제자리`() {
        val c = MediaClassifier().apply { sortieStarted { listOf(wide, zoom) } }
        c.shotReached(0, 0)
        c.classifyFile(600, shot = 0, lens = Lens.WIDE)
        c.shotReached(1, 3_000) // 0번 망원은 끝내 안 옴
        assertEquals(WaypointFile(1, 0, 0, Lens.WIDE), c.classifyFile(3_500, shot = 1, lens = Lens.WIDE).second) // 광각은 0번 빈 자리가 아님
        assertEquals(WaypointFile(1, 1, 1, Lens.ZOOM), c.classifyFile(3_900, shot = 1, lens = Lens.ZOOM).second) // 1번이 다음에 기다리는 망원
    }

    @Test
    fun `일시정지 중 시간 초과한 앱 촬영의 늦은 파일은 지점 사진이 아니다`() {
        val c = MediaClassifier().apply { sortieStarted() }
        c.shotReached(0, 0)
        assertEquals(WAYPOINT, c.classify(600, shot = 0))
        c.screenStarted()
        c.screenEnded(10_000, owed = true) // 사진 알림 없이 끝남
        assertEquals(SCREEN, c.classify(20_000, shot = 0)) // 그 촬영의 늦은 파일
        assertEquals(WAYPOINT, c.classify(30_000, shot = 0))
        c.screenStarted()
        c.screenEnded(40_000, owed = true)
        c.missionResumed() // 재개하면 더 기다리지 않는다
        assertEquals(WAYPOINT, c.classify(50_000, shot = 0))
    }

    @Test
    fun `설비 전주 - 지점마다 동작 2개(지점·설비), 4지점 8장이 제자리로`() {
        val c = MediaClassifier().apply { sortieStarted { listOf(wide, wide.copy(equipment = true)) } }
        val got = (0 until 4).flatMap { k ->
            val at = 10_000L * (k + 1)
            c.shotReached(k, at)
            // 지점 사진은 도착 0.6초 뒤, 설비 사진은 짐벌을 내린 뒤 1.5초 뒤
            listOf(c.classifyFile(at + 600, shot = k).second!!, c.classifyFile(at + 1_500, shot = k).second!!)
        }
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3), got.map { it.shot })
        assertEquals(listOf(false, true, false, true, false, true, false, true), got.map { it.equipment })
    }

    @Test
    fun `렌즈별 동작 - 광각·망원·설비 광각·설비 망원 차례로 가른다`() {
        val c = MediaClassifier().apply { sortieStarted { listOf(wide, zoom, wide.copy(equipment = true), zoom.copy(equipment = true)) } }
        c.shotReached(0, 0)
        val got = listOf(2_000L, 3_000L, 4_000L, 5_000L).map { c.classifyFile(it, shot = 0).second!! }
        assertEquals(listOf(0, 1, 2, 3), got.map { it.action })
        assertEquals(listOf(Lens.WIDE, Lens.ZOOM, Lens.WIDE, Lens.ZOOM), got.map { it.lens })
        assertEquals(listOf(false, false, true, true), got.map { it.equipment })
    }

    @Test
    fun `파일 종류로 렌즈를 알면 빠진 동작을 건너뛴다`() {
        val c = MediaClassifier().apply { sortieStarted { listOf(wide, zoom) } }
        c.shotReached(0, 0)
        assertEquals(WaypointFile(0, 1, 1, Lens.ZOOM, false), c.classifyFile(2_000, shot = 0, lens = Lens.ZOOM).second) // 광각이 안 옴
        c.shotReached(1, 5_000)
        assertEquals(WaypointFile(1, 0, 0, Lens.WIDE, false), c.classifyFile(5_500, shot = 1, lens = Lens.WIDE).second) // 0번은 다 옴
    }

    @Test
    fun `한 동작에 렌즈 둘(동시 저장 됨)이면 그 동작의 렌즈 차례`() {
        val c = MediaClassifier().apply { sortieStarted { listOf(PhotoAction(listOf(Lens.WIDE, Lens.ZOOM), false)) } }
        c.shotReached(0, 0)
        assertEquals(WaypointFile(0, 0, 0, Lens.WIDE), c.classifyFile(1_000, shot = 0).second)
        assertEquals(WaypointFile(0, 1, 0, Lens.ZOOM), c.classifyFile(1_100, shot = 0).second)
    }
}
