package com.example.myfirstapp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myfirstapp.data.AppDatabase
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
    ).build()
    private val dao = db.projectDao()

    private fun pole(id: String) = Pole(id, GeoPoint(37.5, 127.0), "OO선", null)

    @Test
    fun 가져오기_저장_조회_삭제() = runTest {
        val id = dao.importProject("시험", listOf(pole("B-2"), pole("A-1")))
        assertEquals(listOf("A-1", "B-2"), dao.observePoles(id).first().map { it.poleId })
        assertEquals(pole("A-1"), dao.observePoles(id).first().first().toPole())
        assertEquals(2, dao.observeProjects().first().single().poleCount)

        dao.deleteProject(id)
        assertTrue(dao.observeProjects().first().isEmpty())
        assertTrue(dao.observePoles(id).first().isEmpty())
    }

    @Test
    fun 전주번호_중복이면_프로젝트까지_되돌린다() = runTest {
        runCatching { dao.importProject("중복", listOf(pole("A-1"), pole("A-1"))) }
        assertTrue(dao.observeProjects().first().isEmpty())
    }
}
