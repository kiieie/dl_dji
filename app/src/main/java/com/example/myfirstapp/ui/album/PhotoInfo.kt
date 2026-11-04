// lint: 플랫폼 ExifInterface(API 24+)로 DJI JPEG의 시각·GPS를 읽는 데 충분하다. 몇 줄 때문에 androidx.exifinterface 의존성을 더하지 않는다
@file:SuppressLint("ExifInterface")

package com.example.myfirstapp.ui.album

import android.annotation.SuppressLint
import android.media.ExifInterface
import com.example.myfirstapp.core.executor.shutterLinkTarget
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.exifTimeMs
import com.example.myfirstapp.core.model.lensOfFileName
import com.example.myfirstapp.core.model.poleDisplayName
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.data.PoleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 뷰어 ⓘ 정보(10.4.9, Pilot 2 앨범 뷰어): 촬영 시각(머리글)과 (이름, 값) 줄. */
data class PhotoInfo(val file: File, val takenAtMs: Long, val rows: List<Pair<String, String>>)

/** Pilot 2 뷰어 머리글 "2026. 11. 3. 오후 1:49:04". */
internal fun viewerTime(ms: Long): String = SimpleDateFormat("yyyy. M. d. a h:mm:ss", Locale.KOREA).format(Date(ms))

internal fun lensTitle(lens: Lens?) = when (lens) {
    Lens.WIDE -> "광각"
    Lens.ZOOM -> "망원"
    Lens.THERMAL -> "열화상"
    null -> "모름"
}

/** 앱이 남긴 수동 사진 이름 끝 렌즈(`manual_<시각>_<번호>_ZOOM.jpg`), 아니면 DJI 파일 글자(`_W`·`_Z`·`_T`). */
internal fun lensOfAppFile(name: String): Lens? = Lens.entries.firstOrNull { name.substringBeforeLast('.').endsWith("_${it.name}") } ?: lensOfFileName(name)

/**
 * 파일 사진 정보: EXIF(DJI 원본) 촬영 시각·위치·해발 고도, 렌즈, 20m 안 가까운 전주([poles], 셔터 사진 붙이기와 같은 거리), 파일 이름·크기.
 * EXIF가 없으면 시각은 파일 시각, 위치는 "없음".
 */
internal suspend fun photoInfo(f: File, poles: List<PoleEntity>): PhotoInfo = withContext(Dispatchers.IO) {
    val exif = runCatching { ExifInterface(f.path) }.getOrNull()
    val at = exifTimeMs(exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)) ?: f.lastModified()
    val ll = FloatArray(2)
    // 문자열 좌표를 도로 바꿔 주는 옛 함수(API 24부터 있음). 새 함수는 API 29부터
    @Suppress("DEPRECATION")
    val pos = if (exif?.getLatLong(ll) == true) GeoPoint(ll[0].toDouble(), ll[1].toDouble()) else null
    val alt = exif?.getAltitude(Double.NaN)?.takeIf { !it.isNaN() }
    val pole = shutterLinkTarget(pos, poles) { GeoPoint(it.lat, it.lon) }
    PhotoInfo(
        f, at,
        listOf(
            "렌즈" to lensTitle(lensOfAppFile(f.name)),
            "위치" to (pos?.let { "%.6f, %.6f".format(Locale.ROOT, it.lat, it.lon) } ?: "없음"),
            "해발 고도" to (alt?.let { "%.1f m".format(Locale.ROOT, it) } ?: "--"),
            "전주" to (pole?.let { p -> "${poleDisplayName(p.poleId, p.lineName, p.lineNo)} (%.0f m)".format(Locale.ROOT, LocalProjection.distanceM(pos!!, GeoPoint(p.lat, p.lon))) } ?: "20m 안 전주 없음"),
            "파일" to f.name,
            "크기" to "%.1f MB".format(Locale.ROOT, f.length() / 1_048_576.0),
        ),
    )
}
