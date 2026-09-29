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
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Pole
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

// 4.2 엔티티. TELEMETRY_SAMPLE은 에너지 모델 보정(6단계) 때 추가한다(출시 전이므로 스키마 버전은 올리지 않는다).

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

@Entity(
    tableName = "capture",
    foreignKeys = [ForeignKey(VisitEntity::class, ["id"], ["visitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("visitId")],
)
data class CaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val visitId: Long,
    val mediaId: String,
    val filePath: String? = null,
    val lens: String,
    val source: String,
    val lat: Double?,
    val lon: Double?,
    val altRel: Double,
    val gimbalPitch: Double,
    val heading: Double,
    val takenAt: Long,
    val originalDownloaded: Boolean = false,
)

@Entity(
    tableName = "detection",
    foreignKeys = [ForeignKey(CaptureEntity::class, ["id"], ["captureId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("captureId")],
)
data class DetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val captureId: Long,
    val label: String,
    val score: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** 경고 라벨(9.1 `alertLabels`)이면 true. 이상 개수는 이것만 센다. */
    val alert: Boolean,
)

/**
 * 1차 판독(F-19). 판정할 때마다 한 줄을 더하고, 전주마다 가장 최근 줄이 현재 판정이다.
 * [verdict]: `CONFIRMED`(검출 맞음) / `NONE`(검출 없음) / `RETAKE`(재촬영), [tags]: 대상 라벨을 `;`로 이은 값.
 */
@Entity(
    tableName = "review",
    foreignKeys = [ForeignKey(VisitEntity::class, ["id"], ["visitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("visitId")],
)
data class ReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val visitId: Long,
    val verdict: String,
    val tags: String,
    val memo: String?,
    val at: Long,
)

@Entity(
    tableName = "flight_event",
    foreignKeys = [ForeignKey(SortieEntity::class, ["id"], ["sortieId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sortieId")],
)
data class FlightEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sortieId: Long,
    val fromState: String,
    val toState: String,
    val reason: String,
    val visitId: Long?,
    val at: Long,
)

/** 비행 화면이 읽는 방문 한 건(소티 순서, 방문 순서대로). */
data class MissionVisitRow(
    val sortieId: Long,
    val seq: Int,
    val visitId: Long,
    val status: String,
    val poleId: String,
    val lat: Double,
    val lon: Double,
    val lineName: String?,
    val note: String?,
)

/** `MISSION.flightParams`(JSON)를 읽는다. 없는 필드는 기본값을 쓴다. */
fun flightParamsFromJson(json: String): FlightParams {
    val o = JSONObject(json)
    val d = FlightParams()
    return FlightParams(
        transitAltitudeM = o.optDouble("transitAltitudeM", d.transitAltitudeM),
        inspectAltitudeM = o.optDouble("inspectAltitudeM", d.inspectAltitudeM),
        speedMps = o.optDouble("speedMps", d.speedMps),
        offsetDistanceM = o.optDouble("offsetDistanceM", d.offsetDistanceM),
        offsetBearingDeg = o.optDouble("offsetBearingDeg", d.offsetBearingDeg),
        gimbalPitchDeg = o.optDouble("gimbalPitchDeg", d.gimbalPitchDeg),
        lenses = o.optJSONArray("lenses")?.let { a -> List(a.length()) { Lens.valueOf(a.getString(it)) } } ?: d.lenses,
        zoomRatio = o.optDouble("zoomRatio", d.zoomRatio),
        waitSec = o.optInt("waitSec", d.waitSec),
        batteryReservePct = o.optDouble("batteryReservePct", d.batteryReservePct),
        vlosRadiusM = o.optDouble("vlosRadiusM", d.vlosRadiusM),
        downloadMode = o.optString("downloadMode").let { m -> DownloadMode.entries.firstOrNull { it.name == m } } ?: d.downloadMode,
    )
}

/** 결과 화면(10장 ⑤)과 내보내기의 전주 한 줄. */
data class ResultRow(
    val seq: Int,
    val order: Int,
    val visitId: Long,
    val poleId: String,
    val lat: Double,
    val lon: Double,
    val lineName: String?,
    val status: String,
    val skipReason: String?,
    val arrivedAt: Long?,
    val photoCount: Int,
    /** 검출 대상(경고 라벨) 탐지 수. */
    val detectCount: Int,
)

/** 미션 목록(①-1) 카드 한 장. [lastFlightAt]은 소티 착륙(없으면 이륙) 시각 중 가장 늦은 값이다. */
data class MissionSummary(
    val id: Long,
    val status: String,
    val createdAt: Long,
    val sortieCount: Int,
    val poleCount: Int,
    val doneCount: Int,
    val remainingCount: Int,
    val detectedCount: Int,
    val reviewedCount: Int,
    val lastFlightAt: Long?,
)

/** 소티 하나의 저장용 입력: 방문 순서대로 전주 행 id. */
data class SortieDraft(val estDistanceM: Double, val estBatteryPct: Double, val poleRowIds: List<Long>)

/** 프로젝트 목록 카드용(10장 ①). */
data class ProjectSummary(val id: Long, val name: String, val createdAt: Long, val poleCount: Int, val lastMissionId: Long? = null)

@Dao
interface ProjectDao {
    @Query(
        "SELECT project.id, project.name, project.createdAt, COUNT(pole.id) AS poleCount, " +
            "(SELECT MAX(mission.id) FROM mission WHERE mission.projectId = project.id) AS lastMissionId FROM project " +
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

    @Query("SELECT * FROM mission WHERE id = :missionId")
    suspend fun mission(missionId: Long): MissionEntity?

    @Query(
        "SELECT s.id AS sortieId, s.seq, v.id AS visitId, v.status, p.poleId, p.lat, p.lon, p.lineName, p.note " +
            "FROM sortie s JOIN visit v ON v.sortieId = s.id JOIN pole p ON p.id = v.poleRowId " +
            "WHERE s.missionId = :missionId ORDER BY s.seq, v.`order`",
    )
    suspend fun missionVisits(missionId: Long): List<MissionVisitRow>

    @Query("UPDATE mission SET status = :status WHERE id = :missionId")
    suspend fun setMissionStatus(missionId: Long, status: String)

    @Query("UPDATE mission SET modelVersion = :version WHERE id = :missionId")
    suspend fun setModelVersion(missionId: Long, version: String)

    @Query("UPDATE sortie SET status = 'IN_PROGRESS', startedAt = :at WHERE id = :sortieId AND startedAt IS NULL")
    suspend fun startSortie(sortieId: Long, at: Long)

    @Query("UPDATE sortie SET status = 'DONE', endedAt = :at WHERE id = :sortieId")
    suspend fun endSortie(sortieId: Long, at: Long)

    @Query("UPDATE visit SET status = :status, skipReason = :skipReason WHERE id = :visitId")
    suspend fun setVisitStatus(visitId: Long, status: String, skipReason: String?)

    @Query("UPDATE visit SET arrivedAt = :at WHERE id = :visitId")
    suspend fun setArrived(visitId: Long, at: Long)

    @Insert
    suspend fun insertEvent(event: FlightEventEntity)

    @Insert
    suspend fun insertCapture(capture: CaptureEntity): Long

    @Query("UPDATE capture SET filePath = :path, originalDownloaded = :original WHERE id = :captureId")
    suspend fun setCaptureFile(captureId: Long, path: String, original: Boolean)

    @Query("SELECT * FROM capture WHERE id = :captureId")
    suspend fun capture(captureId: Long): CaptureEntity?

    @Insert
    suspend fun insertDetections(detections: List<DetectionEntity>)

    @Query("SELECT name FROM project WHERE id = :projectId")
    suspend fun projectName(projectId: Long): String?

    @Query(
        "SELECT s.seq, v.`order`, v.id AS visitId, p.poleId, p.lat, p.lon, p.lineName, v.status, v.skipReason, v.arrivedAt, " +
            "(SELECT COUNT(*) FROM capture c WHERE c.visitId = v.id) AS photoCount, " +
            "(SELECT COUNT(*) FROM detection d JOIN capture c ON c.id = d.captureId WHERE c.visitId = v.id AND d.alert) AS detectCount " +
            "FROM sortie s JOIN visit v ON v.sortieId = s.id JOIN pole p ON p.id = v.poleRowId " +
            "WHERE s.missionId = :missionId ORDER BY s.seq, v.`order`",
    )
    suspend fun resultRows(missionId: Long): List<ResultRow>

    @Query(
        "SELECT c.* FROM capture c JOIN visit v ON v.id = c.visitId JOIN sortie s ON s.id = v.sortieId " +
            "WHERE s.missionId = :missionId ORDER BY c.id",
    )
    suspend fun missionCaptures(missionId: Long): List<CaptureEntity>

    @Query(
        "SELECT d.* FROM detection d JOIN capture c ON c.id = d.captureId JOIN visit v ON v.id = c.visitId " +
            "JOIN sortie s ON s.id = v.sortieId WHERE s.missionId = :missionId ORDER BY d.id",
    )
    suspend fun missionDetections(missionId: Long): List<DetectionEntity>

    @Insert
    suspend fun insertReview(review: ReviewEntity): Long

    @Query(
        "SELECT m.id, m.status, m.createdAt, " +
            "(SELECT COUNT(*) FROM sortie s WHERE s.missionId = m.id) AS sortieCount, " +
            "(SELECT COUNT(*) FROM visit v JOIN sortie s ON s.id = v.sortieId WHERE s.missionId = m.id) AS poleCount, " +
            "(SELECT COUNT(*) FROM visit v JOIN sortie s ON s.id = v.sortieId WHERE s.missionId = m.id AND v.status = 'DONE') AS doneCount, " +
            "(SELECT COUNT(*) FROM visit v JOIN sortie s ON s.id = v.sortieId WHERE s.missionId = m.id " +
            "AND v.status IN ('PENDING', 'IN_PROGRESS')) AS remainingCount, " +
            "(SELECT COUNT(*) FROM visit v JOIN sortie s ON s.id = v.sortieId WHERE s.missionId = m.id AND EXISTS " +
            "(SELECT 1 FROM detection d JOIN capture c ON c.id = d.captureId WHERE c.visitId = v.id AND d.alert)) AS detectedCount, " +
            "(SELECT COUNT(DISTINCT r.visitId) FROM review r JOIN visit v ON v.id = r.visitId JOIN sortie s ON s.id = v.sortieId " +
            "WHERE s.missionId = m.id) AS reviewedCount, " +
            "(SELECT MAX(COALESCE(s.endedAt, s.startedAt)) FROM sortie s WHERE s.missionId = m.id) AS lastFlightAt " +
            "FROM mission m WHERE m.projectId = :projectId ORDER BY m.id DESC",
    )
    fun observeMissions(projectId: Long): Flow<List<MissionSummary>>

    /** 미션의 전주별 현재 판정(가장 최근 판독). */
    @Query(
        "SELECT r.* FROM review r JOIN visit v ON v.id = r.visitId JOIN sortie s ON s.id = v.sortieId " +
            "WHERE s.missionId = :missionId AND r.id = (SELECT MAX(r2.id) FROM review r2 WHERE r2.visitId = r.visitId)",
    )
    suspend fun latestReviews(missionId: Long): List<ReviewEntity>

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

@Database(
    entities = [
        ProjectEntity::class, PoleEntity::class, MissionEntity::class, SortieEntity::class, VisitEntity::class,
        CaptureEntity::class, DetectionEntity::class, FlightEventEntity::class, ReviewEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "inspection.db").build()
    }
}
