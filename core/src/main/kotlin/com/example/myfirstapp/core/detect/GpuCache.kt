package com.example.myfirstapp.core.detect

// GPU 직렬화 캐시와 두 단계 열기(9.7): 지속 성능(SUSTAINED) 캐시가 없으면 기본(FAST)으로 바로 열고, 땅 위에서 쉴 때 캐시를 뒤에서 만든다.

/**
 * GPU 캐시 파일 이름 규칙. LiteRT GPU delegate는 `<토큰>_<지문>.bin`으로 저장한다(쓰는 동안은 `<토큰><지문><시각>` 임시 파일을 쓰고 이름을 바꾼다).
 * 토큰은 모델 몫(폴더 이름) 8자리 + 나머지 몫(런타임·펌웨어·파일 크기·수정 시각·입력·선호) 8자리 16진이라, 모델마다 옛 캐시를 골라낼 수 있다.
 */
object GpuCacheNames {
    private const val MODEL_PART = 8

    fun token(model: String, variant: String) = "%08x%08x".format(model.hashCode(), variant.hashCode())

    /** [token]으로 다 쓴 캐시 파일이 있는지(쓰다 만 임시 파일은 세지 않는다). */
    fun has(names: Collection<String>, token: String) = names.any { it.startsWith("${token}_") && it.endsWith(".bin") }

    /** 지금 토큰([current])과 모델이 같은데 토큰이 다른 파일(옛 캐시, 쓰다 만 임시 파일 포함). 다른 모델 파일은 건드리지 않는다. */
    fun stale(names: Collection<String>, current: Collection<String>): List<String> {
        val models = current.mapTo(HashSet()) { it.take(MODEL_PART) }
        return names.filter { n -> n.take(MODEL_PART) in models && current.none { n.startsWith(it) } }
    }
}

/**
 * 캐시 만들기를 쉴 이유. [precisePause]는 정밀 탐지가 쉬는 이유(비행·발열·조종기 배터리·속도 측정)를 그대로 쓰고,
 * 열린 GPU 탐지기([gpuOpen], 비행 중 1장·정밀)가 있으면 그것도 쉰다. null이면 만든다.
 */
fun gpuBuildPause(precisePause: String?, gpuOpen: Int): String? = precisePause ?: if (gpuOpen > 0) "탐지기 사용 중" else null

/** 다음에 캐시를 만들 모델: 쉴 이유가 없으면 [targets] 순서로 캐시가 없고 이번 실행에서 실패하지 않은 첫 모델. */
fun nextGpuBuild(targets: List<String>, cached: Set<String>, failed: Set<String>, pause: String?): String? =
    if (pause != null) null else targets.firstOrNull { it !in cached && it !in failed }

/** "모델 GPU 준비" 표시(⑥ 설정 탐지 모델, 비행 전 점검). [building]이면 지금 만드는 중. 모델이 없으면 [text]가 null. */
data class GpuPrep(val done: Int = 0, val total: Int = 0, val building: Boolean = false, val failed: Int = 0, val pause: String? = null) {
    val ready get() = done == total

    val text: String?
        get() = when {
            total == 0 -> null
            ready -> "모델 GPU 준비 완료"
            building -> "모델 GPU 준비 중 $done/$total"
            done + failed == total -> "모델 GPU 준비 $done/$total · 실패 $failed"
            else -> "모델 GPU 준비 $done/$total · 대기(${pause ?: "곧 시작"})"
        }
}
