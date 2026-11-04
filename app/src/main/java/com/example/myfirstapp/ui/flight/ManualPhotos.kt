package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.core.executor.shutterLinkTarget
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.data.MissionEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.RecorderSortie
import com.example.myfirstapp.data.RoomRecorder
import com.example.myfirstapp.detect.LiveDetection
import com.example.myfirstapp.detect.PreciseDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 수동 사진 경로(6.4): 수동 비행 셔터, 미션 화면의 땅 위 셔터, 타이머 사진(10.5.7). 원본을 받아 [mission]의 방문 전주가
 * [com.example.myfirstapp.core.config.Tuning.Photo.SHUTTER_POLE_LINK_M] 안이면 그 방문의 수동 사진(미션 `images/`), 아니면 기타 `manual/<날짜>/`에 둔다.
 * 실행기의 지점 사진 가르기에는 넣지 않고 전주 상태도 바꾸지 않는다. 저장한 사진은 비행 중 최근 1장·착륙 뒤 정밀 탐지로 넘긴다(9.6).
 */
internal class ManualPhotos(
    private val dao: ProjectDao,
    private val appFilesDir: File?,
    /** 사진을 붙일 미션: 수동 비행은 남은 전주가 있는 최근 미션, 미션 화면은 그 미션. */
    private val mission: suspend () -> MissionEntity?,
    private val live: LiveDetection?,
    private val precise: PreciseDetection?,
) {
    /** [info]를 받아 저장하고 저장한 곳 안내("전주 X" 또는 파일 이름)를 돌려준다. */
    suspend fun keep(d: DroneGateway, info: CaptureInfo, lens: Lens): Result<String> = runCatching {
        val bytes = d.download(info, DownloadMode.ORIGINAL).getOrThrow()
        val (where, path) = store(info, bytes, lens)
        live?.offer(bytes, path)
        precise?.kick()
        where
    }

    private suspend fun store(info: CaptureInfo, bytes: ByteArray, lens: Lens): Pair<String, String?> {
        val m = mission()
        val v = if (m == null) null else shutterLinkTarget(info.position, dao.missionVisits(m.id)) { GeoPoint(it.lat, it.lon) }
        if (m != null && v != null) {
            val rec = RoomRecorder(
                dao, m.id, listOf(RecorderSortie(v.sortieId, listOf(v.visitId to v.poleId))),
                File(appFilesDir, "projects/${m.projectId}/missions/${m.id}/images"), DownloadMode.ORIGINAL,
            )
            val id = rec.saveCapture(0, 0, info.copy(lens = lens, viewpoint = null), CaptureSource.MANUAL)
            rec.saveImage(id, bytes)
            return "전주 ${v.poleId}" to dao.capture(id)?.filePath
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val file = File(appFilesDir, "manual/$day/manual_${info.takenAtMs}_${info.mediaId}_${lens}.jpg")
        withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
        return file.name to file.path
    }
}
