package com.example.myfirstapp.drone

import com.example.myfirstapp.data.OpLog
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager
import java.io.File

/**
 * MSDK가 앱 외부 폴더에 스스로 남기는 기록(7.2, 2026-11-06 사용자 결정): 비행 기록(DAT 포함), 웨이라인 KMZ 사본, 내부 로그.
 * Android 10에서는 Pilot 2(시스템 앱)도 읽을 수 있어 앱을 켤 때(SDK 초기화 전)와 화면을 떠날 때 지운다. 화면을 떠날 때 모터가 돌면 건너뛴다.
 * 비행에는 영향이 없다(미션은 기체에 있고, 다시 올릴 때는 앱의 `kmz/`를 쓴다).
 */
private val TRACE_DIRS = listOf("DJI/FlightRecord", "DJI/waypoint", "LOG")

fun cleanDjiTraces(externalFilesDir: File?, reason: String, checkMotors: Boolean = true) {
    val root = externalFilesDir ?: return
    val motorsOn = checkMotors && runCatching { KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn)) }.getOrNull() == true
    if (motorsOn) return
    var bytes = 0L
    for (d in TRACE_DIRS) File(root, d).listFiles()?.forEach { f ->
        bytes += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        f.deleteRecursively()
    }
    if (bytes > 0) OpLog.log("privacy", "djiTracesCleaned", mapOf("reason" to reason, "bytes" to bytes))
}
