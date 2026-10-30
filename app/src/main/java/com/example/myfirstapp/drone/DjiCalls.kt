package com.example.myfirstapp.drone

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DJI 콜백 실패. 메시지는 SDK 설명(없으면 오류 코드). */
class DjiException(e: IDJIError) : Exception(e.description() ?: e.errorCode())

/** 완료 콜백을 suspend로. 실패는 [DjiException]. */
suspend fun djiDone(call: (CommonCallbacks.CompletionCallback) -> Unit): Unit = suspendCancellableCoroutine { c ->
    call(object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() = c.resume(Unit)
        override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
    })
}

/** 값 콜백을 suspend로. 실패는 [DjiException]. */
suspend fun <T> djiValue(call: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit): T = suspendCancellableCoroutine { c ->
    call(object : CommonCallbacks.CompletionCallbackWithParam<T> {
        override fun onSuccess(t: T) = c.resume(t)
        override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
    })
}
