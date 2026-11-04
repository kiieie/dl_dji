package com.example.myfirstapp

import com.example.myfirstapp.core.model.GeoCategory
import com.example.myfirstapp.ui.album.lensOfAppFile
import com.example.myfirstapp.ui.geoZoneColor
import com.example.myfirstapp.core.model.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GEO 구역 지도 색(10.4.9)과 앨범 뷰어 렌즈 글자. */
class GeoZoneColorTest {
    @Test
    fun `GEO 구역 종류마다 DJI 지도 색`() {
        assertEquals("#DE4329", geoZoneColor(GeoCategory.RESTRICTED))
        assertEquals("#1088F2", geoZoneColor(GeoCategory.AUTHORIZATION))
        assertEquals("#EE8815", geoZoneColor(GeoCategory.ENHANCED_WARNING))
        assertEquals("#FFCC00", geoZoneColor(GeoCategory.WARNING))
        assertEquals("#979797", geoZoneColor(GeoCategory.ALTITUDE))
        // 모르는 종류는 고도 제한과 같은 회색, 종류마다 색이 하나씩
        assertEquals(geoZoneColor(GeoCategory.ALTITUDE), geoZoneColor(GeoCategory.UNKNOWN))
        assertEquals(GeoCategory.entries.size - 1, GeoCategory.entries.map(::geoZoneColor).distinct().size)
    }

    @Test
    fun `앱 수동 사진과 SD 파일 이름에서 렌즈를 읽는다`() {
        assertEquals(Lens.ZOOM, lensOfAppFile("manual_1762142400000_12_ZOOM.jpg"))
        assertEquals(Lens.WIDE, lensOfAppFile("DJI_20261103134904_0001_W.JPG"))
        assertNull(lensOfAppFile("photo.jpg"))
    }
}
