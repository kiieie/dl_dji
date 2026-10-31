package com.example.myfirstapp.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 전주 표지 규칙(2026-10-31 동작 고정). 전문가 확인으로 규칙이 바뀌면 이 기대값도 함께 고친다. */
class PoleCodesTest {

    @Test
    fun voltage() {
        assertEquals(Voltage.HIGH, PoleCodes.voltageOf("고압"))
        assertEquals(Voltage.LOW, PoleCodes.voltageOf("저압"))
        assertEquals(Voltage.BOTH, PoleCodes.voltageOf("고저압"))
        assertNull(PoleCodes.voltageOf(null))
        assertNull(PoleCodes.voltageOf(""))
        assertNull(PoleCodes.voltageOf(PoleCodes.GUY))
    }

    @Test
    fun equipments() {
        assertEquals(setOf(Equipment.TRANSFORMER, Equipment.ARRESTER), PoleCodes.equipmentsOf("가공뱅크/피뢰기"))
        assertEquals(setOf(Equipment.TRANSFORMER), PoleCodes.equipmentsOf("변압기"))
        assertEquals(setOf(Equipment.SWITCH), PoleCodes.equipmentsOf("개폐기"))
        assertEquals(emptySet<Equipment>(), PoleCodes.equipmentsOf(null))
        assertEquals(emptySet<Equipment>(), PoleCodes.equipmentsOf("완철"))
    }

    @Test
    fun guyAndEmbedment() {
        assertEquals("지선주", PoleCodes.GUY)
        assertEquals(2.0, PoleCodes.embedmentM(12.0), 1e-9)
        assertEquals(2.5, PoleCodes.embedmentM(15.0), 1e-9)
        assertEquals(2.5, PoleCodes.embedmentM(16.0), 1e-9)
    }
}
