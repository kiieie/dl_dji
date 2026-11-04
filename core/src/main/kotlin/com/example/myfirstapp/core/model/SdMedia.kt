package com.example.myfirstapp.core.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 앨범 기체 앨범 탭(10.4.9, Pilot 2 앨범 [기체 앨범 | 다운로드 완료]): 기체 SD 사진 목록의 묶음·글자. SDK 값을 바꾼 뒤의 판단만 둔다.

/** 기체 SD 사진 한 장. [index]는 SD 파일 번호(같은 촬영의 렌즈별 파일은 번호가 같고 [name]이 다름), [takenAtMs]는 카메라 시계. */
data class SdPhoto(val index: Int, val name: String, val takenAtMs: Long, val sizeBytes: Long) {
    val lens: Lens? get() = lensOfFileName(name)
}

/** DJI 파일 이름 끝 렌즈 글자: `_W` 광각, `_Z` 망원, `_T` 열화상. 없으면(`_V` 가시광 등) null. */
fun lensOfFileName(name: String): Lens? = when (Regex("_([WZT])(?=[_.])", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.uppercase()) {
    "W" -> Lens.WIDE
    "Z" -> Lens.ZOOM
    "T" -> Lens.THERMAL
    else -> null
}

/**
 * Pilot 2 날짜 묶음과 정렬(신형-구형 / 구형-신형): 날짜 열쇠(`yyyyMMdd`, [zone]) → 그날 사진. [newestFirst]면 날짜·사진 모두 최신 먼저,
 * 같은 시각은 파일 번호·이름 순서로 고정한다.
 */
fun sdDays(photos: List<SdPhoto>, newestFirst: Boolean, zone: TimeZone = TimeZone.getDefault()): List<Pair<String, List<SdPhoto>>> {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = zone }
    val order = compareBy<SdPhoto>({ it.takenAtMs }, { it.index }, { it.name })
    val sorted = photos.sortedWith(if (newestFirst) order.reversed() else order)
    return sorted.groupBy { fmt.format(Date(it.takenAtMs)) }.toList()
}

/** 저장소 머리글(Pilot 2 "사용됨 26.6GB / 238.0GB"). 전체나 남은 값을 모르면 null. */
fun storageUsedLabel(totalMb: Long?, freeMb: Long?): String? {
    if (totalMb == null || freeMb == null || totalMb <= 0) return null
    fun gb(mb: Long) = String.format(Locale.ROOT, "%.1fGB", mb / 1024.0)
    return "사용됨 ${gb((totalMb - freeMb).coerceAtLeast(0))} / ${gb(totalMb)}"
}

/** EXIF 촬영 시각 `2026:11:03 13:49:04`(카메라 시계, [zone]) → ms. 못 읽으면 null. */
fun exifTimeMs(text: String?, zone: TimeZone = TimeZone.getDefault()): Long? = text?.trim()?.takeIf { it.length >= 19 }?.let {
    runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply { timeZone = zone; isLenient = false }.parse(it.take(19))?.time }.getOrNull()
}
