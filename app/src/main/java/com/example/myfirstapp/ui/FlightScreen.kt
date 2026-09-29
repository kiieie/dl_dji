package com.example.myfirstapp.ui

import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.core.executor.Command
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.executor.ExecutorUiState
import com.example.myfirstapp.core.executor.InspectionExecutor
import com.example.myfirstapp.core.fake.FakeDetector
import com.example.myfirstapp.detect.TfliteYoloDetector
import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.Notifier
import com.example.myfirstapp.core.port.Sound
import com.example.myfirstapp.core.port.StorageMonitor
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.SortiePlan
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.RecorderSortie
import com.example.myfirstapp.data.RoomRecorder
import com.example.myfirstapp.data.flightParamsFromJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 모의 기체의 시간 배속. 시연과 스모크 시험이 몇 분 안에 끝나게 한다. 실제 기체에는 해당 없음. */
private const val FAKE_TIME_SCALE = 5.0

/** 조종기 쪽 저장 공간 기준(6.5). SD 여유 기준(6.7)과 같게 1GB로 둔다. */
private const val MIN_FREE_BYTES = 1L shl 30

private class Flight(
    val executor: InspectionExecutor,
    val drone: FakeDroneGateway,
    val energy: EnergyModel,
    val home: GeoPoint,
    val poles: List<Pole>,
)

/**
 * 비행 화면 ④(10장). 기체 연동 전이므로 [FakeDroneGateway]로 실행한다.
 * 저장한 `MISSION`에서 남은(PENDING·IN_PROGRESS) 방문만 읽어 소티를 만든다.
 */
