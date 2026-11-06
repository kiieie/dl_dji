package com.example.myfirstapp.data

import com.example.myfirstapp.BuildConfig
import kotlinx.coroutines.flow.first
import java.io.File

/** 배경 지도 종류(7.4, ⑥ 설정에서 고름). 국내 서버 VWorld만(2026-11-06 Bing 위성 뺌). */
enum class MapSource(val title: String) { VWORLD_SATELLITE("VWorld 위성"), VWORLD_BASE("VWorld 일반") }

/**
 * 지도 화면에 까는 배경 한 벌: 온라인 타일 주소(틀)와 라벨 타일 주소, 미리 받은 타일을 담는 캐시 파일 이름(앱 폴더 `maps/`).
 * 주소 틀은 VWorld `{z}/{y}/{x}`. 키가 없는 빌드는 빈 목록이고 캐시만 보인다.
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
        val NONE = MapBackground(MapSource.VWORLD_SATELLITE, emptyList(), SATELLITE_CACHE_NAME, "jpg", VWORLD_ATTRIBUTION)
    }
}

private const val VWORLD_ATTRIBUTION = "© 국토교통부 VWorld"
private const val VWORLD_WMTS = "https://api.vworld.kr/req/wmts/1.0.0"

/** 지도 종류마다 따로 두는 미리 받기 캐시(섞이지 않게). 사용자가 넣은 MBTiles와 구별할 때도 쓴다. */
val CACHE_NAMES = setOf(SATELLITE_CACHE_NAME, "vworld-hybrid-cache.mbtiles", "vworld-base-cache.mbtiles")

/** VWorld 키가 들어 있는 빌드인지(없으면 설정에서 VWorld를 고를 수 없다). */
val vworldAvailable: Boolean get() = BuildConfig.VWORLD_API_KEY.isNotBlank()

/** 고른 배경 한 벌을 만든다. VWorld 키가 없으면 타일 없이 캐시만. */
fun mapBackground(source: MapSource, labels: Boolean): MapBackground {
    val key = BuildConfig.VWORLD_API_KEY
    return when {
        source == MapSource.VWORLD_SATELLITE && key.isNotBlank() -> MapBackground(
            source, listOf("$VWORLD_WMTS/$key/Satellite/{z}/{y}/{x}.jpeg"), source.cacheName, "jpg", VWORLD_ATTRIBUTION,
            labelTiles = if (labels) listOf("$VWORLD_WMTS/$key/Hybrid/{z}/{y}/{x}.png") else emptyList(),
            labelCacheName = if (labels) "vworld-hybrid-cache.mbtiles" else null,
        )
        source == MapSource.VWORLD_BASE && key.isNotBlank() ->
            MapBackground(source, listOf("$VWORLD_WMTS/$key/Base/{z}/{y}/{x}.png"), source.cacheName, "png", VWORLD_ATTRIBUTION)
        else -> MapBackground.NONE
    }
}

/** 설정대로 배경을 만든다(주소는 키로 바로 정해져 인터넷이 필요 없다). */
suspend fun mapBackgroundRetrying(settings: SettingsStore): MapBackground = mapBackground(settings.mapSource.first(), settings.vworldLabels.first())

/** 배경의 미리 받기 캐시 이름. 인터넷 없이 정해진다(경로 그림 바탕 10.4.10도 씀). */
val MapSource.cacheName: String
    get() = when {
        this == MapSource.VWORLD_BASE && vworldAvailable -> "vworld-base-cache.mbtiles"
        else -> SATELLITE_CACHE_NAME
    }

/** 지도에 깔 MBTiles: 사용자가 넣은 지도 + 캐시 [cacheName]. 다른 배경의 캐시와 라벨 캐시는 뺀다(라벨은 [labelCacheFile]로 맨 위에). */
fun baseMapFiles(files: List<File>, cacheName: String): List<File> = files.filter { it.name !in CACHE_NAMES || it.name == cacheName }

fun MapBackground.baseFiles(files: List<File>): List<File> = baseMapFiles(files, cacheName)

fun MapBackground.labelCacheFile(files: List<File>): File? = labelCacheName?.let { n -> files.firstOrNull { it.name == n } }

/** 지도 레이어 버튼(10.5.7)에서 배경을 고른다: 설정에 저장하고(다른 지도 화면도 따름) 새 배경을 돌려준다. */
suspend fun SettingsStore.pickMapSource(source: MapSource): MapBackground {
    setMapSource(source)
    return mapBackground(source, vworldLabels.first())
}
