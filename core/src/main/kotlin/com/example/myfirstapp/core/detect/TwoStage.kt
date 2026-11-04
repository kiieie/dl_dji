package com.example.myfirstapp.core.detect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// 두 단계 탐지(9.6): 비행 중에는 가장 최근 사진 한 장만 가볍게, 착륙 뒤에는 모든 사진을 정밀 모델로.

/**
 * 가장 최근 것만 처리하는 일꾼(9.6 실시간 1단계). 처리하는 동안 들어온 것은 마지막 하나만 남기고 버린다.
 * [offer]는 기다리지 않으므로 실행기(비행 판단)를 막지 않는다. [process]의 실패는 부르는 쪽이 잡는다.
 */
class LatestOnly<T>(scope: CoroutineScope, process: suspend (T) -> Unit) {
    private val inbox = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch { for (x in inbox) process(x) }
    }

    fun offer(x: T) {
        inbox.trySend(x)
    }
}

/** 정밀 탐지 진행: 이번 차례에서 끝낸 수 / 전체(끝낸 수 + 남은 수). [running]이면 지금 처리 중이다(거짓이면 쉬거나 끝남). */
data class BackfillProgress(val done: Int = 0, val total: Int = 0, val running: Boolean = false) {
    val remaining get() = total - done
}

/**
 * 착륙 뒤 정밀 탐지 순서(9.6). [canRun]이 참인 동안 [remaining](정밀 결과가 아직 없는 사진 id, 사진마다 DB에서 다시 읽음)의 첫 사진을 [process]한다.
 * - [canRun]이 거짓이 되면(이륙·발열·배터리·속도 측정) 처리 중인 사진을 취소하고 쉰다. 다시 참이 되면 남은 것부터 한다(끝낸 사진은 [remaining]에 없다).
 * - 다 끝내면 [kick](새 사진 저장·착륙)까지 기다린다. 앱을 다시 켜도 [remaining]이 DB를 보므로 남은 것부터 한다.
 * - 한 사진이 실패하면 이번 실행 동안은 건너뛴다(같은 사진에서 맴돌지 않게). 앱을 다시 켜면 다시 해 본다.
 * [onIdle]은 쉬거나 다 끝냈을 때 부른다(모델을 닫아 메모리를 돌려줌).
 */
class PreciseBackfill(
    private val canRun: Flow<Boolean>,
    private val remaining: suspend () -> List<Long>,
    private val process: suspend (Long) -> Unit,
    private val onIdle: () -> Unit = {},
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val failed = mutableSetOf<Long>()
    private var done = 0
    private val _progress = MutableStateFlow(BackfillProgress())
    val progress: StateFlow<BackfillProgress> = _progress.asStateFlow()

    fun kick() {
        kicks.trySend(Unit)
    }

    suspend fun run() {
        canRun.distinctUntilChanged().collectLatest { ok ->
            if (!ok) return@collectLatest
            try {
                while (true) {
                    val next = remaining().filterNot { it in failed }
                    if (next.isEmpty()) {
                        done = 0
                        _progress.value = BackfillProgress()
                        onIdle()
                        kicks.receive()
                        continue
                    }
                    _progress.value = BackfillProgress(done, done + next.size, running = true)
                    val id = next.first()
                    try {
                        process(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed += id // OOM 등도 여기서 잡는다
                    }
                    done++
                }
            } finally {
                _progress.value = _progress.value.copy(running = false)
                onIdle()
            }
        }
    }
}

/** 모델 폴더 요약(9.1): 폴더 이름, `role`(`live`/`full`, 없으면 null), 정사각 입력 크기. */
data class ModelChoice(val dir: String, val role: String?, val inputSize: Int)

/**
 * 모델 두 칸 고르기(9.6). [ModelChoice] 목록은 규격 검사를 통과한(또는 model.json을 읽은) 폴더만 넣는다.
 * 순서대로 열어 보고 실패하면 다음 후보로 간다.
 */
object ModelPick {
    const val LIVE = "live"
    const val FULL = "full"

    /** 비행 중(live): 설정에서 고른 폴더 → `live` 중 입력이 작은 것 → 역할 없는 것(입력 작은 것, 같으면 이름이 뒤인 것) → `full`. */
    fun liveOrder(models: List<ModelChoice>, preferred: String?): List<String> =
        (listOfNotNull(preferred?.takeIf { p -> models.any { it.dir == p } }) +
            models.sortedWith(compareBy<ModelChoice>({ it.role != LIVE }, { it.role == FULL }, { it.inputSize }).thenByDescending { it.dir }).map { it.dir })
            .distinct()

    /** 정밀(full): 설정에서 고른 폴더 → `full` 중 입력이 큰 것 → 없으면 비행 중 모델이 정밀 탐지도 맡는다. */
    fun fullOrder(models: List<ModelChoice>, preferredFull: String?, preferredLive: String?): List<String> =
        (listOfNotNull(preferredFull?.takeIf { p -> models.any { it.dir == p } }) +
            models.filter { it.role == FULL }.sortedByDescending { it.inputSize }.map { it.dir } +
            liveOrder(models, preferredLive)).distinct()
}
