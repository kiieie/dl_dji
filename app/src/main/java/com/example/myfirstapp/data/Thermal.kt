package com.example.myfirstapp.data

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 조종기(RC Pro, Android 10) 발열 상태(9.6 부담 제한). `PowerManager.THERMAL_STATUS_*`: 0 없음 ~ 3 SEVERE ~ 6 SHUTDOWN.
// API 29 아래에서는 알 수 없으므로 0이다.

/** 지금 발열 상태. */
fun thermalStatus(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(PowerManager::class.java).currentThermalStatus else 0

/** 발열 상태를 바뀔 때마다 받는다. 앱 수명 동안 풀지 않는다(정밀 탐지 일꾼이 한 번 부른다). */
fun watchThermal(context: Context): StateFlow<Int> {
    val state = MutableStateFlow(thermalStatus(context))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(PowerManager::class.java).addThermalStatusListener { state.value = it }
    }
    return state.asStateFlow()
}
