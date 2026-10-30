package com.example.myfirstapp.drone

import com.example.myfirstapp.core.port.RcButton
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.FiveDimensionPressedStatus
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
        // 위쪽 ○ 버튼(C3)은 확인 = 5D 누름과 같게 쓴다(사용자 결정 2026-10-30)
        RcButton.FIVE_D_CENTER to RemoteControllerKey.KeyCustomButton3Down,
        RcButton.RTH to RemoteControllerKey.KeyGoHomeButtonDown,
        // 셔터는 기체가 직접 찍는다. 앱은 SD에서 그 사진을 찾아 받는다(6.4 RC 셔터)
        RcButton.SHUTTER to RemoteControllerKey.KeyShutterButtonDown,
    )
    for ((button, info) in keys) {
        KeyManager.getInstance().listen(KeyTools.createKey(info), holder) { _, down -> if (down == true) trySend(button) }
    }
    // 5D 버튼은 다섯 방향을 한 값으로 준다. 눌리기 시작한 방향만 보낸다.
    KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyFiveDimensionPressedStatus), holder) { old, now ->
        fun edge(was: FiveDimensionPressedStatus?.() -> Boolean?, b: RcButton) {
            if (now.was() == true && old.was() != true) trySend(b)
        }
        edge({ this?.upwards }, RcButton.FIVE_D_UP)
        edge({ this?.downwards }, RcButton.FIVE_D_DOWN)
        edge({ this?.leftwards }, RcButton.FIVE_D_LEFT)
        edge({ this?.rightwards }, RcButton.FIVE_D_RIGHT)
        edge({ this?.middlePressed }, RcButton.FIVE_D_CENTER)
    }
    awaitClose { KeyManager.getInstance().cancelListen(holder) }
}
