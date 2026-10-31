package com.example.myfirstapp.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 조종기(RC Pro) 자체 배터리(10.4.3 상단 바). RC Pro는 안드로이드 기기라 시스템 배터리 방송으로 읽는다. */
data class RcBattery(val pct: Int, val charging: Boolean) {
    companion object {
        /**
         * `ACTION_BATTERY_CHANGED`(sticky)를 한 번 등록해 값을 들고 있는다. 등록하자마자 마지막 값이 돌아오므로 처음부터 채워진다.
         * 앱 수명 동안 풀지 않는다([com.example.myfirstapp.InspectionApp]이 onCreate에서 한 번 부른다).
         */
        fun watch(context: Context): StateFlow<RcBattery?> {
            val state = MutableStateFlow<RcBattery?>(null)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    state.value = from(intent)
                }
            }
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { state.value = from(it) }
            return state.asStateFlow()
        }

        private fun from(intent: Intent): RcBattery? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return RcBattery(level * 100 / scale, charging)
        }
    }
}
