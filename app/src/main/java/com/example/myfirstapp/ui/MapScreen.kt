package com.example.myfirstapp.ui

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myfirstapp.data.PoleEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File

/** 2단계 목표 "지도에서 전주 확인"(13장). 영역 그리기와 경로는 3단계(계획 화면 ③)에서 붙인다. */
@Composable
fun MapScreen(project: ProjectSummary, dao: ProjectDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val poles by remember(project.id) { dao.observePoles(project.id) }.collectAsState(initial = null)
    val mapsDir = remember { File(context.getExternalFilesDir(null), "maps").apply { mkdirs() } }
    var mapFiles by remember { mutableStateOf(listMbtiles(mapsDir)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = "지도 파일을 복사하는 중입니다"
            runCatching { withContext(Dispatchers.IO) { copyMbtiles(context, uri, mapsDir) } }
                .onSuccess {
                    mapFiles = listMbtiles(mapsDir)
                    message = "지도를 추가했습니다: $it"
                }
                .onFailure { message = "지도를 추가하지 못했습니다: ${it.message}" }
        }
    }

    Box(Modifier.fillMaxSize()) {
        poles?.let { PoleMap(it, mapFiles, onPoleClick = { id -> selected = id }) }

        Card(Modifier.padding(16.dp).widthIn(max = 560.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack) { Text("← 목록") }
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text("전주 ${poles?.size ?: 0}개")
                }
                Text(
                    if (mapFiles.isEmpty()) "오프라인 지도(.mbtiles)가 없습니다. 배경 없이 전주만 표시합니다."
                    else "오프라인 지도: ${mapFiles.joinToString { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("지도 파일 추가") }
            }
        }

        val pole = poles?.firstOrNull { it.poleId == selected }
        if (pole != null) {
            Card(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text(pole.poleId, style = MaterialTheme.typography.titleLarge)
                        Text("${pole.lat}, ${pole.lon}  ${pole.lineName.orEmpty()}  ${pole.note.orEmpty()}")
                    }
                    Button(onClick = { selected = null }) { Text("닫기") }
                }
            }
        }
    }
}

/**
 * MapLibre 지도. 배경은 앱 전용 폴더의 MBTiles를 모두 겹쳐 그린다(지역별 파일). 네트워크 소스는 쓰지 않는다.
 * ponytail: 글리프(폰트) 파일이 없어 지도 위 글자(클러스터 개수, 전주번호)는 그리지 않는다. 전주번호는 탭하면 카드로 보여 준다.
 */
@Composable
private fun PoleMap(poles: List<PoleEntity>, mapFiles: List<File>, onPoleClick: (String?) -> Unit) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val onClick by rememberUpdatedState(onPoleClick)
    val mapView = remember {
        MapLibre.getInstance(context)
        // Compose 안에서는 SurfaceView보다 TextureView가 겹침·캡처 문제가 적다.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    var fitted by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.addOnMapClickListener { latLng ->
                val p = map.projection.toScreenLocation(latLng)
                val touch = 24 * density // 장갑 낀 손가락 기준 48dp 영역
                val box = RectF(p.x - touch, p.y - touch, p.x + touch, p.y + touch)
                if (map.queryRenderedFeatures(box, "clusters").isNotEmpty()) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, map.cameraPosition.zoom + 2))
                } else {
                    onClick(map.queryRenderedFeatures(box, "poles-point").firstOrNull()?.getStringProperty("poleId"))
                }
                true
            }
        }
    }

    LaunchedEffect(poles, mapFiles) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson(mapFiles))) { style ->
                val features = poles.map {
                    Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("poleId", it.poleId) }
                }
                // N-04: 전주 1,000개 이상에서도 원활하도록 클러스터링한다.
                style.addSource(
                    GeoJsonSource(
                        "poles", FeatureCollection.fromFeatures(features),
                        GeoJsonOptions().withCluster(true).withClusterRadius(50).withClusterMaxZoom(15),
                    ),
                )
                style.addLayer(
                    CircleLayer("clusters", "poles").withFilter(has("point_count")).withProperties(
                        circleColor("#1F6F5C"),
                        circleRadius(step(get("point_count"), literal(14f), stop(50, 18f), stop(200, 24f))),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                    ),
                )
                style.addLayer(
                    CircleLayer("poles-point", "poles").withFilter(not(has("point_count"))).withProperties(
                        circleColor("#D9480F"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                    ),
                )
                if (!fitted && poles.isNotEmpty()) {
                    fitted = true
                    val points = poles.map { LatLng(it.lat, it.lon) }
                    map.moveCamera(
                        if (points.distinct().size < 2) CameraUpdateFactory.newLatLngZoom(points.first(), 17.0)
                        else {
                            // 왼쪽 위 정보 카드(최대 560dp)에 전주가 가리지 않도록 왼쪽 여백을 더 둔다.
                            val pad = (64 * density).toInt()
                            val bounds = LatLngBounds.Builder().includes(points).build()
                            CameraUpdateFactory.newLatLngBounds(bounds, (592 * density).toInt(), pad, pad, pad)
                        },
                    )
                }
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun styleJson(mapFiles: List<File>): String {
    val sources = mapFiles.mapIndexed { i, f -> """"base$i":{"type":"raster","url":"mbtiles://${f.absolutePath}","tileSize":256}""" }
    val layers = listOf("""{"id":"background","type":"background","paint":{"background-color":"#E6EAE7"}}""") +
        mapFiles.indices.map { """{"id":"base$it","type":"raster","source":"base$it"}""" }
    return """{"version":8,"sources":{${sources.joinToString(",")}},"layers":[${layers.joinToString(",")}]}"""
}

private fun listMbtiles(dir: File) = dir.listFiles { f -> f.extension == "mbtiles" }.orEmpty().sortedBy { it.name }

/** SAF로 고른 MBTiles를 앱 전용 폴더(4.3 `maps/`)에 복사한다. SQLite 파일인지 머리글로 확인한다. */
private fun copyMbtiles(context: Context, uri: Uri, dir: File): String {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    } ?: "map.mbtiles"
    require(name.endsWith(".mbtiles", ignoreCase = true)) { ".mbtiles 파일만 추가할 수 있습니다" }
    // 파일 이름은 스타일 JSON에 들어가므로 글자·숫자·`.`·`-`·`_`만 남긴다.
    val target = File(dir, name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_"))
    val part = File(dir, target.name + ".part")
    resolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it) } } ?: error("파일을 열 수 없습니다")
    val header = part.inputStream().use { String(it.readNBytesCompat(15), Charsets.US_ASCII) }
    if (header != "SQLite format 3") {
        part.delete()
        error("MBTiles(SQLite) 형식이 아닙니다")
    }
    check(part.renameTo(target)) { "파일 이름을 바꾸지 못했습니다" }
    return target.name
}

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(buf, read, n - read)
        if (r < 0) break
        read += r
    }
    return buf.copyOf(read)
}
