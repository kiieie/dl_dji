package com.example.myfirstapp.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.route.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 경로 그림 지도 바탕(10.4.10): 미리 받은 배경 타일(`maps/`의 MBTiles, 지금 고른 배경)을 경로 범위에 맞춰 한 장으로 합친다.
 * **인터넷은 쓰지 않는다.** 만든 그림은 [dir]에 JPEG로 두어 목록을 다시 열 때 읽기만 한다. 덮는 타일이 없으면 null(화면은 지도 색).
 * 경로 선·점은 화면이 같은 [TileMath.ThumbView]로 그 위에 그린다.
 * ponytail: 메모리 캐시 없음 — 목록을 스크롤해 카드가 다시 보이면 JPEG를 다시 읽는다(수 ms, 잠깐 지도 색). 깜빡임이 거슬리면 LruCache를 붙인다.
 */
class MapThumbs(private val mapsDir: File, private val dir: File, private val settings: SettingsStore, private val fill: Int) {
    private val io = Dispatchers.IO.limitedParallelism(AppConfig.Map.THUMB_PARALLEL)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 미션 [mission]의 바탕([w]×[h] 픽셀, 둘레 [pad] 픽셀). [points]는 이륙 지점과 전주(경로 그림과 같은 점). 배경을 바꾸면 다시 낸다. */
    fun flow(mission: Long, points: List<GeoPoint>, w: Int, h: Int, pad: Int): Flow<Bitmap?> {
        val view = TileMath.ThumbView.fit(points, w, h, pad, AppConfig.Map.THUMB_MIN_SPAN_M)
        if (view == null || w <= 0 || h <= 0) return flowOf(null)
        return settings.mapSource.distinctUntilChanged().map { source ->
            withContext(io) {
                runCatching { load("m$mission-${w}x$h-p$pad-", view, w, h, source) }
                    .onFailure { OpLog.log("map", "thumbFail", mapOf("mission" to mission, "error" to "$it")) }
                    .getOrNull()
            }
        }
    }

    /** 같은 배경·범위·캐시 상태로 만든 파일이 있으면 읽고, 없으면 합쳐 저장한다(같은 [prefix]의 옛 파일은 지움). */
    private fun load(prefix: String, view: TileMath.ThumbView, w: Int, h: Int, source: MapSource): Bitmap? {
        val files = baseMapFiles(listMbtiles(mapsDir), source.cacheName)
        if (files.isEmpty()) return null
        // 받기가 끝나면 캐시 파일 크기·시각이 바뀌어 새 이름이 된다
        val tag = listOf(RENDER_VERSION, source.name, view, files.map { "${it.name}:${it.length()}:${it.lastModified()}" }).hashCode()
        val out = File(dir, "$prefix${Integer.toHexString(tag)}.jpg")
        if (out.isFile) BitmapFactory.decodeFile(out.path)?.let { return it }
        val bmp = render(files, view, w, h) ?: return null
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
        val part = File(dir, out.name + ".part")
        part.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, AppConfig.Map.THUMB_JPEG_QUALITY, it) }
        part.renameTo(out)
        return bmp
    }

    /**
     * 확대 차례([TileMath.ThumbView.zoomOrder])대로 해 보고 타일이 하나라도 그려진 첫 확대의 그림. 칸마다 그 확대 타일이 없으면(미리 받은 영역 밖)
     * [AppConfig.Map.THUMB_ANCESTOR_STEPS]단계 아래까지 조상 타일의 그 칸을 늘려 그린다([TileMath.fallbacks], 11-06 RC: 카드 왼쪽이 빈 색).
     * 같은 확대면 이름 순서로 뒤 MBTiles가 이긴다(지도 화면처럼 위에 겹침).
     */
    private fun render(files: List<File>, view: TileMath.ThumbView, w: Int, h: Int): Bitmap? {
        // 받는 중이라 잠겼거나 MBTiles가 아니면 그 파일만 건너뛴다
        val dbs = files.mapNotNull { f -> runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() }.asReversed()
        try {
            val zooms = view.zoomOrder(w, h, AppConfig.Map.THUMB_ZOOM_STEPS, AppConfig.Map.PREFETCH_MAX_ZOOM, AppConfig.Map.THUMB_MAX_TILES)
            for (z in zooms) {
                val bmp = createBitmap(w, h)
                val canvas = Canvas(bmp).apply { drawColor(fill) }
                val (xs, ys) = view.tiles(z, w, h)
                val size = view.tileSize(z).toFloat()
                var drawn = 0
                for (x in xs) for (y in ys) {
                    val (c, data) = TileMath.fallbacks(x, y, z, z - AppConfig.Map.THUMB_ANCESTOR_STEPS)
                        .firstNotNullOfOrNull { c -> dbs.firstNotNullOfOrNull { tile(it, c) }?.let { c to it } } ?: continue
                    val tile = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue // 벡터(pbf) 등
                    val src = Rect(
                        (c.left * tile.width).toInt(), (c.top * tile.height).toInt(),
                        ((c.left + c.size) * tile.width).toInt(), ((c.top + c.size) * tile.height).toInt(),
                    )
                    val l = view.tileLeft(x, z).toFloat()
                    val t = view.tileTop(y, z).toFloat()
                    canvas.drawBitmap(tile, src, RectF(l, t, l + size, t + size), paint)
                    tile.recycle()
                    drawn++
                }
                if (drawn > 0) return bmp
                bmp.recycle()
            }
            return null
        } finally {
            dbs.forEach { it.close() }
        }
    }

    /** MBTiles [db]의 타일 [c] 그림 바이트. 없거나 읽지 못하면 null. */
    private fun tile(db: SQLiteDatabase, c: TileMath.TileCrop): ByteArray? = runCatching {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf("${c.z}", "${c.x}", "${TileMath.tmsRow(c.y, c.z)}"),
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }
    }.getOrNull()

    private companion object {
        /** 그리는 방식이 바뀌면 올린다: 캐시 JPEG 이름이 바뀌어 옛 그림을 다시 만든다(2: 조상 타일로 빈칸 메우기). */
        const val RENDER_VERSION = 2
    }
}
