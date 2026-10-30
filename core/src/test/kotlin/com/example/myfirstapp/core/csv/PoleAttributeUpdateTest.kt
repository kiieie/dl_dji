package com.example.myfirstapp.core.csv

import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoleAttributeUpdateTest {

    private val here = GeoPoint(35.0, 127.0)

    @Test
    fun `전산화번호로 맞춰 속성만 바꾸고 좌표는 둔다`() {
        val existing = listOf(
            Pole("P1", here, lineName = "대평선", lineNo = "13", voltage = null, note = "기존"),
            Pole("P2", here, lineName = "대평선", lineNo = "14"),
            Pole("P3", here),
        )
        val file = listOf(
            Pole("P1", GeoPoint(36.0, 128.0), lineName = "대평선", lineNo = "13", voltage = "고압", lengthM = 16.0, equipment = "가공뱅크"),
            Pole("P2", here, lineName = "대평선", lineNo = "14"),
            Pole("NEW", here, voltage = "저압"),
        )
        val d = diffPoleAttributes(existing, file)

        assertEquals(listOf("P1"), d.changed.map { it.poleId })
        val p1 = d.changed.single()
        assertEquals(here, p1.position)
        assertEquals("고압", p1.voltage)
        assertEquals(16.0, p1.lengthM!!, 0.0)
        assertEquals("가공뱅크", p1.equipment)
        assertEquals("기존", p1.note) // 파일 빈 칸은 기존 값
        assertEquals(1, d.newInFile)
        assertEquals(1, d.missingInFile)
    }

    @Test
    fun `지선주는 파일 값을 따른다`() {
        val d = diffPoleAttributes(listOf(Pole("P1", here, guy = true)), listOf(Pole("P1", here, guy = false)))
        assertEquals(false, d.changed.single().guy)
    }

    @Test
    fun `같은 파일이면 바뀌는 전주가 없다`() {
        val poles = listOf(Pole("P1", here, voltage = "고압"))
        val d = diffPoleAttributes(poles, poles)
        assertTrue(d.changed.isEmpty())
        assertEquals(0, d.newInFile)
        assertEquals(0, d.missingInFile)
    }
}