@Composable
fun FlightScreen(projectId: Long, missionId: Long, dao: ProjectDao, onExit: () -> Unit, onShowResult: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var flight by remember { mutableStateOf<Flight?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val notifier = remember { ToneNotifier() }
    var model by remember { mutableStateOf<TfliteYoloDetector?>(null) }
    DisposableEffect(Unit) { onDispose { notifier.release(); model?.close() } }

    LaunchedEffect(missionId) {
        val mission = dao.mission(missionId) ?: return@LaunchedEffect run { loadError = "미션을 찾을 수 없습니다" }
        val params = flightParamsFromJson(mission.flightParams)
        val energy = EnergyModel(EnergyParams(), params)
        val home = GeoPoint(mission.homeLat, mission.homeLon)
        val rows = dao.missionVisits(missionId).filter { it.status == "PENDING" || it.status == "IN_PROGRESS" }
        if (rows.isEmpty()) return@LaunchedEffect run { loadError = "남은 전주가 없습니다(미션 완료)" }
        val groups = rows.groupBy { it.sortieId }.values.toList()
        val sorties = groups.map { g ->
            val poles = g.map { Pole(it.poleId, GeoPoint(it.lat, it.lon), it.lineName, it.note) }
            val route = listOf(home) + poles.map { it.position } + home
            val dist = route.zipWithNext { a, b -> LocalProjection.distanceM(a, b) }.sum()
            SortiePlan(poles, dist, energy.sortieCost(dist, poles.size), energy.sortieDurationSec(dist, poles.size))
        }
        val recorder = RoomRecorder(
            dao, missionId,
            groups.map { g -> RecorderSortie(g.first().sortieId, g.map { it.visitId to it.poleId }) },
            File(context.getExternalFilesDir(null), "projects/$projectId/missions/$missionId/images"),
            params.downloadMode,
        )
        val ext = context.getExternalFilesDir(null)
        // 앱 폴더 fake_photos/에 JPEG이 있으면 차례로 돌려준다(탐지 모델 시험용, 10장 ⑤)
        val samples = File(ext, "fake_photos").listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty().sortedBy { it.name }
        var shot = 0
        val image = if (samples.isEmpty()) ::fakePhoto else { _: CaptureInfo -> samples[shot++ % samples.size].readBytes() }
        val drone = FakeDroneGateway(scope, home, FakeDroneGateway.Config(timeScale = FAKE_TIME_SCALE, image = image))
        val storage = StorageMonitor { (context.getExternalFilesDir(null)?.usableSpace ?: 0L) >= MIN_FREE_BYTES }
        // 앱 폴더 models/에 규격에 맞는 모델이 있으면 TFLite 탐지기, 없으면 모의 탐지기(9.1)
        model = withContext(Dispatchers.IO) { TfliteYoloDetector.loadLatest(File(ext, "models")) }
        val executor = InspectionExecutor(scope, drone, recorder, notifier, storage, energy, model ?: FakeDetector(), sorties)
        executor.start()
        flight = Flight(executor, drone, energy, home, sorties.flatMap { it.poles })
    }

    val f = flight
    if (f == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(loadError ?: "미션을 불러오는 중입니다")
                if (loadError != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onShowResult) { Text("결과 보기") }
                        OutlinedButton(onClick = onExit) { Text("나가기") }
                    }
                }
            }
        }
        return
    }
    val ui by f.executor.uiState.collectAsState()
    val tele by f.drone.telemetry.collectAsState()
    val canExit = ui.state in setOf(ExecState.Idle, ExecState.SortieDone, ExecState.MissionDone)
    // 비행 중에는 뒤로 가기로 화면을 떠나지 못하게 한다(떠나면 실행기가 멈춘다).
    BackHandler { if (canExit) onExit() }

    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        TopDownView(f, ui, tele, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBar(f, ui, tele)
            ProgressLine(ui)
        }
        Card(Modifier.align(Alignment.TopEnd).padding(12.dp).width(340.dp)) {
            Column(
                Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { Controls(f, ui, canExit, onExit, onShowResult) }
        }
        if (ui.state == ExecState.Paused) {
            // 보조용 큰 [재개]: 화면 하단 중앙, 초록, 일시정지 상태에서만(10장)
            Button(
                onClick = { f.executor.send(Command.Resume) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).width(320.dp).height(96.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B8A3E), contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
            ) { Text("▶ 재개", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun StatusBar(f: Flight, ui: ExecutorUiState, t: Telemetry) {
    val needPct = f.energy.move(t.position?.let { LocalProjection.distanceM(it, f.home) } ?: 0.0)
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("배터리 ${t.batteryPct.toInt()}% (복귀 필요 ${needPct.toInt()}%)", color = Color.White)
        Text("고도 ${t.altitudeM.toInt()}m", color = Color.White)
        Text("GPS ${"●".repeat(t.gpsLevel)}${"○".repeat((5 - t.gpsLevel).coerceAtLeast(0))}", color = Color.White)
        Text("모의 기체 ×${FAKE_TIME_SCALE.toInt()}", color = Color(0xFFFFD43B))
    }
}

@Composable
private fun ProgressLine(ui: ExecutorUiState) {
    val sortieVisits = ui.visits.filter { it.sortieIndex == ui.sortieIndex }
    val done = sortieVisits.count { it.status == VisitStatus.DONE || it.status == VisitStatus.SKIPPED || it.status == VisitStatus.FAILED }
    val sortieCount = (ui.visits.maxOfOrNull { it.sortieIndex } ?: 0) + 1
    val target = ui.target?.let { "현재 ${it.pole.poleId} (${it.order + 1}/${sortieVisits.size})" } ?: "전주 ${sortieVisits.size}개"
    val wait = ui.countdownSec?.let { " ${it}s" }.orEmpty()
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("${stateLabel(ui.state)}$wait", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("소티 ${ui.sortieIndex + 1}/$sortieCount · $target · 처리 $done/${sortieVisits.size}", color = Color.White)
        ui.rthCountdownSec?.let { Text("배터리 부족 — ${it}초 뒤 자동 RTH", color = Color(0xFFFF8787)) }
        ui.message?.let { Text(it, color = Color(0xFFFFD43B)) }
        ui.preflightFailures.forEach { Text("점검 실패: $it", color = Color(0xFFFF8787)) }
    }
}

@Composable
private fun Controls(f: Flight, ui: ExecutorUiState, canExit: Boolean, onExit: () -> Unit, onShowResult: () -> Unit) {
    val send = f.executor::send
    when (ui.state) {
        ExecState.Idle -> Button(onClick = { send(Command.Start) }) { Text("비행 시작") }
        ExecState.SortieDone -> {
            Text("소티 완료. 배터리를 교체한 뒤 다음 소티를 시작하세요.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { f.drone.setBattery(100.0); send(Command.Start) }) { Text("다음 소티") }
                OutlinedButton(onClick = { send(Command.EndMission) }) { Text("미션 종료") }
            }
        }
        ExecState.MissionDone -> {
            Text("미션 완료", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onShowResult) { Text("결과 보기") }
        }
        ExecState.Paused -> {
            Text("일시정지됨 — 수동 조종 가능", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { send(Command.ManualCapture) }) { Text("촬영") }
                FilledTonalButton(onClick = { send(Command.Skip()) }) { Text("건너뛰기") }
                FilledTonalButton(onClick = { send(Command.MarkDone) }) { Text("완료 처리") }
            }
            RthButton { send(Command.ReturnHome) }
        }
        else -> if (ui.state.airborne || ui.state == ExecState.ReturningHome) {
            // 자동 비행 중 버튼은 [RTH](길게)뿐이다(10장).
            if (ui.state != ExecState.ReturningHome) RthButton { send(Command.ReturnHome) }
        }
    }
    if (canExit) OutlinedButton(onClick = onExit) { Text("나가기") }

    // RC 하드웨어 버튼을 대신하는 모의 조작(기체 연동 전 전용).
    Text("모의 RC", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { f.drone.pressRcPause() }) { Text("RC 일시정지") }
        OutlinedButton(onClick = { f.drone.pressRcResume() }) { Text("RC 재개") }
    }
    OutlinedButton(onClick = { f.drone.setBattery((f.drone.telemetry.value.batteryPct - 30).coerceAtLeast(0.0)) }) { Text("배터리 −30%") }
}

/** 모의 기체 사진: 렌즈·미디어 번호·좌표를 그린 JPEG(10장 ⑤). 실제 기체에서는 쓰지 않는다. */
private fun fakePhoto(c: CaptureInfo): ByteArray {
    val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(if (c.lens.name == "ZOOM") 0xFF3B4A5A.toInt() else 0xFF4A6072.toInt())
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C7B6B.toInt() }
    canvas.drawRect(305f, 80f, 335f, 480f, paint) // 전주
    canvas.drawRect(200f, 130f, 440f, 150f, paint) // 완금
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 28f
    canvas.drawText("모의 사진 ${c.lens} ${c.mediaId}", 20f, 40f, paint)
    c.position?.let { canvas.drawText("%.6f, %.6f".format(it.lat, it.lon), 20f, 460f, paint) }
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it); bmp.recycle() }.toByteArray()
}

