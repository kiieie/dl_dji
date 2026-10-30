package com.example.myfirstapp.drone

import com.example.myfirstapp.data.OpLog
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.manager.KeyManager
import kotlin.math.abs

/**
 * RC Pro 하드웨어 입력을 모두 운용 로그(4.4)에 남긴다. 지금 앱이 쓰지 않는 버튼도 기록해 나중에 기능을 붙일 근거로 쓴다(2026-10-30 사용자 요청).
 * 버튼은 눌림·뗌, 다이얼·휠·스위치는 값이 바뀔 때, 스틱은 움직일 때 0.5초에 한 번만. SDK 등록 뒤 한 번 부른다.
 */
fun startDjiInputLog() {
    val keys = KeyManager.getInstance()
    val holder = Any()
    fun <T> on(name: String, info: DJIKeyInfo<T>, log: (T?, T?) -> Unit) =
        runCatching { keys.listen(KeyTools.createKey(info), holder) { old, now -> log(old, now) } }

    val buttons = listOf(
        "PAUSE" to RemoteControllerKey.KeyPauseButtonDown,
        "C1" to RemoteControllerKey.KeyCustomButton1Down,
        "C2" to RemoteControllerKey.KeyCustomButton2Down,
        "C3" to RemoteControllerKey.KeyCustomButton3Down,
        "C4" to RemoteControllerKey.KeyRCCustomButton4Down,
        "RTH" to RemoteControllerKey.KeyGoHomeButtonDown,
        "SHUTTER" to RemoteControllerKey.KeyShutterButtonDown,
        "SHUTTER_LONG" to RemoteControllerKey.KeyRCShutterButtonLongPress,
        "RECORD" to RemoteControllerKey.KeyRecordButtonDown,
        "PLAYBACK" to RemoteControllerKey.KeyRCPlaybackButtonDown,
        "SWITCH" to RemoteControllerKey.KeyRCSwitchButtonDown,
        "RIGHT_WHEEL" to RemoteControllerKey.KeyRCRightWheelButtonDown,
        "AUTH_LED" to RemoteControllerKey.KeyRCAuthLedButtonDown,
    )
    buttons.forEach { (name, info) ->
        // 구독할 때 처음 한 번 오는 현재 값(old == null)은 입력이 아니므로 뺀다
        on(name, info) { old, now -> if (old != null && now != null && now != old) OpLog.log("rcKey", name, mapOf("down" to now)) }
    }
    listOf("LEFT_DIAL" to RemoteControllerKey.KeyLeftDial, "RIGHT_DIAL" to RemoteControllerKey.KeyRightDial, "SCROLL_WHEEL" to RemoteControllerKey.KeyScrollWheel)
        .forEach { (name, info) -> on(name, info) { old, now -> if (old != null && now != null && now != old && now != 0) OpLog.log("rcDial", name, mapOf("value" to now)) } }
    on("FLIGHT_MODE", RemoteControllerKey.KeyFlightModeSwitchState) { old, now -> if (now != old) OpLog.log("rcSwitch", "flightMode", mapOf("value" to now?.name)) }
    on("5D", RemoteControllerKey.KeyFiveDimensionPressedStatus) { old, now -> if (old != null && now != null && now != old) OpLog.log("rc5d", now.toString()) }

    // 스틱: 가운데(±30)를 벗어나 움직이는 동안만 0.5초에 한 번
    val sticks = listOf(
        "LEFT_H" to RemoteControllerKey.KeyStickLeftHorizontal, "LEFT_V" to RemoteControllerKey.KeyStickLeftVertical,
        "RIGHT_H" to RemoteControllerKey.KeyStickRightHorizontal, "RIGHT_V" to RemoteControllerKey.KeyStickRightVertical,
    )
    val last = LongArray(sticks.size)
    sticks.forEachIndexed { i, (name, info) ->
        on(name, info) { old, now ->
            val v = now ?: return@on
            val t = System.currentTimeMillis()
            val leftCenter = abs(v) > 30
            val backToCenter = !leftCenter && abs(old ?: 0) > 30
            if ((leftCenter && t - last[i] >= 500) || backToCenter) {
                last[i] = t
                OpLog.log("rcStick", name, mapOf("value" to v))
            }
        }
    }
}
