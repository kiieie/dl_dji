package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.Detector
import com.example.myfirstapp.core.port.InspectionRecorder
import com.example.myfirstapp.core.port.Sound
import com.example.myfirstapp.core.route.EnergyModel
import com.example.myfirstapp.core.route.EnergyParams
import com.example.myfirstapp.core.route.Enu
import com.example.myfirstapp.core.route.LocalProjection
import com.example.myfirstapp.core.route.SortiePlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InspectionExecutorTest {

    private val home = GeoPoint(37.5, 127.0)
    private val proj = LocalProjection(home)
    private fun pole(id: String, east: Double, north: Double) = Pole(id, proj.toGeo(Enu(east, north)))

    private val threePoles = listOf(pole("A", 100.0, 0.0), pole("B", 100.0, 100.0), pole("C", 0.0, 100.0))

    private class MemoryRecorder : InspectionRecorder {
        val transitions = mutableListOf<Pair<String, String>>()
        val visitUpdates = mutableListOf<Triple<Int, Int, VisitStatus>>()
        val captures = mutableListOf<Pair<Int, CaptureSource>>()
        val images = mutableListOf<Long>()
        val detections = mutableMapOf<Long, List<Detection>>()
        override suspend fun recordTransition(sortieIndex: Int, from: String, to: String, reason: String, poleId: String?) {
            transitions += from to to
        }
        override suspend fun updateVisit(sortieIndex: Int, order: Int, status: VisitStatus, skipReason: String?) {
            visitUpdates += Triple(sortieIndex, order, status)
        }
        val infos = mutableListOf<CaptureInfo>()
        override suspend fun saveCapture(sortieIndex: Int, order: Int, capture: CaptureInfo, source: CaptureSource): Long {
            captures += order to source
            infos += capture
            return captures.size.toLong()
        }
        override suspend fun saveImage(captureId: Long, bytes: ByteArray) { images += captureId }
        override suspend fun saveDetections(captureId: Long, modelVersion: String, detections: List<Detection>) {
            this.detections[captureId] = detections
        }
        val heightModes = mutableListOf<Boolean>()
        override suspend fun recordHeightMode(terrainFollow: Boolean) { heightModes += terrainFollow }
    }

    private class Harness(
        val scope: TestScope,
        val drone: FakeDroneGateway,
        val executor: InspectionExecutor,
        val recorder: MemoryRecorder,
        val sounds: MutableList<Sound>,
    ) {
        val state get() = executor.uiState.value.state
        fun visit(id: String) = executor.uiState.value.visits.first { it.pole.poleId == id }
        fun send(c: Command) { executor.send(c); scope.runCurrent() }

        /** 모의 시간을 흘려 [s] 상태가 될 때까지 기다린다. */
        fun awaitState(s: ExecState, maxSec: Int = 900) {
            repeat(maxSec * 10) {
                if (state == s) return
                scope.advanceTimeBy(100)
                scope.runCurrent()
            }
            fail("$s 상태가 되지 않음. 현재 $state, 전이 ${recorder.transitions}")
        }

        /** 조건이 참이 될 때까지 기다린다. */
        fun awaitUntil(maxSec: Int = 900, cond: () -> Boolean) {
            repeat(maxSec * 10) {
                if (cond()) return
                scope.advanceTimeBy(100)
                scope.runCurrent()
            }
            fail("조건 미충족. 현재 $state")
        }
    }

    private fun TestScope.harness(
        sorties: List<List<Pole>> = listOf(threePoles),
        detector: Detector? = FixedDetector(emptyList()),
        params: FlightParams = FlightParams(shotMode = ShotMode.OVERHEAD),
        droneConfig: FakeDroneGateway.Config = FakeDroneGateway.Config(),
        recoverSortie: Int? = null,
        config: ExecutorConfig = ExecutorConfig(),
        prepare: (FakeDroneGateway) -> Unit = {},
    ): Harness {
        val drone = FakeDroneGateway(backgroundScope, home, droneConfig).also(prepare)
        val recorder = MemoryRecorder()
        val sounds = mutableListOf<Sound>()
        val energy = EnergyModel(EnergyParams(), params)
        val plans = sorties.map { SortiePlan(it, 0.0, 10.0, 0.0) }
        val executor = InspectionExecutor(
            backgroundScope, drone, recorder, { sounds += it }, { true }, energy, detector, plans, config = config, recoverSortie = recoverSortie,
        )
        executor.start()
        runCurrent()
        return Harness(this, drone, executor, recorder, sounds)
    }

    private class FixedDetector(val result: List<Detection>) : Detector {
        override val modelVersion = "test-1"
        override suspend fun detect(image: ByteArray) = result
    }

    @Test
    fun `정상 흐름 - 모든 전주를 촬영·분석하고 복귀해 미션 종료`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)

        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
        assertEquals(6, h.recorder.captures.size) // 전주 3개 × 광각·망원
        assertEquals(6, h.recorder.images.size)
        assertEquals(3, h.sounds.count { it == Sound.ARRIVED })
        assertEquals(3, h.sounds.count { it == Sound.INSPECTION_OK })
        val states = h.recorder.transitions.map { it.second }
        assertEquals(
            listOf("PreflightCheck", "Takeoff", "PreparingLeg", "FlyingLeg", "Arrived", "Capturing", "Downloading", "Analyzing", "Waiting"),
            states.take(9),
        )
        assertTrue(states.containsAll(listOf("ReturningHome", "SortieDone", "MissionDone")))
        assertTrue(h.drone.calls.contains("returnHome"))
    }

    @Test
    fun `이상 탐지 시 경고음`() = runTest {
        val h = harness(detector = FixedDetector(listOf(Detection("crack", 0.9f, 0.1f, 0.1f, 0.2f, 0.2f, alert = true))))
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(3, h.sounds.count { it == Sound.ANOMALY })
        assertEquals(6, h.recorder.detections.size)
    }

    @Test
    fun `경고 라벨이 아닌 탐지는 저장만 하고 정상음`() = runTest {
        val h = harness(detector = FixedDetector(listOf(Detection("pole", 0.9f, 0.1f, 0.1f, 0.2f, 0.2f))))
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(0, h.sounds.count { it == Sound.ANOMALY })
        assertEquals(3, h.sounds.count { it == Sound.INSPECTION_OK })
        assertEquals(6, h.recorder.detections.size)
    }

    @Test
    fun `이동 중 HW 일시정지 후 재개하면 기존 미션을 멈추고 같은 전주로 다시 간다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.drone.pressRcPause()
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)
        assertEquals(VisitStatus.IN_PROGRESS, h.visit("A").status)
        assertTrue(Sound.PAUSED in h.sounds)

        val stopsBefore = h.drone.calls.count { it == "stopMission" }
        h.drone.pressRcResume()
        h.scope.runCurrent()
        assertEquals(ExecState.PreparingLeg, h.state)
        assertEquals("A", h.executor.uiState.value.target?.pole?.poleId)
        h.awaitState(ExecState.FlyingLeg)
        assertEquals(stopsBefore + 1, h.drone.calls.count { it == "stopMission" })
        h.awaitState(ExecState.MissionDone)
        assertEquals(VisitStatus.DONE, h.visit("A").status)
    }

    @Test
    fun `촬영 중 일시정지는 촬영을 마친 뒤 적용되고 재개하면 다음 전주로 간다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.Capturing)
        h.drone.pressRcPause()
        h.scope.runCurrent()
        assertEquals(ExecState.Capturing, h.state)
        h.awaitState(ExecState.Paused)
        assertEquals(VisitStatus.DONE, h.visit("A").status)
        assertEquals(2, h.recorder.captures.size)

        h.send(Command.Resume)
        assertEquals(ExecState.PreparingLeg, h.state)
        assertEquals("B", h.executor.uiState.value.target?.pole?.poleId)
    }

    @Test
    fun `촬영 후 일시정지에서 건너뛰기는 완료된 전주가 아니라 다음 전주에 적용된다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.Waiting)
        h.drone.pressRcPause()
        h.scope.runCurrent()
        h.send(Command.Skip())
        assertEquals(VisitStatus.DONE, h.visit("A").status)
        assertEquals(VisitStatus.SKIPPED, h.visit("B").status)
        h.send(Command.ManualCapture) // 방금 점검한 A에 사선 사진 추가
        h.awaitUntil { h.visit("A").captureCount == 3 }
        h.send(Command.Resume)
        assertEquals("C", h.executor.uiState.value.target?.pole?.poleId)
    }

    @Test
    fun `업로드 실패는 3회 재시도하고 초과하면 일시정지`() = runTest {
        val h = harness()
        h.drone.failNextLegStarts = 3
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        assertEquals(4, h.drone.calls.count { it == "startLeg" })

        val h2 = harness()
        h2.drone.failNextLegStarts = 4
        h2.send(Command.Start)
        h2.awaitState(ExecState.Paused)
        assertTrue(h2.executor.uiState.value.message!!.contains("업로드 실패"))
    }

    @Test
    fun `촬영 실패는 FAILED로 기록하고 다음 전주로 진행`() = runTest {
        val h = harness()
        h.drone.failNextCaptures = 2
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(VisitStatus.FAILED, h.visit("A").status)
        assertEquals(VisitStatus.DONE, h.visit("B").status)
    }

    @Test
    fun `다운로드 실패여도 사진이 있으면 완료이고 비행은 계속된다`() = runTest {
        val h = harness()
        h.drone.failNextDownloads = 2
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(VisitStatus.DONE, h.visit("A").status)
        assertEquals(4, h.recorder.images.size)
    }

    @Test
    fun `탐지기 예외나 모델 없음은 비행을 막지 않는다`() = runTest {
        val broken = object : Detector {
            override val modelVersion = "broken"
            override suspend fun detect(image: ByteArray): List<Detection> = throw OutOfMemoryError("테스트")
        }
        val h = harness(detector = broken)
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(3, h.sounds.count { it == Sound.ERROR })

        val h2 = harness(detector = null)
        h2.send(Command.Start)
        h2.awaitState(ExecState.MissionDone)
        h2.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
    }

    @Test
    fun `연결 끊김 후 재연결하면 일시정지로 복귀`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.drone.simulateLinkLost()
        h.scope.runCurrent()
        assertEquals(ExecState.LinkLost, h.state)
        h.send(Command.Resume) // LinkLost에서는 명령을 무시한다
        assertEquals(ExecState.LinkLost, h.state)
        h.drone.simulateLinkRestored()
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)
    }

    @Test
    fun `기체 주도 RTH 감지 후 착륙하면 소티 종료, 남은 전주는 미점검으로 남는다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.drone.simulateAircraftRth()
        h.scope.runCurrent()
        assertEquals(ExecState.ReturningHome, h.state)
        h.awaitState(ExecState.SortieDone)
        assertEquals(VisitStatus.PENDING, h.visit("A").status)
        assertTrue(!h.drone.calls.contains("returnHome")) // 앱은 RTH 명령을 다시 보내지 않는다

        h.send(Command.EndMission)
        assertEquals(ExecState.MissionDone, h.state)
    }

    @Test
    fun `비행 전 점검 실패 시 Idle로 돌아가고 사유를 보여 준다`() = runTest {
        val h = harness()
        h.drone.preflightReport = h.drone.preflightReport.copy(obstacleAvoidanceOn = false, ldmEnabled = false)
        h.send(Command.Start)
        h.awaitUntil { h.recorder.transitions.size >= 2 }
        assertEquals(ExecState.Idle, h.state)
        val failures = h.executor.uiState.value.preflightFailures
        assertTrue(failures.any { it.contains("장애물 회피") })
        assertTrue(failures.any { it.contains("LDM") })
    }

    @Test
    fun `경고를 확인하고 시작해도 LDM이 꺼져 있으면 막고, 그 밖의 경고는 시작한다`() = runTest {
        val h = harness()
        h.drone.preflightReport = h.drone.preflightReport.copy(obstacleAvoidanceOn = false, ldmEnabled = false)
        h.send(Command.StartIgnoringWarnings)
        h.awaitUntil { h.recorder.transitions.size >= 2 }
        assertEquals(ExecState.Idle, h.state)
        assertTrue("takeoff" !in h.drone.calls)

        h.drone.preflightReport = h.drone.preflightReport.copy(ldmEnabled = true)
        h.send(Command.StartIgnoringWarnings)
        h.awaitState(ExecState.FlyingLeg)
    }

    @Test
    fun `일시정지 중 사진 없는 전주는 완료 처리할 수 없고 건너뛰기는 가능`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.drone.pressRcPause()
        h.scope.runCurrent()

        h.send(Command.MarkDone)
        assertEquals(VisitStatus.IN_PROGRESS, h.visit("A").status)
        h.send(Command.Skip())
        assertEquals(VisitStatus.SKIPPED, h.visit("A").status)

        h.send(Command.Resume)
        assertEquals("B", h.executor.uiState.value.target?.pole?.poleId)
    }

    @Test
    fun `일시정지 중 수동 촬영은 MANUAL로 저장되고 전주를 완료로 만든다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.drone.pressRcPause()
        h.scope.runCurrent()
        h.send(Command.ManualCapture)
        h.awaitUntil { h.visit("A").status == VisitStatus.DONE }
        assertEquals(CaptureSource.MANUAL, h.recorder.captures.single().second)

        h.send(Command.Resume)
        assertEquals("B", h.executor.uiState.value.target?.pole?.poleId)
    }

    @Test
    fun `대기 후 배터리가 부족하면 RTH를 권고하고 10초 무응답이면 자동 RTH`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.Waiting)
        h.drone.setBattery(31.0)
        h.awaitUntil { h.executor.uiState.value.rthCountdownSec != null }
        assertEquals(ExecState.Waiting, h.state)
        assertTrue(Sound.BATTERY_WARNING in h.sounds)
        h.awaitState(ExecState.ReturningHome, maxSec = 11)
        assertTrue(h.drone.calls.contains("returnHome"))
    }

    @Test
    fun `RTH 권고 중 일시정지하면 권고가 취소된다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.Waiting)
        h.drone.setBattery(31.0)
        h.awaitUntil { h.executor.uiState.value.rthCountdownSec != null }
        h.drone.pressRcPause()
        h.scope.runCurrent()
        h.scope.advanceTimeBy(15_000)
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)
        assertEquals(null, h.executor.uiState.value.rthCountdownSec)
    }

    @Test
    fun `소티 2개 - 첫 소티 착륙 후 배터리 교체하고 다음 소티를 시작`() = runTest {
        val h = harness(sorties = listOf(threePoles.take(2), threePoles.drop(2)))
        h.send(Command.Start)
        h.awaitState(ExecState.SortieDone)
        assertEquals(VisitStatus.PENDING, h.visit("C").status)

        h.drone.setBattery(100.0)
        h.send(Command.Start)
        assertEquals(1, h.executor.uiState.value.sortieIndex)
        h.awaitState(ExecState.MissionDone)
        assertEquals(VisitStatus.DONE, h.visit("C").status)
    }

    @Test
    fun `장애물 회피 동작 시 일시정지하고 경고`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.drone.simulateObstacleAvoidance()
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)
        assertTrue(h.executor.uiState.value.message!!.contains("장애물"))
    }

    @Test
    fun `앱 재시작 복구 - 기체가 떠 있으면 일시정지로 돌아가고 재개하면 남은 전주를 점검한다`() = runTest {
        val h = harness(droneConfig = FakeDroneGateway.Config(airborneAt = threePoles[0].position), recoverSortie = 0)
        assertEquals(ExecState.Paused, h.state)
        assertEquals("Idle" to "Paused", h.recorder.transitions.first())
        assertTrue("takeoff" !in h.drone.calls)

        h.send(Command.Resume)
        assertEquals(ExecState.PreparingLeg, h.state)
        assertEquals("A", h.executor.uiState.value.target?.pole?.poleId)
        h.awaitState(ExecState.MissionDone)
        assertTrue(h.executor.uiState.value.visits.all { it.status == VisitStatus.DONE })
    }

    @Test
    fun `앱 재시작 복구 - 기체가 아직 미션을 날고 있으면 먼저 멈추고, 재개하면 옛 미션을 끝내고 다시 올린다`() = runTest {
        val h = harness(
            config = sortieMode, recoverSortie = 0,
            droneConfig = FakeDroneGateway.Config(airborneAt = threePoles[0].position, airborneMode = FlightMode.MISSION),
        )
        assertEquals(ExecState.Paused, h.state)
        assertEquals(listOf("pauseMission"), h.drone.calls)
        h.send(Command.Resume)
        h.awaitState(ExecState.FlyingLeg)
        val i = h.drone.calls.indexOf("stopMission")
        assertTrue(i >= 0 && i < h.drone.calls.indexOfFirst { it.startsWith("startSortie") })
    }

    @Test
    fun `앱 재시작 복구 - 기체가 복귀 중이면 끊지 않고 복귀로 둔다`() = runTest {
        val h = harness(
            config = sortieMode, recoverSortie = 0,
            droneConfig = FakeDroneGateway.Config(airborneAt = threePoles[0].position, airborneMode = FlightMode.GO_HOME),
        )
        assertEquals(ExecState.ReturningHome, h.state)
        assertTrue(h.drone.calls.none { it == "pauseMission" || it == "returnHome" })
    }

    @Test
    fun `앱 재시작 복구 - 연결 없이 켜졌다가 붙었을 때 기체가 미션 중이면 멈춘다`() = runTest {
        val h = harness(
            config = sortieMode, recoverSortie = 0,
            droneConfig = FakeDroneGateway.Config(airborneAt = threePoles[0].position, airborneMode = FlightMode.MISSION),
            prepare = { it.simulateLinkLost() },
        )
        assertEquals(ExecState.LinkLost, h.state)
        h.drone.simulateLinkRestored()
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)
        assertEquals(listOf("pauseMission"), h.drone.calls)
    }

    @Test
    fun `앱 재시작 복구 - 착륙 상태면 소티를 닫고 남은 전주는 다음 소티로 남긴다`() = runTest {
        val h = harness(recoverSortie = 0)
        assertEquals(ExecState.SortieDone, h.state)
        assertEquals(listOf("Idle" to "SortieDone"), h.recorder.transitions)
        assertTrue(h.executor.uiState.value.visits.all { it.status == VisitStatus.PENDING })
    }

    @Test
    fun `앱 재시작 복구 - 연결이 없으면 연결 끊김으로 두고 재연결되면 일시정지`() = runTest {
        val h = harness(
            droneConfig = FakeDroneGateway.Config(airborneAt = threePoles[1].position), recoverSortie = 0,
            prepare = { it.simulateLinkLost() },
        )
        assertEquals(ExecState.LinkLost, h.state)
        h.drone.simulateLinkRestored()
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)
    }

    @Test
    fun `SD 대조 - 도착 뒤 찍혔고 DB에 없는 사진만 되살린다`() {
        fun media(id: String, at: Long) = CaptureInfo(id, Lens.WIDE, null, 30.0, -90.0, 0.0, at)
        val sd = listOf(media("M1", 900), media("M2", 1_000), media("M3", 1_500))
        assertEquals(listOf("M3"), recoveredCaptures(1_000, sd, setOf("M2")).map { it.mediaId })
        assertTrue(recoveredCaptures(null, sd, emptySet()).isEmpty())
    }

    // ---------------------------------------------------------------- 6.1 방식 A(소티 미션, 계속 비행)

    private val sortieMode = ExecutorConfig(sortieMission = true)

    /** 광각만(사진 수를 지점 수로 세는 테스트). 기본은 광각+망원 동시라 지점마다 파일 2개. */
    private val wideOnly = FlightParams(shotMode = ShotMode.OVERHEAD, lenses = listOf(Lens.WIDE))

    @Test
    fun `방식 A - 소티 미션 하나로 날며 지점마다 찍힌 사진을 처리하고 복귀`() = runTest {
        val h = harness(config = sortieMode)
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)

        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
        assertEquals(listOf("startSortie:3"), h.drone.calls.filter { it.startsWith("start") })
        assertTrue(h.drone.calls.none { it.startsWith("capture") }) // 미션 동작이 찍는다
        assertEquals(6, h.recorder.captures.size) // 지점마다 광각+망원 2개
        assertEquals(listOf(2, 2, 2), h.executor.uiState.value.visits.map { it.captureCount })
        h.awaitUntil { h.recorder.images.size == 6 } // 다운로드·탐지는 비행과 병렬, 착륙 뒤에도 끝까지
        assertEquals(6, h.recorder.detections.size)
        assertEquals(3, h.sounds.count { it == Sound.ARRIVED })
        assertTrue("returnHome" !in h.drone.calls) // 완료 동작 RTH는 기체가 한다
    }

    @Test
    fun `방식 A - 4방향 사선은 전주마다 4장`() = runTest {
        val h = harness(config = sortieMode, params = FlightParams(shotMode = ShotMode.DIAGONAL4, lenses = listOf(Lens.WIDE)))
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertTrue("startSortie:12" in h.drone.calls)
        assertEquals(listOf(4, 4, 4), h.recorder.captures.groupBy { it.first }.values.map { it.size })
        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
    }

    @Test
    fun `방식 A - 설치기기 전주는 지점마다 설비 조준 사진이 하나 더 붙는다`() = runTest {
        val poles = listOf(threePoles[0].copy(equipment = "가공뱅크/피뢰기"), threePoles[1], threePoles[2])
        val h = harness(sorties = listOf(poles), config = sortieMode, params = FlightParams(shotMode = ShotMode.DIAGONAL4, lenses = listOf(Lens.WIDE)))
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(listOf(8, 4, 4), h.recorder.captures.groupBy { it.first }.values.map { it.size })
        val a = h.recorder.infos.filterIndexed { i, _ -> h.recorder.captures[i].first == 0 }
        assertEquals(4, a.count { it.equipment })
        assertTrue(a.filter { it.equipment }.all { e -> a.any { !it.equipment && it.viewpoint == e.viewpoint && it.gimbalPitchDeg > e.gimbalPitchDeg } })
    }

    @Test
    fun `방식 A - 지형 추적 시작이 거절되면 다시 올리지 않고 묻고, 이륙 기준을 고르면 그 방식으로 날고 기록한다`() = runTest {
        val h = harness(config = sortieMode, params = FlightParams(shotMode = ShotMode.OVERHEAD, terrainFollow = true)) { it.rejectTerrainStarts = true }
        h.send(Command.Start)
        h.awaitState(ExecState.Paused)
        assertTrue(h.executor.uiState.value.terrainFallbackAsk)
        assertEquals(1, h.drone.calls.count { it.startsWith("startSortie") }) // 같은 KMZ 재시도 없음

        h.send(Command.FlyTakeoffHeight)
        h.awaitState(ExecState.MissionDone)
        assertEquals(listOf("terrain:true", "terrain:false"), h.drone.calls.filter { it.startsWith("terrain:") })
        assertEquals(listOf(false), h.recorder.heightModes)
        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
    }

    @Test
    fun `방식 A - 지형 추적 거절 창을 취소하면 일시정지로 남는다`() = runTest {
        val h = harness(config = sortieMode, params = FlightParams(shotMode = ShotMode.OVERHEAD, terrainFollow = true)) { it.rejectTerrainStarts = true }
        h.send(Command.Start)
        h.awaitState(ExecState.Paused)
        h.send(Command.DismissTerrainFallback)
        assertEquals(ExecState.Paused, h.state)
        assertFalse(h.executor.uiState.value.terrainFallbackAsk)
        h.send(Command.FlyTakeoffHeight) // 묻지 않을 때는 무시
        assertEquals(1, h.drone.calls.count { it.startsWith("startSortie") })
    }

    @Test
    fun `방식 A - 지형 추적으로 시작하면 그 방식을 기록한다`() = runTest {
        val h = harness(config = sortieMode, params = FlightParams(shotMode = ShotMode.OVERHEAD, terrainFollow = true))
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        assertEquals(listOf(true), h.recorder.heightModes)
    }

    @Test
    fun `방식 A - RC 일시정지 후 재개하면 기체의 미션을 이어 간다`() = runTest {
        val h = harness(config = sortieMode)
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.drone.pressRcPause()
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)

        h.send(Command.Resume)
        h.awaitState(ExecState.FlyingLeg)
        assertTrue("resumeMission" in h.drone.calls)
        assertEquals(1, h.drone.calls.count { it.startsWith("startSortie") }) // 새 미션을 올리지 않는다
        h.awaitState(ExecState.MissionDone)
        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
    }

    @Test
    fun `방식 A - 화면 일시정지는 미션을 끝내지 않고 멈춘다`() = runTest {
        val h = harness(config = sortieMode)
        h.send(Command.Start)
        h.awaitState(ExecState.FlyingLeg)
        h.send(Command.Pause)
        h.awaitUntil { "pauseMission" in h.drone.calls }
        assertEquals(ExecState.Paused, h.state)
        assertTrue("stopMission" !in h.drone.calls)
    }

    @Test
    fun `방식 A - 사진이 안 찍힌 전주는 지나가면 실패로 남는다`() = runTest {
        val h = harness(config = sortieMode, params = wideOnly) { it.failNextCaptures = 1 }
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(VisitStatus.FAILED, h.visit("A").status)
        assertEquals(VisitStatus.DONE, h.visit("B").status)
        assertEquals(2, h.recorder.captures.size)
    }

    @Test
    fun `방식 A - 다음 지점에 도착한 뒤 늦게 온 사진도 그 지점 전주에 붙고 실패를 완료로 고친다`() = runTest {
        val h = harness(config = sortieMode, params = wideOnly) { it.lateNextPhotoMs = 20_000 } // A 사진이 B 도착 뒤에 옴
        h.send(Command.Start)
        h.awaitUntil { h.visit("A").status == VisitStatus.FAILED } // B에 도착하며 사진 없는 A를 정리
        h.awaitUntil { h.visit("A").status == VisitStatus.DONE }
        h.awaitState(ExecState.MissionDone)
        assertEquals(listOf(0, 1, 2), h.recorder.captures.map { it.first }.sorted()) // 사진마다 제 전주(순서)
        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
    }

    @Test
    fun `방식 A - 탐지는 뒤에서 저장만 하고 비행 중 경고음은 내지 않는다`() = runTest {
        val h = harness(config = sortieMode, params = wideOnly, detector = FixedDetector(listOf(Detection("nest", 0.9f, 0.1f, 0.1f, 0.2f, 0.2f, alert = true))))
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        h.awaitUntil { h.recorder.detections.size == 3 }
        assertEquals(0, h.sounds.count { it == Sound.ANOMALY })
    }

    @Test
    fun `방식 A - RC 셔터 사진은 가까운 전주의 수동 사진이 되고 자동 사진과 섞이지 않는다`() = runTest {
        val h = harness(config = sortieMode, params = wideOnly)
        h.send(Command.Start)
        h.awaitUntil { h.visit("B").captureCount == 1 } // B 상공에서 자동 사진을 찍은 직후
        val shot = h.drone.pressRcShutter()
        h.awaitUntil { h.recorder.captures.any { it.second == CaptureSource.MANUAL } }
        assertEquals(1 to CaptureSource.MANUAL, h.recorder.captures.last()) // B는 소티 안 순서 1
        assertEquals(1, h.visit("B").captureCount) // 셔터 사진은 완료 판정 사진 수에 안 셈
        h.awaitUntil { "download:${shot.mediaId}" in h.drone.calls }

        h.awaitState(ExecState.MissionDone)
        assertEquals(3, h.recorder.captures.count { it.second == CaptureSource.AUTO })
        assertEquals(1, h.recorder.captures.count { it.second == CaptureSource.MANUAL })
        h.awaitUntil { h.recorder.images.size == 4 }
        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
        assertTrue("listMedia" !in h.drone.calls) // 셔터 사진은 SD 목록을 읽지 않는다
    }

    @Test
    fun `방식 A - 자동 사진이 없고 셔터 사진만 있는 전주는 완료가 아니라 실패다`() = runTest {
        val h = harness(config = sortieMode) { it.failNextCaptures = 1 } // A 자동 사진 실패
        h.send(Command.Start)
        h.awaitUntil { h.visit("A").status == VisitStatus.IN_PROGRESS }
        h.drone.pressRcShutter() // A 상공
        h.awaitUntil { h.recorder.captures.any { it.second == CaptureSource.MANUAL } }
        assertEquals(0 to CaptureSource.MANUAL, h.recorder.captures.first())
        h.awaitState(ExecState.MissionDone)
        assertEquals(VisitStatus.FAILED, h.visit("A").status)
        assertEquals(0, h.visit("A").captureCount)
    }

    @Test
    fun `RC 셔터 - 전주에서 20m 넘게 떨어져 찍으면 전주에 붙이지 않는다`() = runTest {
        val h = harness(config = sortieMode, params = wideOnly)
        h.send(Command.Start)
        h.awaitUntil { h.visit("A").captureCount == 1 }
        // A(100,0)와 B(100,100) 사이, 어느 전주에서도 20m 넘게 떨어진 곳
        h.awaitUntil { h.drone.telemetry.value.position?.let { p -> threePoles.all { LocalProjection.distanceM(p, it.position) > SHUTTER_POLE_LINK_M + 5 } } == true }
        h.drone.pressRcShutter()
        h.awaitUntil { h.executor.uiState.value.message?.contains("연결할 전주가 없습니다") == true }
        assertTrue(h.recorder.captures.none { it.second == CaptureSource.MANUAL })
    }

    @Test
    fun `RC 셔터 - 땅에서 찍은 셔터 사진은 기록하지 않는다`() = runTest {
        val h = harness(config = sortieMode)
        h.drone.pressRcShutter()
        h.scope.advanceTimeBy(5_000)
        h.scope.runCurrent()
        assertTrue(h.recorder.captures.isEmpty())
    }

    @Test
    fun `셔터 사진을 붙일 전주 - 20m 안의 가장 가까운 전주, 위치가 없거나 멀면 없음`() {
        fun at(e: Double, n: Double) = proj.toGeo(Enu(e, n))
        assertEquals("A", shutterLinkTarget(at(110.0, 5.0), threePoles) { it.position }?.poleId)
        assertEquals("B", shutterLinkTarget(at(100.0, 85.0), threePoles) { it.position }?.poleId)
        assertEquals(null, shutterLinkTarget(at(100.0, 50.0), threePoles) { it.position })
        assertEquals(null, shutterLinkTarget(null, threePoles) { it.position })
    }

    @Test
    fun `비행 전 점검 경고를 확인하고 시작하면 그대로 비행한다(연결은 필수)`() = runTest {
        val h = harness(config = sortieMode) { it.preflightReport = it.preflightReport.copy(gpsSatellites = 3) }
        h.send(Command.Start)
        h.awaitState(ExecState.Idle)
        assertTrue(h.executor.uiState.value.preflightFailures.isNotEmpty())
        h.send(Command.StartIgnoringWarnings)
        h.awaitState(ExecState.FlyingLeg)
    }
}
