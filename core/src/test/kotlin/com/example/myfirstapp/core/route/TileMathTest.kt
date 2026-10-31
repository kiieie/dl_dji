package com.example.myfirstapp.core.route

import org.junit.Assert.assertEquals
import org.junit.Test

class TileMathTest {

    @Test
    fun `Bing quadkey는 문서 예제와 같다`() {
        assertEquals("213", TileMath.quadkey(3, 5, 3))
    }

    @Test
    fun `주소 틀을 채운다(VWorld WMTS는 z, y, x 순)`() {
        assertEquals("https://t/a213.jpeg", TileMath.expandUrl("https://t/a{quadkey}.jpeg", 3, 5, 3))
        assertEquals("https://api.vworld.kr/req/wmts/1.0.0/K/Satellite/16/25867/55856.jpeg",
            TileMath.expandUrl("https://api.vworld.kr/req/wmts/1.0.0/K/Satellite/{z}/{y}/{x}.jpeg", 55856, 25867, 16))
    }

    @Test
    fun `대평선 부근 z16 타일 번호와 영역 타일 수`() {
        // 웹 메르카토르 식으로 따로 계산한 값(36.48, 127.33)
        assertEquals(55947, TileMath.tileX(127.33, 16))
        assertEquals(25626, TileMath.tileY(36.48, 16))
        assertEquals(1L, TileMath.count(36.48, 36.48, 127.33, 127.33, 16, 16))
        assertEquals(2L, TileMath.count(36.4801, 36.4801, 127.3301, 127.3301, 15, 16)) // 한 점은 확대마다 1장
    }
}
