package com.example.myfirstapp.ui.flight

import androidx.compose.runtime.Composable
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
import com.example.myfirstapp.ui.rememberMapView
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

/**
 * 비행 화면 지도(10장): 계획 화면과 같은 배경(위성·MBTiles) 위에 소티 경로, 전주 상태, 이륙 지점, 기체를 그린다.
 * [interactive]가 false면 미니맵이라 손으로 움직이지 않는다. [pad]는 처음 맞출 때 가장자리 여백(왼쪽, 위, 오른쪽, 아래).
 */
@Composable
internal fun FlightMap(s: FlightUiState, interactive: Boolean, pad: List<Dp>, modifier: Modifier) {
    val mapView = rememberMapView()
    val density = LocalDensity.current
    var styleGen by remember { mutableIntStateOf(0) }
    var fitted by remember { mutableStateOf(false) }

    LaunchedEffect(s.mapFiles, s.onlineTiles) {
        mapView.getMapAsync { map ->
            map.uiSettings.setAllGesturesEnabled(interactive)
            map.uiSettings.isLogoEnabled = interactive
            map.uiSettings.isAttributionEnabled = interactive
            map.setStyle(Style.Builder().fromJson(styleJson(s.mapFiles, s.onlineTiles))) { style ->
                listOf("route", "poles", "home", "aircraft").forEach { style.addSource(GeoJsonSource(it)) }
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#E9ECEF"), lineWidth(2f)))
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
                style.addLayer(
                    CircleLayer("home-point", "home").withProperties(
                        circleColor("#212529"), circleRadius(6f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                    ),
                )
                style.addLayer(
                    CircleLayer("aircraft-point", "aircraft").withProperties(
                        circleColor(color(Aircraft.toArgb())), circleRadius(7f), circleStrokeColor("#000000"), circleStrokeWidth(2f),
                    ),
                )
                styleGen++
                if (!fitted && s.poles.isNotEmpty()) {
                    fitted = true
                    val points = (s.poles.map { it.position } + listOfNotNull(s.home)).map { LatLng(it.lat, it.lon) }
                    val px = pad.map { with(density) { it.roundToPx() } }
                    map.moveCamera(
                        if (points.distinct().size < 2) CameraUpdateFactory.newLatLngZoom(points.first(), 17.0)
                        else CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), px[0], px[1], px[2], px[3]),
                    )
                }
            }
        }
    }

    LaunchedEffect(styleGen, s.exec.visits, s.telemetry?.position, s.home) {
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
            // 소티마다 이륙 지점 → 전주 순서 → 이륙 지점
            val home = s.home?.toPoint()
            style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
                FeatureCollection.fromFeatures(
                    s.exec.visits.groupBy { it.sortieIndex }.values.map { v ->
                        val pts = v.sortedBy { it.order }.map { it.pole.position.toPoint() }
                        Feature.fromGeometry(LineString.fromLngLats(listOfNotNull(home) + pts + listOfNotNull(home)))
                    },
                ),
            )
            style.getSourceAs<GeoJsonSource>("home")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(home?.let { Feature.fromGeometry(it) })))
            style.getSourceAs<GeoJsonSource>("aircraft")?.setGeoJson(
                FeatureCollection.fromFeatures(listOfNotNull(s.telemetry?.position?.let { Feature.fromGeometry(it.toPoint()) })),
            )
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
