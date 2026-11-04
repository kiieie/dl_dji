package com.example.myfirstapp.ui.replay

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.R
import com.example.myfirstapp.core.input.fiveD
import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.replay.clockText
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.FullscreenAlertDialog
import com.example.myfirstapp.ui.flight.FlightMap
import com.example.myfirstapp.ui.toPoint
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.MAP_TRACK
import com.example.myfirstapp.ui.theme.numberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 전체 궤적(흐린 주황) 소스·레이어 이름. */
private const val FULL_TRACK = "replay-full"

/**
 * 비행 다시 보기(10.4.6): 비행 지도([FlightMap], 기체 화살표·H·주황 궤적·계획 경로·전주)를 시각 t에 맞춰 그리고, 전체 궤적은 흐리게,
 * 사진은 파란 점(t까지 찍은 것은 진하게, 누르면 사진·[판독]). 위 제목·소티 고르기, 아래 재생·배속·시간 막대·t의 ALT·SPD·배터리·짐벌·모드.
 * RC 5D: ↔ 찾기, ● 재생·멈춤(← 뒤로는 화면 ‹ 버튼).
 */
@Composable
fun ReplayScreen(
    dao: ProjectDao, missionId: Long?, sortieId: Long?, sessionId: Long?, onBack: () -> Unit, onReview: (projectId: Long, missionId: Long) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as InspectionApp
    val vm: ReplayViewModel = viewModel(factory = ReplayViewModel.factory(dao, context.getExternalFilesDir(null), app.settings, missionId, sortieId, sessionId))
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { app.rcButtons.fiveD().collect(vm::on5d) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 위치 없이(실내) 난 기록도 값은 보이게, 점이 하나도 없을 때만 안내
            if (!s.loading && s.wallAt == 0L) {
                Text("기록된 비행 궤적이 없습니다", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else key(s.sortieId) { ReplayMap(s, vm) }
            TopBar(s, onBack, vm::selectSortie)
        }
        PlayBar(s, vm)
    }
    s.viewing?.let { p ->
        // 전주에 붙은 사진이면 그 미션 판독으로
        val pid = p.projectId
        val mid = p.missionId
        FullscreenAlertDialog(
            onDismissRequest = vm::closePhoto,
            title = { Text("${p.label} · ${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(p.takenAt))}", fontSize = FontSize.Label) },
            text = { Photo(p.path, Modifier.fillMaxWidth().height(280.dp)) },
            confirmButton = { TextButton(onClick = vm::closePhoto) { Text("닫기") } },
            dismissButton = if (pid != null && mid != null) {
                { TextButton(onClick = { vm.closePhoto(); onReview(pid, mid) }) { Text("판독") } }
            } else null,
        )
    }
}

/** 지도와 사진 점. 사진 점은 Compose로 덧그린다(지도 누름은 [FlightMap]이 기체·조종기 카드로 받으므로). */
@Composable
private fun ReplayMap(s: ReplayUiState, vm: ReplayViewModel) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // 지도가 움직일 때마다 늘려 사진 점 자리를 다시 센다
    var moved by remember { mutableIntStateOf(0) }
    FlightMap(s.map, interactive = true, pad = List(4) { 60.dp }, Modifier.fillMaxSize(), vm::setMapSource, vm::clearTrack) { m, st ->
        st.addSource(GeoJsonSource(FULL_TRACK))
        st.addLayerBelow(LineLayer(FULL_TRACK, FULL_TRACK).withProperties(lineColor(MAP_TRACK), lineOpacity(0.35f), lineWidth(3f)), "track-line")
        if (map !== m) {
            m.addOnCameraMoveListener { moved++ }
            m.addOnCameraIdleListener { moved++ }
        }
        map = m
        style = st
    }
    LaunchedEffect(style, s.fullTrack) {
        val line = s.fullTrack.takeIf { it.size >= 2 }?.let { t -> Feature.fromGeometry(LineString.fromLngLats(t.map { it.toPoint() })) }
        style?.getSourceAs<GeoJsonSource>(FULL_TRACK)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(line)))
    }
    val m = map ?: return
    // 보이는 범위의 사진만 화면 좌표로
    val dots = remember(moved, s.photos, m) {
        val bounds = m.projection.visibleRegion.latLngBounds
        s.photos.mapNotNull { p -> LatLng(p.position.lat, p.position.lon).takeIf { bounds.contains(it) }?.let { p to m.projection.toScreenLocation(it) } }
    }
    val half = with(LocalDensity.current) { (Dimens.TouchSmall / 2).roundToPx() }
    dots.forEach { (p, at) ->
        // 장갑 손가락에 맞게 누르는 칸은 크게, 점은 웨이포인트 크기
        Box(
            Modifier.offset { IntOffset(at.x.toInt() - half, at.y.toInt() - half) }.size(Dimens.TouchSmall).clickable(onClickLabel = "사진 ${p.label}") { vm.openPhoto(p) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(Dimens.MarkerWaypoint).clip(CircleShape).background(if (p.takenAt <= s.wallAt) Accent else Accent.copy(alpha = 0.35f))
                    .border(1.5.dp, MaterialTheme.colorScheme.onBackground, CircleShape),
            )
        }
    }
}

