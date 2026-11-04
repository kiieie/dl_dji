package com.example.myfirstapp.detect

import android.content.Context
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.detect.ModelPick
import com.example.myfirstapp.core.detect.agreeing
import com.example.myfirstapp.core.detect.isProgressiveJpeg
import com.example.myfirstapp.core.detect.jpegSof
import com.example.myfirstapp.core.model.Detection
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
 * `role: live` 모델은 가운데 자르기([AppConfig.Detector.LIVE_CROP_FRACTION])도 잰다: 받은 JPEG(메모리)로 가운데 영역 디코딩(9.7 O1)과
 * 옛 전체 디코딩을 나란히 재고 두 상자를 대조한다. 모델마다 열기(인터프리터·delegate 만들기)와 첫 추론을 잰다(9.7 0):
 * GPU는 먼저 기본(FAST)을 캐시 없이 열고(모델·런타임이 바뀐 뒤 첫 열기), 지속 성능(SUSTAINED) 캐시가 있으면 그것으로 다시 열어 잰다.
 * 캐시가 없으면 FAST로 재고 "캐시 없음 – 뒤에서 생성"을 남긴다. [full]이면 캐시를 만들어 잰다(640 1분·1280 7분, 9.7 두 단계 열기).
 * 이어서 비행 중 모델(자르기)을 [AppConfig.Bench.SUSTAINED_RUNS]번 연달아 돌려 [AppConfig.Bench.THERMAL_EVERY]번마다 발열 상태를 남긴다.
 * 사진은 [photo](절대 경로), 없으면 앱 폴더 `projects/` 아래 가장 최근 실제 사진(모의 기체 사진 `_FAKE_` 제외), 그다음 모의 사진.
 * 결과는 운용 로그(`bench`)와 `logs/bench-<시각>.txt`. 재는 동안 정밀 탐지 일꾼과 GPU 캐시 만들기는 쉰다.
 */
