package com.example.myfirstapp.ui.flight

import android.graphics.RectF
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.R
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.route.FlightTrack
import com.example.myfirstapp.core.route.Viewpoints
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.example.myfirstapp.ui.PoleSymbolRow
import com.example.myfirstapp.ui.poleSymbols
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.ui.theme.numberStyle
import org.maplibre.android.maps.MapLibreMap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.ui.AnnotationTools
import com.example.myfirstapp.ui.DraftPanel
import com.example.myfirstapp.ui.MapCrosshair
import com.example.myfirstapp.ui.MapLayerButton
import com.example.myfirstapp.ui.NOTE_LAYERS
import com.example.myfirstapp.ui.NOTE_VERTEX
import com.example.myfirstapp.ui.PinCard
import com.example.myfirstapp.ui.addAnnotationLayers
import com.example.myfirstapp.ui.addGeoZoneLayers
import com.example.myfirstapp.ui.setGeoZones
import com.example.myfirstapp.ui.annotationViewModel
import com.example.myfirstapp.ui.setAnnotations
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.ui.MapMarkers
import com.example.myfirstapp.ui.MapSearchButton
import com.example.myfirstapp.ui.MapTargetButton
import com.example.myfirstapp.ui.MapTool
import com.example.myfirstapp.ui.MapAttribution
import com.example.myfirstapp.ui.NorthLockGlyph
import com.example.myfirstapp.ui.addMarkerLayers
import com.example.myfirstapp.ui.rememberMapView
import com.example.myfirstapp.ui.setMarkers
import com.example.myfirstapp.ui.styleJson
import com.example.myfirstapp.ui.toPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.MAP_ROUTE
import com.example.myfirstapp.ui.theme.MAP_POLE_PENDING
import com.example.myfirstapp.ui.theme.MAP_POLE_PENDING_STROKE
import com.example.myfirstapp.ui.theme.MAP_TRACK
import com.example.myfirstapp.ui.theme.TextSoft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 지도에 번호를 붙이는 촬영 지점(웨이포인트, 5.6). [number]는 소티 안 순서(1부터), [altitudeM]은 이륙 지점 기준(모르면 null). */
private data class Waypoint(val number: Int, val at: GeoPoint, val altitudeM: Double?)

/** 큰 지도에서 누른 표지(10.5.7 기체·내 조종기 카드). */
private enum class MarkerCard { AIRCRAFT, RC }

/**
 * 비행 화면 지도(10장, Pilot 2 지도 뷰 10.5.4): 계획 화면과 같은 배경(위성·MBTiles) 위에 계획 경로(녹색), 비행 궤적(주황),
 * RTH 중 기체 → H 녹색 선, 전주 상태와 표지(이륙 지점 H, 기체 화살표, 조종기 점, [MapMarkers])를 그린다.
 * [interactive]가 false면 미니맵이라 손으로 움직이지 않고 도구도 없다. [pad]는 처음 맞출 때 가장자리 여백(왼쪽, 위, 오른쪽, 아래).
 * 큰 지도 오른쪽 도구: 검색 · N 잠금 · 레이어([onMapSource]) · 지우개(비행 궤적, [onClearTrack]) · 과녁. 기체·조종기 표지를 누르면 카드.
 * 지도는 처음에 전주 전체(없으면 기체 → H → 조종기)에 맞추고, 떠 있으면(전주가 없으면 언제나) 손으로 옮기기 전까지 기체를 따라간다.
 * 전주번호(선로번호, 없으면 전산화번호 끝 4자리)와 웨이포인트 번호·고도는 Compose 글자로 덧그린다:
 * MapLibre 글자 레이어는 글꼴을 인터넷으로 받아야 해서 쓰지 않는다(7.4 오프라인). 전주 라벨은 큰 지도 확대 15 이상(미니맵은 지금 전주만),
 * 웨이포인트는 확대 [AppConfig.Map.WAYPOINT_MIN_ZOOM] 이상에서 보이고, 겹치는 라벨은 소티 순서로 앞의 것만 남긴다.
 * MapLibre 로고·ⓘ는 숨기고 배경 출처만 오른쪽 아래 작은 글자([MapAttribution]).
 */
