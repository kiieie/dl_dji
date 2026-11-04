package com.example.myfirstapp.detect

import android.content.Context
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.detect.ModelPick
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.thermalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 모델 속도 측정(9.6, 계획 M.5 RC 실측). 기체 없이 조종기만으로 돈다(외부 도구 없이 앱 안에서).
 * 앱 폴더 `models/`의 모든 모델을 CPU([AppConfig.Detector.THREADS] 스레드)와 GPU로 [AppConfig.Bench.WARMUP]번 데우고
 * [AppConfig.Bench.RUNS]번 재어 단계별 시간(디코딩·전처리·추론·후처리, ms 평균/최소/최대)과 앞뒤 발열 상태를 남긴다.
 * `role: live` 모델은 가운데 자르기([AppConfig.Detector.LIVE_CROP_FRACTION])도 잰다. 이어서 비행 중 모델(자르기)을
 * [AppConfig.Bench.SUSTAINED_RUNS]번 연달아 돌려 [AppConfig.Bench.THERMAL_EVERY]번마다 발열 상태를 남긴다.
 * 사진은 앱 폴더 `projects/` 아래 가장 최근 이미지, 없으면 `fake_photos/`. 결과는 운용 로그(`bench`)와 `logs/bench-<시각>.txt`.
 * 재는 동안 정밀 탐지 일꾼은 쉰다.
 */
object ModelBench {
    private val _status = MutableStateFlow("모델 속도 측정: 버튼을 누르세요")
    val status: StateFlow<String> = _status.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope, settings: SettingsStore, precise: PreciseDetection) {
        if (job?.isActive == true) return
        job = scope.launch {
            precise.benchRunning.value = true
            try {
                _status.value = "모델 속도 측정 중…"
                val file = run(context, settings)
                _status.value = "✅ 모델 속도 측정 끝: ${file.path}"
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                OpLog.log("bench", "error", mapOf("message" to (e.message ?: e::class.simpleName)))
                _status.value = "❌ 모델 속도 측정 실패: ${e.message ?: e::class.simpleName}"
            } finally {
                precise.benchRunning.value = false
            }
        }
    }

    private suspend fun run(context: Context, settings: SettingsStore): File {
        val root = context.getExternalFilesDir(null)
        val modelsDir = File(root, "models")
        val photo = withContext(Dispatchers.IO) { benchPhoto(root) } ?: error("측정할 사진이 없습니다(projects/**/images/ 또는 fake_photos/)")
        val specs = withContext(Dispatchers.IO) { readModelSpecs(modelsDir) }
        require(specs.isNotEmpty()) { "models/에 모델이 없습니다" }
        val out = StringBuilder()
        fun line(text: String, detail: Map<String, Any?> = emptyMap()) {
            out.appendLine(text)
            OpLog.log("bench", "result", detail + ("line" to text))
            _status.value = "모델 속도 측정 중… $text"
        }
        line("사진 ${photo.path} (${photo.length() / 1024}KB), 발열 ${thermalStatus(context)}")
        for ((dir, spec) in specs.toSortedMap()) {
            val crops = listOf(1f) + if (spec.role == ModelPick.LIVE) listOf(AppConfig.Detector.LIVE_CROP_FRACTION) else emptyList()
            for (gpu in listOf(false, true)) {
                val det = runCatching { TfliteYoloDetector.open(File(modelsDir, dir), gpu) }
                    .getOrElse { line("$dir ${if (gpu) "GPU" else "CPU"} 열기 실패: ${it.message}"); continue }
                try {
                    if (gpu && det.delegate != "GPU") {
                        line("$dir GPU 안 됨(CPU로 열림) — 건너뜀")
                        continue
                    }
                    for (crop in crops) {
                        val before = thermalStatus(context)
                        repeat(AppConfig.Bench.WARMUP) { det.detectTimed(photo, crop) }
                        val runs = List(AppConfig.Bench.RUNS) { det.detectTimed(photo, crop) }
                        val after = thermalStatus(context)
                        line(
                            "$dir ${det.delegate} 입력${spec.inputSize} 자르기$crop 축소1/${runs.first().sample} " +
                                "decode ${stat(runs) { it.decodeMs }} pre ${stat(runs) { it.preMs }} infer ${stat(runs) { it.inferMs }} " +
                                "post ${stat(runs) { it.postMs }} total ${stat(runs) { it.totalMs }} 상자${runs.last().detections.size} 발열 $before→$after",
                            mapOf("model" to dir, "delegate" to det.delegate, "crop" to crop, "inferAvg" to runs.map { it.inferMs }.average().toInt(), "totalAvg" to runs.map { it.totalMs }.average().toInt()),
                        )
                    }
                } finally {
                    det.close()
                }
            }
        }
        // 연속: 비행 중 모델(자르기), GPU가 되면 GPU
        val live = ModelPick.liveOrder(specs.map { (d, s) -> s.choice(d) }, settings.liveModelDir.first())
        TfliteYoloDetector.openFirst(modelsDir, live, gpu = true)?.let { det ->
            try {
                line("연속 ${AppConfig.Bench.SUSTAINED_RUNS}회: ${det.spec.version} ${det.delegate} 자르기${AppConfig.Detector.LIVE_CROP_FRACTION}, 발열 ${thermalStatus(context)}")
                val chunk = mutableListOf<TfliteYoloDetector.Timed>()
                for (i in 1..AppConfig.Bench.SUSTAINED_RUNS) {
                    chunk += det.detectTimed(photo, AppConfig.Detector.LIVE_CROP_FRACTION)
                    if (i % AppConfig.Bench.THERMAL_EVERY == 0) {
                        line("연속 $i: total ${stat(chunk) { it.totalMs }} 발열 ${thermalStatus(context)}", mapOf("run" to i, "thermal" to thermalStatus(context)))
                        chunk.clear()
                    }
                }
            } finally {
                det.close()
            }
        }
        val file = File(root, "logs/bench-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt")
        withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeText(out.toString()) }
        return file
    }

    /** 평균/최소/최대(ms). */
    private fun stat(runs: List<TfliteYoloDetector.Timed>, f: (TfliteYoloDetector.Timed) -> Double) =
        runs.map(f).let { "%.0f/%.0f/%.0f".format(it.average(), it.min(), it.max()) }

    /** 앱이 저장한 가장 최근 사진(실제 현장 사진), 없으면 모의 사진 폴더의 첫 사진. */
    private fun benchPhoto(root: File?): File? =
        File(root, "projects").walkTopDown().filter { it.isFile && it.extension.equals("jpg", true) && it.parentFile?.name == "images" }.maxByOrNull { it.lastModified() }
            ?: File(root, "fake_photos").listFiles { f -> f.extension.equals("jpg", true) }?.minByOrNull { it.name }
}
