package com.example.myfirstapp.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePositionsTest {
    private val a = GeoPoint(37.0, 127.0)
    private val r = GeoPoint(37.1, 127.1)
    private val d = GeoPoint(37.2, 127.2)

    @Test
    fun `이륙 지점 후보는 기체 → DJI 조종기 → 안드로이드 GPS 차례`() {
        assertEquals(a to "aircraft", LivePositions(aircraft = a, rc = r).homeCandidate(d))
        assertEquals(r to "rc", LivePositions(rc = r).homeCandidate(d))
        assertEquals(d to "device", LivePositions().homeCandidate(d))
        assertEquals(null, LivePositions().homeCandidate(null))
    }

    @Test
    fun `자동 이륙 지점은 더 믿을 만한 출처일 때만 올린다`() {
        assertTrue(LivePositions.upgradesHome("device", "rc"))
        assertTrue(LivePositions.upgradesHome("device", "aircraft"))
        assertTrue(LivePositions.upgradesHome("rc", "aircraft"))
        assertFalse(LivePositions.upgradesHome("aircraft", "rc"))
        assertFalse(LivePositions.upgradesHome("rc", "rc"))
        assertFalse(LivePositions.upgradesHome("rc", "device"))
    }
}