object ModelBench {
    private val _status = MutableStateFlow("모델 속도 측정: 버튼을 누르세요")
    val status: StateFlow<String> = _status.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope, settings: SettingsStore, precise: PreciseDetection, full: Boolean = false, photo: String? = null) {
        if (job?.isActive == true) return
        job = scope.launch {
            precise.benchRunning.value = true
            try {
                _status.value = "모델 속도 측정 중…"
                val file = run(context, settings, precise.gpuCache, full, photo)
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

    private suspend fun run(context: Context, settings: SettingsStore, gpuCache: File, full: Boolean, photoPath: String?): File {
        val root = context.getExternalFilesDir(null)
        val modelsDir = File(root, "models")
        val photo = withContext(Dispatchers.IO) {
            photoPath?.let { p -> File(p).also { require(it.isFile) { "사진이 없습니다: $p" } } } ?: benchPhoto(root)
        } ?: error("측정할 사진이 없습니다(projects/**/images/ 또는 fake_photos/)")
        val specs = withContext(Dispatchers.IO) { readModelSpecs(modelsDir) }
        require(specs.isNotEmpty()) { "models/에 모델이 없습니다" }
        val out = StringBuilder()
        fun line(text: String, detail: Map<String, Any?> = emptyMap()) {
            out.appendLine(text)
            OpLog.log("bench", "result", detail + ("line" to text))
            _status.value = "모델 속도 측정 중… $text"
        }
        // 비행 중 1장은 받은 JPEG를 메모리에서 푼다(가운데 영역 디코딩은 기준 JPEG만, 9.7 O1)
        val bytes = withContext(Dispatchers.IO) { photo.readBytes() }
        val jpeg = jpegSof(bytes)?.let { "JPEG ${if (isProgressiveJpeg(bytes)) "점진" else "기준"}(SOF %02X)".format(it) } ?: "JPEG 형식 모름"
        line("사진 ${photo.path} (${photo.length() / 1024}KB, $jpeg), 발열 ${thermalStatus(context)}")
        line("GPU 백엔드는 logcat 'tflite' 태그에서 확인: \"Initialized OpenCL-based API\"(캐시 됨) 또는 \"OpenGL-based API\"(캐시 안 됨)")
        val prep = (context.applicationContext as? InspectionApp)?.gpuPrep?.status?.value
        line(
            "지속(SUSTAINED) 캐시: ${if (full) "없으면 만들어 잼(full, 모델마다 수 분)" else "있을 때만 잼(만들려면 --es bench full)"} · " +
                "${prep?.text ?: "모델 GPU 준비 모름"}${if (prep?.building == true) " — 지금 만드는 중이라 GPU 측정이 흐려질 수 있음" else ""}",
        )

        /** 열기(인터프리터·delegate 만들기)와 첫 추론 시간(9.7 0). GPU 캐시 파일 수 앞뒤로 캐시를 썼는지 본다. 실패하면 null. */
        suspend fun openTimed(dir: String, gpu: Boolean, how: String, cache: File?, sustained: Boolean = false): TfliteYoloDetector? {
            val cached = gpuCache.listFiles()?.size ?: 0
            val t0 = System.nanoTime()
            val det = runCatching { TfliteYoloDetector.open(File(modelsDir, dir), gpu, cache, sustained) }
                .getOrElse { line("$dir ${if (gpu) "GPU" else "CPU"} 열기 실패: ${it.message}"); return null }
            val openMs = ((System.nanoTime() - t0) / 1e6).toInt()
            val first = det.detectTimed(photo)
            line(
                "$dir ${det.name} 열기($how) ${openMs}ms + 첫 추론 ${first.inferMs.toInt()}ms(전체 ${first.totalMs.toInt()}ms) " +
                    "GPU 캐시 파일 $cached→${gpuCache.listFiles()?.size ?: 0}",
                mapOf("model" to dir, "delegate" to det.delegate, "gpu" to det.preference, "how" to how, "openMs" to openMs, "firstInferMs" to first.inferMs.toInt()),
            )
            return det
        }

        for ((dir, spec) in specs.toSortedMap()) {
            val crops = listOf(1f) + if (spec.role == ModelPick.LIVE) listOf(AppConfig.Detector.LIVE_CROP_FRACTION) else emptyList()
            for (gpu in listOf(false, true)) {
                // GPU는 기본(FAST)을 캐시 없이 먼저 연다(모델을 바꾼 뒤·런타임이 바뀐 뒤 첫 열기와 같음). 지속 성능은 캐시가 있을 때만(full이면 만든다)
                var det = openTimed(dir, gpu, if (gpu) "FAST 캐시 없이" else "1회", cache = null) ?: continue
                if (gpu && det.delegate == "GPU") {
                    val has = TfliteYoloDetector.hasSustainedCache(File(modelsDir, dir), gpuCache)
                    if (has || full) {
                        det.close()
                        det = openTimed(dir, true, if (has) "SUSTAINED 캐시로" else "SUSTAINED 캐시 만들기", gpuCache, sustained = true) ?: continue
                    } else {
                        line("$dir GPU SUSTAINED 캐시 없음 – 뒤에서 생성(모델 GPU 준비), 아래는 FAST로 잼")
                    }
                }
                try {
                    if (gpu && det.delegate != "GPU") {
                        line("$dir GPU 안 됨(CPU로 열림) — 건너뜀")
                        continue
                    }
                    for (crop in crops) {
                        // 자르기는 가운데 영역 디코딩(새)과 전체 디코딩(옛)을 나란히, 전체 사진은 파일로(정밀 탐지와 같게)
                        val last = mutableMapOf<Boolean, List<Detection>>()
                        for (region in if (crop < 1f) listOf(true, false) else listOf(false)) {
                            val once = suspend { if (crop < 1f) det.detectTimed(bytes, crop, region) else det.detectTimed(photo) }
                            val before = thermalStatus(context)
                            repeat(AppConfig.Bench.WARMUP) { once() }
                            val runs = List(AppConfig.Bench.RUNS) { once() }
                            val after = thermalStatus(context)
                            last[region] = runs.last().detections
                            line(
                                "$dir ${det.name} 입력${spec.inputSize} 자르기$crop ${if (runs.first().region) "영역디코딩" else "전체디코딩"} 축소1/${runs.first().sample} " +
                                    "decode ${stat(runs) { it.decodeMs }} pre ${stat(runs) { it.preMs }} infer ${stat(runs) { it.inferMs }} " +
                                    "post ${stat(runs) { it.postMs }} total ${stat(runs) { it.totalMs }} 상자${runs.last().detections.size} 발열 $before→$after",
                                mapOf(
                                    "model" to dir, "delegate" to det.delegate, "crop" to crop, "region" to runs.first().region,
                                    "inferAvg" to runs.map { it.inferMs }.average().toInt(), "totalAvg" to runs.map { it.totalMs }.average().toInt(),
                                ),
                            )
                        }
                        if (crop < 1f) {
                            val old = last.getValue(false)
                            val new = last.getValue(true)
                            line("$dir ${det.name} 상자 대조(옛 ${old.size}개 중 새와 같은 것): ${agreeing(old, new, AppConfig.Bench.BOX_MATCH_IOU, AppConfig.Bench.BOX_MATCH_SCORE)}, 새 ${new.size}개")
                        }
                    }
                } finally {
                    det.close()
                }
            }
        }
        // 연속: 비행 중 모델(자르기), GPU가 되면 GPU
        val live = ModelPick.liveOrder(specs.map { (d, s) -> s.choice(d) }, settings.liveModelDir.first())
        TfliteYoloDetector.openFirst(modelsDir, live, gpu = true, gpuCache)?.let { det ->
            try {
                line("연속 ${AppConfig.Bench.SUSTAINED_RUNS}회: ${det.spec.version} ${det.name} 자르기${AppConfig.Detector.LIVE_CROP_FRACTION}, 발열 ${thermalStatus(context)}")
                val chunk = mutableListOf<TfliteYoloDetector.Timed>()
                for (i in 1..AppConfig.Bench.SUSTAINED_RUNS) {
                    chunk += det.detectTimed(bytes, AppConfig.Detector.LIVE_CROP_FRACTION)
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

    /** 줄에 쓰는 이름: CPU4, GPU-FAST, GPU-SUSTAINED. */
    private val TfliteYoloDetector.name get() = delegate + (preference?.let { "-$it" } ?: "")

    /**
     * 앱이 저장한 가장 최근 실제 사진(탐지 상자가 나오게, 모의 기체 사진은 이름에 `_FAKE_`), 없으면 가장 최근 모의 기체 사진,
     * 그것도 없으면 모의 사진 폴더의 첫 사진.
     */
    private fun benchPhoto(root: File?): File? {
        val photos = File(root, "projects").walkTopDown().filter { it.isFile && it.extension.equals("jpg", true) && it.parentFile?.name == "images" }.toList()
        return photos.filterNot { "_FAKE_" in it.name }.maxByOrNull { it.lastModified() }
            ?: photos.maxByOrNull { it.lastModified() }
            ?: File(root, "fake_photos").listFiles { f -> f.extension.equals("jpg", true) }?.minByOrNull { it.name }
    }
}