@OptIn(FlowPreview::class) // sample
@Composable
internal fun FlightMap(
    s: FlightUiState, interactive: Boolean, pad: List<Dp>, modifier: Modifier,
    onMapSource: (MapSource) -> Unit = {}, onClearTrack: () -> Unit = {},
    /** 왼쪽 주석 도구(핀포인트·선·영역·⊗, 10.4.7)와 가운데 십자선. 비행 다시 보기는 보기만 해서 끈다. */
    tools: Boolean = true,
    /** 스타일을 (다시) 읽을 때마다 표지를 더한 뒤 부른다: 다른 화면이 제 레이어를 얹는다(비행 다시 보기, 10.4.6). */
    onStyle: (MapLibreMap, Style) -> Unit = { _, _ -> },
    /** 기체·조종기 카드의 시각(ms): 비행 다시 보기는 재생 위치의 시각, null이면 지금. */
    clockMs: Long? = null,
    /** 라이브러리 패널에서 누른 경로(10.5.8): 소티마다 이륙 지점 → 전주 → 이륙 지점. 파란 점선으로 그리고 바뀌면 그 경로에 맞춘다. */
    preview: List<List<GeoPoint>> = emptyList(),
    /** 기체·조종기 카드 [H 지정](10.5.7): 홈포인트를 기체(true면 조종기) 위치로. null이면 버튼 없음(다시 보기). */
    onSetHome: ((toRc: Boolean) -> Unit)? = null,
) {
    val mapView = rememberMapView()
    val density = LocalDensity.current
    var styleGen by remember { mutableIntStateOf(0) }
    var fitted by remember { mutableStateOf(false) }
    // 손으로 옮기면 따라가기를 멈춘다. 과녁 → 기체로 다시 따라간다
    var panned by remember { mutableStateOf(false) }
    // N 잠금(Pilot 2): 북쪽 위 고정 ↔ 기수 방향이 위
    var northUp by rememberSaveable { mutableStateOf(true) }
    var card by remember { mutableStateOf<MarkerCard?>(null) }
    // 레이어 → GEO 구역(10.4.9): 처음은 켬(Pilot 2 맞춤 설정 비행 영역 기본 켬)
    var geoOn by rememberSaveable { mutableStateOf(true) }
    var labels by remember { mutableStateOf<List<Pair<Pole, Offset>>>(emptyList()) }
    var wpLabels by remember { mutableStateOf<List<Pair<Waypoint, Offset>>>(emptyList()) }
    val latest by rememberUpdatedState(s)
    // 핀포인트·선·영역(10.4.7): 모든 지도에 그리고, 큰 지도(다시 보기 빼고)에서 만들고 고친다
    val notes = annotationViewModel()
    val ns by notes.state.collectAsStateWithLifecycle()
    val annotate = interactive && tools

    // 소티마다 실제 촬영 지점(5.6, 4방향이면 전주마다 4점). 방문 상태만 바뀌면 다시 펼치지 않는다
    val visitKey = s.exec.visits.map { Triple(it.sortieIndex, it.order, it.pole) }
    val legs = remember(visitKey, s.params, s.home) {
        s.exec.visits.groupBy { it.sortieIndex }.values.map { v ->
            val poles = v.sortedBy { it.order }.map { it.pole }
            val p = s.params
            val h = s.home
            if (p != null && h != null) Viewpoints.expand(poles, p, h).mapIndexed { i, vp -> Waypoint(i + 1, vp.position, vp.altitudeM) }
            else poles.mapIndexed { i, pole -> Waypoint(i + 1, pole.position, null) }
        }
    }
    val latestLegs by rememberUpdatedState(legs)

    fun relabel(map: MapLibreMap) {
        val st = latest
        val zoom = map.cameraPosition.zoom
        val bounds = map.projection.visibleRegion.latLngBounds
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        // 보이는 범위 밖은 화면 좌표로 바꾸지 않는다(전주·지점이 수백 개)
        fun screen(p: GeoPoint): Offset? {
            val ll = LatLng(p.lat, p.lon).takeIf { bounds.contains(it) } ?: return null
            val pt = map.projection.toScreenLocation(ll)
            return if (pt.x !in 0f..w || pt.y !in 0f..h) null else Offset(pt.x, pt.y)
        }
        val targets = when {
            interactive && zoom >= AppConfig.Map.LABEL_MIN_ZOOM -> st.poles
            !interactive -> listOfNotNull(st.exec.target?.pole)
            else -> emptyList()
        }
        // 겹치는 라벨은 숨긴다(Pilot 2처럼 경로 전체가 보이는 확대에서도 어지럽지 않게): 웨이포인트를 소티 순서로 먼저, 그다음 전주 라벨
        val placed = mutableListOf<Rect>()
        fun fits(r: Rect) = placed.none { it.overlaps(r) }.also { if (it) placed += r }
        val dp = density.density
        wpLabels = if (interactive && zoom >= AppConfig.Map.WAYPOINT_MIN_ZOOM) latestLegs.flatten().mapNotNull { wp ->
            val at = screen(wp.at) ?: return@mapNotNull null
            // 번호 원(16dp)과 그 아래 고도 글(약 36×13dp)
            (wp to at).takeIf { fits(Rect(at.x - 8 * dp, at.y - 8 * dp, at.x + 28 * dp, at.y + 21 * dp)) }
        } else emptyList()
        labels = targets.mapNotNull { p ->
            val at = screen(p.position) ?: return@mapNotNull null
            // 점 오른쪽 위 라벨(아래 offset과 같은 자리), 폭은 글자 수로 어림
            val box = Rect(at.x + 18, at.y - 42, at.x + 18 + (poleLabel(p).length * 9 + 24) * dp, at.y - 42 + 22 * dp)
            (p to at).takeIf { !interactive || fits(box) }
        }
    }

    // 지도 설정·듣는 것은 한 번만(배경을 바꿔 스타일을 다시 읽어도 쌓이지 않게)
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.setAllGesturesEnabled(interactive)
            map.addOnCameraMoveListener { relabel(map) }
            map.addOnCameraIdleListener { relabel(map) }
            map.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) panned = true }
            if (interactive) map.addOnMapClickListener { ll ->
                // 기체 화살표·조종기 점을 누르면 카드(10.5.7). 장갑 손가락이라 둘레 24dp
                val p = map.projection.toScreenLocation(ll)
                val r = 24 * density.density
                val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
                // 선·영역을 그리는 중이면 꼭짓점을 고르거나 누른 곳에 점을 더한다(10.4.7)
                if (annotate && notes.state.value.draft != null) {
                    val v = map.queryRenderedFeatures(box, NOTE_VERTEX).firstOrNull()?.getNumberProperty("i")?.toInt()
                    if (v != null) notes.selectVertex(v) else notes.addVertex(GeoPoint(ll.latitude, ll.longitude))
                    return@addOnMapClickListener true
                }
                card = when {
                    map.queryRenderedFeatures(box, "marker-aircraft").isNotEmpty() -> MarkerCard.AIRCRAFT
                    map.queryRenderedFeatures(box, "marker-rc").isNotEmpty() -> MarkerCard.RC
                    else -> null
                }
                // 핀포인트를 누르면 카드, 선·영역은 편집(10.4.7)
                val note = if (annotate && card == null) map.queryRenderedFeatures(box, *NOTE_LAYERS).firstOrNull()?.getNumberProperty("id")?.toLong() else null
                if (note != null) notes.tap(note) else notes.select(null)
                true
            }
        }
    }

    LaunchedEffect(s.mapFiles, s.background) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson(s.mapFiles, s.background))) { style ->
                // GEO 구역은 맨 아래(경로·전주·표지가 위)
                style.addGeoZoneLayers(scale = if (interactive) 1f else 0.75f)
                listOf("route", "track", "rth", "poles", "preview").forEach { style.addSource(GeoJsonSource(it)) }
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor(MAP_ROUTE), lineWidth(2f)))
                style.addLayer(LineLayer("preview-line", "preview").withProperties(lineColor(Accent.toArgb()), lineWidth(3f), lineDasharray(arrayOf(2f, 1f))))
                style.addLayer(
                    CircleLayer("poles-point", "poles").withProperties(
                        circleColor(
                            match(
                                get("status"), color(android.graphics.Color.parseColor(MAP_POLE_PENDING)),
                                stop(VisitStatus.DONE.name, color(PoleDone.toArgb())),
                                stop(VisitStatus.SKIPPED.name, color(PoleSkipped.toArgb())),
                                stop(VisitStatus.FAILED.name, color(PoleFailed.toArgb())),
                                stop(VisitStatus.IN_PROGRESS.name, color(PoleActive.toArgb())),
                            ),
                        ),
                        circleRadius(if (interactive) 7f else 4f),
                        circleStrokeColor(match(get("status"), literal("#FFFFFF"), stop(VisitStatus.PENDING.name, literal(MAP_POLE_PENDING_STROKE)))),
                        circleStrokeWidth(if (interactive) 1.5f else 1f),
                    ),
                )
                // 날아간 궤적(주황)과 RTH 선(녹색)은 전주 점 위, 표지 아래: 11-04 RC에서 궤적이 전주 점에 가려 안 보였다
                style.addLayer(
                    LineLayer("track-line", "track").withProperties(
                        lineColor(MAP_TRACK), lineWidth(if (interactive) 4f else 2.5f), lineJoin(Property.LINE_JOIN_ROUND), lineCap(Property.LINE_CAP_ROUND),
                    ),
                )
                style.addLayer(LineLayer("rth-line", "rth").withProperties(lineColor(MAP_ROUTE), lineWidth(3f)))
                style.addAnnotationLayers(scale = if (interactive) 1f else 0.75f)
                style.addMarkerLayers(scale = if (interactive) 1f else 0.75f)
                onStyle(map, style)
                styleGen++
            }
        }
    }

    // 표지(10.5.4)·RTH 선과 카메라. 미션은 처음에 전주 전체에 한 번 맞추고, 떠 있으면 기체를 따라간다.
    // 전주가 없으면(수동 비행) 기체 → 이륙 지점 → 조종기 위치에 맞추고 따라간다(11-03 현장: 수동 비행 미니맵이 흰 칸). 손으로 옮기면 멈춘다.
    // 셋 다 모르면(실내) 지난 비행 마지막 위치·최근 미션 이륙 지점([FlightUiState.mapFallback], 11-04 RC)
    val t = s.telemetry
    val rth = s.exec.state == ExecState.ReturningHome || t?.flightMode == FlightMode.GO_HOME
    LaunchedEffect(styleGen, t?.position, t?.headingDeg, t?.rcPosition, t?.isFlying, t?.home, s.home, s.mapFallback, northUp, rth) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            // H는 기체가 알린 홈포인트([H 지정]으로 바뀜), 모르면 계획 이륙 지점
            val home = t?.home ?: s.home
            style.setMarkers(MapMarkers(home = home, aircraft = t?.position, headingDeg = t?.headingDeg ?: 0.0, rc = t?.rcPosition))
            val back = t?.position?.takeIf { rth }?.let { a -> home?.let { h -> LineString.fromLngLats(listOf(a.toPoint(), h.toPoint())) } }
            style.getSourceAs<GeoJsonSource>("rth")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(back?.let { Feature.fromGeometry(it) })))
            // 전주 전체(수백 개) 목록은 처음 맞출 때만 만든다(매 틱 만들지 않게, 성능 2026-11-07)
            val single = t?.position ?: s.home ?: t?.rcPosition ?: s.mapFallback
            if (s.poles.isEmpty() && single == null) return@getMapAsync
            if (!fitted) {
                fitted = true
                val points = if (s.poles.isNotEmpty()) s.poles.map { it.position } + listOfNotNull(s.home) else listOfNotNull(single)
                val latLngs = points.map { LatLng(it.lat, it.lon) }
                val px = pad.map { with(density) { it.roundToPx() } }
                map.moveCamera(
                    if (latLngs.distinct().size < 2) CameraUpdateFactory.newLatLngZoom(latLngs.first(), AppConfig.Map.SINGLE_POINT_ZOOM)
                    else CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(latLngs).build(), px[0], px[1], px[2], px[3]),
                )
                return@getMapAsync
            }
            val bearing = if (northUp) 0.0 else t?.headingDeg ?: 0.0
            val follow = when {
                panned -> null
                s.poles.isEmpty() -> single
                t?.isFlying == true -> t.position
                else -> null
            }
            when {
                follow != null -> map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).target(LatLng(follow.lat, follow.lon)).bearing(bearing).build()),
                )
                // 기수 방향은 손으로 옮긴 뒤에도 돈다
                !northUp -> map.moveCamera(CameraUpdateFactory.bearingTo(bearing))
            }
        }
    }
    LaunchedEffect(styleGen, s.geoZones, geoOn) {
        mapView.getMapAsync { map -> map.style?.takeIf { it.isFullyLoaded }?.setGeoZones(if (geoOn) s.geoZones else emptyList()) }
    }
    LaunchedEffect(styleGen, ns.items, ns.draft, ns.vertex) {
        mapView.getMapAsync { map -> map.style?.takeIf { it.isFullyLoaded }?.setAnnotations(ns.items, ns.draft, ns.vertex) }
    }
    // 라이브러리 패널 미리보기(10.5.8): 누른 경로에 맞춘다(오른쪽은 패널 폭만큼 비움). 따라가기는 멈추고 과녁으로 다시 따라간다
    LaunchedEffect(styleGen, preview) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            val lines = preview.filter { it.size >= 2 }.map { l -> Feature.fromGeometry(LineString.fromLngLats(l.map { it.toPoint() })) }
            style.getSourceAs<GeoJsonSource>("preview")?.setGeoJson(FeatureCollection.fromFeatures(lines))
            val pts = preview.flatten().map { LatLng(it.lat, it.lon) }.distinct()
            if (pts.size < 2) return@getMapAsync
            panned = true
            val px = pad.map { with(density) { it.roundToPx() } }
            val panel = with(density) { Dimens.SidePanel.roundToPx() }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts).build(), px[0], px[1], px[2] + panel, px[3]))
        }
    }
    // N 잠금으로 돌아오면 손으로 옮긴 뒤라도 북쪽을 위로
    LaunchedEffect(northUp) { if (northUp) mapView.getMapAsync { it.moveCamera(CameraUpdateFactory.bearingTo(0.0)) } }

    // 궤적(최대 3천 점)은 점이 늘 때마다 통째로 다시 올리지 않고 [AppConfig.Map.TRACK_DRAW_MS]마다 한 번(다시 보기는 10Hz로 바뀜)
    LaunchedEffect(styleGen) {
        snapshotFlow { latest.track }.sample(AppConfig.Map.TRACK_DRAW_MS).collect { track ->
            mapView.getMapAsync { map ->
                val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
                val line = track.takeIf { it.size >= 2 }?.let { tr -> Feature.fromGeometry(LineString.fromLngLats(tr.map { it.toPoint() })) }
                style.getSourceAs<GeoJsonSource>("track")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(line)))
            }
        }
    }

    LaunchedEffect(styleGen, s.exec.visits, legs, s.home) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            val status = s.exec.visits.associate { it.pole.poleId to it.status.name }
            style.getSourceAs<GeoJsonSource>("poles")?.setGeoJson(
                FeatureCollection.fromFeatures(
                    s.poles.map { p ->
                        Feature.fromGeometry(p.position.toPoint()).apply { addStringProperty("status", status[p.poleId] ?: VisitStatus.PENDING.name) }
                    },
                ),
            )
            // 소티마다 이륙 지점 → 실제 촬영 지점 → 이륙 지점
            val home = s.home?.toPoint()
            style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
                FeatureCollection.fromFeatures(
                    legs.map { leg -> Feature.fromGeometry(LineString.fromLngLats(listOfNotNull(home) + leg.map { it.at.toPoint() } + listOfNotNull(home))) },
                ),
            )
            relabel(map)
        }
    }

    /** 지도를 [p]로 옮긴다(검색·과녁). [follow]면 다시 기체를 따라간다. */
    fun jump(p: GeoPoint, follow: Boolean = false) {
        panned = !follow
        mapView.getMapAsync { map ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), maxOf(map.cameraPosition.zoom, AppConfig.Map.SINGLE_POINT_ZOOM)))
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // 웨이포인트(Pilot 2): 흰 원에 파란 번호, 아래에 흰 바탕 고도
        wpLabels.forEach { (wp, at) ->
            val half = with(density) { (Dimens.MarkerWaypoint / 2).roundToPx() }
            Column(Modifier.offset { IntOffset(at.x.toInt() - half, at.y.toInt() - half) }) {
                Box(Modifier.size(Dimens.MarkerWaypoint).clip(CircleShape).background(Color.White).border(1.dp, Accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${wp.number}", style = numberStyle(FontSize.Micro), color = Accent, maxLines = 1)
                }
                wp.altitudeM?.let {
                    Text("%.1fm".format(it), style = numberStyle(FontSize.Micro), color = Color.Black, modifier = Modifier.background(Color.White, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp))
                }
            }
        }
        // 점 오른쪽 위에 검은 바탕 흰 글자와 설비 기호(글자 16.5sp, 2026-10-31 사용자 요청 1.5배)(고/저압·변압기·개폐기·피뢰기, 판독·앨범 범례와 같음)
        labels.forEach { (p, at) ->
            Row(
                Modifier.offset { IntOffset(at.x.toInt() + 18, at.y.toInt() - 42) }.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(poleLabel(p), style = numberStyle(if (interactive) FontSize.MapLabel else FontSize.MapLabelMini), color = Color.White, maxLines = 1)
                PoleSymbolRow(poleSymbols(p.voltage, p.equipment), size = if (interactive) 15.dp else 12.dp)
            }
        }
        if (!interactive) return@Box
        if (annotate) {
            MapCrosshair(Modifier.align(Alignment.Center))
            // 왼쪽 주석 도구(Pilot 2 지도 뷰): 카메라 뷰 왼쪽 열 자리. 핀포인트는 지도 가운데 십자선, 고도는 기체 지금 고도
            AnnotationTools(
                ns, notes, t?.position,
                onPin = {
                    mapView.getMapAsync { m -> m.cameraPosition.target?.let { c -> notes.pinAt(GeoPoint(c.latitude, c.longitude), t?.takeIf { it.connected }?.altitudeM) } }
                },
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = Dimens.TopBar + 19.dp),
            )
        }
        // 배경 출처: 오른쪽 아래(라이브러리 칸은 폭 70% 자리라 겹치지 않음)
        MapAttribution(s.background.attribution, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 10.dp))
        // 오른쪽 도구(10.5.4, 위에서 아래로): 검색 · N 잠금 · 레이어 · 지우개 · 과녁. 카메라 뷰의 오른쪽 열 자리
        Column(Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar + 37.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val size = Dimens.TouchSmall
            MapSearchButton(s.poles, size, notes = ns.items, onPickNote = { jump(it.points[0]) }, onPickPoint = { jump(it) }) { jump(it.position) }
            // Pilot 2: 북쪽 고정이면 검은 칸에 N·화살촉·노란 자물쇠, 기수 방향이면 흰 칸에 자물쇠 없이
            MapTool(if (northUp) "북쪽 고정(누르면 기수 방향)" else "기수 방향(누르면 북쪽 고정)", size = size, dark = northUp, onClick = { northUp = !northUp }) { tint ->
                NorthLockGlyph(locked = northUp, tint)
            }
            MapLayerButton(s.background.source, size, onMapSource, geo = geoOn, onToggleGeo = { geoOn = !geoOn })
            MapTool("비행 궤적 지우기", enabled = s.track.isNotEmpty(), size = size, onClick = onClearTrack) { tint ->
                Icon(painterResource(R.drawable.ic_erase), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
            MapTargetButton(
                size, aircraftKnown = t?.position != null, rcKnown = t?.rcPosition != null,
                onAircraft = { t?.position?.let { jump(it, follow = true) } }, onRc = { t?.rcPosition?.let { jump(it) } },
            )
        }
        card?.let { c ->
            MarkerInfoCard(
                c, t, clockMs, onClose = { card = null }, onPin = { notes.pinAircraft(t); card = null },
                onSetHome = onSetHome?.let { f -> { f(c == MarkerCard.RC); card = null } }, Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 8.dp),
            )
        }
        if (annotate) {
            ns.items.firstOrNull { it.id == ns.selected }?.let { PinCard(it, t?.position, notes, Modifier.align(Alignment.TopCenter).padding(top = Dimens.TopBar + 8.dp)) }
            ns.draft?.let { DraftPanel(it, notes, Modifier.align(Alignment.TopEnd).padding(top = Dimens.TopBar).fillMaxHeight()) }
        }
    }
}

