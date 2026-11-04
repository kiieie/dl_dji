package com.example.myfirstapp

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.PoleOverride
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.data.toJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** 전주별 설정(5.7)은 `MISSION.flightParams` JSON에 같이 남는다(Room 스키마는 그대로). */
class FlightParamsJsonTest {
    @Test
    fun `전주별 설정을 저장하고 다시 읽는다, 옛 JSON은 비어 있다`() {
        val p = FlightParams().withOverride("A-1", PoleOverride(altitudeM = 35.0)).withOverride("B", PoleOverride(gimbalPitchDeg = -60.0))
        assertEquals(p, flightParamsFromJson(p.toJson()))
        assertEquals(emptyMap<String, PoleOverride>(), flightParamsFromJson(FlightParams().toJson()).poleOverrides)
    }
}
