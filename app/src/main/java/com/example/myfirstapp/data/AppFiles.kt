package com.example.myfirstapp.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.myfirstapp.detect.TfliteYoloDetector
import org.json.JSONObject
import java.io.File

// 앱 폴더(4.3)의 지도(maps/)와 탐지 모델(models/) 파일 관리. 계획 화면과 ⑥ 설정이 같이 쓴다.

fun listMbtiles(dir: File) = dir.listFiles { f -> f.extension == "mbtiles" }.orEmpty().sortedBy { it.name }

/** SAF로 고른 MBTiles를 앱 전용 폴더(4.3 `maps/`)에 복사한다. SQLite 파일인지 머리글로 확인한다. */
fun copyMbtiles(resolver: ContentResolver, uri: Uri, mapsDir: File): String {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    } ?: "map.mbtiles"
    require(name.endsWith(".mbtiles", ignoreCase = true)) { ".mbtiles 파일만 추가할 수 있습니다" }
    // 파일 이름은 스타일 JSON에 들어가므로 글자·숫자·`.`·`-`·`_`만 남긴다.
    val target = File(mapsDir, safeName(name))
    val part = File(mapsDir, target.name + ".part")
    resolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it) } } ?: error("파일을 열 수 없습니다")
    val header = part.inputStream().use { String(it.readNBytesCompat(15), Charsets.US_ASCII) }
    if (header != "SQLite format 3") {
        part.delete()
        error("MBTiles(SQLite) 형식이 아닙니다")
    }
    check(part.renameTo(target)) { "파일 이름을 바꾸지 못했습니다" }
    return target.name
}

/** 모델 폴더 한 개의 검사 결과. [error]가 있으면 규격 검사에 실패한 것이다. */
data class ModelInfo(val dir: String, val version: String?, val labels: Int, val error: String?)

/** `models/` 아래 폴더를 이름 역순(사용 우선순위, 9.1)으로 검사한다. 인터프리터를 열어 보므로 IO 스레드에서 부른다. */
fun inspectModels(root: File): List<ModelInfo> =
    root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedByDescending { it.name }.map { dir ->
        val labels = File(dir, "labels.txt").takeIf { it.exists() }?.readLines()?.count { it.isNotBlank() } ?: 0
        runCatching { TfliteYoloDetector.load(dir).use { it.modelVersion } }
            .fold({ ModelInfo(dir.name, it, labels, null) }, { ModelInfo(dir.name, null, labels, it.message ?: it::class.simpleName) })
    }

private val MODEL_FILES = listOf("model.tflite", "labels.txt", "model.json")

/**
 * SAF로 고른 폴더(F-18)에서 모델 파일 세 개(9.1)를 임시 폴더로 복사하고 규격 검사를 통과하면 `models/<version>/`으로 옮긴다.
 * 돌려주는 값은 새 폴더 이름이다.
 */
fun importModelFolder(resolver: ContentResolver, treeUri: Uri, root: File): String {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    val found = mutableMapOf<String, Uri>()
    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        ?.use { c -> while (c.moveToNext()) found[c.getString(0)] = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(1)) }
    val missing = MODEL_FILES - found.keys
    require(missing.isEmpty()) { "폴더에 ${missing.joinToString()} 파일이 없습니다" }

    val tmp = File(root, ".import").apply { deleteRecursively(); mkdirs() }
    try {
        MODEL_FILES.forEach { name ->
            resolver.openInputStream(found.getValue(name))?.use { input -> File(tmp, name).outputStream().use { input.copyTo(it) } }
                ?: error("$name 파일을 열 수 없습니다")
        }
        val version = JSONObject(File(tmp, "model.json").readText()).getString("version")
        TfliteYoloDetector.load(tmp).close() // 규격이 맞지 않으면 이유를 담은 예외
        val target = File(root, safeName(version))
        require(!target.exists()) { "이미 있는 모델입니다: ${target.name}" }
        check(tmp.renameTo(target)) { "모델 폴더를 옮기지 못했습니다" }
        return target.name
    } finally {
        tmp.deleteRecursively()
    }
}

private fun safeName(name: String) = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(buf, read, n - read)
        if (r < 0) break
        read += r
    }
    return buf.copyOf(read)
}
