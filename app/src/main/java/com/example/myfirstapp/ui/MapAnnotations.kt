package com.example.myfirstapp.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.example.myfirstapp.core.model.Annotation
import com.example.myfirstapp.core.model.AnnotationColor
import com.example.myfirstapp.core.model.AnnotationKind
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.MAP_STROKE_LIGHT
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon as GeoJsonPolygon

// 지도 주석 레이어(10.4.7, Pilot 2 "포인트·선·영역 주석"): 모든 지도(계획·비행 큰 지도·미니맵·수동 비행·다시 보기)가 같이 쓴다.
// 이름·길이 글자는 비트맵으로 그린다(MapLibre 글자 레이어는 글꼴을 인터넷으로 받아야 함, 7.4 오프라인).

private const val NOTES = "note"
private const val NOTE_LABELS = "note-labels"
private const val NOTE_EDIT = "note-edit"

/** 지도에서 누르면 주석을 고르는 레이어(속성 `id`). */
internal val NOTE_LAYERS = arrayOf("note-pin", "note-line", "note-fill")

/** 그리는 중 꼭짓점 레이어(속성 `i` = 꼭짓점 번호). */
internal const val NOTE_VERTEX = "note-vertex"

private fun pinIcon(c: AnnotationColor) = "note-pin-${c.name}"

/** 주석 소스·레이어를 지금 맨 위에 더한다(표지 [addMarkerLayers]보다 먼저 부른다). [scale]은 미니맵에서 줄인다. */
internal fun Style.addAnnotationLayers(scale: Float = 1f) {
    AnnotationColor.entries.forEach { addImage(pinIcon(it), pinBitmap(it)) }
    listOf(NOTES, NOTE_LABELS, NOTE_EDIT).forEach { addSource(GeoJsonSource(it)) }
    addLayer(FillLayer("note-fill", NOTES).withFilter(eq(geometryType(), literal("Polygon"))).withProperties(fillColor(get("color")), fillOpacity(0.3f)))
    addLayer(
        LineLayer("note-line", NOTES).withFilter(neq(geometryType(), literal("Point")))
            .withProperties(lineColor(get("color")), lineWidth(2.5f * scale), lineJoin(Property.LINE_JOIN_ROUND)),
    )
    addLayer(
        SymbolLayer("note-pin", NOTES).withFilter(eq(geometryType(), literal("Point")))
            .withProperties(iconImage(get("icon")), iconSize(scale), iconAllowOverlap(true), iconIgnorePlacement(true)),
    )
    // 이름은 마름모 오른쪽, 선은 가운데 구간 가운데, 영역은 꼭짓점 평균 자리
    addLayer(
        SymbolLayer("note-label", NOTE_LABELS).withProperties(
            iconImage(get("icon")), iconAnchor(get("anchor")), iconSize(scale), iconAllowOverlap(true), iconIgnorePlacement(true),
        ),
    )
    addLayer(
        CircleLayer(NOTE_VERTEX, NOTE_EDIT).withFilter(eq(get("kind"), literal("v")))
            .withProperties(circleColor(get("fill")), circleRadius(7f), circleStrokeColor(MAP_STROKE_LIGHT), circleStrokeWidth(2f)),
    )
    addLayer(
        SymbolLayer("note-length", NOTE_EDIT).withFilter(eq(get("kind"), literal("len")))
            .withProperties(iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true)),
    )
}

/**
 * 주석을 그린다. [draft]는 그리는 중인 선·영역(저장된 것을 고치는 중이면 그것 대신 그린다)과 그 꼭짓점(흰 원, 고른 것 [vertex] 또는 마지막은 파랑)·구간 길이.
 * 스타일을 다 읽기 전이면 소스가 없어 아무것도 하지 않는다.
 */
