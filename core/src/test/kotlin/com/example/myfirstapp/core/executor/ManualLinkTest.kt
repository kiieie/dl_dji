package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.lensOfFocal35
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 수동 사진의 전주·미션 고르기(6.4)와 사진 렌즈(5.6). 11-09 전문가 수동 비행 사례. */
class ManualLinkTest {
    private data class P(val id: String, val at: GeoPoint, val project: Long = 1)

    private fun near(at: GeoPoint?, poles: List<P>) = shutterPole(at, poles, { it.at }, { it.project }, { it.id }).map { it.id }

    private val base = GeoPoint(36.39, 127.386)
    private fun east(m: Double) = GeoPoint(base.lat, base.lon + m / (111_320.0 * Math.cos(Math.toRadians(base.lat))))

    @Test
    fun `가장 가까운 전주와 다른 프로젝트에 또 가져온 같은 전주`() {
        val poles = listOf(
            P("TEST0003", east(0.0)), P("TEST0004", east(15.0)), P("dup", east(15.4), project = 2),
            P("TEST0004", east(17.5), project = 3), P("pair", east(15.5)), P("far", east(40.0)),
        )
        // TEST0004 앞 1.6m: TEST0003(13.4m)도 20m 안이지만 가장 가까운 TEST0004만. 같은 프로젝트 쌍주(0.5m)는 빼고,
        // 다른 프로젝트는 1m 안(dup)이거나 전주번호가 같으면(2.5m) 넣는다. 가까운 순서
        assertEquals(listOf("TEST0004", "dup", "TEST0004"), near(east(13.4), poles))
        assertEquals(emptyList<String>(), near(east(70.0), poles))
        assertEquals(emptyList<String>(), near(null, poles))
    }

    @Test
    fun `수동 비행은 최근 비행 미션 먼저, 날지 않은 새 계획보다`() {
        val now = 1_000_000_000L
        val flown = ManualLinkCandidate(30, 300, createdAt = now - 86_400_000, flownAt = now - 600_000, open = true)
        val plan = ManualLinkCandidate(31, 310, createdAt = now - 300_000, flownAt = null, open = true)
        assertEquals(30L, pickManualLink(listOf(plan, flown), now)?.missionId)
    }

    @Test
    fun `비행이 오래됐으면 전주가 남은 가장 최근 미션, 없으면 null`() {
        val now = 1_000_000_000L
        val old = ManualLinkCandidate(10, 100, createdAt = now - 95_000_000, flownAt = now - 90_000_000, open = false)
        val openOld = ManualLinkCandidate(11, 110, createdAt = now - 80_000_000, flownAt = null, open = true)
        val openNew = ManualLinkCandidate(12, 120, createdAt = now - 70_000_000, flownAt = null, open = true)
        assertEquals(12L, pickManualLink(listOf(old, openOld, openNew), now)?.missionId)
        assertNull(pickManualLink(listOf(old), now))
    }

    @Test
    fun `미션 화면은 그 미션의 방문만`() {
        val c = ManualLinkCandidate(30, 300, createdAt = 0, flownAt = 1, open = true)
        assertEquals(300L, pickManualLink(listOf(c), 2, fixedMission = 30)?.visitId)
        assertNull(pickManualLink(listOf(c), 2, fixedMission = 31))
    }

    @Test
    fun `렌즈는 EXIF 초점으로, 줌 2~6_9배는 광각`() {
        assertEquals(Lens.WIDE, lensOfFocal35(24, Lens.ZOOM))
        assertEquals(Lens.ZOOM, lensOfFocal35(161, Lens.ZOOM))
        assertEquals(Lens.ZOOM, lensOfFocal35(161, Lens.WIDE))
        assertEquals(Lens.ZOOM, lensOfFocal35(0, Lens.ZOOM))
        assertEquals(Lens.THERMAL, lensOfFocal35(40, Lens.THERMAL))
    }
}
