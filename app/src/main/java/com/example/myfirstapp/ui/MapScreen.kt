package com.example.myfirstapp.ui

import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.PoleEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.route.RoutePlan
import com.example.myfirstapp.core.route.UnreachablePole
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
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
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
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.util.Locale
import org.maplibre.geojson.Polygon as GeoJsonPolygon

/** 소티별 경로 색. 전주(주황)·제외(회색)·영역(파랑)과 겹치지 않는 색을 돌려 쓴다(10.1 데이터 시각화 색). */
private val SORTIE_COLORS = listOf("#9C36B5", "#0C8599", "#C2255C", "#F59F00", "#3B5BDB")
private const val AREA_COLOR = "#1864AB"

// 전주 점 크기: 축소하면 작게(전체 1,000개가 서로 덜 겹치게), 확대하면 손가락으로 누르기 쉽게 키운다.
private val POLE_RADIUS = interpolate(linear(), zoom(), stop(11, 2.5f), stop(14, 4.5f), stop(16, 7f))
private val POLE_STROKE = interpolate(linear(), zoom(), stop(11, 0.5f), stop(14, 1.5f), stop(16, 2f))

/**
 * 계획 화면 ③(10장): 전주 지도, 영역(다각형) 그리기, 이륙 지점, 경로와 소티 미리보기.
 * 계획 로직은 [PlanViewModel]에 있고, 이 화면은 상태를 그리고 누른 것을 올려 보낸다.
 */
@Composable
fun MapScreen(
    projectId: Long,
    dao: ProjectDao,
    onBack: () -> Unit,
    onStartFlight: (missionId: Long) -> Unit,
    replanFrom: Long? = null,
) {
    val context = LocalContext.current
    val vm: PlanViewModel = viewModel(
        factory = PlanViewModel.factory(
            dao, projectId, replanFrom, File(context.getExternalFilesDir(null), "maps").apply { mkdirs() }, context.contentResolver,
        ),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onMapFilePicked) }
    val inArea = remember(s.poles, s.vertices) { s.inArea }

    Box(Modifier.fillMaxSize()) {
        s.poles?.let {
            PoleMap(
                poles = it,
                mapFiles = s.mapFiles,
                overlay = Overlay(s.vertices, s.home, s.plan, s.excludedPoints),
                focus = s.focus,
                onPoleClick = vm::selectPole,
                onMapTap = if (s.tool == Tool.NONE) null else vm::onMapTap,
            )
        }

        Card(
            Modifier.padding(16.dp).widthIn(max = PANEL_WIDTH_DP.dp),
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack) { Text("← 목록") }
                    Text(s.projectName, style = MaterialTheme.typography.titleMedium)
                    Text("전주 ${s.poles?.size ?: 0}개 · 지도 ${s.mapFiles.size}개", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 패널 높이를 아끼려고 지도 파일 이름은 뺐다. 지도가 없을 때만 알린다.
                if (s.mapFiles.isEmpty()) {
                    Text("오프라인 지도(.mbtiles)가 없습니다. 배경 없이 전주만 표시합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

                when (s.tool) {
                    Tool.AREA -> {
                        Text("지도를 눌러 꼭짓점을 추가하세요 (${s.vertices.size}개)", color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::finishArea, enabled = s.vertices.size >= 3) { Text("완료") }
                            OutlinedButton(onClick = vm::undoVertex, enabled = s.vertices.isNotEmpty()) { Text("되돌리기") }
                        }
                    }
                    Tool.HOME -> {
                        Text("지도를 눌러 이륙 지점을 정하세요", color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = vm::cancelTool) { Text("취소") }
                    }
                    Tool.NONE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = vm::startArea) { Text("영역 그리기") }
                            FilledTonalButton(onClick = vm::startHome) { Text("이륙 지점") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 경로를 보는 동안에는 파라미터 대신 경로 요약을 보여 준다(가로 1080px 화면에 다 들어가게).
                            if (s.plan == null) {
                                Button(onClick = vm::computePlan, enabled = s.canPlan) { Text(if (s.planning) "계산 중" else "경로 계산") }
                                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("지도 파일 추가") }
                            } else {
                                val missionId = s.savedMissionId
                                if (missionId == null) Button(onClick = vm::savePlan) { Text("계획 저장") }
                                else Button(onClick = { onStartFlight(missionId) }) { Text("비행 시작") }
                                OutlinedButton(onClick = vm::resetPlan) { Text("다시 설정") }
                            }
                        }
                    }
                }

                if (s.tool == Tool.NONE && s.plan == null) ParamSteppers(s.params, vm::updateParams)
                s.paramErrors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (s.vertices.size >= 3) {
                    Text(
                        "영역 안 전주 ${inArea.size}개" + if (s.excluded.isEmpty()) "" else " · 제외 ${s.excluded.size}개",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (s.tool == Tool.NONE && s.vertices.size >= 3 && s.home == null) {
                    Text("이륙 지점을 정하면 경로를 계산할 수 있습니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.plan?.let { PlanSummary(it, s.planMs, s.params) }
            }
        }

        val pole = s.poles?.firstOrNull { it.poleId == s.selected }
        if (pole != null) {
            Card(
                Modifier.align(Alignment.BottomCenter).padding(24.dp),
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val isExcluded = pole.poleId in s.excluded
                    Column {
                        Text(pole.poleId, style = MaterialTheme.typography.titleLarge)
                        Text("${pole.lat}, ${pole.lon}  ${pole.lineName.orEmpty()}  ${pole.note.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isExcluded) Text("경로에서 제외됨", color = MaterialTheme.colorScheme.error)
                    }
                    if (inArea.any { it.id == pole.id }) {
                        OutlinedButton(onClick = { vm.toggleExcluded(pole.poleId) }) { Text(if (isExcluded) "포함" else "제외") }
                    }
                    Button(onClick = { vm.selectPole(null) }) { Text("닫기") }
                }
            }
        }
    }
}

