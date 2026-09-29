package com.example.myfirstapp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myfirstapp.data.AppDatabase
import com.example.myfirstapp.data.CaptureEntity
import com.example.myfirstapp.data.DetectionEntity
import com.example.myfirstapp.data.MissionEntity
import com.example.myfirstapp.data.ReviewEntity
import com.example.myfirstapp.data.SortieDraft
import com.example.myfirstapp.data.exportMission
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
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
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

    @Test
    fun 결과_조회와_내보내기_ZIP() = runTest {
        val id = dao.importProject("결과", listOf(pole("A-1"), pole("A-2")))
        val rows = dao.observePoles(id).first().associate { it.poleId to it.id }
        val missionId = dao.saveMission(
            MissionEntity(0, id, 0, "{}", 37.5, 127.0, FlightParams().toJson(), null, "DONE"),
            listOf(SortieDraft(900.0, 40.0, listOf(rows.getValue("A-1"), rows.getValue("A-2")))),
        )
        assertEquals(missionId, dao.observeProjects().first().single().lastMissionId)
        val (v1, v2) = dao.visits(dao.sorties(missionId).single().id).map { it.id }
        dao.setVisitStatus(v1, "DONE", null)
        dao.setVisitStatus(v2, "SKIPPED", "강풍, \"접근 불가\"")
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("export-test").apply { deleteRecursively(); mkdirs() }
        val image = File(dir, "A-1_1_WIDE.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val wide = dao.insertCapture(CaptureEntity(0, v1, "m1", image.absolutePath, "WIDE", "AUTO", 37.5, 127.0, 30.0, -10.0, 0.0, 1))
        dao.insertCapture(CaptureEntity(0, v1, "m2", null, "ZOOM", "AUTO", 37.5, 127.0, 30.0, -10.0, 0.0, 2))
        // 검출 대상 1개와 일반 탐지 1개: 검출 수와 검출 대상은 경고 라벨만 센다
        dao.insertDetections(
            listOf(
                DetectionEntity(0, wide, "bird_nest", 0.9f, 0.1f, 0.1f, 0.2f, 0.2f, alert = true),
                DetectionEntity(0, wide, "전주", 0.8f, 0.5f, 0.1f, 0.1f, 0.8f, alert = false),
            ),
        )

        val result = dao.resultRows(missionId)
        assertEquals(listOf(2 to 1, 0 to 0), result.map { it.photoCount to it.detectCount })

        // 1차 판독: 같은 전주를 두 번 판정하면 나중 판정이 현재 판정이다
        dao.insertReview(ReviewEntity(0, v1, "RETAKE", "", null, 10))
        dao.insertReview(ReviewEntity(0, v1, "CONFIRMED", "bird_nest;foreign_object", "둥지, \"큼\"", 20))
        assertEquals(listOf(v1 to "CONFIRMED"), dao.latestReviews(missionId).map { it.visitId to it.verdict })

        val r = exportMission(dao, missionId, File(dir, "out.zip"))
        assertEquals(2 to 1, r.poleCount to r.imageCount)
        ZipFile(r.zip).use { zip ->
            assertEquals(setOf("result.csv", "mission.json", "images/A-1_1_WIDE.jpg"), zip.entries().toList().map { it.name }.toSet())
            val csv = zip.getInputStream(zip.getEntry("result.csv")).readBytes().decodeToString()
            assertTrue(csv.startsWith("\uFEFF소티,"))
            val lines = csv.trimEnd().split("\r\n")
            assertEquals(3, lines.size)
            assertTrue(lines[1], lines[1].startsWith("1,1,A-1,") && lines[1].endsWith(",2,1,조류 둥지,검출 맞음,조류 둥지;이물질,\"둥지, \"\"큼\"\"\""))
            assertTrue(lines[2].contains(",SKIPPED,\"강풍, \"\"접근 불가\"\"\","))
            assertTrue(lines[2], lines[2].endsWith(",0,0,,,,"))
            val json = JSONObject(zip.getInputStream(zip.getEntry("mission.json")).readBytes().decodeToString())
            val caps = json.getJSONArray("poles").getJSONObject(0).getJSONArray("captures")
            assertEquals("images/A-1_1_WIDE.jpg", caps.getJSONObject(0).getString("file"))
            assertTrue(caps.getJSONObject(1).isNull("file"))
            val dets = caps.getJSONObject(0).getJSONArray("detections")
            assertEquals(2, dets.length())
            val review = json.getJSONArray("poles").getJSONObject(0).getJSONObject("review")
            assertEquals("CONFIRMED" to 2, review.getString("verdict") to review.getJSONArray("tags").length())
            assertTrue(json.getJSONArray("poles").getJSONObject(1).isNull("review"))
            assertEquals(setOf("bird_nest" to true, "전주" to false), (0 until 2).map { dets.getJSONObject(it).let { d -> d.getString("label") to d.getBoolean("alert") } }.toSet())
        }
    }
}
