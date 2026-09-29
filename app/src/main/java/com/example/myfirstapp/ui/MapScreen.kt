package com.example.myfirstapp.ui

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myfirstapp.data.PoleEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ProjectSummary
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.Polygon
import com.example.myfirstapp.core.route.RoutePlan
import com.example.myfirstapp.core.route.RoutePlanner
import com.example.myfirstapp.core.route.UnreachablePole
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
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.util.Locale
import org.maplibre.geojson.Polygon as GeoJsonPolygon

/** 지도 도구. AREA는 누를 때마다 꼭짓점을 더하고, HOME은 한 번 눌러 이륙 지점을 정한다. */
private enum class Tool { NONE, AREA, HOME }

/** 소티별 경로 색. 클러스터(초록)·전주(주황)·영역(파랑)과 겹치지 않는 색을 돌려 쓴다. */
private val SORTIE_COLORS = listOf("#9C36B5", "#0C8599", "#C2255C", "#F59F00", "#3B5BDB")
private const val AREA_COLOR = "#1864AB"

/** 계획 화면 ③(10장): 전주 지도, 영역(다각형) 그리기, 이륙 지점, 경로와 소티 미리보기. */
@Composable
fun MapScreen(project: ProjectSummary, dao: ProjectDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val poles by remember(project.id) { dao.observePoles(project.id) }.collectAsState(initial = null)
    val mapsDir = remember { File(context.getExternalFilesDir(null), "maps").apply { mkdirs() } }
    var mapFiles by remember { mutableStateOf(listMbtiles(mapsDir)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf(Tool.NONE) }
    var vertices by remember { mutableStateOf(emptyList<GeoPoint>()) }
    var home by remember { mutableStateOf<GeoPoint?>(null) }
    var plan by remember { mutableStateOf<RoutePlan?>(null) }
    var planMs by remember { mutableStateOf(0L) }
    var planning by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(emptyList<GeoPoint>()) }
    val inArea = remember(poles, vertices) {
        val all = poles.orEmpty()
        if (vertices.size < 3) emptyList() else Polygon(vertices).let { area -> all.filter { area.contains(GeoPoint(it.lat, it.lon)) } }
    }

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

    fun computePlan(start: GeoPoint) = scope.launch {
        planning = true
        val t0 = System.nanoTime()
        // ponytail: 파라미터는 기본값(FlightParams, EnergyParams)으로 계산한다. 핵심 4개 설정 UI는 다음 단계에서 붙인다.
        val result = withContext(Dispatchers.Default) {
            RoutePlanner(EnergyModel(EnergyParams(), FlightParams())).plan(start, inArea.map { it.toPole() })
        }
        planMs = (System.nanoTime() - t0) / 1_000_000
        plan = result
        planning = false
        // 증빙 스크립트가 같은 입력으로 영역 판정과 가시권 판정을 다시 계산해 대조한다.
        Log.i(
            "RoutePlan",
            "vertices=${vertices.joinToString(";") { "${it.lat},${it.lon}" }} home=${start.lat},${start.lon} " +
                "inArea=${inArea.size} sorties=${result.sorties.map { it.poles.size }} " +
                "vlos=${result.unreachable.count { it.reason == UnreachablePole.Reason.VLOS_EXCEEDED }} " +
                "battery=${result.unreachable.count { it.reason == UnreachablePole.Reason.BATTERY_EXCEEDED }} ms=$planMs",
        )
    }

    Box(Modifier.fillMaxSize()) {
        poles?.let {
            PoleMap(
                poles = it,
                mapFiles = mapFiles,
                overlay = Overlay(vertices, home, plan),
                focus = focus,
                onPoleClick = { id -> selected = id },
                onMapTap = when (tool) {
                    Tool.NONE -> null
                    Tool.AREA -> { p -> vertices = vertices + p; plan = null }
                    Tool.HOME -> { p -> home = p; tool = Tool.NONE; plan = null }
                },
            )
        }

        Card(Modifier.padding(16.dp).widthIn(max = PANEL_WIDTH_DP.dp)) {
            Column(
                Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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

                when (tool) {
                    Tool.AREA -> {
                        Text("지도를 눌러 꼭짓점을 추가하세요 (${vertices.size}개)", color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { tool = Tool.NONE; focus = vertices }, enabled = vertices.size >= 3) { Text("완료") }
                            OutlinedButton(onClick = { vertices = vertices.dropLast(1) }, enabled = vertices.isNotEmpty()) { Text("되돌리기") }
                        }
                    }
                    Tool.HOME -> {
                        Text("지도를 눌러 이륙 지점을 정하세요", color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = { tool = Tool.NONE }) { Text("취소") }
                    }
                    Tool.NONE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { vertices = emptyList(); plan = null; selected = null; tool = Tool.AREA }) { Text("영역 그리기") }
                            FilledTonalButton(onClick = { selected = null; tool = Tool.HOME }) { Text("이륙 지점") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val start = home
                            Button(
                                onClick = { if (start != null) computePlan(start) },
                                enabled = start != null && inArea.isNotEmpty() && !planning,
                            ) { Text(if (planning) "계산 중" else "경로 계산") }
                            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("지도 파일 추가") }
                        }
                    }
                }

                if (vertices.size >= 3) Text("영역 안 전주 ${inArea.size}개", style = MaterialTheme.typography.titleSmall)
                if (tool == Tool.NONE && vertices.size >= 3 && home == null) Text("이륙 지점을 정하면 경로를 계산할 수 있습니다", style = MaterialTheme.typography.bodySmall)
                plan?.let { PlanSummary(it, planMs) }
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

