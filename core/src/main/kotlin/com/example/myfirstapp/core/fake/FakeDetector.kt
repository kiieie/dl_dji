package com.example.myfirstapp.core.fake

import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.port.Detector

/**
 * 모의 탐지기(10장 ⑤). 사진 [every]장마다 1장에 "모의 이상" 박스를 하나 낸다.
 * 탐지 모델 없이 결과 화면과 이상 필터를 확인하는 데 쓴다.
 */
class FakeDetector(private val every: Int = 5) : Detector {
    override val modelVersion = "fake-1"
    private var count = 0

    override suspend fun detect(image: ByteArray): List<Detection> =
        if (++count % every == 0) listOf(Detection("모의 이상", 0.9f, 0.35f, 0.3f, 0.3f, 0.4f, alert = true)) else emptyList()
}
