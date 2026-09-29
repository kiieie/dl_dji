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
        val sortie = sorties.getOrNull(sortieIndex) ?: return
        val visitId = poleId?.let { id -> sortie.visits.firstOrNull { it.second == id }?.first }
        dao.insertEvent(FlightEventEntity(0, sortie.sortieId, from, to, reason, visitId, now))
        when (to) {
            "PreflightCheck" -> dao.setMissionStatus(missionId, "IN_PROGRESS")
            "Takeoff" -> dao.startSortie(sortie.sortieId, now)
            "Arrived" -> visitId?.let { dao.setArrived(it, now) }
            "SortieDone" -> dao.endSortie(sortie.sortieId, now)
            "MissionDone" -> dao.setMissionStatus(missionId, "DONE")
        }
    }

    override suspend fun updateVisit(sortieIndex: Int, order: Int, status: VisitStatus, skipReason: String?) {
        dao.setVisitStatus(visitId(sortieIndex, order), status.name, skipReason)
    }

    override suspend fun saveCapture(sortieIndex: Int, order: Int, capture: CaptureInfo, source: CaptureSource): Long =
        dao.insertCapture(
            CaptureEntity(
                visitId = visitId(sortieIndex, order), mediaId = capture.mediaId, lens = capture.lens.name, source = source.name,
                lat = capture.position?.lat, lon = capture.position?.lon, altRel = capture.altitudeM,
                gimbalPitch = capture.gimbalPitchDeg, heading = capture.headingDeg, takenAt = capture.takenAtMs,
            ),
        )

    override suspend fun saveImage(captureId: Long, bytes: ByteArray) {
        val c = dao.capture(captureId) ?: return
        // 4.3 파일 이름 규칙: <poleId>_<timestamp>_<lens>.jpg
        val file = File(imagesDir, "${poleIdOfVisit[c.visitId]}_${c.takenAt}_${c.lens}.jpg")
        withContext(Dispatchers.IO) {
            imagesDir.mkdirs()
            file.writeBytes(bytes)
        }
        dao.setCaptureFile(captureId, file.absolutePath, original = downloadMode == DownloadMode.ORIGINAL)
    }

    override suspend fun saveDetections(captureId: Long, modelVersion: String, detections: List<Detection>) {
        dao.setModelVersion(missionId, modelVersion)
        dao.insertDetections(detections.map { DetectionEntity(0, captureId, it.label, it.score, it.x, it.y, it.w, it.h) })
    }
}