/**
 * 기체·내 조종기 카드(10.5.7, Pilot 2 지도에서 표지를 누름): 시각([clockMs], 없으면 지금), 기체는 ALT(이륙 지점 기준)·ASL(해발, `KeyHeightAboveSeaLevel`),
 * 위도·경도. 조종기 ASL은 SDK 조종기 GPS(`RcGPSInfo`)에 고도가 없어 뺀다. [H 지정]([onSetHome], null이면 없음)은 홈포인트를 그 표지 위치로,
 * 기체 카드 [핀포인트]는 지금 기체 위치에 핀포인트를 남긴다(10.4.7, 위성이 모자라면 실패). 편집은 핀포인트 카드에 있다(10.4.7).
 */
@Composable
private fun MarkerInfoCard(c: MarkerCard, t: Telemetry?, clockMs: Long?, onClose: () -> Unit, onPin: () -> Unit, onSetHome: (() -> Unit)?, modifier: Modifier) {
    val at = if (c == MarkerCard.AIRCRAFT) t?.position else t?.rcPosition
    Column(modifier.width(Dimens.CardWidth).background(CardBg, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (c == MarkerCard.AIRCRAFT) "기체" else "내 조종기", Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.Bold, color = Color.White)
            Text("✕", Modifier.clickable(onClickLabel = "닫기", onClick = onClose).padding(horizontal = 6.dp), fontSize = FontSize.Body, color = TextSoft)
        }
        val rows = listOfNotNull(
            "시각" to SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(clockMs ?: System.currentTimeMillis())),
            t?.takeIf { c == MarkerCard.AIRCRAFT }?.let { "ALT" to "%.1f m".format(it.altitudeM) },
            t?.takeIf { c == MarkerCard.AIRCRAFT }?.let { "ASL" to (it.altitudeAslM?.let { a -> "%.1f m".format(a) } ?: "--") },
            "위도" to (at?.let { "%.6f".format(it.lat) } ?: "위치 모름"),
            "경도" to (at?.let { "%.6f".format(it.lon) } ?: "-"),
        )
        rows.forEach { (label, value) ->
            Row {
                Text(label, Modifier.width(40.dp), fontSize = FontSize.Caption, color = TextSoft)
                Text(value, style = numberStyle(FontSize.Body), color = Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // [H 지정]: 떠 있어도 된다(Pilot 2). 위치를 모르면 끈다
            onSetHome?.let { OutlinedButton(onClick = it, enabled = at != null, modifier = Modifier.height(Dimens.ButtonChip)) { Text("H 지정", fontSize = FontSize.Caption) } }
            if (c == MarkerCard.AIRCRAFT) OutlinedButton(onClick = onPin, enabled = t?.position != null, modifier = Modifier.height(Dimens.ButtonChip)) { Text("핀포인트", fontSize = FontSize.Caption) }
        }
    }
}

