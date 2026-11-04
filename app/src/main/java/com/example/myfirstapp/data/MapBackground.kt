package com.example.myfirstapp.data

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File

/** 배경 지도 종류(7.4, ⑥ 설정에서 고름). */
enum class MapSource(val title: String) { BING("Bing 위성"), VWORLD_SATELLITE("VWorld 위성"), VWORLD_BASE("VWorld 일반") }

/**
 * 지도 화면에 까는 배경 한 벌: 온라인 타일 주소(틀)와 라벨 타일 주소, 미리 받은 타일을 담는 캐시 파일 이름(앱 폴더 `maps/`).
 * 주소 틀은 Bing `{quadkey}` 또는 VWorld `{z}/{y}/{x}`. 타일이 없으면(토큰·키 없음, 인터넷 없이 처음) 빈 목록이고 캐시만 보인다.
 */
data class MapBackground(
    val source: MapSource,
    val tiles: List<String>,
    val cacheName: String,
    val format: String,
    val attribution: String,
    val labelTiles: List<String> = emptyList(),
    val labelCacheName: String? = null,
) {
    companion object {
        val NONE = MapBackground(MapSource.BING, emptyList(), SATELLITE_CACHE_NAME, "jpg", BING_ATTRIBUTION)
    }
}

const val BING_ATTRIBUTION = "© Microsoft Bing, Cesium ion"
private const val VWORLD_ATTRIBUTION = "© 국토교통부 VWorld"
private const val VWORLD_WMTS = "https://api.vworld.kr/req/wmts/1.0.0"

/** 지도 종류마다 따로 두는 미리 받기 캐시(섞이지 않게). 사용자가 넣은 MBTiles와 구별할 때도 쓴다. */
val CACHE_NAMES = setOf(SATELLITE_CACHE_NAME, "vworld-satellite-cache.mbtiles", "vworld-hybrid-cache.mbtiles", "vworld-base-cache.mbtiles")

/** VWorld 키가 들어 있는 빌드인지(없으면 설정에서 VWorld를 고를 수 없다). */
val vworldAvailable: Boolean get() = BuildConfig.VWORLD_API_KEY.isNotBlank()

/** 고른 배경 한 벌을 만든다. VWorld 키가 없으면 Bing으로 되돌린다. Bing 주소는 저장한 것을 먼저 쓴다([onlineSatelliteTiles]). */
suspend fun mapBackground(source: MapSource, labels: Boolean): MapBackground {
    val key = BuildConfig.VWORLD_API_KEY
    return when {
        source == MapSource.VWORLD_SATELLITE && key.isNotBlank() -> MapBackground(
            source, listOf("$VWORLD_WMTS/$key/Satellite/{z}/{y}/{x}.jpeg"), "vworld-satellite-cache.mbtiles", "jpg", VWORLD_ATTRIBUTION,
            labelTiles = if (labels) listOf("$VWORLD_WMTS/$key/Hybrid/{z}/{y}/{x}.png") else emptyList(),
            labelCacheName = if (labels) "vworld-hybrid-cache.mbtiles" else null,
        )
        source == MapSource.VWORLD_BASE && key.isNotBlank() ->
            MapBackground(source, listOf("$VWORLD_WMTS/$key/Base/{z}/{y}/{x}.png"), "vworld-base-cache.mbtiles", "png", VWORLD_ATTRIBUTION)
        else -> MapBackground.NONE.copy(tiles = onlineSatelliteTiles())
    }
}

/** 설정대로 배경을 만든다. Bing 주소를 아직 못 받았으면(인터넷 없음) 10초마다 다시 해 본다. 화면을 닫으면 취소된다. */
suspend fun mapBackgroundRetrying(settings: SettingsStore): MapBackground {
    while (true) {
        // 기다리는 동안 지도 레이어 버튼으로 바꿨을 수 있어 매번 다시 읽는다
        val bg = mapBackground(settings.mapSource.first(), settings.vworldLabels.first())
        // 토큰이 없으면 다시 해도 같다
        if (bg.tiles.isNotEmpty() || (bg.source == MapSource.BING && BuildConfig.CESIUM_ION_TOKEN.isBlank())) return bg
        delay(AppConfig.Timeouts.BACKGROUND_RETRY_MS)
    }
}

/** 지도에 깔 MBTiles: 사용자가 넣은 지도 + 지금 배경의 캐시. 다른 배경의 캐시와 라벨 캐시는 뺀다(라벨은 [labelCacheFile]로 맨 위에). */
fun MapBackground.baseFiles(files: List<File>): List<File> = files.filter { it.name !in CACHE_NAMES || it.name == cacheName }

fun MapBackground.labelCacheFile(files: List<File>): File? = labelCacheName?.let { n -> files.firstOrNull { it.name == n } }

/** 지도 레이어 버튼(10.5.7)에서 배경을 고른다: 설정에 저장하고(다른 지도 화면도 따름) 새 배경을 돌려준다. */
suspend fun SettingsStore.pickMapSource(source: MapSource): MapBackground {
    setMapSource(source)
    return mapBackground(source, vworldLabels.first())
}