/** 위 띠: ‹ 뒤로 · 제목(미션·소티 또는 수동 비행, 날짜) · 소티가 여럿이면 소티 칩. */
@Composable
private fun TopBar(s: ReplayUiState, onBack: () -> Unit, onSortie: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.TopBar).background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSmall)) {
            Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
        }
        Text("비행 다시 보기", fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
        Text(s.title, Modifier.weight(1f), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (s.sorties.size > 1) s.sorties.forEach { o ->
            val on = o.id == s.sortieId
            Surface(
                onClick = { onSortie(o.id) }, shape = RoundedCornerShape(4.dp), modifier = Modifier.height(Dimens.ButtonChip),
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text("소티 ${o.seq}", fontSize = FontSize.Body) }
            }
        }
    }
}

/** 아래 띠: 재생·멈춤 · 배속 · 시간 막대 · "mm:ss / mm:ss", 그 아래 t의 값. */
@Composable
private fun PlayBar(s: ReplayUiState, vm: ReplayViewModel) {
    val p = s.playback
    val t = s.map.telemetry
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 8.dp, end = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = vm::togglePlay, enabled = p.durationMs > 0, modifier = Modifier.size(Dimens.TouchMedium)) {
                if (p.playing) Canvas(Modifier.size(12.dp)) {
                    val c = Accent
                    drawRect(c, Offset(size.width * 0.1f, 0f), Size(size.width * 0.3f, size.height))
                    drawRect(c, Offset(size.width * 0.6f, 0f), Size(size.width * 0.3f, size.height))
                } else Icon(painterResource(R.drawable.ic_play), contentDescription = "재생", tint = Accent, modifier = Modifier.size(16.dp))
            }
            TextButton(onClick = vm::nextSpeed, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(Dimens.ButtonChip)) {
                Text("${p.speed}×", style = numberStyle(FontSize.Label))
            }
            Slider(
                value = p.posMs.toFloat(), onValueChange = { vm.seek(it.toLong()) }, valueRange = 0f..p.durationMs.coerceAtLeast(1).toFloat(),
                enabled = p.durationMs > 0, modifier = Modifier.weight(1f),
            )
            Text("${clockText(p.posMs)} / ${clockText(p.durationMs)}", style = numberStyle(FontSize.Body))
        }
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Figure("ALT", t?.let { "%.1f m".format(it.altitudeM) })
            Figure("SPD", t?.let { "%.1f m/s".format(it.speedMps) })
            Figure("배터리", t?.let { "${it.batteryPct.toInt()}%" })
            Figure("짐벌", t?.let { "%.0f°".format(it.gimbalPitchDeg) })
            Figure("모드", t?.let { modeLabel(it.flightMode) })
            Spacer(Modifier.weight(1f))
            if (s.wallAt > 0) Text(SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(s.wallAt)), style = numberStyle(FontSize.Body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Figure(label: String, value: String?) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value ?: "--", style = numberStyle(FontSize.Body))
}

/** 기록한 비행 모드의 짧은 이름. */
private fun modeLabel(m: FlightMode) = when (m) {
    FlightMode.ON_GROUND -> "땅"
    FlightMode.TAKING_OFF -> "이륙"
    FlightMode.MISSION -> "비행 경로"
    FlightMode.MISSION_PAUSED -> "일시정지"
    FlightMode.MANUAL -> "수동"
    FlightMode.GO_HOME -> "RTH"
    FlightMode.LANDING -> "착륙"
    FlightMode.UNKNOWN -> "--"
}

/** 사진 파일(4분의 1로 디코딩). 없으면 빈 칸. */
@Composable
private fun Photo(path: String?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { path?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 4 }) }?.asImageBitmap() }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier, contentScale = ContentScale.Fit) }
}

/** 비행 기록(10.4.6, Pilot 2 프로필 → 비행 기록): 날짜 · 비행(미션·소티 또는 수동) · 비행 시간 · 거리, 최신순. 누르면 다시 보기. */
@Composable
fun FlightRecordsScreen(dao: ProjectDao, onBack: () -> Unit, onOpen: (FlightRecord) -> Unit) {
    val vm: FlightRecordsViewModel = viewModel(factory = FlightRecordsViewModel.factory(dao))
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(Dimens.HeaderList).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(Dimens.TouchSmall)) {
                Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(16.dp))
            }
            Text("비행 기록", fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
        }
        when {
            s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.records.isEmpty() -> Text(
                "기록된 비행이 없습니다. 자동 비행 소티와 수동 비행(카메라 뷰)의 궤적이 여기에 남습니다.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.records, key = { "${it.sortieId}-${it.sessionId}" }) { r ->
                    Surface(onClick = { onOpen(r) }, shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.height(Dimens.RowTable).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(r.startAt)), style = numberStyle(FontSize.Body), modifier = Modifier.width(120.dp))
                            Text(r.title, Modifier.weight(1f), fontSize = FontSize.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(clockText(r.durationMs), style = numberStyle(FontSize.Body))
                            Text(if (r.distanceM < 1000) "%.0f m".format(r.distanceM) else "%.2f km".format(r.distanceM / 1000), style = numberStyle(FontSize.Body), modifier = Modifier.width(64.dp))
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}
