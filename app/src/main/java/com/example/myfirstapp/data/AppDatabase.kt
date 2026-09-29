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
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import kotlinx.coroutines.flow.Flow

// 4.2 엔티티 중 2단계(가져오기, 지도 표시)에 필요한 PROJECT, POLE만 둔다.
// ponytail: MISSION~TELEMETRY_SAMPLE은 3·4단계에서 쓰는 시점에 추가한다(출시 전이므로 스키마 버전은 올리지 않는다).

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

    /** CSV 가져오기 결과를 새 프로젝트로 저장한다. 전주번호가 겹치면 전체를 되돌린다. */
    @Transaction
    suspend fun importProject(name: String, poles: List<Pole>, now: Long = System.currentTimeMillis()): Long {
        val projectId = insertProject(ProjectEntity(name = name, createdAt = now))
        insertPoles(poles.map { PoleEntity(0, projectId, it.poleId, it.position.lat, it.position.lon, it.lineName, it.note) })
        return projectId
    }
}

@Database(entities = [ProjectEntity::class, PoleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "inspection.db").build()
    }
}
