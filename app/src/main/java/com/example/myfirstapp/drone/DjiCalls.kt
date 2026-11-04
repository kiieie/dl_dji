package com.example.myfirstapp.drone

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DJI 콜백 실패. 메시지는 SDK 설명(없으면 오류 코드), [detail]은 운용 로그용 전체 값. */
class DjiException(e: IDJIError) : Exception(e.description() ?: e.errorCode()) {
    val detail = "type=${e.errorType()} code=${e.errorCode()} inner=${e.innerCode()} desc=${e.description()} hint=${e.hint()}"
}

/** DJI 콜백이 [timeoutMs] 안에 오지 않음(2026-11-03 현장: 콜백 하나가 안 와 카메라 명령이 모두 멈춤). */
class DjiTimeoutException(timeoutMs: Long) : Exception("응답 없음(${timeoutMs}ms)")

/** 운용 로그용 오류 설명: DJI 오류면 코드 전체, 아니면 메시지. */
fun Throwable.djiDetail(): String = (this as? DjiException)?.detail ?: (message ?: javaClass.simpleName)

/** [timeoutMs]가 있으면 그 안에 끝나지 않을 때 [DjiTimeoutException]. */
private suspend fun <T> timed(timeoutMs: Long?, block: suspend () -> T): T {
    if (timeoutMs == null) return block()
    val r = withTimeoutOrNull(timeoutMs) { Result.success(block()) } ?: throw DjiTimeoutException(timeoutMs)
    return r.getOrThrow()
}

/** 완료 콜백을 suspend로. 실패는 [DjiException], [timeoutMs] 안에 콜백이 없으면 [DjiTimeoutException]. */
suspend fun djiDone(timeoutMs: Long? = null, call: (CommonCallbacks.CompletionCallback) -> Unit): Unit = timed(timeoutMs) {
    suspendCancellableCoroutine { c ->
        call(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = c.resume(Unit)
            override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
        })
    }
}

/** 값 콜백을 suspend로. 실패는 [DjiException], [timeoutMs] 안에 콜백이 없으면 [DjiTimeoutException]. */
suspend fun <T> djiValue(timeoutMs: Long? = null, call: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit): T = timed(timeoutMs) {
    suspendCancellableCoroutine { c ->
        call(object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(t: T) = c.resume(t)
            override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
        })
    }
}
