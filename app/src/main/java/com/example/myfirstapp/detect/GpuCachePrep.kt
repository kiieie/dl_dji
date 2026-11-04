package com.example.myfirstapp.detect

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.detect.GpuPrep
import com.example.myfirstapp.core.detect.ModelPick
import com.example.myfirstapp.core.detect.gpuBuildPause
import com.example.myfirstapp.core.detect.nextGpuBuild
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 지속 성능(SUSTAINED) GPU 캐시를 뒤에서 만든다(9.7 두 단계 열기). 캐시가 없으면 탐지기는 기본(FAST)으로 바로 열리고([TfliteYoloDetector.open]),
 * 이 일꾼이 쓰는 모델(비행 중 → 정밀, [ModelPick.inUse])마다 SUSTAINED로 한 번 열어 캐시를 남긴다. 다음 열기부터 SUSTAINED(약 0.2초).
 * 정밀 탐지가 쉬는 이유(비행·발열·조종기 배터리·속도 측정)가 없고 열린 GPU 탐지기도 없는 채로 [AppConfig.Detector.GPU_BUILD_SETTLE_MS]가 지나면 시작한다.
 * 자기 스레드에서 열어 비행 중 1장·정밀 추론 스레드(`detector`)를 막지 않는다. 앱 수명 동안 하나([com.example.myfirstapp.InspectionApp]).
 */
class GpuCachePrep(
    private val modelsDir: File,
    private val cacheDir: File,
    private val settings: SettingsStore,
    precisePause: Flow<String?>,
    scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(GpuPrep())

    /** ⑥ 설정 탐지 모델·비행 전 점검의 "모델 GPU 준비 중 n/m"·"완료". */
    val status: StateFlow<GpuPrep> = _status.asStateFlow()

    private val kicks = MutableStateFlow(0)

    /** 이번 실행에서 실패한 모델(OpenCL이 아니거나 열리지 않음). 같은 모델에서 맴돌지 않게 앱을 다시 켤 때까지 건너뛴다. */
    private val failed = mutableSetOf<String>()

    // ponytail: 시작한 만들기는 멈출 수 없다(네이티브 열기, 640 60초·1280 7분). 그 사이 이륙하면 끝날 때까지 GPU를 함께 쓴다.
    // 그래서 카메라 뷰가 열려 있으면(비행 중 모델이 열림) 시작하지 않는다. 문제가 되면 별도 프로세스로 옮겨 죽일 수 있게 한다
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "gpu-cache") }.asCoroutineDispatcher()

    /** 모델을 가져오거나 지웠다(⑥ 설정). 할 일을 다시 본다. */
    fun kick() = kicks.update { it + 1 }

    init {
        scope.launch {
            combine(precisePause, TfliteYoloDetector.gpuOpen, settings.liveModelDir, settings.fullModelDir, kicks) { p, open, _, _, _ -> gpuBuildPause(p, open) }
                .collectLatest { pause ->
                    while (true) {
                        val (targets, cached) = scan()
                        val next = nextGpuBuild(targets, cached, failed, pause)
                        _status.value = GpuPrep(cached.size, targets.size, failed = targets.count { it in failed }, pause = pause)
                        if (next == null) break
                        // 착륙·화면 닫기 직후 정밀 탐지·비행 중 모델이 먼저 열 틈을 준다(그 사이 열리면 다시 기다린다)
                        delay(AppConfig.Detector.GPU_BUILD_SETTLE_MS)
                        _status.update { it.copy(building = true) }
                        build(next)
                    }
                }
        }
    }

    /** 쓰는 모델과 그중 지속 성능 캐시가 있는 것. */
    private suspend fun scan(): Pair<List<String>, Set<String>> = withContext(Dispatchers.IO) {
        val specs = readModelSpecs(modelsDir)
        val targets = ModelPick.inUse(specs.map { (d, s) -> s.choice(d) }, settings.liveModelDir.first(), settings.fullModelDir.first())
        targets to targets.filterTo(HashSet()) { TfliteYoloDetector.hasSustainedCache(File(modelsDir, it), cacheDir) }
    }

    /** 시작하면 끝까지 한다(멈출 수 없는 네이티브 열기). 끝난 뒤 기다리는 동안 상태가 바뀌었으면 다음 차례에서 다시 본다. */
    private suspend fun build(dir: String) = withContext(NonCancellable + thread) {
        OpLog.log("detect", "gpuCache", mapOf("step" to "build", "model" to dir))
        val t0 = System.nanoTime()
        runCatching { TfliteYoloDetector.buildSustainedCache(File(modelsDir, dir), cacheDir) }
            .onSuccess { OpLog.log("detect", "gpuCache", mapOf("step" to "built", "model" to dir, "ms" to it, "files" to cacheDir.list()?.size)) }
            .onFailure { e ->
                failed += dir // OOM·네이티브 라이브러리 오류도 여기서 잡는다
                OpLog.log("detect", "gpuCache", mapOf("step" to "fail", "model" to dir, "ms" to (System.nanoTime() - t0) / 1_000_000, "message" to (e.message ?: e::class.simpleName)))
            }
    }
}
