package com.example.myfirstapp.sound

import com.example.myfirstapp.AppConfig
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.example.myfirstapp.core.port.Notifier
import com.example.myfirstapp.core.port.Sound

/**
 * 알림음(11장). 녹음한 음성 파일이 준비되기 전까지 ToneGenerator 신호음으로 대신한다.
 * 스모크 시험이 재생 순서를 확인하도록 logcat(`Sound`)에도 남긴다.
 */
class ToneNotifier : Notifier {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, AppConfig.Sound.VOLUME) }.getOrNull()

    override fun play(sound: Sound) {
        Log.i("Sound", sound.name)
        val (type, ms) = when (sound) {
            Sound.ARRIVED -> ToneGenerator.TONE_PROP_BEEP to AppConfig.Sound.ARRIVED_MS
            Sound.INSPECTION_OK -> ToneGenerator.TONE_PROP_ACK to AppConfig.Sound.INSPECTION_OK_MS
            Sound.ANOMALY -> ToneGenerator.TONE_PROP_BEEP2 to AppConfig.Sound.ANOMALY_MS
            Sound.PAUSED -> ToneGenerator.TONE_PROP_PROMPT to AppConfig.Sound.PAUSED_MS
            Sound.RESUMED -> ToneGenerator.TONE_PROP_ACK to AppConfig.Sound.RESUMED_MS
            Sound.BATTERY_WARNING -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK to AppConfig.Sound.BATTERY_WARNING_MS
            Sound.ERROR -> ToneGenerator.TONE_PROP_NACK to AppConfig.Sound.ERROR_MS
        }
        tone?.startTone(type, ms)
    }

    /** 충돌 경고 짧은 삑 하나(11장). 반복 간격은 부르는 쪽(`ui/flight/ObstacleWatch.kt`)이 등급에 따라 정한다. */
    fun obstacleBeep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, AppConfig.Sound.OBSTACLE_BEEP_MS)
    }

    fun release() = tone?.release()
}