private const val PANEL_WIDTH_DP = 420

/** 핵심 파라미터 4개(6.3). 장갑 낀 손가락으로 누를 수 있게 48dp [−][+] 버튼으로 바꾼다. 범위 검증은 [FlightParams.validate]. */
@Composable
private fun ParamSteppers(p: FlightParams, onChange: (FlightParams) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stepper("이동 고도", "${p.transitAltitudeM.toInt()}m", Modifier.weight(1f)) { onChange(p.copy(transitAltitudeM = p.transitAltitudeM + it * 5)) }
        Stepper("점검 고도", "${p.inspectAltitudeM.toInt()}m", Modifier.weight(1f)) { onChange(p.copy(inspectAltitudeM = p.inspectAltitudeM + it * 5)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stepper("속도", "${p.speedMps.toInt()}m/s", Modifier.weight(1f)) { onChange(p.copy(speedMps = p.speedMps + it)) }
        Stepper("예비율", "${p.batteryReservePct.toInt()}%", Modifier.weight(1f)) { onChange(p.copy(batteryReservePct = p.batteryReservePct + it * 5)) }
    }
}

/** [onStep]에는 −1 또는 +1이 넘어간다. 버튼 설명(contentDescription)은 스모크 스크립트가 찾는 이름이다. */
@Composable
private fun Stepper(label: String, value: String, modifier: Modifier, onStep: (Int) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
        listOf(-1 to "−", 1 to "+").forEach { (d, sign) ->
            OutlinedButton(
                onClick = { onStep(d) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "$label ${if (d < 0) "낮추기" else "높이기"}" },
                contentPadding = PaddingValues(0.dp),
            ) { Text(sign) }
        }
    }
}

