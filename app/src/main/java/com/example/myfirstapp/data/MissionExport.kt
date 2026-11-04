package com.example.myfirstapp.data

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.Annotations
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.ReviewVerdict
import com.example.myfirstapp.core.model.TargetLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 내보내기 결과: ZIP 파일과 담은 사진 수. */
data class ExportResult(val zip: File, val poleCount: Int, val imageCount: Int)

/** 내보낸 ZIP들을 운용자가 고른 폴더(SAF 트리)로 복사한다(라이브러리 다중 내보내기, 10.5.8). 복사한 개수를 돌려준다. */
suspend fun copyZipsToTree(resolver: android.content.ContentResolver, tree: android.net.Uri, files: List<File>): Int = withContext(Dispatchers.IO) {
    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
    files.count { f ->
        runCatching {
            val doc = android.provider.DocumentsContract.createDocument(resolver, parent, "application/zip", f.name) ?: return@runCatching false
            resolver.openOutputStream(doc)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
    }
}

private val timeFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private fun csvField(v: Any?): String {
    val s = v?.toString().orEmpty()
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/**
 * 미션 결과를 ZIP으로 묶는다(F-16, 10장 ⑤). 들어가는 것:
 * - `result.csv`: 전주별 결과와 1차 판독(UTF-8 BOM, 엑셀에서 한글이 깨지지 않게). 2차 판독(다른 플랫폼)의 입력이다
 * - `mission.json`: 미션·소티·파라미터와 전주별 사진·탐지
 * - `annotations.geojson`: 미션 둘레의 핀포인트·선·영역(10.4.7, 2차 판독 플랫폼이 이상 위치·메모로 쓴다)
 * - `images/`: 받은 사진(파일이 없는 사진은 JSON에만 남는다)
 * - `logs/`: 미션 기간의 운용 로그(4.4)
 */
suspend fun exportMission(dao: ProjectDao, missionId: Long, zip: File): ExportResult {
    val mission = requireNotNull(dao.mission(missionId)) { "미션 #$missionId 없음" }
    val rows = dao.resultRows(missionId)
    val captures = dao.missionCaptures(missionId)
    val detections = dao.missionDetections(missionId).groupBy { it.captureId }
    val capturesByVisit = captures.groupBy { it.visitId }
    val reviews = dao.latestReviews(missionId).associateBy { it.visitId }

    val csv = buildString {
        append('\uFEFF') // UTF-8 BOM(소스에 문자 그대로 두면 lint ByteOrderMark 오류)
        append("소티,순서,전주번호,위도,경도,선로명,선로번호,전주규격,설치기기,상태,건너뛴 이유,도착 시각,사진 수,검출 수,검출 대상,1차 판정,판정 대상,메모\r\n")
        rows.forEach { r ->
            val labels = capturesByVisit[r.visitId].orEmpty().flatMap { c -> detections[c.id].orEmpty().filter { it.alert }.map { it.label } }.distinct()
            val review = reviews[r.visitId]
            listOf(
                r.seq, r.order, r.poleId, r.lat, r.lon, r.lineName, r.lineNo, r.lengthM?.let { "${it.toInt()}M" }, r.equipment, r.status, r.skipReason,
                r.arrivedAt?.let { timeFormat.format(Date(it)) }, r.photoCount, r.detectCount, labels.joinToString(";", transform = TargetLabels::title),
                review?.let { ReviewVerdict.valueOf(it.verdict).title },
                review?.tags?.split(';')?.filter { it.isNotEmpty() }?.joinToString(";", transform = TargetLabels::title), review?.memo,
            ).joinTo(this, ",") { csvField(it) }
            append("\r\n")
        }
    }

    val images = captures.mapNotNull { c -> c.filePath?.let(::File)?.takeIf { it.isFile } }
    val around = rows.map { GeoPoint(it.lat, it.lon) } + GeoPoint(mission.homeLat, mission.homeLon)
    val notes = Annotations.near(dao.annotations().map { it.toAnnotation() }, around, AppConfig.Annotation.EXPORT_MARGIN_M)
    val json = JSONObject()
        .put("missionId", mission.id)
        .put("project", dao.projectName(mission.projectId))
        .put("createdAt", mission.createdAt)
        .put("status", mission.status)
        .put("home", JSONObject().put("lat", mission.homeLat).put("lon", mission.homeLon))
        .put("flightParams", JSONObject(mission.flightParams))
        .put("modelVersion", mission.modelVersion ?: JSONObject.NULL)
        .put("sorties", JSONArray(dao.sorties(missionId).map { s ->
            JSONObject().put("seq", s.seq).put("status", s.status).put("estDistanceM", s.estDistanceM)
                .put("estBatteryPct", s.estBatteryPct).put("startedAt", s.startedAt ?: JSONObject.NULL)
                .put("endedAt", s.endedAt ?: JSONObject.NULL)
        }))
        .put("poles", JSONArray(rows.map { r ->
            JSONObject().put("sortie", r.seq).put("order", r.order).put("poleId", r.poleId).put("lineNo", r.lineNo ?: JSONObject.NULL).put("lengthM", r.lengthM ?: JSONObject.NULL)
                .put("equipment", r.equipment ?: JSONObject.NULL).put("lat", r.lat).put("lon", r.lon)
                .put("status", r.status).put("skipReason", r.skipReason ?: JSONObject.NULL)
                .put("arrivedAt", r.arrivedAt ?: JSONObject.NULL)
                .put("review", reviews[r.visitId]?.let { v ->
                    JSONObject().put("verdict", v.verdict).put("tags", JSONArray(v.tags.split(';').filter { it.isNotEmpty() }))
                        .put("memo", v.memo ?: JSONObject.NULL).put("at", v.at)
                } ?: JSONObject.NULL)
                .put("captures", JSONArray(capturesByVisit[r.visitId].orEmpty().map { c ->
                    val file = c.filePath?.let(::File)?.takeIf { it.isFile }
                    JSONObject().put("mediaId", c.mediaId).put("lens", c.lens).put("equipment", c.equipmentShot).put("source", c.source).put("takenAt", c.takenAt)
                        .put("lat", c.lat ?: JSONObject.NULL).put("lon", c.lon ?: JSONObject.NULL).put("altRel", c.altRel)
                        .put("gimbalPitch", c.gimbalPitch).put("heading", c.heading)
                        .put("originalDownloaded", c.originalDownloaded)
                        .put("file", file?.let { "images/${it.name}" } ?: JSONObject.NULL)
                        .put("detections", JSONArray(detections[c.id].orEmpty().map { d ->
                            JSONObject().put("label", d.label).put("score", d.score.toDouble())
                                .put("x", d.x.toDouble()).put("y", d.y.toDouble()).put("w", d.w.toDouble()).put("h", d.h.toDouble()).put("alert", d.alert)
                        }))
                }))
        }))

    withContext(Dispatchers.IO) {
        zip.parentFile?.mkdirs()
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("result.csv")); out.write(csv.toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("mission.json")); out.write(json.toString(2).toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("annotations.geojson")); out.write(annotationsGeoJson(notes).toString(2).toByteArray()); out.closeEntry()
            images.forEach { f ->
                out.putNextEntry(ZipEntry("images/${f.name}")); f.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
            // 운용 로그(4.4): 미션을 만든 날부터의 조작 기록
            OpLog.files(mission.createdAt - DAY_MS).forEach { f ->
                out.putNextEntry(ZipEntry("logs/${f.name}")); f.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
    }
    return ExportResult(zip, rows.size, images.size)
}

private const val DAY_MS = 24L * 3600 * 1000
