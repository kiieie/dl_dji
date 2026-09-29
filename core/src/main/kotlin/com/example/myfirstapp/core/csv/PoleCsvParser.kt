package com.example.myfirstapp.core.csv

import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 전주 CSV 가져오기(F-01, F-03, 4.1). */
object PoleCsvParser {

    /** 대한민국 좌표 범위(도서 지역 포함, 여유 있게). */
    val LAT_RANGE = 32.0..39.5
    val LON_RANGE = 124.0..132.0

    enum class Column { POLE_ID, LAT, LON, LINE_NAME, NOTE }

    private val REQUIRED = listOf(Column.POLE_ID, Column.LAT, Column.LON)

    /** 헤더 동의어. 비교 전에 소문자로 바꾸고 공백·`_`·`-`를 지운다. */
    private val SYNONYMS: Map<Column, Set<String>> = mapOf(
        Column.POLE_ID to setOf("poleid", "pole", "id", "전주번호", "전주", "전주id", "전산화번호"),
        Column.LAT to setOf("lat", "latitude", "위도", "y"),
        Column.LON to setOf("lon", "lng", "long", "longitude", "경도", "x"),
        Column.LINE_NAME to setOf("linename", "line", "선로명", "선로", "선로이름"),
        Column.NOTE to setOf("note", "memo", "remark", "비고", "메모"),
    )

    fun parse(bytes: ByteArray): CsvImportResult {
        val (text, encoding) = decode(bytes)
        val rows = splitRows(text)
        if (rows.isEmpty()) return CsvImportResult.HeaderError(encoding, REQUIRED, emptyList())

        val header = rows.first().fields
        val mapping = recognizeHeader(header)
        val missing = REQUIRED.filter { it !in mapping }
        if (missing.isNotEmpty()) return CsvImportResult.HeaderError(encoding, missing, header)

        val poles = mutableListOf<Pole>()
        val issues = mutableListOf<CsvIssue>()
        val seenIds = mutableSetOf<String>()
        for (row in rows.drop(1)) {
            if (row.fields.all { it.isBlank() }) continue
            fun field(c: Column) = mapping[c]?.let { row.fields.getOrNull(it) }?.trim().orEmpty()

            val id = field(Column.POLE_ID)
            val latText = field(Column.LAT)
            val lonText = field(Column.LON)
            val missingNames = buildList {
                if (id.isEmpty()) add("전주번호")
                if (latText.isEmpty()) add("위도")
                if (lonText.isEmpty()) add("경도")
            }
            if (missingNames.isNotEmpty()) {
                issues += CsvIssue(row.line, id.ifEmpty { null }, CsvIssue.Kind.MISSING_VALUE, "값 없음: ${missingNames.joinToString()}")
                continue
            }
            val lat = latText.toDoubleOrNull()
            val lon = lonText.toDoubleOrNull()
            if (lat == null || lon == null) {
                issues += CsvIssue(row.line, id, CsvIssue.Kind.INVALID_NUMBER, "좌표가 숫자가 아님: $latText, $lonText")
                continue
            }
            if (lat !in LAT_RANGE || lon !in LON_RANGE) {
                val hint = if (lon in LAT_RANGE && lat in LON_RANGE) " (위도와 경도가 바뀐 것으로 보임)" else ""
                issues += CsvIssue(row.line, id, CsvIssue.Kind.OUT_OF_RANGE, "대한민국 좌표 범위 밖: $lat, $lon$hint")
                continue
            }
            if (!seenIds.add(id)) {
                issues += CsvIssue(row.line, id, CsvIssue.Kind.DUPLICATE_ID, "중복 전주번호(첫 번째 행만 사용)")
                continue
            }
            poles += Pole(
                poleId = id,
                position = GeoPoint(lat, lon),
                lineName = field(Column.LINE_NAME).ifEmpty { null },
                note = field(Column.NOTE).ifEmpty { null },
            )
        }
        return CsvImportResult.Success(encoding, poles, issues)
    }

    /** 헤더 인식. 열 이름 → 열 번호. 같은 열이 여러 번 나오면 첫 번째를 쓴다. */
    fun recognizeHeader(header: List<String>): Map<Column, Int> {
        val result = mutableMapOf<Column, Int>()
        header.forEachIndexed { index, raw ->
            val key = normalize(raw)
            val column = SYNONYMS.entries.firstOrNull { key in it.value }?.key ?: return@forEachIndexed
            result.putIfAbsent(column, index)
        }
        return result
    }

    private fun normalize(s: String) = s.trim().removePrefix("﻿").lowercase().filterNot { it == ' ' || it == '_' || it == '-' }

    /** UTF-8(BOM 포함)로 엄격하게 해석하고, 실패하면 CP949로 읽는다. */
    internal fun decode(bytes: ByteArray): Pair<String, String> {
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
        try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
            return text to "UTF-8"
        } catch (_: CharacterCodingException) {
            val cp949 = CP949_NAMES.firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
                ?: return String(body, Charsets.ISO_8859_1) to "ISO-8859-1"
            return String(body, cp949) to "CP949"
        }
    }

    /** JVM과 Android(ICU)에서 이름이 다를 수 있어 순서대로 찾는다. */
    private val CP949_NAMES = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR")

    internal data class Row(val line: Int, val fields: List<String>)

    /** RFC 4180 방식: 큰따옴표 안의 쉼표·줄바꿈·`""`를 처리한다. 구분자는 헤더에서 쉼표/탭/세미콜론 중 판별한다. */
    internal fun splitRows(text: String): List<Row> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', '\t', ';').maxBy { d -> firstLine.count { it == d } }
        val rows = mutableListOf<Row>()
        var fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var line = 1
        var rowStart = 1
        var i = 0
        fun endField() { fields += sb.toString(); sb.clear() }
        fun endRow() {
            endField()
            rows += Row(rowStart, fields)
            fields = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> endField()
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                    line++
                    rowStart = line
                }
                else -> { if (c == '\n') line++; sb.append(c) }
            }
            i++
        }
        if (sb.isNotEmpty() || fields.isNotEmpty()) endRow()
        return rows
    }
}

data class CsvIssue(val line: Int, val poleId: String?, val kind: Kind, val message: String) {
    enum class Kind { MISSING_VALUE, INVALID_NUMBER, OUT_OF_RANGE, DUPLICATE_ID }
}

sealed interface CsvImportResult {
    val encoding: String

    /** 필수 헤더(전주번호, 위도, 경도)를 찾지 못함. MVP 이후 컬럼 매핑 화면으로 처리한다. */
    data class HeaderError(override val encoding: String, val missing: List<PoleCsvParser.Column>, val header: List<String>) : CsvImportResult

    /** 유효한 전주와, 제외한 행의 문제 목록. */
    data class Success(override val encoding: String, val poles: List<Pole>, val issues: List<CsvIssue>) : CsvImportResult
}
