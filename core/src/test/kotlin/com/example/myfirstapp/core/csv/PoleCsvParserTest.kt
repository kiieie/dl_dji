package com.example.myfirstapp.core.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    @Test
    fun `설비 엑셀의 도분초 좌표를 십진수로 바꾼다`() {
        assertEquals(127 + 21 / 60.0 + 50.09 / 3600.0, PoleCsvParser.coordinate("127.21.50.09")!!, 1e-9)
        assertEquals(36 + 28 / 60.0 + 0.8175 / 3600.0, PoleCsvParser.coordinate("36.28.0.8175")!!, 1e-9)
        assertEquals(36.4755, PoleCsvParser.coordinate("36.4755")!!, 1e-12)
        assertNull(PoleCsvParser.coordinate("가.나.다"))
    }

    /** 공유 문자열, 숫자 셀, 빈 셀(열 건너뜀)이 섞인 최소 xlsx. */
    private fun xlsx(): ByteArray {
        val strings = listOf("전산화번호", "선로명", "선로번호", "고/저압", "설치기기", "전주규격", "설치년월", "경도", "위도",
            "1365P152", "대평선", "13", "고압", "가공뱅크", "16M", "127.16.50.46", "36.28.7.447", "1365X001", "지선주", "127.16.51.00", "36.28.8.000")
        fun s(ref: String, i: Int) = """<c r="$ref" t="s"><v>$i</v></c>"""
        val sheet = """<worksheet><sheetData>
<row r="1">${s("A1", 0)}${s("B1", 1)}${s("C1", 2)}${s("D1", 3)}${s("E1", 4)}${s("F1", 5)}${s("G1", 6)}${s("H1", 7)}${s("I1", 8)}</row>
<row r="2">${s("A2", 9)}${s("B2", 10)}${s("C2", 11)}${s("D2", 12)}${s("E2", 13)}${s("F2", 14)}<c r="G2"><v>201805</v></c>${s("H2", 15)}${s("I2", 16)}</row>
<row r="3">${s("A3", 17)}${s("B3", 10)}${s("D3", 18)}${s("H3", 19)}${s("I3", 20)}</row>
</sheetData></worksheet>"""
        val shared = "<sst>" + strings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>"
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("xl/sharedStrings.xml")); z.write(shared.toByteArray())
                z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(sheet.toByteArray())
            }
        }.toByteArray()
    }

    @Test
    fun `설비 엑셀에서 선로번호·길이·설치기기·지선주를 읽는다`() {
        val r = success(PoleCsvParser.parse(xlsx()))
        assertEquals("XLSX", r.encoding)
        assertEquals(2, r.poles.size)
        val p = r.poles[0]
        assertEquals("1365P152", p.poleId)
        assertEquals("대평선 13", p.displayName)
        assertEquals(16.0, p.lengthM!!, 0.0)
        assertEquals(13.5, p.aboveGroundM!!, 1e-9)
        assertEquals("가공뱅크", p.equipment)
        assertEquals("201805", p.installedYm)
        assertEquals(36 + 28 / 60.0 + 7.447 / 3600.0, p.position.lat, 1e-9)
        assertTrue(!p.guy)
        val guy = r.poles[1]
        assertTrue(guy.guy)
        assertEquals("1365X001", guy.displayName) // 선로번호가 없으면 전주번호
        assertNull(guy.aboveGroundM)
    }

    /** 실제 대평선 설비 엑셀(git 제외, 있을 때만): 1,643개가 모두 들어오고 좌표가 대평 부근이다. */
    @Test
    fun `대평선 설비 엑셀을 그대로 읽는다`() {
        val f = java.io.File("../docs/drone_dl_test/drone_dl_test/261021_대평선_전주촬영/비행계획/대평선_배전전주_좌표 (추출분).xlsx")
        assumeTrue(f.exists())
        val r = success(PoleCsvParser.parse(f.readBytes()))
        assertEquals(1643, r.poles.size + r.issues.size)
        assertTrue(r.poles.size > 1600)
        assertTrue(r.poles.all { it.position.lat in 36.3..36.6 && it.position.lon in 127.1..127.5 })
        assertTrue(r.poles.count { it.guy } > 100)
        assertTrue(r.poles.count { it.lengthM != null } > 1600)
        println("대평선: 전주 ${r.poles.size}, 문제 ${r.issues.size} ${r.issues.take(3)}, 지선주 ${r.poles.count { it.guy }}, 설치기기 ${r.poles.count { it.equipment != null }}")
    }
}