/** 잘못 누르지 않도록 1초 이상 눌러야 동작하는 RTH 버튼(10장). */
@Composable
private fun RthButton(onLongPress: () -> Unit) {
    Box(
        Modifier.size(width = 200.dp, height = 56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFC92A2A))
            .semantics { contentDescription = "RTH 길게 누르기" }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
        contentAlignment = Alignment.Center,
    ) { Text("RTH (길게)", color = Color.White, fontWeight = FontWeight.Bold) }
}

/** FPV 자리에 그리는 위에서 본 그림: 이륙 지점(흰 사각형), 소티 경로(회색 선), 전주(상태별 색), 기체(노랑). */
@Composable
private fun TopDownView(f: Flight, ui: ExecutorUiState, t: Telemetry, modifier: Modifier) {
    val proj = remember(f.home) { LocalProjection(f.home) }
    val points = remember(f.poles) { f.poles.map { proj.toEnu(it.position) } }
    val status = ui.visits.associate { it.pole.poleId to it.status }
    val enuOf = f.poles.indices.associate { f.poles[it].poleId to points[it] }
    // 상태 패널(왼쪽 위)과 조작 카드(오른쪽)를 피한 영역에 가운데 맞춰 그린다.
    Canvas(modifier.padding(start = 24.dp, end = 380.dp, top = 170.dp, bottom = 24.dp)) {
        val all = points + Enu(0.0, 0.0)
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1.0); val spanY = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf(size.width / spanX, size.height / spanY)
        val offX = (size.width - spanX * scale) / 2; val offY = (size.height - spanY * scale) / 2
        fun at(x: Double, y: Double) = Offset((offX + (x - minX) * scale).toFloat(), (size.height - offY - (y - minY) * scale).toFloat())
        fun at(e: Enu) = at(e.x, e.y)
        val homeAt = at(0.0, 0.0)
        ui.visits.groupBy { it.sortieIndex }.values.forEach { v ->
            val route = listOf(homeAt) + v.sortedBy { it.order }.mapNotNull { enuOf[it.pole.poleId]?.let(::at) } + homeAt
            route.zipWithNext { a, b -> drawLine(Color(0x66FFFFFF), a, b, strokeWidth = 3f) }
        }
        drawRect(Color.White, topLeft = homeAt - Offset(10f, 10f), size = Size(20f, 20f))
        f.poles.forEachIndexed { i, p ->
            val c = when (status[p.poleId]) {
                VisitStatus.DONE -> Color(0xFF40C057)
                VisitStatus.SKIPPED -> Color(0xFF868E96)
                VisitStatus.FAILED -> Color(0xFFFA5252)
                VisitStatus.IN_PROGRESS -> Color(0xFF4DABF7)
                else -> Color(0xFFFD7E14)
            }
            drawCircle(c, 12f, at(points[i].x, points[i].y))
        }
        t.position?.let { proj.toEnu(it) }?.let { drawCircle(Color(0xFFFFD43B), 16f, at(it.x, it.y)) }
    }
}

