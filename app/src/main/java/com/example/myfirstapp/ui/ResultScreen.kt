package com.example.myfirstapp.ui

import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.data.CaptureEntity
import com.example.myfirstapp.data.DetectionEntity
import com.example.myfirstapp.data.ExportResult
import com.example.myfirstapp.data.MissionEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ResultRow
import com.example.myfirstapp.data.exportMission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private class MissionResult(
    val mission: MissionEntity,
    val rows: List<ResultRow>,
    val captures: Map<Long, List<CaptureEntity>>,
    val detections: Map<Long, List<DetectionEntity>>,
)

private val STATUS_LABEL = mapOf(
    "DONE" to "완료", "SKIPPED" to "건너뜀", "FAILED" to "실패", "PENDING" to "미점검", "IN_PROGRESS" to "미점검",
)

/** 결과 화면 ⑤(10장): 전주 목록과 필터(왼쪽), 고른 전주의 사진과 탐지 박스(오른쪽), 내보내기. */
@Composable
fun ResultScreen(projectId: Long, missionId: Long, dao: ProjectDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<MissionResult?>(null) }
    var anomaliesOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var exported by remember { mutableStateOf<ExportResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(missionId) {
        val mission = dao.mission(missionId) ?: return@LaunchedEffect run { message = "미션을 찾을 수 없습니다" }
        data = MissionResult(
            mission, dao.resultRows(missionId),
            dao.missionCaptures(missionId).groupBy { it.visitId }, dao.missionDetections(missionId).groupBy { it.captureId },
        )
    }

    // 앱 폴더에 만든 ZIP을 운용자가 고른 위치(SAF)로 복사한다.
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val r = exported ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult run { message = "저장을 취소했습니다. ZIP은 앱 폴더에 남아 있습니다." }
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { out -> r.zip.inputStream().use { it.copyTo(out) } }
                }
            }.fold(
                { "내보냈습니다: 전주 ${r.poleCount}개, 사진 ${r.imageCount}장" },
                { "저장하지 못했습니다: ${it.message}" },
            )
            Log.i("Export", "saved uri=$uri")
        }
    }

    val d = data
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message ?: "결과를 불러오는 중입니다") }
        return
    }
    val counts = d.rows.groupingBy { STATUS_LABEL[it.status] ?: it.status }.eachCount()
    val anomalyPoles = d.rows.count { it.anomalyCount > 0 }
    val shown = if (anomaliesOnly) d.rows.filter { it.anomalyCount > 0 } else d.rows

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("결과 — 미션 #${d.mission.id}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    listOf("완료", "건너뜀", "실패", "미점검").joinToString(" · ") { "$it ${counts[it] ?: 0}" } +
                        " · 이상 $anomalyPoles · 모델 ${d.mission.modelVersion ?: "없음"}",
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            Button(onClick = {
                scope.launch {
                    val zip = File(context.getExternalFilesDir(null), "projects/$projectId/missions/$missionId/export/$missionId.zip")
                    runCatching { exportMission(dao, missionId, zip) }
                        .onSuccess { r ->
                            exported = r
                            Log.i("Export", "zip=${r.zip.absolutePath} poles=${r.poleCount} images=${r.imageCount}")
                            saver.launch("${dao.projectName(projectId) ?: "project"}_mission$missionId.zip")
                        }
                        .onFailure { message = "내보내기 실패: ${it.message}" }
                }
            }) { Text("내보내기") }
            OutlinedButton(onClick = onBack) { Text("나가기") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!anomaliesOnly, { anomaliesOnly = false }, label = { Text("전체 ${d.rows.size}") })
                    FilterChip(anomaliesOnly, { anomaliesOnly = true }, label = { Text("이상만 $anomalyPoles") })
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.visitId }) { r -> PoleRow(r, r.visitId == selected) { selected = r.visitId } }
                }
            }
            Box(Modifier.weight(0.6f)) {
                val row = d.rows.firstOrNull { it.visitId == selected }
                if (row == null) Text("왼쪽에서 전주를 고르세요.") else PoleDetail(row, d.captures[row.visitId].orEmpty(), d.detections)
            }
        }
    }
}

@Composable
private fun PoleRow(r: ResultRow, selected: Boolean, onClick: () -> Unit) {
    val colors = if (selected) CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${r.seq}-${r.order}", style = MaterialTheme.typography.labelMedium)
            Text(r.poleId, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(STATUS_LABEL[r.status] ?: r.status)
            Text("사진 ${r.photoCount}")
            if (r.anomalyCount > 0) Text("이상 ${r.anomalyCount}", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PoleDetail(r: ResultRow, captures: List<CaptureEntity>, detections: Map<Long, List<DetectionEntity>>) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${r.poleId} · ${STATUS_LABEL[r.status] ?: r.status}", style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(
                r.lineName, "%.6f, %.6f".format(r.lat, r.lon),
                r.arrivedAt?.let { "도착 " + DateFormat.getTimeInstance().format(Date(it)) }, r.skipReason,
            ).joinToString(" · "),
        )
        if (captures.isEmpty()) Text("사진이 없습니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            captures.forEach { c ->
                Column(Modifier.weight(1f)) {
                    Photo(c, detections[c.id].orEmpty())
                    Text("${if (c.lens == "ZOOM") "망원" else if (c.lens == "WIDE") "광각" else c.lens} · ${c.source}")
                    detections[c.id].orEmpty().forEach { Text("${it.label} ${(it.score * 100).toInt()}%", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** 사진 위에 탐지 박스(0~1 비율 좌표)를 그린다. 파일이 없거나 읽지 못하면 자리만 표시한다. */
@Composable
private fun Photo(c: CaptureEntity, boxes: List<DetectionEntity>) {
    val bitmap = remember(c.filePath) { c.filePath?.let { BitmapFactory.decodeFile(it) }?.asImageBitmap() }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color(0xFF263238))) {
        if (bitmap != null) Image(bitmap, contentDescription = "${c.lens} 사진", modifier = Modifier.fillMaxSize())
        else Text("사진 파일 없음", color = Color.White, modifier = Modifier.align(Alignment.Center))
        Canvas(Modifier.fillMaxSize()) {
            boxes.forEach { b ->
                drawRect(
                    Color(0xFFFF5252), Offset(b.x * size.width, b.y * size.height), Size(b.w * size.width, b.h * size.height),
                    style = Stroke(3.dp.toPx()),
                )
            }
        }
    }
}
