package com.example.myfirstapp.drone

import android.util.Log
import com.example.myfirstapp.AppConfig
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 기체 SD 목록·받기 공용(미션 사진 받기 `DjiDroneGateway`와 앨범 기체 앨범 `DjiSdAlbum`). SDK의 SD 목록(`mediaFileListData`)은 하나뿐이라
 * 목록을 받고 읽는 동안·파일을 받는 동안은 [lock]을 잡는다. 그래야 앨범이 미션 사진 받기의 목록을 바꾸지 않는다.
 */
internal object DjiMedia {
    val lock = Mutex()
    val media get() = MediaDataCenter.getInstance().mediaManager

    /** 미디어 모드를 켰는지(한 번 켜면 두고 쓴다). */
    @Volatile private var enabled = false

    /**
     * SD 최신 목록 [count]개를 받는다. 비행 중에는 미디어 모드 없이 응답이 오지 않을 수 있어(2026-10-30 시뮬레이터), 10초 안에 답이 없으면
     * 미디어 모드를 켜고 다시 받는다. 어느 쪽으로 됐는지 로그로 남긴다. [lock]을 잡은 채 부른다.
     */
    suspend fun pullRecent(count: Int) {
        val param = PullMediaFileListParam.Builder().mediaFileIndex(-1).count(count).build()
        if (!enabled) {
            val ok = withTimeoutOrNull(AppConfig.Timeouts.SD_LIST_MS) { djiDone { media.pullMediaFileListFromCamera(param, it) } } != null
            if (ok) return Log.i(TAG, "SD 목록(미디어 모드 없이) ${media.mediaFileListData.data.size}개").let {}
            Log.w(TAG, "SD 목록 응답 없음 → 미디어 모드 켬")
            runCatching { djiDone { media.enable(it) } }.onFailure { Log.w(TAG, "미디어 모드 실패: ${it.message}") }
            enabled = true
        }
        withTimeoutOrNull(AppConfig.Timeouts.SD_LIST_MEDIA_MODE_MS) { djiDone { media.pullMediaFileListFromCamera(param, it) } } ?: error("SD 목록 응답 없음")
        Log.i(TAG, "SD 목록(미디어 모드) ${media.mediaFileListData.data.size}개")
    }

    private const val TAG = "DjiMedia"
}
