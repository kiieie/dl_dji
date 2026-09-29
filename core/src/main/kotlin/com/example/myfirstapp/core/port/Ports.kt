package com.example.myfirstapp.core.port

import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.core.model.Detection
import com.example.myfirstapp.core.model.VisitStatus

/** 탐지기 포트(9장). 모델이 없거나 로드에 실패하면 구현을 주입하지 않는다(null). */
interface Detector {
    val modelVersion: String
    suspend fun detect(image: ByteArray): List<Detection>
}

/** 알림음(11장). */
enum class Sound { ARRIVED, INSPECTION_OK, ANOMALY, PAUSED, RESUMED, BATTERY_WARNING, ERROR }

fun interface Notifier {
    fun play(sound: Sound)
}

/** 구간마다 확인하는 저장 공간(6.5). 조종기 쪽 공간을 확인한다. */
fun interface StorageMonitor {
    suspend fun hasEnoughSpace(): Boolean
}

/**
 * 실행 기록 포트. 앱에서는 Room으로 구현한다(4.2).
 * 방문은 (소티 순번, 소티 안 순서)로 식별한다.
 */
interface InspectionRecorder {
    suspend fun recordTransition(sortieIndex: Int, from: String, to: String, reason: String, poleId: String?)
    suspend fun updateVisit(sortieIndex: Int, order: Int, status: VisitStatus, skipReason: String? = null)

    /** 사진 기록을 저장하고 ID를 돌려준다. */
    suspend fun saveCapture(sortieIndex: Int, order: Int, capture: CaptureInfo, source: CaptureSource): Long
    suspend fun saveImage(captureId: Long, bytes: ByteArray)
    suspend fun saveDetections(captureId: Long, modelVersion: String, detections: List<Detection>)
}
