package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val home = GeoPoint(37.5, 127.0)

    @Test
    fun `위도 0_001도는 약 111m`() {
        assertEquals(111.2, LocalProjection.distanceM(home, GeoPoint(37.501, 127.0)), 0.1)
        assertEquals(88.2, LocalProjection.distanceM(home, GeoPoint(37.5, 127.001)), 0.2)
    }

    @Test
    fun `ENU 변환 왕복`() {
        val p = LocalProjection(home)
        val g = GeoPoint(37.51234, 127.00987)
        val back = p.toGeo(p.toEnu(g))
        assertEquals(g.lat, back.lat, 1e-9)
        assertEquals(g.lon, back.lon, 1e-9)
    }

    @Test
    fun `사각형과 오목 다각형 포함 판정`() {
        val rect = Polygon.rectangle(GeoPoint(37.0, 127.0), GeoPoint(38.0, 128.0))
        assertTrue(rect.contains(GeoPoint(37.5, 127.5)))
        assertFalse(rect.contains(GeoPoint(38.5, 127.5)))

        // ㄷ자 모양: 가운데 홈은 밖
        val u = Polygon(
            listOf(
                GeoPoint(0.0, 0.0), GeoPoint(0.0, 3.0), GeoPoint(3.0, 3.0), GeoPoint(3.0, 2.0),
                GeoPoint(1.0, 2.0), GeoPoint(1.0, 1.0), GeoPoint(3.0, 1.0), GeoPoint(3.0, 0.0),
            ),
        )
        assertTrue(u.contains(GeoPoint(0.5, 1.5)))
        assertTrue(u.contains(GeoPoint(2.0, 0.5)))
        assertFalse(u.contains(GeoPoint(2.0, 1.5)))
    }
}
