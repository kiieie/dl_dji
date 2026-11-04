package com.example.myfirstapp.ui.flight

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.route.Viewpoints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.ui.MapMarkers
import com.example.myfirstapp.ui.addMarkerLayers
import com.example.myfirstapp.ui.rememberMapView
import com.example.myfirstapp.ui.setMarkers
import com.example.myfirstapp.ui.styleJson
import com.example.myfirstapp.ui.toPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.MAP_ROUTE

/**
 * 비행 화면 지도(10장): 계획 화면과 같은 배경(위성·MBTiles) 위에 소티 경로, 전주 상태와 표지(이륙 지점 H, 기체 화살표, 조종기 점, [MapMarkers])를 그린다.
 * [interactive]가 false면 미니맵이라 손으로 움직이지 않는다. [pad]는 처음 맞출 때 가장자리 여백(왼쪽, 위, 오른쪽, 아래).
 * 전주번호(선로번호, 없으면 전산화번호 끝 4자리, 홈 카드와 같음)는 지도 위에 Compose 글자로 덧그린다:
 * MapLibre 글자 레이어는 글꼴을 인터넷으로 받아야 해서 쓰지 않는다(7.4 오프라인). 큰 지도는 확대 15 이상에서 보이는 전주 전부, 미니맵은 지금 전주만.
 */
@Composable
internal fun FlightMap(s: FlightUiState, interactive: Boolean, pad: List<Dp>, modifier: Modifier) {
    val mapView = rememberMapView()
    val density = LocalDensity.current
    var styleGen by remember { mutableIntStateOf(0) }
    var fitted by remember { mutableStateOf(false) }
    var panned by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf<List<Pair<Pole, Offset>>>(emptyList()) }
    val latest by rememberUpdatedState(s)
    fun relabel(map: MapLibreMap) {
        val st = latest
        val zoom = map.cameraPosition.zoom
        val targets = when {
            interactive && zoom >= AppConfig.Map.LABEL_MIN_ZOOM -> st.poles
            !interactive -> listOfNotNull(st.exec.target?.pole)
            else -> emptyList()
        }
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        labels = targets.mapNotNull { p ->
            val pt = map.projection.toScreenLocation(LatLng(p.position.lat, p.position.lon))
            if (pt.x !in 0f..w || pt.y !in 0f..h) null else p to Offset(pt.x, pt.y)
        }
    }

    LaunchedEffect(s.mapFiles, s.background) {
        mapView.getMapAsync { map ->
            map.uiSettings.setAllGesturesEnabled(interactive)
            map.uiSettings.isLogoEnabled = interactive
            map.uiSettings.isAttributionEnabled = interactive
            map.setStyle(Style.Builder().fromJson(styleJson(s.mapFiles, s.background))) { style ->
                listOf("route", "poles").forEach { style.addSource(GeoJsonSource(it)) }
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor(MAP_ROUTE), lineWidth(2f)))
                style.addLayer(
                    CircleLayer("poles-point", "poles").withProperties(
                        circleColor(
                            match(
                                get("status"), color(PolePending.toArgb()),
                                stop(VisitStatus.DONE.name, color(PoleDone.toArgb())),
                                stop(VisitStatus.SKIPPED.name, color(PoleSkipped.toArgb())),
                                stop(VisitStatus.FAILED.name, color(PoleFailed.toArgb())),
                                stop(VisitStatus.IN_PROGRESS.name, color(PoleActive.toArgb())),
                            ),
                        ),
                        circleRadius(if (interactive) 7f else 4f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1f),
                    ),
                )
                style.addMarkerLayers(scale = if (interactive) 1f else 0.75f)
                map.addOnCameraMoveListener { relabel(map) }
                map.addOnCameraIdleListener { relabel(map) }
                // 손으로 지도를 옮기면 기체를 따라가지 않는다
                map.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) panned = true }
                styleGen++
            }
        }
    }

    // 표지(10.5.4)와 카메라: 미션은 전주 전체에 한 번 맞춘다. 전주가 없으면(수동 비행) 기체 → 이륙 지점 → 조종기 위치에 맞추고,
    // 손으로 옮기기 전까지 기체를 따라간다(11-03 현장: 수동 비행 미니맵이 흰 칸).
    val t = s.telemetry
    LaunchedEffect(styleGen, t?.position, t?.headingDeg, t?.rcPosition, s.home) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            style.setMarkers(MapMarkers(home = s.home, aircraft = t?.position, headingDeg = t?.headingDeg ?: 0.0, rc = t?.rcPosition))
            val points = if (s.poles.isNotEmpty()) s.poles.map { it.position } + listOfNotNull(s.home)
            else listOfNotNull(t?.position ?: s.home ?: t?.rcPosition)
            if (points.isEmpty()) return@getMapAsync
            val latLngs = points.map { LatLng(it.lat, it.lon) }
            if (!fitted) {
                fitted = true
                val px = pad.map { with(density) { it.roundToPx() } }
                map.moveCamera(
                    if (latLngs.distinct().size < 2) CameraUpdateFactory.newLatLngZoom(latLngs.first(), AppConfig.Map.SINGLE_POINT_ZOOM)
                    else CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(latLngs).build(), px[0], px[1], px[2], px[3]),
                )
            } else if (s.poles.isEmpty() && !panned) {
                map.moveCamera(CameraUpdateFactory.newLatLng(latLngs.first()))
            }
        }
    }

    LaunchedEffect(styleGen, s.exec.visits, s.home) {
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
            // 소티마다 이륙 지점 → 실제 촬영 지점(5.6, 4방향이면 4패스) → 이륙 지점
            val home = s.home?.toPoint()
            style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
                FeatureCollection.fromFeatures(
                    s.exec.visits.groupBy { it.sortieIndex }.values.map { v ->
                        val poles = v.sortedBy { it.order }.map { it.pole }
                        val p = s.params
                        val pts = (if (p != null && s.home != null) Viewpoints.expand(poles, p, s.home).map { it.position } else poles.map { it.position })
                            .map { it.toPoint() }
                        Feature.fromGeometry(LineString.fromLngLats(listOfNotNull(home) + pts + listOfNotNull(home)))
                    },
                ),
            )
            relabel(map)
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
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
    }
}

/** 지도에 쓸 짧은 전주 이름: 선로번호, 없으면 전산화번호 끝 4자리(홈 카드와 같음). */
internal fun poleLabel(p: Pole) = p.lineNo?.takeIf { it.isNotBlank() } ?: p.poleId.takeLast(4)
