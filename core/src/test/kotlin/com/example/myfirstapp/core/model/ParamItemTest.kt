package com.example.myfirstapp.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ParamItemTest {

    @Test
    fun `사선과 전주 위는 보이는 항목이 다르다`() {
        assertEquals(8 - 1, FlightParams(shotMode = ShotMode.DIAGONAL4).items().size)
        assertEquals(
            listOf(ParamItem.SHOT_MODE, ParamItem.INSPECT_ALTITUDE, ParamItem.SPEED, ParamItem.TRANSIT_ALTITUDE, ParamItem.BATTERY_RESERVE, ParamItem.TERRAIN_FOLLOW),
            FlightParams(shotMode = ShotMode.OVERHEAD).items(),
        )
    }

    @Test
    fun `위아래는 끝에서 멈추고 없는 항목이면 처음으로`() {
        val p = FlightParams(shotMode = ShotMode.OVERHEAD)
        assertEquals(ParamItem.SHOT_MODE, p.moveItem(null, 1))
        assertEquals(ParamItem.SHOT_MODE, p.moveItem(ParamItem.SHOT_MODE, -1))
        assertEquals(ParamItem.TERRAIN_FOLLOW, p.moveItem(ParamItem.TERRAIN_FOLLOW, 1))
        assertEquals(ParamItem.SPEED, p.moveItem(ParamItem.INSPECT_ALTITUDE, 1))
        // 사선 고도를 고른 채 전주 위로 바꾸면 그 항목이 없다
        assertEquals(ParamItem.SHOT_MODE, p.moveItem(ParamItem.DIAGONAL_ALTITUDE, 1))
    }

    @Test
    fun `좌우는 화면 버튼과 같은 단계로 바꾼다`() {
        val p = FlightParams()
        assertEquals(28.0, p.step(ParamItem.DIAGONAL_ALTITUDE, 1).diagonalAltitudeM, 0.0)
        assertEquals(45.0, p.step(ParamItem.TRANSIT_ALTITUDE, -1).transitAltitudeM, 0.0)
        assertEquals(35.0, p.step(ParamItem.INSPECT_ALTITUDE, 1).inspectAltitudeM, 0.0)
        assertEquals(ShotMode.OVERHEAD, p.step(ParamItem.SHOT_MODE, -1).shotMode)
        assertFalse(p.step(ParamItem.TERRAIN_FOLLOW, 1).terrainFollow)
    }
}
