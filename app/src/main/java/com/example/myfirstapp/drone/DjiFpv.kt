package com.example.myfirstapp.drone

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myfirstapp.AppConfig
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.delay

/**
 * 기체 카메라 영상(FPV). UX SDK 대신 SDK 영상 스트림을 SurfaceView에 바로 그린다(10장, UX SDK는 Maven에 없음).
 * 영상은 비율을 지켜 가운데(CENTER_INSIDE)라 4:3 사진 모드면 양옆에 띠가 생긴다. 스트림 프레임 크기가 바뀌면 [onStreamSize]로 알린다
 * (격자·줌 영역 사각형을 영상 자리에 맞춤, 10.5.2).
 */
@Composable
fun DjiFpvView(modifier: Modifier, onStreamSize: (IntSize) -> Unit = {}) {
    val report by rememberUpdatedState(onStreamSize)
    LaunchedEffect(Unit) {
        var last: IntSize? = null
        while (true) {
            val info = runCatching { MediaDataCenter.getInstance().cameraStreamManager.getAircraftStreamFrameInfo(ComponentIndexType.LEFT_OR_MAIN) }.getOrNull()
            val size = info?.takeIf { it.width > 0 && it.height > 0 }?.let { IntSize(it.width, it.height) }
            if (size != null && size != last) { last = size; report(size) }
            delay(AppConfig.Polling.FPV_SIZE_MS)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    private val streams get() = MediaDataCenter.getInstance().cameraStreamManager
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        streams.putCameraStreamSurface(ComponentIndexType.LEFT_OR_MAIN, holder.surface, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) = streams.removeCameraStreamSurface(holder.surface)
                })
            }
        },
    )
}
