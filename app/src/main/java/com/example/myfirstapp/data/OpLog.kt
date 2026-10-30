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
        writer.execute {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
            d.listFiles { f -> f.name.startsWith("ops-") && f.lastModified() < cutoff }?.forEach { it.delete() }
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

    /** 내보내기(4.3)용: [fromMs] 이후 로그 파일들. */
    fun files(fromMs: Long): List<File> =
        dir?.listFiles { f -> f.name.startsWith("ops-") && f.lastModified() >= fromMs }?.sortedBy { it.name }.orEmpty()
}
