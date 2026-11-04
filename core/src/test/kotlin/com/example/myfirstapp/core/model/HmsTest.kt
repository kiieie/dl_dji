package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Enu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HmsTest {
    private fun issue(level: HmsLevel) = HmsIssue("0x16100001", "제목", "설명", level)

    @Test
    fun `HMS 전체 등급은 가장 높은 항목, 없으면 표준`() {
        assertEquals(HmsLevel.NORMAL, hmsLevel(emptyList()))
        assertEquals(HmsLevel.NORMAL, hmsLevel(listOf(issue(HmsLevel.NORMAL))))
        assertEquals(HmsLevel.CAUTION, hmsLevel(listOf(issue(HmsLevel.NORMAL), issue(HmsLevel.CAUTION))))
        assertEquals(HmsLevel.SERIOUS, hmsLevel(listOf(issue(HmsLevel.SERIOUS), issue(HmsLevel.CAUTION))))
    }

    @Test
    fun `정보 코드 앞 두 자리로 모듈을 고른다`() {
        assertEquals("비행 컨트롤러", hmsModule("0x16100001"))
        assertEquals("비행 컨트롤러", hmsModule("0X20010000"))
        assertEquals("비전 시스템", hmsModule("0x1a010007"))
        assertEquals("짐벌 카메라", hmsModule("0x1D000001"))
        assertEquals("배터리", hmsModule("0x11000002"))
        assertEquals("기타", hmsModule("0x1F000001"))
        assertEquals("기타", hmsModule(""))
        // 모든 모듈은 목록 줄에 있다
        listOf("0x16", "0x11", "0x1A", "0x1C", "0x17", "0x1B", "0x19", "0x15").forEach { assertEquals(true, hmsModule(it) in HMS_MODULES) }
    }

    @Test
    fun `GEO 요약은 품은 높이 제한, 가까운 구역과 거리`() {
        val at = GeoPoint(37.0, 127.0)
        val p = LocalProjection(at)
        val circle = GeoZone("공항", listOf(GeoArea(p.toGeo(Enu(0.0, 3000.0)), radiusM = 1000.0)))
        val height = GeoZone("고도 제한", listOf(GeoArea(at, radiusM = 500.0, limitM = 120)))
        val square = GeoZone(
            "사각", listOf(GeoArea(points = listOf(Enu(-100.0, -100.0), Enu(100.0, -100.0), Enu(100.0, 100.0), Enu(-100.0, 100.0)).map(p::toGeo), limitM = 60)),
        )
        val none = geoSummary(listOf(circle), at, 1500)
        assertEquals(1500, none.maxAltitudeM)
        assertEquals("공항", none.nearest)
        assertEquals(2000.0, none.nearestM!!, 1.0)
        val inside = geoSummary(listOf(circle, height, square), at, 1500)
        assertEquals(60, inside.maxAltitudeM)
        assertEquals(3, inside.zones)
        assertEquals(0.0, inside.nearestM!!, 0.001)
        val empty = geoSummary(emptyList(), at, 1500)
        assertEquals(GeoSummary(1500, 0, null, null), empty)
        assertNull(geoSummary(listOf(GeoZone("빈", emptyList())), at, 1500).nearest)
    }

    @Test
    fun `설정 범위는 기체 값, 못 읽으면 기본 범위이고 값은 그 안으로`() {
        val fallback = 20..1500
        assertEquals(20..500, settingRange(20, 500, fallback))
        assertEquals(fallback, settingRange(null, 500, fallback))
        assertEquals(fallback, settingRange(20, null, fallback))
        assertEquals(fallback, settingRange(600, 500, fallback))
        // 스테퍼·값 칸은 받은 범위로 자른다(기체 RTH 고도 20~500)
        assertEquals(500, (480 + 100).coerceIn(settingRange(20, 500, fallback)))
        assertEquals(20, (30 - 100).coerceIn(settingRange(20, 500, fallback)))
    }
}