private fun stateLabel(s: ExecState) = when (s) {
    ExecState.Idle -> "비행 대기"
    ExecState.PreflightCheck -> "비행 전 점검"
    ExecState.Takeoff -> "이륙 중"
    ExecState.PreparingLeg -> "구간 준비"
    ExecState.FlyingLeg -> "이동 중"
    ExecState.Arrived -> "도착"
    ExecState.Capturing -> "촬영 중"
    ExecState.Downloading -> "다운로드 중"
    ExecState.Analyzing -> "분석 중"
    ExecState.Waiting -> "대기"
    ExecState.Paused -> "일시정지됨"
    ExecState.LinkLost -> "연결 끊김"
    ExecState.ReturningHome -> "복귀 중"
    ExecState.SortieDone -> "소티 완료"
    ExecState.MissionDone -> "미션 완료"
}

/**
 * 알림음(11장). 녹음한 음성 파일이 준비되기 전까지 ToneGenerator 신호음으로 대신한다.
 * 스모크 시험이 재생 순서를 확인하도록 logcat(`Sound`)에도 남긴다.
 */
private class ToneNotifier : Notifier {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

    override fun play(sound: Sound) {
        Log.i("Sound", sound.name)
        val (type, ms) = when (sound) {
            Sound.ARRIVED -> ToneGenerator.TONE_PROP_BEEP to 150
            Sound.INSPECTION_OK -> ToneGenerator.TONE_PROP_ACK to 300
            Sound.ANOMALY -> ToneGenerator.TONE_PROP_BEEP2 to 600
            Sound.PAUSED -> ToneGenerator.TONE_PROP_PROMPT to 300
            Sound.RESUMED -> ToneGenerator.TONE_PROP_ACK to 300
            Sound.BATTERY_WARNING -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK to 1500
            Sound.ERROR -> ToneGenerator.TONE_PROP_NACK to 400
        }
        tone?.startTone(type, ms)
    }

    fun release() = tone?.release()
}
