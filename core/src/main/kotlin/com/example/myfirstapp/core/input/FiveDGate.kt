package com.example.myfirstapp.core.input

import com.example.myfirstapp.core.port.RcButton

/**
 * RC Pro 5D 버튼 입력 정리(10장 1차 판독). 손가락이 기울어 가운데와 방향이 거의 동시에 들어오는 일이 잦다(2026-10-29 실측):
 * 가운데 전에 방향이 0~100ms 먼저 오고, 방향을 세게 누르면 가운데가 따라오고, 가운데를 뗄 때 방향이 약 210ms 뒤 온다.
 * 의도를 시간으로 가를 수 없으므로 판정이 잘못 저장되지 않게 한다:
 * - 누름은 [settleMs] 기다렸다가 내보낸다. 그 사이 가운데↔방향이 겹치면 둘 다 버리고 [Result.conflict]를 알린다(다시 누르게 함).
 * - 가운데를 내보낸 뒤 [afterCenterMs] 안의 방향은 버린다(뗄 때 기울어짐).
 * 시간은 밀리초로 받는다. 부르는 쪽이 누를 때 [press], [settleMs]가 지난 뒤 [tick]을 부른다.
 */
class FiveDGate(val settleMs: Long = 150, private val afterCenterMs: Long = 300) {
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
