package com.example.myfirstapp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myfirstapp.data.AppDatabase
import com.example.myfirstapp.data.MissionEntity
import com.example.myfirstapp.data.SortieDraft
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.data.toJson
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.Lens
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

    @Test
    fun 계획_저장은_소티와_방문_순서를_남기고_프로젝트와_함께_지워진다() = runTest {
        val id = dao.importProject("계획", listOf(pole("A-1"), pole("A-2"), pole("A-3")))
        val rows = dao.observePoles(id).first().associate { it.poleId to it.id }
        val mission = MissionEntity(0, id, 0, "{}", 37.5, 127.0, "{}", null, "PLANNED")
        val missionId = dao.saveMission(
            mission,
            listOf(SortieDraft(900.0, 40.0, listOf(rows.getValue("A-3"), rows.getValue("A-1"))), SortieDraft(500.0, 20.0, listOf(rows.getValue("A-2")))),
        )

        val sorties = dao.sorties(missionId)
        assertEquals(listOf(1, 2), sorties.map { it.seq })
        assertEquals(listOf(rows["A-3"], rows["A-1"]), dao.visits(sorties[0].id).map { it.poleRowId })
        assertEquals(listOf(1, 2), dao.visits(sorties[0].id).map { it.order })

        dao.deleteProject(id)
        assertTrue(dao.missions(id).isEmpty())
        assertTrue(dao.visits(sorties[0].id).isEmpty())
    }

    @Test
    fun 비행_파라미터는_JSON으로_저장했다가_그대로_읽는다() {
        val p = FlightParams(transitAltitudeM = 70.0, speedMps = 11.0, lenses = listOf(Lens.ZOOM), waitSec = 3, downloadMode = DownloadMode.ORIGINAL)
        assertEquals(p, flightParamsFromJson(p.toJson()))
        assertEquals(FlightParams(), flightParamsFromJson("{}"))
    }
}
