package com.example.myfirstapp.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PoleSearchTest {
    private val at = GeoPoint(36.47, 127.28)
    private val poles = listOf(
        Pole("8126Z113", at, "대평선", lineNo = "113"),
        Pole("8126Z013", at, "대평선", lineNo = "13"),
        Pole("8126Z130", at, "대평선", lineNo = "130"),
        Pole("9000A001", at),
    )

    private fun ids(q: String, limit: Int = 10) = PoleSearch.search(poles, q, limit).map { it.poleId }

    @Test
    fun `선로번호는 같은 것 → 앞부분 → 들어 있음 순`() {
        assertEquals(listOf("8126Z013", "8126Z130", "8126Z113"), ids("13"))
    }

    @Test
    fun `전주번호·선로명 번호, 공백·대소문자 무시`() {
        assertEquals(listOf("9000A001"), ids("9000 a"))
        assertEquals(listOf("8126Z013"), ids("대평선 13").take(1))
        assertEquals(listOf("8126Z013"), ids("대평선13").take(1))
    }

    @Test
    fun `빈 검색어·없음·개수 제한`() {
        assertEquals(emptyList<String>(), ids("  "))
        assertEquals(emptyList<String>(), ids("없는번호"))
        assertEquals(2, ids("8126", limit = 2).size)
    }
}
