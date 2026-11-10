package com.example.myfirstapp.core.detect

import java.util.Locale

/**
 * 라벨별 상자 색 번호(9.8, 2026-11-09 사용자 결정). 색만 보고 라벨을 알 수 있게 라벨마다 팔레트 번호 하나를 준다.
 * 새 라벨은 지금까지 준 번호 다음 번호를 받고, 한 번 준 번호는 바꾸지 않는다(모델을 바꾸거나 라벨이 늘어도 옛 라벨 색 그대로).
 */
object LabelColors {
    /** [known]에 없는 [labels]를 나온 순서대로 다음 번호로 더한 것. 바뀐 것이 없으면 [known] 그대로. */
    fun assign(known: Map<String, Int>, labels: Iterable<String>): Map<String, Int> {
        var next = (known.values.maxOrNull() ?: -1) + 1
        val add = linkedMapOf<String, Int>()
        labels.forEach { l -> if (l.isNotBlank() && l !in known && l !in add) add[l] = next++ }
        return if (add.isEmpty()) known else known + add
    }
}

/** 사진 한 장의 상자(0~1 비율, 왼쪽 위 기준). */
data class LabeledBox(val label: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * 재학습 데이터(9.8): YOLO 라벨 파일 내용. 줄마다 `클래스번호 cx cy w h`(0~1). [classes]에 없는 라벨은 뺀다.
 * 상자가 없으면 빈 문자열(설비 없는 사진 = 배경 예시로 학습에 쓴다).
 */
fun yoloLabelText(boxes: List<LabeledBox>, classes: List<String>): String = boxes.mapNotNull { b ->
    val cls = classes.indexOf(b.label).takeIf { it >= 0 } ?: return@mapNotNull null
    val x0 = b.x.coerceIn(0f, 1f)
    val y0 = b.y.coerceIn(0f, 1f)
    val x1 = (b.x + b.w).coerceIn(0f, 1f)
    val y1 = (b.y + b.h).coerceIn(0f, 1f)
    if (x1 <= x0 || y1 <= y0) return@mapNotNull null
    String.format(Locale.ROOT, "%d %.6f %.6f %.6f %.6f", cls, (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
}.joinToString("\n", postfix = "\n").let { if (it == "\n") "" else it }

/** YOLO 학습 설정(`data.yaml`): [list]는 학습 사진 목록 파일(같은 폴더 기준). 검증도 같은 목록(현장 데이터는 PC에서 다시 나눈다). */
fun yoloDataYaml(classes: List<String>, list: String): String = buildString {
    append("path: .\ntrain: $list\nval: $list\nnames:\n")
    classes.forEachIndexed { i, c -> append("  $i: $c\n") }
}
