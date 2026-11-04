package com.example.myfirstapp

import com.example.myfirstapp.ui.uniqueProjectName
import org.junit.Assert.assertEquals
import org.junit.Test

/** 같은 이름 프로젝트 가져오기(11-03 현장: daepyeong 2개). */
class ProjectNameTest {
    @Test
    fun `같은 이름이면 날짜, 그것도 있으면 번호를 붙인다`() {
        assertEquals("a", uniqueProjectName("a", setOf("b"), "20261104"))
        assertEquals("a-20261104", uniqueProjectName("a", setOf("a"), "20261104"))
        assertEquals("a-20261104-2", uniqueProjectName("a", setOf("a", "a-20261104"), "20261104"))
        assertEquals("a-20261104-3", uniqueProjectName("a", setOf("a", "a-20261104", "a-20261104-2"), "20261104"))
    }
}
