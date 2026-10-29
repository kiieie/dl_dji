package com.example.myfirstapp.drone

import com.example.myfirstapp.core.port.RcButton
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.manager.KeyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** RC Pro 버튼 누름을 DJI KeyManager로 받는다. SDK 등록 뒤에 모아야 한다(PoC ⑥, `rckey.log`). */
fun djiRcButtonPresses(): Flow<RcButton> = callbackFlow {
    val holder = Any()
    val keys = mapOf(
        RcButton.PAUSE to RemoteControllerKey.KeyPauseButtonDown,
        RcButton.C1 to RemoteControllerKey.KeyCustomButton1Down,
        RcButton.C2 to RemoteControllerKey.KeyCustomButton2Down,
        RcButton.RTH to RemoteControllerKey.KeyGoHomeButtonDown,
    )
    for ((button, info) in keys) {
        KeyManager.getInstance().listen(KeyTools.createKey(info), holder) { _, down -> if (down == true) trySend(button) }
    }
    awaitClose { KeyManager.getInstance().cancelListen(holder) }
}
