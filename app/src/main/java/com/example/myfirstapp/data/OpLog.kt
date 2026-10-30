package com.example.myfirstapp.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 운용 로그(4.4): 모든 조작과 기록을 기기 안 `logs/ops-YYYYMMDD.jsonl`에 한 줄씩 남긴다. 외부로 보내지 않는다.
 * 한 스레드에서 차례로 쓰므로 어디서 불러도 된다. [init] 전에는 Logcat에만 남긴다.
 */
object OpLog {
    private const val KEEP_DAYS = 30
    private val writer = Executors.newSingleThreadExecutor()
    private var dir: File? = null
    private val day get() = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

    fun init(context: Context) {
        val d = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        dir = d
        watchCrashes(d)
        checkLastSession(d)
        writer.execute {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
            d.listFiles { f -> f.name != "running" && f.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    /** [type]은 조작 종류(예: "tap", "nav", "flight", "plan", "setting", "error"), [what]은 무엇을, [detail]은 값. */
    fun log(type: String, what: String, detail: Map<String, Any?> = emptyMap()) {
        val now = System.currentTimeMillis()
        val line = JSONObject().put("t", now).put("type", type).put("what", what).apply {
            detail.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
        }.toString()
        Log.i("OpLog", line)
        val d = dir ?: return
        writer.execute { runCatching { File(d, "ops-${day.format(Date(now))}.jsonl").appendText(line + "\n") } }
    }

    /**
     * 처리되지 않은 예외: 프로세스가 곧 죽으므로 그 자리에서 바로 `crash-시각.txt`에 쓰고, 원래 처리기(시스템 종료 창)로 넘긴다.
     */
    private fun watchCrashes(d: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val now = System.currentTimeMillis()
                File(d, "crash-$now.txt").writeText("시각 $now\n스레드 ${thread.name}\n${Log.getStackTraceString(e)}")
                File(d, "ops-${day.format(Date(now))}.jsonl")
                    .appendText(JSONObject().put("t", now).put("type", "crash").put("what", e.toString()).toString() + "\n")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * 갑자기 꺼짐 대비: 실행 중 표시 파일을 두고 정상 종료([markCleanExit]) 때 지운다. 시작할 때 남아 있으면 이전 실행이 강제 종료·ANR·
     * 메모리 부족 등으로 끝난 것이므로, 앱 자신의 logcat(같은 앱 기록만 읽힘)을 `logcat-시각.txt`로 남긴다.
     */
    private fun checkLastSession(d: File) {
        val marker = File(d, "running")
        val lastStart = marker.takeIf { it.exists() }?.readText()
        marker.writeText(System.currentTimeMillis().toString())
        if (lastStart == null) return
        writer.execute {
            runCatching {
                val now = System.currentTimeMillis()
                val out = File(d, "logcat-$now.txt")
                // 마지막 5,000줄(DJI SDK 로그가 많아 크기를 제한)
                // redirectOutput(File)은 API 26부터라(minSdk 24) 출력 스트림을 직접 파일로 복사한다
                val p = ProcessBuilder("logcat", "-d", "-t", "5000", "-v", "threadtime").redirectErrorStream(true).start()
                p.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                p.waitFor()
                log("crash", "previousSessionAbnormal", mapOf("logcat" to out.name, "startedAt" to lastStart))
            }
        }
    }

    /** 사용자가 앱을 정상으로 닫을 때(액티비티가 끝날 때) 부른다. */
    fun markCleanExit() {
        dir?.let { File(it, "running").delete() }
        log("app", "exit")
    }

    /** 내보내기(4.3)용: [fromMs] 이후 로그 파일들. */
    fun files(fromMs: Long): List<File> =
        dir?.listFiles { f -> f.name.endsWith(".jsonl") || f.name.endsWith(".txt") }?.filter { it.lastModified() >= fromMs }?.sortedBy { it.name }.orEmpty()
}

/**
 * 비행 궤적 한 줄(4.4, 떠 있는 동안 1초마다): 자동 비행은 DB `TELEMETRY_SAMPLE`에도 남고, 수동 비행은 운용 로그에만 남는다.
 * 나중에 운용 로그만 보고도 조작·상태·위치를 함께 되짚을 수 있게 한다.
 */
fun logTelemetry(source: String, t: com.example.myfirstapp.core.model.Telemetry) = OpLog.log(
    "tele", source,
    mapOf(
        "lat" to t.position?.lat, "lon" to t.position?.lon, "alt" to t.altitudeM, "spd" to t.speedMps, "vs" to t.verticalSpeedMps,
        "hdg" to t.headingDeg, "gimbal" to t.gimbalPitchDeg, "bat" to t.batteryPct, "mode" to t.flightMode.name,
        "sats" to t.satellites, "link" to t.signalPct,
    ),
)
