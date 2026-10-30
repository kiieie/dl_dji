package com.example.myfirstapp.drone

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/** 기체 카메라 영상(FPV). UX SDK 대신 SDK 영상 스트림을 SurfaceView에 바로 그린다(10장, UX SDK는 Maven에 없음). */
@Composable
fun DjiFpvView(modifier: Modifier) {
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
