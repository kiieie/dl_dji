package com.example.myfirstapp.data

import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.InspectionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 비행 한 번에 실행하는 소티. [visits]는 방문 순서대로 (VISIT id, 전주번호)다. 실행기의 소티 번호는 이 목록의 위치다. */
data class RecorderSortie(val sortieId: Long, val visits: List<Pair<Long, String>>)

/**
 * 실행 기록 포트의 Room 구현(4.2). 이미지는 `projects/<projectId>/missions/<missionId>/images/`에 저장한다(4.3).
 * 실행기의 (소티 번호, 방문 순서)는 비행 화면이 읽은 [sorties]의 위치이고, 여기서 DB id로 바꾼다.
 */
class RoomRecorder(
    private val dao: ProjectDao,
    private val missionId: Long,
    private val sorties: List<RecorderSortie>,
    private val imagesDir: File,
    private val downloadMode: DownloadMode,
) : InspectionRecorder {

    private val poleIdOfVisit = sorties.flatMap { it.visits }.toMap()

    private fun visitId(sortieIndex: Int, order: Int) = sorties[sortieIndex].visits[order].first

    override suspend fun recordTransition(sortieIndex: Int, from: String, to: String, reason: String, poleId: String?) {
        val now = System.currentTimeMillis()
        OpLog.log("exec", to, mapOf("from" to from, "reason" to reason, "pole" to poleId, "sortie" to sortieIndex, "mission" to missionId))
        val sortie = sorties.getOrNull(sortieIndex) ?: return
        val visitId = poleId?.let { id -> sortie.visits.firstOrNull { it.second == id }?.first }
        dao.insertEvent(FlightEventEntity(0, sortie.sortieId, from, to, reason, visitId, now))
        when (to) {
            "PreflightCheck" -> dao.setMissionStatus(missionId, "IN_PROGRESS")
            // 방식 A는 "Takeoff"가 미션 시작 명령 전이라 거절돼도 남는다(2026-11-06: 날지 않은 소티가 재시작 복구에서 "소티 완료"로 닫힘).
            // 기체가 미션을 받아 날기 시작한 때 연다(재개·남은 전주도 같은 소티를 다시 연다)
            "FlyingLeg" -> dao.startSortie(sortie.sortieId, now)
            "Arrived" -> visitId?.let { dao.setArrived(it, now) }
            "SortieDone" -> dao.endSortie(sortie.sortieId, now)
            // 남은 전주가 있으면 부분 완료(홈·이어하기에 남음, 2026-10-30 사용자 결정)
            "MissionDone" -> dao.setMissionStatus(missionId, if (dao.remainingVisits(missionId) > 0) "PARTIAL" else "DONE")
        }
    }

    override suspend fun updateVisit(sortieIndex: Int, order: Int, status: VisitStatus, skipReason: String?) {
        dao.setVisitStatus(visitId(sortieIndex, order), status.name, skipReason)
    }

    override suspend fun saveCapture(sortieIndex: Int, order: Int, capture: CaptureInfo, source: CaptureSource): Long =
        dao.insertCapture(capture.toEntity(visitId(sortieIndex, order), source)).also {
            OpLog.log("photo", source.name, mapOf("capture" to it, "media" to capture.mediaId, "viewpoint" to capture.viewpoint, "pole" to poleIdOfVisit[visitId(sortieIndex, order)]))
        }

    override suspend fun saveImage(captureId: Long, bytes: ByteArray) {
        val c0 = dao.capture(captureId) ?: return
        // 렌즈는 받은 파일로 확인한다(5.6): 화면에서 ZOOM이어도 7배 미만이면 광각 센서 사진. EXIF 읽기는 IO에서
        val lens = withContext(Dispatchers.IO) { actualLens(bytes, c0.lens) }
        val c = c0.copy(lens = lens)
        // 4.3 파일 이름 규칙: <poleId>_<viewpoint>_<timestamp>_<mediaId>_<lens>.jpg (수동 촬영은 지점 m, 사진 번호로 같은 초 겹침을 막음)
        // 설비 조준 사진은 지점 번호 뒤에 e
        val file = File(imagesDir, "${poleIdOfVisit[c.visitId]}_${c.viewpoint ?: "m"}${if (c.equipmentShot) "e" else ""}_${c.takenAt}_${c.mediaId}_${c.lens}.jpg")
        val started = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            imagesDir.mkdirs()
            file.writeBytes(bytes)
        }
        // 파일을 쓴 뒤에 렌즈를 고친다(쓰기가 실패하면 DB는 그대로)
        if (lens != c0.lens) {
            dao.setCaptureLens(captureId, lens)
            OpLog.log("photo", "lensFixed", mapOf("capture" to captureId, "from" to c0.lens, "to" to lens))
        }
        dao.setCaptureFile(captureId, file.absolutePath, original = downloadMode == DownloadMode.ORIGINAL)
        // 성능 리포트 B-SAVE: 다운로드 로그(media download)와의 간격 = 받기 시간
        OpLog.log("photo", "saved", mapOf("capture" to captureId, "bytes" to bytes.size, "ms" to System.currentTimeMillis() - started))
    }

    override suspend fun recordHeightMode(terrainFollow: Boolean) {
        OpLog.log("exec", "heightMode", mapOf("terrain" to terrainFollow, "mission" to missionId))
        dao.setFlownHeightMode(missionId, if (terrainFollow) "TERRAIN" else "TAKEOFF")
    }

    override suspend fun saveDetections(captureId: Long, modelVersion: String, detections: List<Detection>) {
        OpLog.log("detect", modelVersion, mapOf("capture" to captureId, "count" to detections.size, "alert" to detections.count { it.alert }))
        dao.setModelVersion(missionId, modelVersion)
        dao.insertDetections(detections.map { DetectionEntity(0, captureId, it.label, it.score, it.x, it.y, it.w, it.h, it.alert) })
    }
}

/** 촬영 기록 한 건. 이미지 파일은 받은 뒤 [ProjectDao.setCaptureFile]로 채운다. */
fun CaptureInfo.toEntity(visitId: Long, source: CaptureSource) = CaptureEntity(
    visitId = visitId, mediaId = mediaId, lens = lens.name, source = source.name,
    lat = position?.lat, lon = position?.lon, altRel = altitudeM,
    gimbalPitch = gimbalPitchDeg, heading = headingDeg, takenAt = takenAtMs, viewpoint = viewpoint, equipmentShot = equipment,
)
