package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.model.ShotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightParamsTest {
    @Test
    fun `기본값은 유효하다`() {
        assertTrue(FlightParams().validate().isEmpty())
        assertEquals(60.0, FlightParams().rthAltitudeM, 0.0)
    }

    @Test
    fun `150m 상한과 범위를 검증한다`() {
        assertTrue(FlightParams(transitAltitudeM = 145.0).validate().any { it.contains("이동 고도") })
        assertTrue(FlightParams(transitAltitudeM = 120.0).validate().isEmpty())
        assertTrue(FlightParams(inspectAltitudeM = 60.0).validate().any { it.contains("점검 고도") })
        assertTrue(FlightParams(batteryReservePct = 19.0).validate().any { it.contains("예비율") })
        assertTrue(FlightParams(speedMps = 20.0).validate().any { it.contains("속도") })
    }

    @Test
    fun `사선 고도 하한은 지형 추적이어도 27m(현장 PoC 전), 이동 고도 이하`() {
        val d = FlightParams(shotMode = ShotMode.DIAGONAL4)
        assertTrue(d.validate().isEmpty())
        assertTrue(d.copy(diagonalAltitudeM = 24.5).validate().any { "27m" in it })
        assertTrue(d.copy(terrainFollow = false, diagonalAltitudeM = 24.5).validate().any { "27m" in it })
        assertTrue(d.copy(diagonalAltitudeM = 60.0).validate().any { "이동 고도 이하" in it })
    }

    @Test
    fun `지형 추적은 촬영 고도와 전주별 고도 모두 30m 이상(DJI 비전 감지 30~200m, 11-03 현장 27m 거절)`() {
        val d = FlightParams(shotMode = ShotMode.DIAGONAL4)
        assertEquals(30.0, d.diagonalAltitudeM, 0.0)
        assertEquals(8.0, d.zoomRatio, 0.0)
        assertTrue(d.copy(diagonalAltitudeM = 27.0).validate().any { "지형 추적" in it })
        assertTrue(d.copy(diagonalAltitudeM = 27.0, terrainFollow = false).validate().isEmpty())
        assertEquals(30.0, d.shotAltitudeRangeM.start, 0.0)
        assertEquals(27.0, d.copy(terrainFollow = false).shotAltitudeRangeM.start, 0.0)
        assertTrue(d.withOverride("P1", PoleOverride(altitudeM = 28.0)).validate().any { "P1" in it })
        assertTrue(d.copy(terrainFollow = false).withOverride("P1", PoleOverride(altitudeM = 28.0)).validate().isEmpty())
    }

    @Test
    fun `전주당 사진 수는 지점 수 × 렌즈 수, 설치기기 사선은 2배`() {
        val bank = Pole("P", GeoPoint(36.0, 127.0), equipment = "가공뱅크")
        assertEquals(8, FlightParams().photosPerPole())
        assertEquals(16, FlightParams().photosPerPole(bank))
        assertEquals(2, FlightParams(shotMode = ShotMode.OVERHEAD).photosPerPole(bank))
        assertEquals(4, FlightParams(lenses = listOf(Lens.WIDE), equipmentDropM = emptyMap()).photosPerPole(bank))
    }

    @Test
    fun `미션 예상 사진 수는 전주마다 설비를 보고 더한다(홈·라이브러리·미션 칸 같은 값)`() {
        val bank = Pole("P", GeoPoint(36.0, 127.0), equipment = "가공뱅크")
        val plain = Pole("Q", GeoPoint(36.0, 127.0))
        assertEquals(16 + 8, FlightParams().expectedPhotos(listOf(bank, plain)))
        assertEquals(0, FlightParams().expectedPhotos(emptyList()))
    }
}
