package com.example.myfirstapp

import com.example.myfirstapp.core.detect.ModelChoice
import com.example.myfirstapp.detect.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** model.json 새 필드(9.1 role·displayNames)와 옛 모델 호환. */
class ModelSpecTest {
    @Test
    fun `설비 모델 model_json의 역할·화면 이름·빈 경고 라벨을 읽는다`() {
        val s = ModelSpec.parse(
            """{"version":"facility-v1-coco-640-fp16","role":"live","input":{"width":640,"height":640,"format":"RGB","normalize":"0-1"},
               "output":{"boxes":"pixel"},"thresholds":{"score":0.25,"iou":0.45},"alertLabels":[],
               "displayNames":{"COS":"컷아웃 스위치","LP":"라인포스트 애자","SUS":"현수 애자","LA":"피뢰기"}}""",
        )
        assertEquals("live", s.role)
        assertEquals(640, s.inputSize)
        assertEquals("라인포스트 애자", s.displayNames["LP"])
        assertEquals(4, s.displayNames.size)
        assertTrue(s.alertLabels.isEmpty()) // 설비는 표시만, 검출로 세지 않음
        assertTrue(s.pixelBoxes)
        assertEquals(0.25f, s.score)
        assertEquals(ModelChoice("dir", "live", 640), s.choice("dir"))
    }

    @Test
    fun `새 필드가 없는 옛 모델도 읽는다`() {
        val s = ModelSpec.parse("""{"version":"coco","input":{"width":640,"height":640},"alertLabels":["person"]}""")
        assertNull(s.role)
        assertTrue(s.displayNames.isEmpty())
        assertEquals(setOf("person"), s.alertLabels)
        assertFalse(s.pixelBoxes)
        assertEquals(AppConfig.Detector.SCORE.toFloat(), s.score)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `정사각이 아니면 거부한다`() {
        ModelSpec.parse("""{"version":"x","input":{"width":640,"height":480}}""")
    }
}