/** [H 지정] 결과 안내(자동·수동 비행 화면 공통). */
internal fun homeSetMessage(toRc: Boolean) = if (toRc) "홈포인트를 조종기 위치로 바꿨습니다" else "홈포인트를 기체 위치로 바꿨습니다"

/**
 * 아래 라이브러리(10.5.4, Pilot 2 경로 라이브러리 바로가기 p2s_7): 검은 칸에 경로 아이콘만. 비행 화면 안 라이브러리 패널(10.5.8)을 열고 닫는다.
 * 떠 있어도 열린다(패널은 미리보기만). 자리는 [LibraryButtonAlignment].
 */
@Composable
internal fun LibraryButton(onClick: () -> Unit) {
    MapTool("라이브러리", size = Dimens.TouchMedium, dark = true, onClick = onClick) { tint ->
        Icon(painterResource(R.drawable.ic_route), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 큰 지도 라이브러리 바로가기 자리: Pilot 2 p2s_7처럼 아래, 가운데가 화면 폭의 약 70%(768dp에서 버튼 칸 52dp 기준). */
internal val LibraryButtonAlignment = BiasAlignment(0.42f, 1f)

/** 떠 있는 동안 기체 위치를 지도 비행 궤적(주황 선)에 더한다([FlightTrack]). */
internal fun FlightUiState.withTrack(t: Telemetry) =
    copy(track = FlightTrack.append(track, t.position.takeIf { t.isFlying }, AppConfig.Map.TRACK_MIN_STEP_M, AppConfig.Map.TRACK_MAX_POINTS))

/** 지도에 쓸 짧은 전주 이름: 선로번호, 없으면 전산화번호 끝 4자리(홈 카드와 같음). */
internal fun poleLabel(p: Pole) = p.lineNo?.takeIf { it.isNotBlank() } ?: p.poleId.takeLast(4)
