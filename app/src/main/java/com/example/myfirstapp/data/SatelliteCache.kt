package com.example.myfirstapp.data

import android.content.Context
import android.util.Log
import com.example.myfirstapp.core.model.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** 미리 받을 확대 단계(7.4). 비행 미니맵·계획 화면에서 전주가 보이는 범위. */
private const val MIN_ZOOM = 13
private const val MAX_ZOOM = 19

/** 한 번에 미리 받을 타일 수 상한(약 25KB/장이면 500MB). 넘으면 최대 확대를 낮춘다. */
private const val MAX_TILES = 20_000L

/** 영역 둘레 여유(도, 약 300m): 이륙 지점·이동 고도 구간이 영역 밖으로 조금 나간다. */
private const val PAD_DEG = 0.003

/**
 * 계획을 저장할 때 미션 영역의 위성 타일을 미리 받는다(7.4, 2026-10-31 사용자 결정). 현장은 인터넷이 없으므로
 * 사무실에서 저장하는 순간 받아 MapLibre 오프라인 DB에 둔다. 이미 받은 타일은 다시 받지 않는다(MapLibre가 같은 주소를 한 번만 저장).
 * 인터넷이 없거나 타일 주소가 아직 없으면 아무것도 하지 않는다(그때는 화면에서 본 타일만 캐시에 남음). 메인 스레드에서 부른다.
 */
fun prefetchSatellite(context: Context, points: List<GeoPoint>, name: String) {
    val tiles = savedSatelliteTiles()
    if (tiles.isEmpty() || points.isEmpty()) return
    val south = points.minOf { it.lat } - PAD_DEG
    val north = points.maxOf { it.lat } + PAD_DEG
    val west = points.minOf { it.lon } - PAD_DEG
    val east = points.maxOf { it.lon } + PAD_DEG
    var maxZoom = MAX_ZOOM
    while (maxZoom > MIN_ZOOM && tileCount(south, north, west, east, MIN_ZOOM, maxZoom) > MAX_TILES) maxZoom--
    MapLibre.getInstance(context)
    val style = File(context.filesDir, "satellite-style.json").apply { writeText(satelliteOnlyStyle(tiles)) }
    val bounds = LatLngBounds.Builder().include(LatLng(south, west)).include(LatLng(north, east)).build()
    val definition = OfflineTilePyramidRegionDefinition("file://${style.absolutePath}", bounds, MIN_ZOOM.toDouble(), maxZoom.toDouble(), 1f)
    val manager = OfflineManager.getInstance(context)
    manager.createOfflineRegion(definition, name.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
        override fun onCreate(offlineRegion: OfflineRegion) {
            OpLog.log("map", "prefetch", mapOf("name" to name, "maxZoom" to maxZoom, "tiles" to tileCount(south, north, west, east, MIN_ZOOM, maxZoom)))
            offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (!status.isComplete) return
                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    OpLog.log("map", "prefetchDone", mapOf("name" to name, "tiles" to status.completedTileCount, "bytes" to status.completedTileSize))
                }

                override fun onError(error: OfflineRegionError) { Log.w("SatelliteCache", "미리 받기 오류: ${error.reason} ${error.message}") }
                override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
            })
            offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }

        override fun onError(error: String) { Log.w("SatelliteCache", "미리 받기 영역 생성 실패: $error") }
    })
}

/** 위성 원본 하나만 있는 스타일(오프라인 영역 정의용). 화면 스타일([com.example.myfirstapp.ui.styleJson])과 같은 타일 주소를 써야 같은 캐시를 쓴다. */
private fun satelliteOnlyStyle(tiles: List<String>): String {
    val urls = tiles.joinToString(",") { "\"$it\"" }
    return """{"version":8,"sources":{"online":{"type":"raster","tiles":[$urls],"tileSize":256,"maxzoom":19}},""" +
        """"layers":[{"id":"online","type":"raster","source":"online"}]}"""
}

/** 웹 메르카토르 타일 수(확대 [minZ]~[maxZ]). */
internal fun tileCount(south: Double, north: Double, west: Double, east: Double, minZ: Int, maxZ: Int): Long = (minZ..maxZ).sumOf { z ->
    val n = 1 shl z
    fun x(lon: Double) = ((lon + 180) / 360 * n).toLong()
    fun y(lat: Double) = ((1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2 * n).toLong()
    (x(east) - x(west) + 1) * (y(south) - y(north) + 1)
}
