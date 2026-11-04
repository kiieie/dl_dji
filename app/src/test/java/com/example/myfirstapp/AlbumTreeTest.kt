package com.example.myfirstapp

import com.example.myfirstapp.data.AlbumPhoto
import com.example.myfirstapp.data.CaptureEntity
import com.example.myfirstapp.ui.album.AlbumGroup
import com.example.myfirstapp.ui.album.lineTree
import com.example.myfirstapp.ui.album.nodePath
import com.example.myfirstapp.ui.album.visibleRows
import com.example.myfirstapp.ui.result.clampListWidth
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone

/** 앨범 선로별 보기 나무(10.4.3)와 판독 목록 폭. */
class AlbumTreeTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private var id = 0L

    private fun photo(line: String?, no: String?, poleId: String, day: Int, hour: Int = 9, mission: Long = 1) = AlbumPhoto(
        CaptureEntity(
            id = ++id, visitId = 1, mediaId = "m$id", lens = "WIDE", source = "AUTO", lat = null, lon = null, altRel = 0.0, gimbalPitch = 0.0, heading = 0.0,
            takenAt = GregorianCalendar(utc).apply { clear(); set(2026, 10, day, hour, 0) }.timeInMillis,
        ),
        poleId, line, no, null, null, alertCount = 0, missionId = mission,
    )

    @Test
    fun `선로 - 전주(선로번호 자연 순서) - 날짜(최신 먼저), 선로 없음과 기타는 맨 뒤`() {
        val photos = listOf(
            photo("대평선", "137L28R13", "P13", 3),
            photo("대평선", "137L28R2", "P2", 3),
            photo("대평선", "137L28R2", "P2", 4, mission = 2), // 다른 미션·프로젝트라도 같은 전주
            photo("대평선", null, "P0", 4),
            photo(null, null, "X1", 3),
            photo("가평선", "5", "G5", 3),
        )
        val manual = listOf(AlbumGroup.ManualDay("20261102", listOf(File("a.jpg"))), AlbumGroup.ManualDay("20261104", listOf(File("b.jpg"), File("c.jpg"))))
        val tree = lineTree(photos, manual, utc)

        assertEquals(listOf("가평선", "대평선", "선로 없음", "기타"), tree.map { it.title })
        val daepyeong = tree[1]
        assertEquals(listOf("137L28R2", "137L28R13", "P0"), daepyeong.children.map { it.title })
        assertEquals(listOf(4, 2, 1, 1), listOf(daepyeong.count, daepyeong.children[0].count, daepyeong.children[1].count, daepyeong.children[2].count))
        assertEquals(listOf("2026-11-04", "2026-11-03"), daepyeong.children[0].children.map { it.title })
        assertEquals(listOf("2026-11-04", "2026-11-02"), tree[3].children.map { it.title })
        assertEquals(3, tree[3].count)

        // 펼친 칸만 아이가 보이고, 고른 날짜 칸의 경로는 선로 › 전주 › 날짜
        val pole = daepyeong.children[0]
        assertEquals(listOf("가평선", "대평선", "137L28R2", "2026-11-04", "2026-11-03", "137L28R13", "P0", "선로 없음", "기타"),
            visibleRows(tree, setOf(daepyeong.key, pole.key)).map { it.title })
        assertEquals(listOf("대평선", "137L28R2", "2026-11-03"), nodePath(tree, pole.children[1].key).map { it.title })
        assertEquals(emptyList<String>(), nodePath(tree, "없는 칸").map { it.title })
    }

    @Test
    fun `판독 목록 폭은 196~480dp`() {
        assertEquals(196f, clampListWidth(100f), 0f)
        assertEquals(300f, clampListWidth(300f), 0f)
        assertEquals(480f, clampListWidth(900f), 0f)
    }
}
