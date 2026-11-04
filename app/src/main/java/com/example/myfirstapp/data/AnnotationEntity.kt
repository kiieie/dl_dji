package com.example.myfirstapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.myfirstapp.core.model.Annotation
import com.example.myfirstapp.core.model.AnnotationColor
import com.example.myfirstapp.core.model.AnnotationKind
import com.example.myfirstapp.core.model.Annotations
import com.example.myfirstapp.core.model.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * 지도 주석(Room 11, 10.4.7): 핀포인트·선·영역. 기기 안에만 두고(데이터 주권), 미션 ZIP에 GeoJSON으로 넣는다(4.3).
 * [points]는 GeoJSON 좌표 배열 `[[경도,위도],…]`(영역은 닫지 않음). [altitudeM]은 핀포인트 고도(이륙 지점 기준).
 */
@Entity(tableName = "annotation")
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val name: String,
    val color: String,
    val points: String,
    val altitudeM: Double?,
    val createdAt: Long,
)

internal fun AnnotationEntity.toAnnotation() = Annotation(
    id, AnnotationKind.valueOf(kind), name, AnnotationColor.entries.firstOrNull { it.name == color } ?: AnnotationColor.BLUE,
    JSONArray(points).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> GeoPoint(p.getDouble(1), p.getDouble(0)) } } },
    altitudeM, createdAt,
)

private fun coords(points: List<GeoPoint>) = JSONArray(points.map { JSONArray(listOf(it.lon, it.lat)) })

internal fun Annotation.toEntity() = AnnotationEntity(id, kind.name, name, color.name, coords(points).toString(), altitudeM, createdAt)

/**
 * 내보내기 `annotations.geojson`(4.3): 핀포인트 Point, 선 LineString, 영역 Polygon(닫음).
 * 속성은 이름·종류·색(#hex)·만든 시각(ms), 핀포인트 고도(이륙 지점 기준 m), 선 길이(m)·영역 넓이(m²).
 */
internal fun annotationsGeoJson(items: List<Annotation>): JSONObject = JSONObject().put("type", "FeatureCollection").put(
    "features",
    JSONArray(items.map { a ->
        val geometry = when (a.kind) {
            AnnotationKind.PIN -> JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(a.points[0].lon, a.points[0].lat)))
            AnnotationKind.LINE -> JSONObject().put("type", "LineString").put("coordinates", coords(a.points))
            AnnotationKind.AREA -> JSONObject().put("type", "Polygon").put("coordinates", JSONArray(listOf(coords(a.points + a.points[0]))))
        }
        val props = JSONObject().put("name", a.name).put("kind", a.kind.name).put("color", a.color.hex).put("createdAt", a.createdAt)
        when (a.kind) {
            AnnotationKind.PIN -> props.put("altitudeM", a.altitudeM ?: JSONObject.NULL)
            AnnotationKind.LINE -> props.put("lengthM", Annotations.lengthM(a.points))
            AnnotationKind.AREA -> props.put("areaM2", Annotations.areaM2(a.points))
        }
        JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", props)
    }),
)
