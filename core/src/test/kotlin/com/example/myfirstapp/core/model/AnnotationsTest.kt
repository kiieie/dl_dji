package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.Enu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationsTest {
    private val origin = GeoPoint(36.0, 127.0)
    private val proj = LocalProjection(origin)
    private fun at(x: Double, y: Double) = proj.toGeo(Enu(x, y))

    @Test
    fun `선 길이는 구간 거리의 합`() {
        assertEquals(70.0, Annotations.lengthM(listOf(at(0.0, 0.0), at(30.0, 40.0), at(30.0, 60.0))), 0.01)
        assertEquals(0.0, Annotations.lengthM(listOf(at(0.0, 0.0))), 0.0)
    }

    @Test
    fun `영역 넓이는 꼭짓점 순서와 방향에 상관없다`() {
        val square = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 50.0), at(0.0, 50.0))
        assertEquals(5_000.0, Annotations.areaM2(square), 1.0)
        assertEquals(5_000.0, Annotations.areaM2(square.reversed()), 1.0)
        assertEquals(0.0, Annotations.areaM2(square.take(2)), 0.0)
    }

    @Test
    fun `새 이름은 같은 머리말의 가장 큰 번호 다음`() {
        assertEquals("M3E 1", Annotations.nextName("M3E", emptyList()))
        assertEquals("M3E 8", Annotations.nextName("M3E", listOf("M3E 2", "M3E 7", "전선 위험", "M3E 3x", "M3T-1 9")))
    }

    @Test
    fun `이름 찾기는 같음 앞부분 포함 순`() {
        val items = listOf("둥지 2", "M3E 1", "새 둥지", "둥지").map { Annotation(kind = AnnotationKind.PIN, name = it) }
        assertEquals(listOf("둥지", "둥지 2", "새 둥지"), Annotations.search(items, "둥 지", 5).map { it.name })
        assertTrue(Annotations.search(items, " ", 5).isEmpty())
    }

    @Test
    fun `미션 둘레 안에 점이 있는 주석만 고른다`() {
        val poles = listOf(at(0.0, 0.0), at(200.0, 100.0))
        val inside = Annotation(kind = AnnotationKind.PIN, name = "안", points = listOf(at(250.0, 120.0)))
        val crossing = Annotation(kind = AnnotationKind.LINE, name = "걸침", points = listOf(at(-2_000.0, 0.0), at(-80.0, 0.0)))
        val far = Annotation(kind = AnnotationKind.AREA, name = "멀리", points = listOf(at(5_000.0, 0.0), at(5_100.0, 0.0), at(5_100.0, 90.0)))
        assertEquals(listOf("안", "걸침"), Annotations.near(listOf(inside, crossing, far), poles, 100.0).map { it.name })
        assertTrue(Annotations.near(listOf(inside), emptyList(), 100.0).isEmpty())
    }

    @Test
    fun `저장할 만큼 점이 있는지`() {
        assertFalse(Annotation(kind = AnnotationKind.AREA, name = "a", points = listOf(origin, origin)).complete)
        assertTrue(Annotation(kind = AnnotationKind.LINE, name = "b", points = listOf(origin, origin)).complete)
    }
}
