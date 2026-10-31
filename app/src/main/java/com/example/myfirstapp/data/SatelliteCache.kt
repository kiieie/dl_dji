package com.example.myfirstapp.data

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.myfirstapp.core.model.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

/** 미리 받은 위성 타일을 모으는 MBTiles(앱 폴더 `maps/`). 다른 MBTiles처럼 계획·비행 지도에 겹쳐 그려진다. */
const val SATELLITE_CACHE_NAME = "satellite-cache.mbtiles"

/** 받기가 끝나지 않은 영역(한 줄에 "이름 남 북 서 동 최대확대"). 앱을 다시 켜면 이어 받는다. */
private const val PENDING_NAME = "satellite-pending.txt"

/** 연속으로 이만큼 못 받으면 인터넷이 끊긴 것으로 보고 멈춘다. */
private const val MAX_FAILS = 5

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val lock = Mutex()

/**
 * 계획을 저장할 때 미션 영역의 위성 타일을 미리 받는다(7.4, 2026-10-31 사용자 결정). 현장은 인터넷이 없으므로
 * 사무실에서 저장하는 순간 받아 `maps/satellite-cache.mbtiles`에 넣는다. 이미 있는 타일은 다시 받지 않는다.
 * 인터넷이 없거나 타일 주소가 아직 없으면 남겨 두었다가 [resumeSatellitePrefetch]가 이어 받는다.
 * (MapLibre 오프라인 영역은 `file://` 스타일을 읽지 못해 쓰지 않는다 — 2026-10-31 RC 확인.)
 */
fun prefetchSatellite(appFilesDir: File, points: List<GeoPoint>, name: String) {
    if (points.isEmpty()) return
    val s = points.minOf { it.lat } - PAD_DEG
    val n = points.maxOf { it.lat } + PAD_DEG
    val w = points.minOf { it.lon } - PAD_DEG
    val e = points.maxOf { it.lon } + PAD_DEG
    var maxZoom = MAX_ZOOM
    while (maxZoom > MIN_ZOOM && tileCount(s, n, w, e, MIN_ZOOM, maxZoom) > MAX_TILES) maxZoom--
    scope.launch {
        lock.withLock { File(appFilesDir, PENDING_NAME).appendText("$name $s $n $w $e $maxZoom\n") }
        OpLog.log("map", "prefetch", mapOf("name" to name, "maxZoom" to maxZoom, "tiles" to tileCount(s, n, w, e, MIN_ZOOM, maxZoom)))
        resume(appFilesDir)
    }
}

/** 끝나지 않은 미리 받기를 이어 받는다(앱을 끄거나 인터넷이 끊겨 멈춘 것). 지도를 열 때 부른다. */
fun resumeSatellitePrefetch(appFilesDir: File) {
    scope.launch { resume(appFilesDir) }
}

private suspend fun resume(appFilesDir: File) = lock.withLock {
    val urls = savedSatelliteTiles()
    val pending = File(appFilesDir, PENDING_NAME)
    if (urls.isEmpty() || !pending.isFile) return@withLock
    val areas = pending.readLines().filter { it.isNotBlank() }
    val left = areas.filterNot { line ->
        val p = line.split(" ")
        runCatching { download(appFilesDir, urls, p[0], p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toInt()) }
            .onFailure { Log.w("SatelliteCache", "미리 받기 멈춤(${p[0]}): ${it.message}") }
            .getOrDefault(false)
    }
    if (left.isEmpty()) pending.delete() else pending.writeText(left.joinToString("\n", postfix = "\n"))
}

/**
 * 영역 타일을 받아 MBTiles에 넣는다. 이미 있는 타일은 건너뛴다. 다 받았으면 true(하나라도 못 받았으면 false, 다음에 이어 받음).
 * 타일 하나 실패는 넘어가고, 연속 [MAX_FAILS]번 실패하면(인터넷 끊김) 예외로 멈춘다.
 */
private fun download(appFilesDir: File, urls: List<String>, name: String, s: Double, n: Double, w: Double, e: Double, maxZoom: Int): Boolean {
    val file = File(appFilesDir, "maps/$SATELLITE_CACHE_NAME").apply { parentFile?.mkdirs() }
    val db = SQLiteDatabase.openOrCreateDatabase(file, null)
    try {
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))")
        // 영역을 더해 가므로 범위는 전 세계로 둔다(없는 타일은 비어 아래 온라인 배경이 보임)
        listOf("name" to "위성 미리 받기", "format" to "jpg", "minzoom" to "$MIN_ZOOM", "maxzoom" to "$MAX_ZOOM", "bounds" to "-180,-85,180,85", "type" to "overlay")
            .forEach { (k, v) -> db.execSQL("INSERT OR REPLACE INTO metadata VALUES (?, ?)", arrayOf(k, v)) }
        var fetched = 0
        var bytes = 0L
        var failed = 0
        var inARow = 0
        for (z in MIN_ZOOM..maxZoom) {
            val nz = 1 shl z
            for (x in tileX(w, nz)..tileX(e, nz)) for (y in tileY(n, nz)..tileY(s, nz)) {
                val row = nz - 1 - y // MBTiles는 TMS(아래부터)
                val have = db.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf("$z", "$x", "$row")).use { it.moveToFirst() }
                if (have) continue
                val url = urls[(x + y) % urls.size].replace("{quadkey}", quadkey(x, y, z))
                val data = runCatching { get(url) }.getOrElse {
                    failed++
                    if (++inARow >= MAX_FAILS) throw it
                    continue
                }
                inARow = 0
                db.execSQL("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", arrayOf(z, x, row, data))
                fetched++
                bytes += data.size
            }
        }
        OpLog.log("map", "prefetchDone", mapOf("name" to name, "fetched" to fetched, "bytes" to bytes, "failed" to failed))
        return failed == 0
    } finally {
        db.close()
    }
}

private fun get(url: String): ByteArray {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 10_000
    try {
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        return c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}

/** Bing quadkey(타일 x, y, 확대 z). */
internal fun quadkey(x: Int, y: Int, z: Int): String = buildString {
    for (i in z downTo 1) {
        val mask = 1 shl (i - 1)
        append((if (x and mask != 0) 1 else 0) + (if (y and mask != 0) 2 else 0))
    }
}

private fun tileX(lon: Double, n: Int) = ((lon + 180) / 360 * n).toInt()
private fun tileY(lat: Double, n: Int) = ((1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2 * n).toInt()

/** 웹 메르카토르 타일 수(확대 [minZ]~[maxZ]). */
internal fun tileCount(south: Double, north: Double, west: Double, east: Double, minZ: Int, maxZ: Int): Long = (minZ..maxZ).sumOf { z ->
    val n = 1 shl z
    (tileX(east, n) - tileX(west, n) + 1).toLong() * (tileY(south, n) - tileY(north, n) + 1)
}
