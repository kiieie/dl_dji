package com.example.myfirstapp

import android.app.Application
import com.example.myfirstapp.data.AppDatabase
import com.example.myfirstapp.data.SettingsStore

/** 수동 DI 조립 지점(3.3). DJI SDK 초기화도 PoC 이후 이곳으로 옮긴다. */
class InspectionApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    val settings by lazy { SettingsStore(this) }
}
