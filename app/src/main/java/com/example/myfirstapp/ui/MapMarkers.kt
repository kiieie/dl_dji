package com.example.myfirstapp.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.ui.theme.MAP_AIRCRAFT
import com.example.myfirstapp.ui.theme.MAP_HOME
import com.example.myfirstapp.ui.theme.MAP_RC
import com.example.myfirstapp.ui.theme.MAP_STROKE_DARK
import com.example.myfirstapp.ui.theme.MAP_STROKE_LIGHT
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/** 지도 표지(10.5.4, Pilot 2와 같게): 홈 = 노란 원 검은 "H", 기체 = 기수 방향 파란 화살표, 조종기 = 파란 점. 모르면 null. */
internal data class MapMarkers(val home: GeoPoint? = null, val aircraft: GeoPoint? = null, val headingDeg: Double = 0.0, val rc: GeoPoint? = null)

private const val HOME_ICON = "marker-home-icon"
private const val AIRCRAFT_ICON = "marker-aircraft-icon"

/**
 * 표지 소스·레이어를 지금 맨 위에 더한다(스타일을 만들 때 다른 레이어 뒤에 부른다). [scale]은 미니맵에서 줄인다.
 * 그림은 비트맵이라 글리프(인터넷 글꼴)가 필요 없다(7.4 오프라인).
 */
internal fun Style.addMarkerLayers(scale: Float = 1f) {
    addImage(HOME_ICON, homeBitmap())
    addImage(AIRCRAFT_ICON, aircraftBitmap())
    listOf("marker-home", "marker-rc", "marker-aircraft").forEach { addSource(GeoJsonSource(it)) }
    addLayer(
        CircleLayer("marker-rc", "marker-rc").withProperties(
            circleColor(MAP_RC), circleRadius(5f * scale), circleStrokeColor(MAP_STROKE_LIGHT), circleStrokeWidth(1.5f),
        ),
    )
    addLayer(SymbolLayer("marker-home", "marker-home").withProperties(iconImage(HOME_ICON), iconSize(scale), iconAllowOverlap(true), iconIgnorePlacement(true)))
    addLayer(
        SymbolLayer("marker-aircraft", "marker-aircraft").withProperties(
            iconImage(AIRCRAFT_ICON), iconSize(scale), iconRotate(get("heading")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true),
        ),
    )
}

/** 표지 위치를 바꾼다. 스타일을 다 읽기 전이면 소스가 없어 아무것도 하지 않는다. */
internal fun Style.setMarkers(m: MapMarkers) {
    fun set(id: String, at: GeoPoint?, block: Feature.() -> Unit = {}) =
        getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(at?.let { Feature.fromGeometry(it.toPoint()).apply(block) })))
    set("marker-home", m.home)
    set("marker-rc", m.rc)
    set("marker-aircraft", m.aircraft) { addNumberProperty("heading", m.headingDeg) }
}

private val dp get() = Resources.getSystem().displayMetrics.density

/** 홈: 노란 원(22dp)에 검은 테두리와 "H". */
private fun homeBitmap(): Bitmap {
    val size = (22 * dp).toInt()
    return createBitmap(size, size).also { b ->
        val c = Canvas(b)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(r, r, r - dp, paint.apply { color = MAP_HOME.toColorInt(); style = Paint.Style.FILL })
        c.drawCircle(r, r, r - dp, paint.apply { color = MAP_STROKE_DARK.toColorInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp })
        paint.apply { style = Paint.Style.FILL; color = android.graphics.Color.BLACK; textSize = 13 * dp; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("H", r, r - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

/** 기체: 위(기수)를 가리키는 파란 화살촉(28dp), 흰 테두리. 지도에서 기수 각도만큼 돌린다. */
private fun aircraftBitmap(): Bitmap {
    val size = (28 * dp).toInt()
    val s = size.toFloat()
    val arrow = Path().apply {
        moveTo(s / 2, s * 0.06f)
        lineTo(s * 0.86f, s * 0.9f)
        lineTo(s / 2, s * 0.7f)
        lineTo(s * 0.14f, s * 0.9f)
        close()
    }
    return createBitmap(size, size).also { b ->
        val c = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
        c.drawPath(arrow, paint.apply { color = MAP_AIRCRAFT.toColorInt(); style = Paint.Style.FILL })
        c.drawPath(arrow, paint.apply { color = MAP_STROKE_LIGHT.toColorInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp })
    }
}
