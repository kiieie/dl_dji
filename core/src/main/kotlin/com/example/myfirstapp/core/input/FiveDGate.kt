package com.example.myfirstapp.core.input

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.port.RcButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * RC Pro 5D 버튼 입력 정리(10장 1차 판독). 손가락이 기울어 가운데와 방향이 거의 동시에 들어오는 일이 잦다(2026-10-29 실측):
 * 가운데 전에 방향이 0~100ms 먼저 오고, 방향을 세게 누르면 가운데가 따라오고, 가운데를 뗄 때 방향이 약 210ms 뒤 온다.
 * 의도를 시간으로 가를 수 없으므로 판정이 잘못 저장되지 않게 한다:
 * - 누름은 [settleMs] 기다렸다가 내보낸다. 그 사이 가운데↔방향이 겹치면 둘 다 버리고 [Result.conflict]를 알린다(다시 누르게 함).
 * - 가운데를 내보낸 뒤 [afterCenterMs] 안의 방향은 버린다(뗄 때 기울어짐).
 * 시간은 밀리초로 받는다. 부르는 쪽이 누를 때 [press], [settleMs]가 지난 뒤 [tick]을 부른다.
 */
class FiveDGate(val settleMs: Long = Tuning.FiveD.SETTLE_MS, private val afterCenterMs: Long = Tuning.FiveD.AFTER_CENTER_MS) {
    data class Result(val actions: List<RcButton> = emptyList(), val conflict: Boolean = false)

    private var pending: RcButton? = null
    private var pendingAt = 0L
    private var ignoreAllUntil = 0L
    private var ignoreDirectionUntil = 0L

    fun press(b: RcButton, t: Long): Result {
        val settled = tick(t).actions
        if (t < ignoreAllUntil) return Result(settled)
        if (b != RcButton.FIVE_D_CENTER && t < ignoreDirectionUntil) return Result(settled)
        val p = pending
        if (p != null && (p == RcButton.FIVE_D_CENTER) != (b == RcButton.FIVE_D_CENTER)) {
            pending = null
            ignoreAllUntil = t + afterCenterMs
            return Result(settled, conflict = true)
        }
        // 같은 종류가 빨리 이어지면(방향 연속) 앞의 것은 바로 내보낸다
        val early = listOfNotNull(p)
        pending = b
        pendingAt = t
        return Result(settled + early)
    }

    fun tick(t: Long): Result {
        val p = pending ?: return Result()
        if (t - pendingAt < settleMs) return Result()
        pending = null
        if (p == RcButton.FIVE_D_CENTER) ignoreDirectionUntil = t + afterCenterMs
        return Result(listOf(p))
    }
}

/**
 * RC 버튼 흐름에서 5D만 [gate]로 걸러 내보낸다(누름 뒤 [FiveDGate.settleMs] 기다림 포함). 빈 결과는 내보내지 않는다.
 * 화면이 맨 위에 있는 동안만 모은다(Compose `LaunchedEffect`). 대화상자 등으로 받지 않을 때는 받는 쪽이 무시한다.
 */
fun Flow<RcButton>.fiveD(gate: FiveDGate = FiveDGate(), now: () -> Long = System::currentTimeMillis): Flow<FiveDGate.Result> = channelFlow {
    collect { b ->
        if (!b.name.startsWith("FIVE_D_")) return@collect
        gate.press(b, now()).takeIf { it.actions.isNotEmpty() || it.conflict }?.let { send(it) }
        launch {
            delay(gate.settleMs)
            gate.tick(now()).takeIf { it.actions.isNotEmpty() }?.let { send(it) }
        }
    }
}

/** 목록에서 고른 줄을 [delta]만큼 옮긴다. 끝에서는 멈추고, 고른 줄이 없으면(-1) 첫 줄. 빈 목록이면 -1. */
fun moveIndex(i: Int, delta: Int, size: Int): Int = when {
    size == 0 -> -1
    i < 0 -> 0
    else -> (i + delta).coerceIn(0, size - 1)
}
