package com.example.myfirstapp.data

import com.example.myfirstapp.AppConfig
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.myfirstapp.BuildConfig
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.route.TileMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** VWorld 위성 미리 받기 캐시(앱 폴더 `maps/`). 다른 지도는 따로([MapBackground.cacheName]). */
const val SATELLITE_CACHE_NAME = "vworld-satellite-cache.mbtiles"

/** 2026-11-06 전 Bing 위성(미국 서버) 캐시. 앱을 켤 때 지운다([dropBingCache]). */
private const val BING_CACHE_NAME = "satellite-cache.mbtiles"

/**
 * 받기가 끝나지 않은 영역. 한 줄에 "이름 남 북 서 동 최대확대 캐시이름 형식 주소틀,주소틀…"(주소틀은 `{z}/{y}/{x}`).
 * 주소가 없는 줄(옛 Bing 위성)은 버린다. 앱을 다시 켜면 이어 받는다.
 */
private const val PENDING_NAME = "satellite-pending.txt"

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val lock = Mutex()

/**
 * 계획을 저장할 때 미션 영역의 배경 타일을 미리 받는다(7.4, 2026-10-31 사용자 결정). 현장은 인터넷이 없으므로
 * 사무실에서 저장하는 순간 지금 고른 배경([MapBackground], 라벨 포함)을 받아 그 배경의 MBTiles 캐시에 넣는다. 이미 있는 타일은 다시 받지 않는다.
 * 인터넷이 없거나 타일 주소가 아직 없으면 남겨 두었다가 [resumeSatellitePrefetch]가 이어 받는다.
 * (MapLibre 오프라인 영역은 `file://` 스타일을 읽지 못해 쓰지 않는다 — 2026-10-31 RC 확인.)
 */
fun prefetchSatellite(appFilesDir: File, points: List<GeoPoint>, name: String, bg: MapBackground) {
    if (points.isEmpty()) return
    val s = points.minOf { it.lat } - AppConfig.Map.PREFETCH_PAD_DEG
    val n = points.maxOf { it.lat } + AppConfig.Map.PREFETCH_PAD_DEG
    val w = points.minOf { it.lon } - AppConfig.Map.PREFETCH_PAD_DEG
    val e = points.maxOf { it.lon } + AppConfig.Map.PREFETCH_PAD_DEG
    var maxZoom = AppConfig.Map.PREFETCH_MAX_ZOOM
    while (maxZoom > AppConfig.Map.PREFETCH_MIN_ZOOM && TileMath.count(s, n, w, e, AppConfig.Map.PREFETCH_MIN_ZOOM, maxZoom) > AppConfig.Map.PREFETCH_MAX_TILES) maxZoom--
    // VWorld 주소의 키는 `{KEY}`로 적어 파일에 평문으로 남기지 않는다
    val key = BuildConfig.VWORLD_API_KEY
    fun hide(urls: List<String>) = urls.joinToString(",") { if (key.isBlank()) it else it.replace(key, "{KEY}") }
    val lines = listOfNotNull(
        bg.tiles.takeIf { it.isNotEmpty() }?.let { "$name $s $n $w $e $maxZoom ${bg.cacheName} ${bg.format} ${hide(it)}" },
        bg.labelCacheName?.takeIf { bg.labelTiles.isNotEmpty() }?.let { "$name-label $s $n $w $e $maxZoom $it png ${hide(bg.labelTiles)}" },
    )
    scope.launch {
        lock.withLock { File(appFilesDir, PENDING_NAME).appendText(lines.joinToString("\n", postfix = "\n")) }
        OpLog.log("map", "prefetch", mapOf("name" to name, "source" to bg.source.name, "maxZoom" to maxZoom, "tiles" to TileMath.count(s, n, w, e, AppConfig.Map.PREFETCH_MIN_ZOOM, maxZoom)))
        resume(appFilesDir)
    }
}

/** 옛 Bing 위성 캐시와 그 타일 주소를 지운다(2026-11-06 VWorld만). 앱을 켤 때 부른다. */
fun dropBingCache(appFilesDir: File, internalFilesDir: File) {
    File(appFilesDir, "maps/$BING_CACHE_NAME").delete()
    File(internalFilesDir, "satellite-tiles.txt").delete()
}

