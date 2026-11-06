package com.example.myfirstapp

import com.example.myfirstapp.ui.home.newestPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 홈 앨범 타일 바탕(10.5.5, 11-06 RC: 사진이 수백 장인데 회색). */
class NewestPhotoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, at: Long) = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText("x"); setLastModified(at) }

    @Test
    fun `DB 사진과 manual 사진 중 늦은 것, 지워진 DB 파일은 건너뛴다`() {
        val old = file("projects/1/missions/2/images/a.jpg", 1_000_000)
        val manual = file("manual/20261106/manual_1_2_WIDE.jpg", 3_000_000)
        file("manual/20261105/manual_0_1_WIDE.jpg", 4_000_000) // 지난 날 폴더는 보지 않는다(최근 날짜 폴더의 최신 파일)
        val gone = File(tmp.root, "projects/1/missions/2/images/gone.jpg").path
        assertEquals(manual, newestPhoto(listOf(gone, old.path), tmp.root))
        val newer = file("projects/1/missions/2/images/b.jpg", 5_000_000)
        assertEquals(newer, newestPhoto(listOf(newer.path, old.path), tmp.root))
        assertEquals(old, newestPhoto(listOf(gone, old.path), null))
        assertNull(newestPhoto(listOf(gone), File(tmp.root, "없음")))
    }
}
