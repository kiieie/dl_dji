package com.example.myfirstapp.sound

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
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

    override fun play(sound: Sound) {
        Log.i("Sound", sound.name)
        val (type, ms) = when (sound) {
            Sound.ARRIVED -> ToneGenerator.TONE_PROP_BEEP to 150
            Sound.INSPECTION_OK -> ToneGenerator.TONE_PROP_ACK to 300
            Sound.ANOMALY -> ToneGenerator.TONE_PROP_BEEP2 to 600
            Sound.PAUSED -> ToneGenerator.TONE_PROP_PROMPT to 300
            Sound.RESUMED -> ToneGenerator.TONE_PROP_ACK to 300
            Sound.BATTERY_WARNING -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK to 1500
            Sound.ERROR -> ToneGenerator.TONE_PROP_NACK to 400
        }
        tone?.startTone(type, ms)
    }

    fun release() = tone?.release()
}