internal fun Style.setAnnotations(items: List<Annotation>, draft: Annotation? = null, vertex: Int? = null) {
    val shown = items.filter { it.id != draft?.id } + listOfNotNull(draft)
    val features = shown.mapNotNull { a ->
        val pts = a.points.map { it.toPoint() }
        val g = when {
            a.kind == AnnotationKind.PIN && pts.isNotEmpty() -> pts[0]
            a.kind == AnnotationKind.AREA && pts.size >= 3 -> GeoJsonPolygon.fromLngLats(listOf(pts + pts[0]))
            pts.size >= 2 -> LineString.fromLngLats(pts)
            else -> return@mapNotNull null
        }
        Feature.fromGeometry(g).apply {
            addNumberProperty("id", a.id)
            addStringProperty("color", a.color.hex)
            addStringProperty("icon", pinIcon(a.color))
        }
    }
    getSourceAs<GeoJsonSource>(NOTES)?.setGeoJson(FeatureCollection.fromFeatures(features))
    val labels = shown.mapNotNull { a ->
        val at = labelPoint(a) ?: return@mapNotNull null
        Feature.fromGeometry(at.toPoint()).apply {
            addStringProperty("icon", labelImage(a.name, dark = true))
            addStringProperty("anchor", if (a.kind == AnnotationKind.PIN) Property.ICON_ANCHOR_LEFT else Property.ICON_ANCHOR_BOTTOM)
        }
    }
    getSourceAs<GeoJsonSource>(NOTE_LABELS)?.setGeoJson(FeatureCollection.fromFeatures(labels))
    val pts = draft?.points.orEmpty()
    val sel = vertex ?: pts.lastIndex
    val ring = if (draft?.kind == AnnotationKind.AREA && pts.size >= 3) pts + pts[0] else pts
    val edit = pts.mapIndexed { i, p ->
        Feature.fromGeometry(p.toPoint()).apply {
            addStringProperty("kind", "v")
            addNumberProperty("i", i)
            addStringProperty("fill", if (i == sel) AnnotationColor.BLUE.hex else MAP_STROKE_LIGHT)
        }
    } + ring.zipWithNext { a, b ->
        Feature.fromGeometry(GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2).toPoint()).apply {
            addStringProperty("kind", "len")
            addStringProperty("icon", labelImage("%.1fm".format(LocalProjection.distanceM(a, b)), dark = false))
        }
    }
    getSourceAs<GeoJsonSource>(NOTE_EDIT)?.setGeoJson(FeatureCollection.fromFeatures(edit))
}

/** 이름 자리: 핀포인트는 그 점, 선은 가운데 구간 가운데, 영역은 꼭짓점 평균. */
private fun labelPoint(a: Annotation): GeoPoint? = when {
    !a.complete -> null
    a.kind == AnnotationKind.PIN -> a.points[0]
    a.kind == AnnotationKind.LINE -> (a.points.size / 2).let { i -> GeoPoint((a.points[i - 1].lat + a.points[i].lat) / 2, (a.points[i - 1].lon + a.points[i].lon) / 2) }
    else -> GeoPoint(a.points.sumOf { it.lat } / a.points.size, a.points.sumOf { it.lon } / a.points.size)
}

/** 글자 그림을 스타일에 한 번만 넣고 이름을 돌려준다. [dark]면 검은 반투명 바탕(전주 라벨과 같음), 아니면 Pilot 2 구간 길이처럼 회색 바탕. */
internal fun Style.labelImage(text: String, dark: Boolean): String {
    val id = "note-label-$dark-$text"
    if (getImage(id) == null) addImage(id, labelBitmap(text, if (dark) 0x99000000.toInt() else 0xCC5A5F5A.toInt()))
    return id
}

private val dp get() = Resources.getSystem().displayMetrics.density

private fun labelBitmap(text: String, bg: Int): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = FontSize.Caption.value * dp; typeface = Typeface.DEFAULT_BOLD }
    val pad = 4 * dp
    val w = (paint.measureText(text) + 2 * pad).toInt()
    val h = (paint.fontSpacing + pad).toInt()
    return createBitmap(w, h).also { b ->
        val c = Canvas(b)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 2 * dp, 2 * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        c.drawText(text, pad, h / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

/** 핀포인트: 색 마름모(Pilot 2 핀포인트 아이콘)에 흰 테두리, 가운데 흰 점. */
private fun pinBitmap(c: AnnotationColor): Bitmap {
    val size = (Dimens.MarkerPin.value * dp).toInt()
    val s = size.toFloat()
    val diamond = Path().apply { moveTo(s / 2, dp); lineTo(s - dp, s / 2); lineTo(s / 2, s - dp); lineTo(dp, s / 2); close() }
    return createBitmap(size, size).also { b ->
        val canvas = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
        canvas.drawPath(diamond, paint.apply { color = c.hex.toColorInt(); style = Paint.Style.FILL })
        canvas.drawPath(diamond, paint.apply { color = MAP_STROKE_LIGHT.toColorInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp })
        canvas.drawCircle(s / 2, s / 2, 2 * dp, paint.apply { style = Paint.Style.FILL })
    }
}
