package com.example.myfirstapp.drone

import android.content.Context
import com.dji.wpmzsdk.manager.WPMZManager
import dji.sdk.wpmz.value.mission.WaylineCheckError

/** DJI WPMZ 검사기 준비(앱 시작 때 한 번). */
fun initWpmz(context: Context) = runCatching { WPMZManager.getInstance().init(context) }

/**
 * 올리기 전에 KMZ를 DJI 검사기로 본다(6.2). 문제가 없으면 빈 목록, 있으면 오류 이름(예: InvalidExecuteAltitudeMode).
 * 검사기를 부를 수 없으면(초기화 전 등) "검사 불가"를 돌려준다.
 */
fun checkKmz(path: String): List<String> = runCatching {
    WPMZManager.getInstance().checkValidation(path).value.orEmpty().filter { it != WaylineCheckError.NoError }.map { it.name }
}.getOrElse { listOf("검사 불가: ${it.message}") }
