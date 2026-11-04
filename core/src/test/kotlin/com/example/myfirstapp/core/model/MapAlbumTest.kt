package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.route.LocalProjection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 지도 좌표 검색·GEO 구역 그리기·기체 앨범 묶음과 글자(10.4.9). */
class MapAlbumTest {
    private val seoul = TimeZone.getTimeZone("Asia/Seoul")

    @Test
    fun `좌표 검색은 위도 경도 두 수를 쉼표나 빈칸으로 읽는다`() {
        assertEquals(GeoPoint(36.48, 127.33), parseCoordinate("36.48, 127.33"))
        assertEquals(GeoPoint(36.48, 127.33), parseCoordinate(" 36.48 127.33 "))
        assertEquals(GeoPoint(36.48, 127.33), parseCoordinate("36.48,127.33"))
        assertEquals(GeoPoint(-33.5, -70.6), parseCoordinate("-33.5, -70.6"))
        // 경도를 먼저 쓰면(첫 수가 위도 범위 밖) 바꿔 읽는다
        assertEquals(GeoPoint(36.48, 127.33), parseCoordinate("127.33, 36.48"))
    }

    @Test
    fun `좌표가 아니면 null`() {
        listOf("", "36.48", "137L28R13", "대평선 12", "36.48, 127.33, 5", "95, 200", "0, 0", "abc, 127").forEach { assertNull(it, parseCoordinate(it)) }
    }

    @Test
    fun `GEO 원 영역은 반지름만큼 떨어진 꼭짓점으로 그린다`() {
        val c = GeoPoint(36.5, 127.3)
        val ring = circleRing(c, 500.0, 36)
        assertEquals(36, ring.size)
        ring.forEach { assertEquals(500.0, LocalProjection.distanceM(c, it), 0.5) }
        // 첫 꼭짓점은 정북
        assertTrue(ring[0].lat > c.lat && kotlin.math.abs(ring[0].lon - c.lon) < 1e-9)
    }

    @Test
    fun `제한 구역 안 높이 제한 영역은 고도 제한 구역으로 그린다`() {
        val noFly = GeoArea(points = listOf(GeoPoint(0.0, 0.0)), limitM = 0)
        val limited = GeoArea(points = listOf(GeoPoint(0.0, 0.0)), limitM = 120)
        val restricted = GeoZone("비행장", listOf(noFly, limited), GeoCategory.RESTRICTED)
        assertEquals(GeoCategory.RESTRICTED, restricted.displayCategory(noFly))
        assertEquals(GeoCategory.ALTITUDE, restricted.displayCategory(limited))
        assertEquals(GeoCategory.WARNING, GeoZone("경고", listOf(limited), GeoCategory.WARNING).displayCategory(limited))
    }

    @Test
    fun `SD 파일 이름 끝 글자로 렌즈를 읽는다`() {
        assertEquals(Lens.WIDE, lensOfFileName("DJI_20261103134904_0001_W.JPG"))
        assertEquals(Lens.ZOOM, lensOfFileName("DJI_20261103134904_0001_Z.jpg"))
        assertEquals(Lens.THERMAL, lensOfFileName("DJI_20261103134904_0001_T_E.JPG"))
        assertNull(lensOfFileName("DJI_20261103134904_0001_V.JPG"))
        assertNull(lensOfFileName("manual_1_2_WIDE.jpg"))
    }

    @Test
    fun `SD 사진은 날짜로 묶고 신형-구형 또는 구형-신형으로 정렬한다`() {
        // 2026-11-03 13:00, 2026-11-03 09:00, 2026-11-02 23:30 (KST)
        val a = SdPhoto(3, "c_W.JPG", 1_793_678_400_000, 1)
        val b = SdPhoto(2, "b_W.JPG", 1_793_664_000_000, 1)
        val c = SdPhoto(1, "a_W.JPG", 1_793_629_800_000, 1)
        val newest = sdDays(listOf(c, a, b), newestFirst = true, zone = seoul)
        assertEquals(listOf("20261103", "20261102"), newest.map { it.first })
        assertEquals(listOf(a, b), newest[0].second)
        val oldest = sdDays(listOf(a, b, c), newestFirst = false, zone = seoul)
        assertEquals(listOf("20261102", "20261103"), oldest.map { it.first })
        assertEquals(listOf(b, a), oldest[1].second)
    }

    @Test
    fun `저장소 글자는 사용량과 전체를 GB로`() {
        assertEquals("사용됨 26.6GB / 238.0GB", storageUsedLabel(243_712, 216_474))
        assertEquals("사용됨 0.0GB / 1.0GB", storageUsedLabel(1024, 2048))
        assertNull(storageUsedLabel(null, 100))
        assertNull(storageUsedLabel(0, 0))
    }

    @Test
    fun `EXIF 시각을 읽고 이상한 값은 null`() {
        assertEquals(1_793_678_400_000, exifTimeMs("2026:11:03 13:00:00", seoul))
        assertNull(exifTimeMs(null, seoul))
        assertNull(exifTimeMs("    :  :     :  :  ", seoul))
        assertNull(exifTimeMs("2026:13:40 99:00:00", seoul))
    }

    @Test
    fun `모의 기체 H 지정은 텔레메트리 홈을 기체 위치로 바꾸고 조종기 위치를 모르면 실패`() = runTest {
        val home = GeoPoint(36.5, 127.3)
        val air = GeoPoint(36.501, 127.301)
        val d = FakeDroneGateway(this, home, FakeDroneGateway.Config(airborneAt = air))
        assertTrue(d.setHome(toRc = true).isFailure)
        assertTrue(d.setHome(toRc = false).isSuccess)
        assertEquals(air, d.telemetry.value.home)
    }
}
