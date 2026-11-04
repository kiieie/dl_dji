package com.example.myfirstapp.detect

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.detect.ModelChoice
import org.json.JSONObject
import java.io.File

/**
 * `model.json`(9.1)을 읽은 값. `role`(`live`/`full`)·`displayNames`는 없어도 된다(옛 모델). 입력은 정사각 RGB 0~1만 받는다.
 * 인터프리터를 열지 않으므로 싸다(모델 고르기·판독 화면 라벨 이름에 쓴다).
 */
data class ModelSpec(
    val version: String,
    val inputSize: Int,
    val role: String?,
    /** 라벨 → 화면 이름(예: LP → 라인포스트 애자). 없는 라벨은 라벨 그대로 보인다. */
    val displayNames: Map<String, String>,
    val alertLabels: Set<String>,
    val pixelBoxes: Boolean,
    val score: Float,
    val iou: Float,
) {
    fun choice(dir: String) = ModelChoice(dir, role, inputSize)

    companion object {
        fun parse(json: String): ModelSpec {
            val o = JSONObject(json)
            val inp = o.getJSONObject("input")
            val size = inp.getInt("width")
            require(size == inp.getInt("height")) { "정사각 입력만 지원합니다" }
            require(inp.optString("format", "RGB") == "RGB" && inp.optString("normalize", "0-1") == "0-1") { "입력 형식은 RGB 0-1이어야 합니다" }
            val th = o.optJSONObject("thresholds") ?: JSONObject()
            val names = o.optJSONObject("displayNames")
            return ModelSpec(
                version = o.getString("version"),
                inputSize = size,
                role = o.optString("role").ifBlank { null },
                displayNames = names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),
                alertLabels = o.optJSONArray("alertLabels")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                pixelBoxes = o.optJSONObject("output")?.optString("boxes") == "pixel",
                score = th.optDouble("score", AppConfig.Detector.SCORE).toFloat(),
                iou = th.optDouble("iou", AppConfig.Detector.IOU).toFloat(),
            )
        }

        fun read(dir: File) = parse(File(dir, "model.json").readText())
    }
}

/** `models/` 아래 model.json을 읽을 수 있는 폴더(폴더 이름 → 값). 읽지 못한 폴더는 뺀다. IO 스레드에서 부른다. */
fun readModelSpecs(root: File): Map<String, ModelSpec> =
    root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        .mapNotNull { d -> runCatching { d.name to ModelSpec.read(d) }.getOrNull() }.toMap()

/** 모든 모델의 라벨 화면 이름을 합친 것(판독·앨범 상자 이름). */
fun labelNames(root: File): Map<String, String> = readModelSpecs(root).values.fold(emptyMap()) { acc, s -> acc + s.displayNames }
