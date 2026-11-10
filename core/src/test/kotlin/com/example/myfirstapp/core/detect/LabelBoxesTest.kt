package com.example.myfirstapp.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LabelBoxesTest {
    @Test
    fun `새 라벨은 다음 번호, 옛 라벨 번호는 그대로`() {
        val first = LabelColors.assign(emptyMap(), listOf("COS", "LP", "SUS", "LA"))
        assertEquals(mapOf("COS" to 0, "LP" to 1, "SUS" to 2, "LA" to 3), first)
        // 새 모델이 순서를 바꾸고 라벨을 더해도 옛 번호는 그대로
        val second = LabelColors.assign(first, listOf("NEST", "LA", "COS", "NEST", ""))
        assertEquals(mapOf("COS" to 0, "LP" to 1, "SUS" to 2, "LA" to 3, "NEST" to 4), second)
        assertSame(second, LabelColors.assign(second, listOf("LP")))
    }

    @Test
    fun `YOLO 라벨 줄은 가운데·크기 비율, 모르는 라벨과 빈 상자는 뺀다`() {
        val classes = listOf("COS", "LP")
        val text = yoloLabelText(
            listOf(
                LabeledBox("LP", 0.1f, 0.2f, 0.2f, 0.4f),
                LabeledBox("NEST", 0.1f, 0.1f, 0.1f, 0.1f),
                LabeledBox("COS", 0.9f, 0.9f, 0.2f, 0.2f), // 사진 밖으로 나간 부분은 자른다
                LabeledBox("COS", 0.5f, 0.5f, 0f, 0.1f),
            ),
            classes,
        )
        assertEquals("1 0.200000 0.400000 0.200000 0.400000\n0 0.950000 0.950000 0.100000 0.100000\n", text)
        assertEquals("", yoloLabelText(emptyList(), classes))
        assertEquals("path: .\ntrain: train.txt\nval: train.txt\nnames:\n  0: COS\n  1: LP\n", yoloDataYaml(classes, "train.txt"))
    }
}
