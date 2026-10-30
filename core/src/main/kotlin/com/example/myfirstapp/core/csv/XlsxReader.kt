package com.example.myfirstapp.core.csv

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * .xlsx 첫 시트를 문자열 표로 읽는다(4.5). 새 의존성 없이 zip + XML(DOM)만 쓴다.
 * 공유 문자열·인라인 문자열·숫자 셀을 읽고, 빈 셀은 열 위치(`r="C5"`)로 채운다. 수식은 저장된 값만 쓴다.
 */
object XlsxReader {

    fun isXlsx(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun firstSheetRows(bytes: ByteArray): List<List<String>> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = z.readBytes()
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { xml ->
            val si = parse(xml).getElementsByTagName("si")
            List(si.length) { i -> texts(si.item(i) as Element) }
        }.orEmpty()
        // 시트 순서는 workbook.xml이 정하지만 내보낸 파일은 sheet1이 첫 시트다
        val sheet = files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
            ?: return emptyList()
        val rows = parse(files.getValue(sheet)).getElementsByTagName("row")
        return List(rows.length) { i ->
            val cells = mutableListOf<String>()
            val cs = (rows.item(i) as Element).getElementsByTagName("c")
            for (j in 0 until cs.length) {
                val c = cs.item(j) as Element
                val col = column(c.getAttribute("r")) ?: cells.size
                while (cells.size < col) cells += ""
                val v = c.getElementsByTagName("v").item(0)?.textContent
                cells += when (c.getAttribute("t")) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> texts(c)
                    else -> v.orEmpty()
                }
            }
            cells
        }
    }

    /** `r="AB12"` → 27(0부터). */
    private fun column(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
    }

    /** 요소 안의 `<t>`를 모두 이어 붙인다(서식이 섞인 문자열). */
    private fun texts(e: Element): String {
        val t = e.getElementsByTagName("t")
        return (0 until t.length).joinToString("") { t.item(it).textContent }
    }

    private fun parse(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
}
