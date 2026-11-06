package com.example.myfirstapp.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 전압 대상과 손 선택(4.5.8). 좌표는 합성 값이다. */
class PoleTargetsTest {
    private fun pole(id: String, voltage: String?) = Pole(id, GeoPoint(0.0, id.length * 1e-4), voltage = voltage)

    private val poles = listOf(pole("H1", "고압"), pole("H2", "고"), pole("B1", "고저압"), pole("L1", "저압"), pole("U1", null), pole("U2", "기타"))

    private fun targets(voltages: Set<Voltage?>, manual: Map<String, Boolean> = emptyMap()) =
        poles.filter { PoleTargets.isTarget(it.poleId, it.voltage, voltages, manual) }.map { it.poleId }

    @Test
    fun `기본은 모든 전압이라 지금과 같다`() {
        assertEquals(poles.map { it.poleId }, targets(FlightParams().voltageTargets))
    }

    @Test
    fun `고른 전압만 넣고 미상은 따로 고른다`() {
        assertEquals(listOf("H1", "H2"), targets(setOf(Voltage.HIGH)))
        assertEquals(listOf("H1", "H2", "B1"), targets(setOf(Voltage.HIGH, Voltage.BOTH)))
        assertEquals(listOf("L1", "U1", "U2"), targets(setOf(Voltage.LOW, null)))
        assertEquals(emptyList<String>(), targets(emptySet()))
    }

    @Test
    fun `손 선택이 전압 대상보다 먼저다`() {
        val manual = mapOf("L1" to true, "H1" to false)
        assertEquals(listOf("H2", "L1"), targets(setOf(Voltage.HIGH), manual))
        // 칩을 바꿔도 손으로 넣고 뺀 전주는 그대로
        assertEquals(listOf("H2", "B1", "L1", "U1", "U2"), targets(PoleTargets.ALL_VOLTAGES, manual))
    }

    @Test
    fun `원하는 묶음만 대상이 되는 손 선택을 만들고 전압 대상과 같은 전주는 적지 않는다`() {
        val wanted = setOf("H1", "L1")
        val manual = PoleTargets.manualFor(poles, wanted, setOf(Voltage.HIGH))
        assertEquals(mapOf("H2" to false, "L1" to true), manual)
        assertEquals(listOf("H1", "L1"), targets(setOf(Voltage.HIGH), manual))
        // 옛 미션(모든 전압)으로 다시 날기
        assertEquals(listOf("H1", "L1"), targets(PoleTargets.ALL_VOLTAGES, PoleTargets.manualFor(poles, wanted, PoleTargets.ALL_VOLTAGES)))
    }
}