/** 끝나지 않은 미리 받기를 이어 받는다(앱을 끄거나 인터넷이 끊겨 멈춘 것). 지도를 열 때 부른다. */
fun resumeSatellitePrefetch(appFilesDir: File) {
    scope.launch { resume(appFilesDir) }
}

private suspend fun resume(appFilesDir: File) = lock.withLock {
    val pending = File(appFilesDir, PENDING_NAME)
    if (!pending.isFile) return@withLock
    val left = pending.readLines().filter { it.isNotBlank() }.filterNot { line ->
        val p = line.split(" ")
        // 주소가 없는 줄은 옛 Bing 위성: 다시 받지 않는다
        val urls = p.getOrNull(8)?.split(",")?.filter { it.isNotBlank() }?.map { it.replace("{KEY}", BuildConfig.VWORLD_API_KEY) } ?: return@filterNot true
        runCatching { download(appFilesDir, urls, p[0], p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toInt(), p[6], p[7]) }
            .onFailure { Log.w("SatelliteCache", "미리 받기 멈춤(${p[0]}): ${it.message}") }
            .getOrDefault(false)
    }
    if (left.isEmpty()) pending.delete() else pending.writeText(left.joinToString("\n", postfix = "\n"))
}

/**
 * 영역 타일을 받아 캐시 MBTiles에 넣는다. 이미 있는 타일은 건너뛴다. 다 받았으면 true(하나라도 못 받았으면 false, 다음에 이어 받음).
 * 타일 하나 실패는 넘어가고, 연속 [AppConfig.Map.PREFETCH_MAX_FAILS]번 실패하면(인터넷 끊김) 예외로 멈춘다.
 */
private fun download(
    appFilesDir: File, urls: List<String>, name: String, s: Double, n: Double, w: Double, e: Double, maxZoom: Int, cache: String, format: String,
): Boolean {
    val file = File(appFilesDir, "maps/$cache").apply { parentFile?.mkdirs() }
    val db = SQLiteDatabase.openOrCreateDatabase(file, null)
    try {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))")
        // 영역을 더해 가므로 범위는 전 세계로 둔다(없는 타일은 비어 아래 온라인 배경이 보임)
        listOf("name" to "배경 미리 받기", "format" to format, "minzoom" to "${AppConfig.Map.PREFETCH_MIN_ZOOM}", "maxzoom" to "${AppConfig.Map.PREFETCH_MAX_ZOOM}", "bounds" to "-180,-85,180,85", "type" to "overlay")
            .forEach { (k, v) -> db.execSQL("INSERT OR REPLACE INTO metadata VALUES (?, ?)", arrayOf(k, v)) }
        var fetched = 0
        var bytes = 0L
        var failed = 0
        var inARow = 0
        for (z in AppConfig.Map.PREFETCH_MIN_ZOOM..maxZoom) {
            for (x in TileMath.tileX(w, z)..TileMath.tileX(e, z)) for (y in TileMath.tileY(n, z)..TileMath.tileY(s, z)) {
                val row = TileMath.tmsRow(y, z) // MBTiles는 TMS(아래부터)
                val have = db.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf("$z", "$x", "$row")).use { it.moveToFirst() }
                if (have) continue
                val url = TileMath.expandUrl(urls[(x + y) % urls.size], x, y, z)
                val data = runCatching { get(url) }.getOrElse {
                    failed++
                    if (++inARow >= AppConfig.Map.PREFETCH_MAX_FAILS) throw it
                    continue
                }
                inARow = 0
                db.execSQL("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", arrayOf(z, x, row, data))
                fetched++
                bytes += data.size
            }
        }
        OpLog.log("map", "prefetchDone", mapOf("name" to name, "cache" to cache, "fetched" to fetched, "bytes" to bytes, "failed" to failed))
        return failed == 0
    } finally {
        db.close()
    }
}

private fun get(url: String): ByteArray {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = AppConfig.Timeouts.HTTP_MS
    c.readTimeout = AppConfig.Timeouts.HTTP_MS
    try {
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        return c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}
