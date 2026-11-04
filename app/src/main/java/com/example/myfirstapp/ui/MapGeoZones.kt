package com.example.myfirstapp.ui

import com.example.myfirstapp.core.model.GeoCategory
import com.example.myfirstapp.core.model.GeoZone
import com.example.myfirstapp.core.model.circleRing
import com.example.myfirstapp.ui.theme.MAP_GEO_ALTITUDE
import com.example.myfirstapp.ui.theme.MAP_GEO_APPROVED
import com.example.myfirstapp.ui.theme.MAP_GEO_AUTHORIZATION
import com.example.myfirstapp.ui.theme.MAP_GEO_ENHANCED_WARNING
import com.example.myfirstapp.ui.theme.MAP_GEO_REGULATORY
import com.example.myfirstapp.ui.theme.MAP_GEO_RESTRICTED
import com.example.myfirstapp.ui.theme.MAP_GEO_WARNING
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Polygon

// GEO 구역 지도 레이어(10.4.9, Pilot 2 레이어 → GEO 구역): SDK가 알린 기체 둘레 구역(`DjiFlySafe`, 로컬만)을 종류별 색으로 그린다.

private const val GEO_ZONES = "geo-zones"

/** 종류별 색(DJI Fly Safe 지도): 제한 빨강, 승인 파랑, 강화 경고 주황, 경고 노랑, 고도 제한 회색, 규제 제한 하늘, 허가 녹색. */
internal fun geoZoneColor(c: GeoCategory): String = when (c) {
    GeoCategory.RESTRICTED -> MAP_GEO_RESTRICTED
    GeoCategory.AUTHORIZATION -> MAP_GEO_AUTHORIZATION
    GeoCategory.ENHANCED_WARNING -> MAP_GEO_ENHANCED_WARNING
    GeoCategory.WARNING -> MAP_GEO_WARNING
    GeoCategory.ALTITUDE, GeoCategory.UNKNOWN -> MAP_GEO_ALTITUDE
    GeoCategory.REGULATORY -> MAP_GEO_REGULATORY
    GeoCategory.APPROVED -> MAP_GEO_APPROVED
}

/** GEO 구역 면·테두리 레이어. 경로·전주·표지보다 아래에 그리도록 다른 레이어보다 먼저 더한다. */
internal fun Style.addGeoZoneLayers(scale: Float = 1f) {
    addSource(GeoJsonSource(GEO_ZONES))
    addLayer(FillLayer("geo-fill", GEO_ZONES).withProperties(fillColor(get("color")), fillOpacity(0.18f)))
    addLayer(LineLayer("geo-line", GEO_ZONES).withProperties(lineColor(get("color")), lineWidth(1.5f * scale)))
}

/** 구역을 그린다(빈 목록이면 지움). 원은 다각형으로, 위험한 종류(제한)가 위에 오게 늦게 그린다. 스타일을 다 읽기 전이면 아무것도 하지 않는다. */
internal fun Style.setGeoZones(zones: List<GeoZone>) {
    val features = zones.flatMap { z -> z.areas.map { a -> z.displayCategory(a) to a } }
        .sortedByDescending { (c, _) -> c.ordinal }
        .mapNotNull { (c, a) ->
            val ring = if (a.center != null && a.radiusM > 0) circleRing(a.center!!, a.radiusM) else a.points.takeIf { it.size >= 3 } ?: return@mapNotNull null
            Feature.fromGeometry(Polygon.fromLngLats(listOf((ring + ring.first()).map { it.toPoint() }))).apply { addStringProperty("color", geoZoneColor(c)) }
        }
    getSourceAs<GeoJsonSource>(GEO_ZONES)?.setGeoJson(FeatureCollection.fromFeatures(features))
}
