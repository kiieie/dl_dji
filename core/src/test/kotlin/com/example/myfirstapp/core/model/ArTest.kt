package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.LocalProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArTest {
    private val here = GeoPoint(35.0, 127.0)
    private val proj = LocalProjection(here)

    /** 기체에서 동 [east]·북 [north] m 자리. */
    private fun at(east: Double, north: Double) = proj.toGeo(Enu(east, north))

    /** 고도 50m, 북쪽을 보는 4:3 광각 카메라. */
    private fun cam(yaw: Double = 0.0, pitch: Double = 0.0, lens: Lens = Lens.WIDE, zoom: Double = 1.0) =
        ArCamera(here, 50.0, yaw, pitch, lensHfovDeg(lens, zoom), 4.0 / 3)

    @Test
    fun `바로 앞은 영상 가운데`() {
        val p = arProject(cam(), at(0.0, 100.0), 50.0)!!
        assertTrue(p.inView)
        assertEquals(0.5, p.x, 1e-6)
        assertEquals(0.5, p.y, 1e-6)
        assertEquals(100.0, p.distanceM, 0.1)
        // 기수를 동쪽으로 돌리면 동쪽 점이 가운데
        assertEquals(0.5, arProject(cam(yaw = 90.0), at(100.0, 0.0), 50.0)!!.x, 1e-6)
    }

    @Test
    fun `오른쪽 90도는 오른쪽 가장자리에 붙고 화살표는 오른쪽`() {
        val p = arProject(cam(), at(100.0, 0.0), 50.0)!!
        assertFalse(p.inView)
        assertEquals(1.0, p.x, 1e-6)
        assertEquals(0.5, p.y, 1e-6)
        assertEquals(90.0, p.arrowDeg, 1e-6)
    }

    @Test
    fun `짐벌 -90도면 바로 아래가 가운데이고 앞쪽 땅은 위쪽`() {
        val below = arProject(cam(pitch = -90.0), at(0.0, 0.6), 0.0)!!
        assertTrue(below.inView)
        assertEquals(0.5, below.x, 1e-3)
        assertEquals(0.5, below.y, 0.02)
        // 북쪽 20m 땅(고도 50m에서 22°): 영상 위쪽 절반 안
        val ahead = arProject(cam(pitch = -90.0), at(0.0, 20.0), 0.0)!!
        assertTrue(ahead.inView)
        assertEquals(0.5, ahead.x, 1e-6)
        assertTrue(ahead.y < 0.5 && ahead.y > 0.0)
        // 짐벌 0°(수평)에서는 같은 땅이 화면 아래로 벗어난다
        val level = arProject(cam(), at(0.0, 20.0), 0.0)!!
        assertFalse(level.inView)
        assertEquals(1.0, level.y, 1e-6)
    }

    @Test
    fun `뒤는 가장자리에 붙고 바로 뒤면 아래`() {
        val back = arProject(cam(), at(0.0, -100.0), 50.0)!!
        assertFalse(back.inView)
        assertEquals(0.5, back.x, 1e-6)
        assertEquals(1.0, back.y, 1e-6)
        assertEquals(180.0, back.arrowDeg, 1e-6)
        // 오른쪽 뒤는 오른쪽 가장자리(그쪽으로 돌면 보임)
        val rightBack = arProject(cam(), at(100.0, -100.0), 50.0)!!
        assertFalse(rightBack.inView)
        assertEquals(1.0, rightBack.x, 1e-6)
    }

    @Test
    fun `줌 배율만큼 화각이 좁아져 가운데에서 더 멀리 보인다`() {
        assertEquals(Tuning.Display.WIDE_HFOV_DEG, lensHfovDeg(Lens.WIDE, 7.0), 1e-9)
        assertEquals(Tuning.Display.WIDE_HFOV_DEG, lensHfovDeg(Lens.ZOOM, 1.0), 1e-9)
        // 오른쪽 5°쯤(북 100m, 동 8.75m)
        val target = at(8.75, 100.0)
        val wide = arProject(cam(), target, 50.0)!!
        val zoom = arProject(cam(lens = Lens.ZOOM, zoom = 7.0), target, 50.0)!!
        assertTrue(wide.inView && zoom.inView)
        assertEquals(7.0, (zoom.x - 0.5) / (wide.x - 0.5), 1e-6)
        assertFalse(arProject(cam(lens = Lens.ZOOM, zoom = 14.0), target, 50.0)!!.inView)
    }

    @Test
    fun `기체와 같은 자리는 그리지 않는다`() {
        assertNull(arProject(cam(), here, 50.2))
    }

    @Test
    fun `밖의 핀포인트는 고른 것만 가장자리에 남는다`() {
        val east = at(100.0, 0.0)
        val ahead = at(0.0, 100.0)
        val pins = listOf(
            Annotation(1, AnnotationKind.PIN, "M3E 1", AnnotationColor.RED, listOf(east), 50.0),
            Annotation(2, AnnotationKind.PIN, "M3E 2", AnnotationColor.GREEN, listOf(east), 50.0),
            Annotation(3, AnnotationKind.PIN, "M3E 3", points = listOf(ahead), altitudeM = 50.0),
            Annotation(4, AnnotationKind.LINE, "선", points = listOf(ahead, east)),
        )
        val wps = listOf(ArWaypoint(7, ahead, 50.0), ArWaypoint(8, east, 50.0), ArWaypoint(9, ahead, 50.0))
        val marks = arMarks(cam(), arTargets(at(0.0, -50.0), wps, pins, selectedPin = 2))
        assertEquals(listOf("M3E 2", "M3E 3", "7", "H"), marks.map { it.target.label })
        assertEquals(AnnotationColor.GREEN, marks[0].target.color)
        assertEquals(ArKind.NEXT_WAYPOINT, marks[2].target.kind)
        // 홈은 뒤(가장자리), 그다음 웨이포인트 8은 오른쪽 밖이라 빠짐
        assertFalse(marks[3].point.inView)
    }

    @Test
    fun `핀포인트는 가까우면 크고 멀면 작다`() {
        assertEquals(1.0, arPinScale(10.0), 1e-9)
        assertEquals(Tuning.Display.AR_PIN_MIN_SCALE, arPinScale(1_000.0), 1e-9)
        val mid = arPinScale((Tuning.Display.AR_PIN_NEAR_M + Tuning.Display.AR_PIN_FAR_M) / 2)
        assertEquals((1 + Tuning.Display.AR_PIN_MIN_SCALE) / 2, mid, 1e-9)
    }
}
