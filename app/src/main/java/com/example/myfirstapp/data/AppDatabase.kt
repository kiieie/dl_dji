package com.example.myfirstapp.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

// 4.2 엔티티 중 가져오기·계획 저장에 필요한 PROJECT, POLE, MISSION, SORTIE, VISIT만 둔다.
// ponytail: CAPTURE~TELEMETRY_SAMPLE은 4단계에서 쓰는 시점에 추가한다(출시 전이므로 스키마 버전은 올리지 않는다).

@Entity(tableName = "project")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "pole",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["projectId", "poleId"], unique = true)],
)
data class PoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val poleId: String,
    val lat: Double,
    val lon: Double,
    val lineName: String?,
    val note: String?,
) {
    fun toPole() = Pole(poleId, GeoPoint(lat, lon), lineName, note)
}

@Entity(
    tableName = "mission",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class MissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val createdAt: Long,
    val areaGeoJson: String,
    val homeLat: Double,
    val homeLon: Double,
    /** 계획 시점의 비행 파라미터(JSON). 나중에 기본값을 바꿔도 기록은 유지된다. */
    val flightParams: String,
    val modelVersion: String?,
    val status: String,
)

@Entity(
    tableName = "sortie",
    foreignKeys = [ForeignKey(MissionEntity::class, ["id"], ["missionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("missionId")],
)
data class SortieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val missionId: Long,
    val seq: Int,
    val estDistanceM: Double,
    val estBatteryPct: Double,
    val status: String,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
)

@Entity(
    tableName = "visit",
    foreignKeys = [
        ForeignKey(SortieEntity::class, ["id"], ["sortieId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PoleEntity::class, ["id"], ["poleRowId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sortieId"), Index("poleRowId")],
)
data class VisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sortieId: Long,
    val poleRowId: Long,
    val order: Int,
    val status: String,
    val arrivedAt: Long? = null,
    val skipReason: String? = null,
)

/** `MISSION.flightParams` 저장 형식. 필드 이름은 [FlightParams]와 같다. */
fun FlightParams.toJson(): String = JSONObject()
    .put("transitAltitudeM", transitAltitudeM).put("inspectAltitudeM", inspectAltitudeM).put("speedMps", speedMps)
    .put("offsetDistanceM", offsetDistanceM).put("offsetBearingDeg", offsetBearingDeg).put("gimbalPitchDeg", gimbalPitchDeg)
    .put("lenses", JSONArray(lenses.map { it.name })).put("zoomRatio", zoomRatio).put("waitSec", waitSec)
    .put("batteryReservePct", batteryReservePct).put("vlosRadiusM", vlosRadiusM).put("downloadMode", downloadMode.name)
    .toString()

/** 소티 하나의 저장용 입력: 방문 순서대로 전주 행 id. */
data class SortieDraft(val estDistanceM: Double, val estBatteryPct: Double, val poleRowIds: List<Long>)

/** 프로젝트 목록 카드용(10장 ①). */
data class ProjectSummary(val id: Long, val name: String, val createdAt: Long, val poleCount: Int)

@Dao
interface ProjectDao {
    @Query(
        "SELECT project.id, project.name, project.createdAt, COUNT(pole.id) AS poleCount FROM project " +
            "LEFT JOIN pole ON pole.projectId = project.id GROUP BY project.id ORDER BY project.createdAt DESC",
    )
    fun observeProjects(): Flow<List<ProjectSummary>>

    @Query("SELECT * FROM pole WHERE projectId = :projectId ORDER BY poleId")
    fun observePoles(projectId: Long): Flow<List<PoleEntity>>

    @Insert
    suspend fun insertProject(project: ProjectEntity): Long

    @Insert
    suspend fun insertPoles(poles: List<PoleEntity>)

    @Query("DELETE FROM project WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Insert
    suspend fun insertMission(mission: MissionEntity): Long

    @Insert
    suspend fun insertSortie(sortie: SortieEntity): Long

    @Insert
    suspend fun insertVisits(visits: List<VisitEntity>)

    @Query("SELECT * FROM mission WHERE projectId = :projectId ORDER BY id")
    suspend fun missions(projectId: Long): List<MissionEntity>

    @Query("SELECT * FROM sortie WHERE missionId = :missionId ORDER BY seq")
    suspend fun sorties(missionId: Long): List<SortieEntity>

    @Query("SELECT * FROM visit WHERE sortieId = :sortieId ORDER BY `order`")
    suspend fun visits(sortieId: Long): List<VisitEntity>

    /** 계획 저장(F-07·F-08 결과): 미션과 소티, 방문 순서를 한 번에 넣는다. 소티 번호와 방문 순서는 1부터 센다. */
    @Transaction
    suspend fun saveMission(mission: MissionEntity, sorties: List<SortieDraft>): Long {
        val missionId = insertMission(mission)
        sorties.forEachIndexed { i, s ->
            val sortieId = insertSortie(SortieEntity(0, missionId, i + 1, s.estDistanceM, s.estBatteryPct, "PENDING"))
            insertVisits(s.poleRowIds.mapIndexed { k, pole -> VisitEntity(0, sortieId, pole, k + 1, "PENDING") })
        }
        return missionId
    }

    /** CSV 가져오기 결과를 새 프로젝트로 저장한다. 전주번호가 겹치면 전체를 되돌린다. */
    @Transaction
    suspend fun importProject(name: String, poles: List<Pole>, now: Long = System.currentTimeMillis()): Long {
        val projectId = insertProject(ProjectEntity(name = name, createdAt = now))
        insertPoles(poles.map { PoleEntity(0, projectId, it.poleId, it.position.lat, it.position.lon, it.lineName, it.note) })
        return projectId
    }
}

@Database(entities = [ProjectEntity::class, PoleEntity::class, MissionEntity::class, SortieEntity::class, VisitEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "inspection.db").build()
    }
}
