package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FinishAction
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.RcLostAction
import com.example.myfirstapp.core.model.ShotMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 웨이포인트 경로 체크리스트(10.5.5) 값이 KMZ 미션 설정에 들어간다. */
class WpmlChecklistTest {
    private val home = GeoPoint(36.48, 127.33)
    private val proj = LocalProjection(home)
    private val poles = listOf(Pole("P0", proj.toGeo(Enu(30.0, 0.0))), Pole("P1", proj.toGeo(Enu(60.0, 0.0))))

    private fun config(p: FlightParams): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { WpmlWriter.write(Viewpoints.expand(poles, p, home), p, it, nowMs = 0, start = home) }.toByteArray()
        val files = buildMap { ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { put(it.name, z.readBytes().decodeToString()) } } }
        // 두 파일의 미션 설정은 같아야 한다
        val tags = listOf("flyToWaylineMode", "finishAction", "exitOnRCLost", "executeRCLostAction", "takeOffSecurityHeight", "globalTransitionalSpeed")
        return tags.associateWith { tag ->
            val values = files.values.map { Regex("<wpml:$tag>([^<]+)<").find(it)!!.groupValues[1] }.distinct()
            assertEquals("$tag 두 파일이 같아야 함", 1, values.size)
            values.single()
        }
    }

    @Test
    fun `기본값은 전과 같다 - 시작 위치로 상승, 완료 RTH, 신호 끊김 RTH, 이륙 안전 고도 자동`() {
        val c = config(FlightParams(shotMode = ShotMode.OVERHEAD))
        assertEquals("safely", c["flyToWaylineMode"])
        assertEquals("goHome", c["finishAction"])
        assertEquals("executeLostAction", c["exitOnRCLost"])
        assertEquals("goBack", c["executeRCLostAction"])
        assertEquals("30.0", c["takeOffSecurityHeight"]) // 점검 고도 30m > 기본 20m
    }

    @Test
    fun `체크리스트 값 - 곧바로 시작, 완료 착륙, 호버, 이륙 안전 고도와 속도`() {
        val c = config(
            FlightParams(
                shotMode = ShotMode.OVERHEAD, climbToStart = false, finishAction = FinishAction.AUTO_LAND,
                rcLostAction = RcLostAction.HOVER, takeoffSecurityM = 45.0, speedMps = 5.0,
            ),
        )
        assertEquals("pointToPoint", c["flyToWaylineMode"])
        assertEquals("autoLand", c["finishAction"])
        assertEquals("executeLostAction", c["exitOnRCLost"])
        assertEquals("hover", c["executeRCLostAction"])
        assertEquals("45.0", c["takeOffSecurityHeight"])
        assertEquals("5.0", c["globalTransitionalSpeed"])
    }

    @Test
    fun `신호 끊김에도 경로 계속이면 goContinue`() {
        val c = config(FlightParams(shotMode = ShotMode.OVERHEAD, rcLostAction = RcLostAction.CONTINUE, finishAction = FinishAction.GOTO_FIRST))
        assertEquals("goContinue", c["exitOnRCLost"])
        assertEquals("gotoFirstWaypoint", c["finishAction"])
    }

    @Test
    fun `이륙 안전 고도는 범위 밖이면 검증 오류`() {
        assertTrue(FlightParams(takeoffSecurityM = 1.0).validate().any { it.contains("안전한 이륙 고도") })
        assertTrue(FlightParams(takeoffSecurityM = 30.0).validate().none { it.contains("안전한 이륙 고도") })
    }
}
