package com.example.myfirstapp.core.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class PoleCsvParserTest {

    private fun success(r: CsvImportResult) = r as CsvImportResult.Success

    @Test
    fun `UTF-8 BOM과 영문 헤더를 읽는다`() {
        val csv = "﻿pole_id,lat,lon,line_name,note\nA-1,37.5,127.0,OO선,비고\nA-2,37.501,127.001,,\n"
        val r = success(PoleCsvParser.parse(csv.toByteArray(Charsets.UTF_8)))
        assertEquals("UTF-8", r.encoding)
        assertEquals(2, r.poles.size)
        assertEquals("OO선", r.poles[0].lineName)
        assertEquals("비고", r.poles[0].note)
        assertNull(r.poles[1].lineName)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `CP949 한글 헤더를 판별해 읽는다`() {
        val csv = "전주번호,위도,경도,선로명\r\nB-1,36.1,128.2,가나선\r\n"
        val r = success(PoleCsvParser.parse(csv.toByteArray(Charset.forName("x-windows-949"))))
        assertEquals("CP949", r.encoding)
        assertEquals("B-1", r.poles.single().poleId)
        assertEquals("가나선", r.poles.single().lineName)
        assertEquals(36.1, r.poles.single().position.lat, 1e-9)
    }

    @Test
    fun `누락, 숫자 오류, 범위 밖, 중복을 보고하고 해당 행을 제외한다`() {
        val csv = """
            Pole ID,Latitude,Longitude
            A-1,37.5,127.0
            ,37.5,127.0
            A-3,abc,127.0
            A-4,127.0,37.5
            A-5,10.0,10.0
            A-1,37.6,127.1
        """.trimIndent()
        val r = success(PoleCsvParser.parse(csv.toByteArray()))
        assertEquals(listOf("A-1"), r.poles.map { it.poleId })
        assertEquals(
            listOf(CsvIssue.Kind.MISSING_VALUE, CsvIssue.Kind.INVALID_NUMBER, CsvIssue.Kind.OUT_OF_RANGE, CsvIssue.Kind.OUT_OF_RANGE, CsvIssue.Kind.DUPLICATE_ID),
            r.issues.map { it.kind },
        )
        assertEquals(listOf(3, 4, 5, 6, 7), r.issues.map { it.line })
        assertTrue(r.issues[2].message.contains("바뀐"))
        assertTrue(!r.issues[3].message.contains("바뀐"))
    }

    @Test
    fun `큰따옴표 안의 쉼표와 줄바꿈을 처리한다`() {
        val csv = "pole_id,lat,lon,note\n\"A,1\",37.5,127.0,\"두 줄\n비고 \"\"인용\"\"\"\nA-2,37.5,127.0,x\n"
        val r = success(PoleCsvParser.parse(csv.toByteArray()))
        assertEquals(listOf("A,1", "A-2"), r.poles.map { it.poleId })
        assertEquals("두 줄\n비고 \"인용\"", r.poles[0].note)
    }

    @Test
    fun `탭 구분자를 판별한다`() {
        val csv = "id\tlat\tlng\nA-1\t37.5\t127.0\n"
        assertEquals(1, success(PoleCsvParser.parse(csv.toByteArray())).poles.size)
    }

    @Test
    fun `필수 헤더가 없으면 HeaderError`() {
        val r = PoleCsvParser.parse("name,foo,bar\nA,1,2\n".toByteArray())
        r as CsvImportResult.HeaderError
        assertEquals(listOf(PoleCsvParser.Column.POLE_ID, PoleCsvParser.Column.LAT, PoleCsvParser.Column.LON), r.missing)
    }

    @Test
    fun `빈 파일은 HeaderError`() {
        assertTrue(PoleCsvParser.parse(ByteArray(0)) is CsvImportResult.HeaderError)
    }
}
