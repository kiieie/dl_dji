package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
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
        override suspend fun recordTransition(from: String, to: String, reason: String, poleId: String?) {
            transitions += from to to
        }
        override suspend fun updateVisit(sortieIndex: Int, order: Int, status: VisitStatus, skipReason: String?) {
            visitUpdates += Triple(sortieIndex, order, status)
        }
        override suspend fun saveCapture(sortieIndex: Int, order: Int, capture: CaptureInfo, source: CaptureSource): Long {
            captures += order to source
            return captures.size.toLong()
        }
        override suspend fun saveImage(captureId: Long, bytes: ByteArray) { images += captureId }
        override suspend fun saveDetections(captureId: Long, modelVersion: String, detections: List<Detection>) {
            this.detections[captureId] = detections
        }
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
        params: FlightParams = FlightParams(),
    ): Harness {
        val drone = FakeDroneGateway(backgroundScope, home)
        val recorder = MemoryRecorder()
        val sounds = mutableListOf<Sound>()
        val energy = EnergyModel(EnergyParams(), params)
        val plans = sorties.map { SortiePlan(it, 0.0, 10.0, 0.0) }
        val executor = InspectionExecutor(backgroundScope, drone, recorder, { sounds += it }, { true }, energy, detector, plans)
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
        val h = harness(detector = FixedDetector(listOf(Detection("crack", 0.9f, 0.1f, 0.1f, 0.2f, 0.2f))))
        h.send(Command.Start)
        h.awaitState(ExecState.MissionDone)
        assertEquals(3, h.sounds.count { it == Sound.ANOMALY })
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
}
