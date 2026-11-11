package com.example.myfirstapp.core.executor

import com.example.myfirstapp.core.fake.FakeDroneGateway
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.FinishAction
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.CaptureInfo
import com.example.myfirstapp.core.port.InspectionRecorder
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

/** 웨이포인트 경로 체크리스트 두 단계(10.5.5): [비행 임무 업로드] → [Uploaded] → [시작]. */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadStartTest {
    private val home = GeoPoint(37.5, 127.0)
    private val proj = LocalProjection(home)
    private val poles = listOf(Pole("A", proj.toGeo(Enu(100.0, 0.0))), Pole("B", proj.toGeo(Enu(100.0, 100.0))))
    private val params = FlightParams(shotMode = ShotMode.OVERHEAD, lenses = listOf(Lens.WIDE), terrainFollow = false)

    private class Recorder : InspectionRecorder {
        val transitions = mutableListOf<String>()
        override suspend fun recordTransition(sortieIndex: Int, from: String, to: String, reason: String, poleId: String?) { transitions += to }
        override suspend fun updateVisit(sortieIndex: Int, order: Int, status: VisitStatus, skipReason: String?) {}
        private var n = 0L
        override suspend fun saveCapture(sortieIndex: Int, order: Int, capture: CaptureInfo, source: CaptureSource): Long = ++n
        override suspend fun saveImage(captureId: Long, bytes: ByteArray) {}
        override suspend fun saveDetections(captureId: Long, modelVersion: String, detections: List<Detection>) {}
        override suspend fun recordHeightMode(terrainFollow: Boolean) {}
    }

    private class H(val scope: TestScope, val drone: FakeDroneGateway, val executor: InspectionExecutor, val recorder: Recorder) {
        val state get() = executor.uiState.value.state
        fun send(c: Command) { executor.send(c); scope.runCurrent() }
        fun await(s: ExecState) {
            repeat(9_000) {
                if (state == s) return
                scope.advanceTimeBy(100)
                scope.runCurrent()
            }
            fail("$s 상태가 되지 않음. 현재 $state, 전이 ${recorder.transitions}")
        }
    }

    private fun TestScope.harness(p: FlightParams = params, prepare: (FakeDroneGateway) -> Unit = {}): H {
        val drone = FakeDroneGateway(backgroundScope, home).also(prepare)
        val recorder = Recorder()
        val executor = InspectionExecutor(
            backgroundScope, drone, recorder, {}, { true }, EnergyModel(EnergyParams(), p), null,
            listOf(SortiePlan(poles, 0.0, 10.0, 0.0)), ExecutorConfig(sortieMission = true),
        )
        executor.start()
        runCurrent()
        return H(this, drone, executor, recorder)
    }

    @Test
    fun `업로드는 점검 뒤 올리기만 하고 떠오르지 않으며, 시작하면 올린 미션을 날아 끝낸다`() = runTest {
        val h = harness()
        h.send(Command.Upload())
        h.await(ExecState.Uploaded)
        assertEquals(listOf("PreflightCheck", "PreparingLeg", "Uploaded"), h.recorder.transitions)
        assertTrue("startSortie:2" in h.drone.calls)
        assertTrue("startUploaded" !in h.drone.calls)
        assertTrue("takeoff" !in h.drone.calls)
        assertFalse(h.drone.telemetry.value.isFlying)

        h.send(Command.Start)
        h.await(ExecState.MissionDone)
        assertEquals(1, h.drone.calls.count { it.startsWith("startSortie") }) // 다시 올리지 않음
        assertTrue("startUploaded" in h.drone.calls)
        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
    }

    @Test
    fun `업로드 취소하면 대기로 돌아가고 다음 시작은 한 번에 올리고 난다`() = runTest {
        val h = harness()
        h.send(Command.Upload())
        h.await(ExecState.Uploaded)
        h.send(Command.CancelUpload)
        assertEquals(ExecState.Idle, h.state)
        h.send(Command.Start)
        h.await(ExecState.MissionDone)
        assertEquals(2, h.drone.calls.count { it.startsWith("startSortie") })
    }

    @Test
    fun `체크리스트 파라미터로 올리고, 완료 동작이 RTH가 아니면 미션 뒤 복귀가 아니라 일시정지`() = runTest {
        val h = harness()
        h.send(Command.Upload(params.copy(finishAction = FinishAction.NO_ACTION)))
        h.await(ExecState.Uploaded)
        h.send(Command.Start)
        h.await(ExecState.Paused)
        assertTrue("ReturningHome" !in h.recorder.transitions)
        assertTrue(h.executor.uiState.value.message!!.contains("완료 동작"))
        h.executor.uiState.value.visits.forEach { assertEquals(VisitStatus.DONE, it.status) }
        // 남은 전주가 없으니 재개하면 RTH
        h.send(Command.Resume)
        h.await(ExecState.ReturningHome)
    }

    @Test
    fun `올린 지형 추적 미션의 시작이 거절되면 대기로 돌아가 이륙 기준을 묻는다`() = runTest {
        val h = harness(params.copy(terrainFollow = true)) {
            it.rejectTerrainStarts = true
            it.kmzTakesOff = true
        }
        h.send(Command.Upload())
        h.await(ExecState.Uploaded)
        h.send(Command.Start)
        h.await(ExecState.Idle)
        assertTrue(h.executor.uiState.value.terrainFallbackAsk)
        h.send(Command.FlyTakeoffHeight)
        h.await(ExecState.FlyingLeg)
        assertEquals(listOf("terrain:true", "terrain:false"), h.drone.calls.filter { it.startsWith("terrain:") })
    }

    @Test
    fun `모드 스위치가 N이 아니면 비행 전 점검에서 막고 무엇을 할지 알린다`() = runTest {
        val h = harness { it.setRcMode('T') }
        h.send(Command.Upload())
        h.await(ExecState.Idle)
        assertTrue(h.executor.uiState.value.message!!, "N으로 옮긴" in h.executor.uiState.value.message!!)
        assertTrue(h.drone.calls.none { it.startsWith("startSortie") })
    }

    @Test
    fun `기체가 모드 스위치로 시작을 거절하면 지형 추적을 묻지 않고 재시도하지 않는다`() = runTest {
        val h = harness(params.copy(terrainFollow = true)) {
            it.rejectRcModeStarts = true
            it.kmzTakesOff = true
        }
        h.send(Command.Upload())
        h.await(ExecState.Uploaded)
        h.send(Command.Start)
        h.await(ExecState.Idle)
        assertFalse(h.executor.uiState.value.terrainFallbackAsk)
        assertTrue(h.executor.uiState.value.message!!, "N으로 옮긴" in h.executor.uiState.value.message!!)
        assertEquals(1, h.drone.calls.count { it.startsWith("startSortie") })
    }

    @Test
    fun `일시정지 중 모드 스위치가 N이 아니면 재개하지 않고 안내하며, N으로 돌리면 재개한다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.await(ExecState.FlyingLeg)
        h.drone.pressRcPause()
        h.await(ExecState.Paused)
        h.drone.setRcMode('T')
        h.send(Command.Resume)
        h.scope.advanceTimeBy(1_000)
        h.scope.runCurrent()
        assertEquals(ExecState.Paused, h.state)
        assertTrue(h.executor.uiState.value.message!!, "T 모드" in h.executor.uiState.value.message!!)
        assertTrue("resumeMission" !in h.drone.calls)
        h.drone.setRcMode('N')
        h.send(Command.Resume)
        h.await(ExecState.FlyingLeg)
    }

    @Test
    fun `비행 중에는 업로드 명령을 받지 않는다`() = runTest {
        val h = harness()
        h.send(Command.Start)
        h.await(ExecState.FlyingLeg)
        h.send(Command.Upload())
        assertEquals(ExecState.FlyingLeg, h.state)
    }
}