private const val PANEL_WIDTH_DP = 420

/** 소티별 예상 거리·시간·배터리(F-08)와 도달 불가 전주(5.4). */
@Composable
private fun PlanSummary(plan: RoutePlan, planMs: Long) {
    val visits = plan.sorties.sumOf { it.poles.size }
    val km = plan.sorties.sumOf { it.estDistanceM } / 1000
    val min = plan.sorties.sumOf { it.estDurationSec } / 60
    Text("소티 ${plan.sorties.size}개 · 전주 ${visits}개 · ${fmt(km)}km · ${fmt(min)}분", style = MaterialTheme.typography.titleSmall)
    plan.sorties.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(SORTIE_COLORS[i % SORTIE_COLORS.size]))))
            Text(
                "소티 ${i + 1}: 전주 ${s.poles.size}개 · ${fmt(s.estDistanceM / 1000)}km · ${fmt(s.estDurationSec / 60)}분 · 배터리 ${s.estBatteryPct.toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (plan.unreachable.isNotEmpty()) {
        val vlos = plan.unreachable.count { it.reason == UnreachablePole.Reason.VLOS_EXCEEDED }
        Text(
            "도달 불가 ${plan.unreachable.size}개 (가시권 초과 ${vlos}, 배터리 초과 ${plan.unreachable.size - vlos})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text("계산 ${planMs}ms · 기본 파라미터(고도 50m, 8m/s, 예비율 30%, 가시권 500m)", style = MaterialTheme.typography.bodySmall)
}

private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

/** 지도 위에 그릴 계획 요소. */
private data class Overlay(val vertices: List<GeoPoint>, val home: GeoPoint?, val plan: RoutePlan?)

/**
 * MapLibre 지도. 배경은 앱 전용 폴더의 MBTiles를 모두 겹쳐 그린다(지역별 파일). 네트워크 소스는 쓰지 않는다.
 * ponytail: 글리프(폰트) 파일이 없어 지도 위 글자(클러스터 개수, 전주번호)는 그리지 않는다. 전주번호는 탭하면 카드로 보여 준다.
 */
@Composable
private fun PoleMap(
    poles: List<PoleEntity>,
    mapFiles: List<File>,
    overlay: Overlay,
    focus: List<GeoPoint>,
    onPoleClick: (String?) -> Unit,
    onMapTap: ((GeoPoint) -> Unit)?,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val onClick by rememberUpdatedState(onPoleClick)
    val onTap by rememberUpdatedState(onMapTap)
    val mapView = remember {
        MapLibre.getInstance(context)
        // Compose 안에서는 SurfaceView보다 TextureView가 겹침·캡처 문제가 적다.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    var fitted by remember { mutableStateOf(false) }
    var styleGen by remember { mutableStateOf(0) }

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
                onTap?.let {
                    it(GeoPoint(latLng.latitude, latLng.longitude))
                    return@addOnMapClickListener true
                }
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
                // 영역과 경로는 전주 아래에, 꼭짓점과 이륙 지점은 맨 위에 그린다.
                style.addSource(GeoJsonSource("draw"))
                style.addSource(GeoJsonSource("route"))
                style.addSource(GeoJsonSource("home"))
                style.addLayer(
                    FillLayer("area-fill", "draw").withFilter(eq(geometryType(), literal("Polygon")))
                        .withProperties(fillColor(AREA_COLOR), fillOpacity(0.12f)),
                )
                style.addLayer(
                    LineLayer("area-line", "draw").withFilter(neq(geometryType(), literal("Point")))
                        .withProperties(lineColor(AREA_COLOR), lineWidth(3f)),
                )
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor(get("color")), lineWidth(4f)))
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
                style.addLayer(
                    CircleLayer("area-vertex", "draw").withFilter(eq(geometryType(), literal("Point"))).withProperties(
                        circleColor("#FFFFFF"), circleRadius(6f), circleStrokeColor(AREA_COLOR), circleStrokeWidth(3f),
                    ),
                )
                style.addLayer(
                    CircleLayer("home-point", "home").withProperties(
                        circleColor("#212529"), circleRadius(10f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
                    ),
                )
                styleGen++
                if (!fitted && poles.isNotEmpty()) {
                    fitted = true
                    val points = poles.map { LatLng(it.lat, it.lon) }
                    map.moveCamera(
                        if (points.distinct().size < 2) CameraUpdateFactory.newLatLngZoom(points.first(), 17.0)
                        else {
                            // 왼쪽 위 계획 패널에 전주가 가리지 않도록 왼쪽 여백을 더 둔다.
                            val pad = (64 * density).toInt()
                            val bounds = LatLngBounds.Builder().includes(points).build()
                            CameraUpdateFactory.newLatLngBounds(bounds, ((PANEL_WIDTH_DP + 32) * density).toInt(), pad, pad, pad)
                        },
                    )
                }
            }
        }
    }

    // 영역을 다 그리면 그 영역으로 확대한다(경로를 알아볼 수 있게).
    LaunchedEffect(focus) {
        if (focus.size < 2) return@LaunchedEffect
        mapView.getMapAsync { map ->
            val pad = (64 * density).toInt()
            val bounds = LatLngBounds.Builder().includes(focus.map { LatLng(it.lat, it.lon) }).build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, ((PANEL_WIDTH_DP + 32) * density).toInt(), pad, pad, pad))
        }
    }

    LaunchedEffect(overlay, styleGen) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            style.getSourceAs<GeoJsonSource>("draw")?.setGeoJson(drawFeatures(overlay.vertices))
            style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(routeFeatures(overlay.plan))
            style.getSourceAs<GeoJsonSource>("home")?.setGeoJson(
                FeatureCollection.fromFeatures(listOfNotNull(overlay.home?.let { Feature.fromGeometry(it.toPoint()) })),
            )
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun GeoPoint.toPoint(): Point = Point.fromLngLat(lon, lat)

/** 그리는 중인 영역: 꼭짓점 3개 이상이면 다각형, 2개면 선, 그리고 꼭짓점 점들. */
private fun drawFeatures(vertices: List<GeoPoint>): FeatureCollection {
    val pts = vertices.map { it.toPoint() }
    val shape = when {
        pts.size >= 3 -> Feature.fromGeometry(GeoJsonPolygon.fromLngLats(listOf(pts + pts.first())))
        pts.size == 2 -> Feature.fromGeometry(LineString.fromLngLats(pts))
        else -> null
    }
    return FeatureCollection.fromFeatures(listOfNotNull(shape) + pts.map { Feature.fromGeometry(it) })
}

/** 소티마다 이륙 지점 → 전주들 → 이륙 지점으로 닫힌 선 하나. */
private fun routeFeatures(plan: RoutePlan?): FeatureCollection = FeatureCollection.fromFeatures(
    if (plan == null) emptyList() else plan.sorties.mapIndexed { i, s ->
        val line = listOf(plan.home) + s.poles.map { it.position } + plan.home
        Feature.fromGeometry(LineString.fromLngLats(line.map { it.toPoint() }))
            .apply { addStringProperty("color", SORTIE_COLORS[i % SORTIE_COLORS.size]) }
    },
)

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
