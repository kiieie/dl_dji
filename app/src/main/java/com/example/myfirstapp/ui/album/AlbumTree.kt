package com.example.myfirstapp.ui.album

import com.example.myfirstapp.core.route.LineNumbers
import com.example.myfirstapp.data.AlbumPhoto
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 앨범 선로별 보기(10.4.3, Finder식) 나무 한 칸: 선로(깊이 0) → 전주(1) → 촬영 날짜(2), 맨 아래 "기타"(0) → 날짜(1).
 * [photos]·[files]는 이 칸 아래 모든 사진이다(전주에 붙은 것은 DB, 붙지 않은 수동 사진은 `manual/<날짜>/` 파일).
 */
data class TreeNode(
    val key: String,
    val title: String,
    val depth: Int,
    val children: List<TreeNode> = emptyList(),
    val photos: List<AlbumPhoto> = emptyList(),
    val files: List<File> = emptyList(),
) {
    val count get() = photos.size + files.size
}

const val NO_LINE = "선로 없음"
const val OTHER = "기타"

/**
 * 사진을 선로명(없으면 [NO_LINE], 맨 뒤) → 전주(선로번호 자연 순서, 번호 없으면 뒤에 전산화번호 순) → 날짜(최신 먼저)로 묶는다.
 * 같은 전산화번호는 프로젝트가 달라도 한 전주로 본다. 수동 하루치([manualDays])는 맨 아래 [OTHER] 밑에 날짜별로 둔다.
 */
fun lineTree(photos: List<AlbumPhoto>, manualDays: List<AlbumGroup.ManualDay>, zone: TimeZone = TimeZone.getDefault()): List<TreeNode> {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = zone }
    val lines = photos.groupBy { it.lineName?.takeIf(String::isNotBlank) }.entries
        .sortedWith(compareBy(nullsLast(LineNumbers.ORDER)) { it.key })
        .map { (line, linePhotos) ->
            val lineKey = "L|${line.orEmpty()}"
            val poles = linePhotos.groupBy { it.poleId }.values
                .sortedWith(compareBy(nullsLast(LineNumbers.ORDER)) { ps: List<AlbumPhoto> -> ps.first().lineNo?.takeIf(String::isNotBlank) }.thenBy { it.first().poleId })
                .map { ps ->
                    val p = ps.first()
                    val poleKey = "$lineKey|${p.poleId}"
                    val days = ps.groupBy { fmt.format(Date(it.capture.takenAt)) }.entries.sortedByDescending { it.key }
                        .map { (day, dayPhotos) -> TreeNode("$poleKey|$day", dayTitle(day), 2, photos = dayPhotos.sortedWith(compareByDescending<AlbumPhoto> { it.capture.takenAt }.thenByDescending { it.capture.id })) }
                    TreeNode(poleKey, p.lineNo?.takeIf(String::isNotBlank) ?: p.poleId, 1, days, photos = days.flatMap { it.photos })
                }
            TreeNode(lineKey, line ?: NO_LINE, 0, poles, photos = poles.flatMap { it.photos })
        }
    val other = manualDays.sortedByDescending { it.day }.map { TreeNode("E|${it.day}", dayTitle(it.day), 1, files = it.files) }
    return if (other.isEmpty()) lines else lines + TreeNode("E", OTHER, 0, other, files = other.flatMap { it.files })
}

/** 펼친 칸([expanded])의 아이들까지 위에서부터 한 줄로 편다(왼쪽 목록에 보이는 줄, 5D ↕ 순서). */
fun visibleRows(tree: List<TreeNode>, expanded: Set<String>): List<TreeNode> =
    tree.flatMap { n -> listOf(n) + if (n.key in expanded) visibleRows(n.children, expanded) else emptyList() }

/** 맨 위에서 [key] 칸까지의 칸들(오른쪽 머리글 `대평선 › 137L28R13 › 2026-11-03`). 없으면 빈 목록. */
fun nodePath(tree: List<TreeNode>, key: String?): List<TreeNode> = tree.firstNotNullOfOrNull { n ->
    if (n.key == key) listOf(n) else nodePath(n.children, key).takeIf { it.isNotEmpty() }?.let { listOf(n) + it }
}.orEmpty()

/** `20261103` → `2026-11-03`. */
fun dayTitle(day: String) = if (day.length == 8) "${day.substring(0, 4)}-${day.substring(4, 6)}-${day.substring(6)}" else day