/** 소티별 예상 거리·시간·배터리(F-08)와 도달 불가 전주(5.4). */
@Composable
private fun PlanSummary(plan: RoutePlan, planMs: Long, params: FlightParams) {
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
    Text(
        "이동 ${params.transitAltitudeM.toInt()}m · 점검 ${params.inspectAltitudeM.toInt()}m · ${params.speedMps.toInt()}m/s · " +
            "예비율 ${params.batteryReservePct.toInt()}% · 가시권 ${params.vlosRadiusM.toInt()}m · 계산 ${planMs}ms",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

/** 지도 위에 그릴 계획 요소. */
private data class Overlay(val vertices: List<GeoPoint>, val home: GeoPoint?, val plan: RoutePlan?, val excluded: List<GeoPoint>)

/**
 * MapLibre 지도. 배경은 앱 전용 폴더의 MBTiles를 모두 겹쳐 그린다(지역별 파일). 네트워크 소스는 쓰지 않는다.
 * ponytail: 글리프(폰트) 파일이 없어 지도 위 글자(전주번호)는 그리지 않는다. 전주번호는 탭하면 카드로 보여 준다.
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
                onClick(map.queryRenderedFeatures(box, "poles-point").firstOrNull()?.getStringProperty("poleId"))
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
                // 전주는 묶지 않고 하나하나 점으로 그린다(N-04). 1,000개 수준의 원 레이어는 MapLibre가 무리 없이 그린다.
                style.addSource(GeoJsonSource("poles", FeatureCollection.fromFeatures(features)))
                // 영역과 경로는 전주 아래에, 꼭짓점과 이륙 지점은 맨 위에 그린다.
                style.addSource(GeoJsonSource("draw"))
                style.addSource(GeoJsonSource("route"))
                style.addSource(GeoJsonSource("home"))
                style.addSource(GeoJsonSource("excluded"))
                style.addLayer(
                    FillLayer("area-fill", "draw").withFilter(eq(geometryType(), literal("Polygon")))
                        .withProperties(fillColor(AREA_COLOR), fillOpacity(0.12f)),
                )
                style.addLayer(
                    LineLayer("area-line", "draw").withFilter(neq(geometryType(), literal("Point")))
                        .withProperties(lineColor(AREA_COLOR), lineWidth(3f)),
                )
                style.addLayer(
                    LineLayer("route-line", "route").withFilter(eq(geometryType(), literal("LineString")))
                        .withProperties(lineColor(get("color")), lineWidth(4f)),
                )
                style.addLayer(
                    CircleLayer("poles-point", "poles").withProperties(
                        circleColor("#D9480F"), circleRadius(POLE_RADIUS), circleStrokeColor("#FFFFFF"), circleStrokeWidth(POLE_STROKE),
                    ),
                )
                // 경로가 꺾이는 곳마다 방문 전주를 소티 색 점으로 찍는다(꺾이는 곳이 전주임을 보이게).
                style.addLayer(
                    CircleLayer("route-stop", "route").withFilter(eq(geometryType(), literal("Point"))).withProperties(
                        circleColor("#FFFFFF"), circleRadius(5f), circleStrokeColor(get("color")), circleStrokeWidth(3f),
                    ),
                )
                // 제외한 전주(F-06)는 원래 점 위에 회색으로 덮어 그린다.
                style.addLayer(
                    CircleLayer("excluded-point", "excluded").withProperties(
                        circleColor("#868E96"), circleRadius(POLE_RADIUS), circleStrokeColor("#FFFFFF"), circleStrokeWidth(POLE_STROKE),
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
            style.getSourceAs<GeoJsonSource>("excluded")?.setGeoJson(
                FeatureCollection.fromFeatures(overlay.excluded.map { Feature.fromGeometry(it.toPoint()) }),
            )
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
        pts.size >= 3 -> Feature.fromGeometry(areaPolygon(vertices))
        pts.size == 2 -> Feature.fromGeometry(LineString.fromLngLats(pts))
        else -> null
    }
    return FeatureCollection.fromFeatures(listOfNotNull(shape) + pts.map { Feature.fromGeometry(it) })
}

/** 점검 영역을 닫힌 GeoJSON 다각형으로. `MISSION.areaGeoJson`에도 이 형태로 저장한다. */
internal fun areaPolygon(vertices: List<GeoPoint>): GeoJsonPolygon =
    vertices.map { it.toPoint() }.let { GeoJsonPolygon.fromLngLats(listOf(it + it.first())) }

/** 소티마다 이륙 지점 → 전주들 → 이륙 지점으로 닫힌 선 하나와 방문 전주 점들. */
private fun routeFeatures(plan: RoutePlan?): FeatureCollection = FeatureCollection.fromFeatures(
    if (plan == null) emptyList() else plan.sorties.flatMapIndexed { i, s ->
        val color = SORTIE_COLORS[i % SORTIE_COLORS.size]
        val line = listOf(plan.home) + s.poles.map { it.position } + plan.home
        (listOf(LineString.fromLngLats(line.map { it.toPoint() })) + s.poles.map { it.position.toPoint() })
            .map { g -> Feature.fromGeometry(g).apply { addStringProperty("color", color) } }
    },
)

private fun styleJson(mapFiles: List<File>): String {
    val sources = mapFiles.mapIndexed { i, f -> """"base$i":{"type":"raster","url":"mbtiles://${f.absolutePath}","tileSize":256}""" }
    val layers = listOf("""{"id":"background","type":"background","paint":{"background-color":"#E6EAE7"}}""") +
        mapFiles.indices.map { """{"id":"base$it","type":"raster","source":"base$it"}""" }
    return """{"version":8,"sources":{${sources.joinToString(",")}},"layers":[${layers.joinToString(",")}]}"""
}
