package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.executor.ManualLinkCandidate
import com.example.myfirstapp.core.executor.pickManualLink
import com.example.myfirstapp.core.executor.shutterPole
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.DownloadMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.DroneGateway
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.PoleEntity
import com.example.myfirstapp.data.actualLens
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.RecorderSortie
import com.example.myfirstapp.data.RoomRecorder
import com.example.myfirstapp.detect.LiveDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

/**
 * 수동 사진 경로(6.4): 수동 비행 셔터, 미션 화면의 땅 위 셔터, 타이머 사진(10.5.7). 원본을 받아 **모든 프로젝트 전주** 중
 * [com.example.myfirstapp.core.config.Tuning.Photo.SHUTTER_POLE_LINK_M] 안의 가장 가까운 전주를 고르고(`shutterPole`), 그 전주가 든 미션의 방문
 * (`pickManualLink`: [fixedMission]이 있으면 그 미션, 없으면 최근 비행 미션 → 전주가 남은 최근 미션)의 수동 사진(미션 `images/`)으로 둔다.
 * 붙일 방문이 없으면 기타 `manual/<날짜>/`(안내문에는 가까운 전주). 실행기의 지점 사진 가르기에는 넣지 않고 전주 상태도 바꾸지 않는다.
 * 저장한 사진은 카메라 뷰 마지막 사진으로 보인다(누르면 탐지, 9.6).
 */
internal class ManualPhotos(
    private val dao: ProjectDao,
    private val appFilesDir: File?,
    /** 사진을 붙일 미션: 미션 화면은 그 미션, 수동 비행은 null(전주로 고름). */
    private val fixedMission: Long?,
    private val live: LiveDetection?,
) {
    /** [info]를 받아 저장하고 저장한 곳(붙인 전주, 파일)을 돌려준다. */
    suspend fun keep(d: DroneGateway, info: CaptureInfo, lens: Lens): Result<SavedPhoto> = runCatching {
        val t0 = System.currentTimeMillis()
        val bytes = d.download(info, DownloadMode.ORIGINAL).getOrThrow()
        val t1 = System.currentTimeMillis()
        val saved = store(info, bytes, lens)
        // 성능 리포트 B-SAVE: 받기·저장 시간(저장은 예전에 실행기 메시지 루프를 막던 부분)
        OpLog.log("photo", "manualKept", mapOf("bytes" to bytes.size, "downloadMs" to t1 - t0, "saveMs" to System.currentTimeMillis() - t1))
        live?.offer(saved.path)
        saved
    }

    /** 찍은 자리의 전주(같은 자리 다른 프로젝트 전주 포함, 가장 가까운 것 먼저). 위치가 없거나 20m 안에 없으면 빈 목록. 실행기 RC 셔터도 쓴다(6.4). */
    suspend fun polesAt(at: GeoPoint?): List<PoleEntity> {
        at ?: return emptyList()
        val m = Tuning.Photo.SHUTTER_POLE_LINK_M
        val dLat = m / METERS_PER_DEG
        val dLon = m / (METERS_PER_DEG * cos(Math.toRadians(at.lat)))
        val near = dao.polesIn(at.lat - dLat, at.lat + dLat, at.lon - dLon, at.lon + dLon)
        return shutterPole(at, near, { GeoPoint(it.lat, it.lon) }, { it.projectId }, { it.poleId })
    }

    private suspend fun store(info: CaptureInfo, bytes: ByteArray, lens: Lens): SavedPhoto {
        val poles = polesAt(info.position)
        // 가까운 전주 순서를 지킨다(같은 미션 안 동점이면 가까운 전주의 방문)
        val rank = poles.withIndex().associate { (i, p) -> p.id to i }
        val rows = if (poles.isEmpty()) emptyList() else dao.manualLinkRows(poles.map { it.id }).sortedBy { rank[it.poleRowId] }
        val pick = pickManualLink(
            rows.map { ManualLinkCandidate(it.missionId, it.visitId, it.createdAt, it.flownAt, it.open) },
            System.currentTimeMillis(), fixedMission,
        )
        val v = pick?.let { p -> rows.first { it.visitId == p.visitId } }
        OpLog.log("photo", "manualLink", mapOf("pole" to poles.firstOrNull()?.poleId, "mission" to v?.missionId, "candidates" to rows.size))
        if (v != null) {
            val rec = RoomRecorder(
                dao, v.missionId, listOf(RecorderSortie(v.sortieId, listOf(v.visitId to v.poleId))),
                File(appFilesDir, "projects/${v.projectId}/missions/${v.missionId}/images"), DownloadMode.ORIGINAL,
            )
            val id = rec.saveCapture(0, 0, info.copy(lens = lens, viewpoint = null), CaptureSource.MANUAL)
            rec.saveImage(id, bytes)
            return SavedPhoto(v.poleId, dao.capture(id)?.filePath)
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val file = withContext(Dispatchers.IO) {
            File(appFilesDir, "manual/$day/manual_${info.takenAtMs}_${info.mediaId}_${actualLens(bytes, lens.name)}.jpg").also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }
        }
        return SavedPhoto(null, file.path, nearPole = poles.firstOrNull()?.poleId)
    }

    private companion object {
        /** 위도 1도의 길이(m, 20m 사각형 조회용 대략값). */
        const val METERS_PER_DEG = 111_320.0
    }
}

/** 저장한 수동 사진: 붙인 전주([poleId], 기타 `manual/`이면 null)와 파일 경로. */
internal data class SavedPhoto(val poleId: String?, val path: String?, val nearPole: String? = null) {
    /** 카메라 뷰 안내 줄: Pilot 2처럼 파일 이름 없이 짧게(11-06 RC). 전주는 있는데 붙일 미션이 없으면 "미션 밖". */
    val message get() = when {
        poleId != null -> "사진 저장됨 · 전주 $poleId"
        nearPole != null -> "사진 저장됨 · 전주 $nearPole(미션 밖)"
        else -> "사진 저장됨"
    }
}
