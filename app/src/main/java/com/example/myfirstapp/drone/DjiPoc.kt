package com.example.myfirstapp.drone

import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 기체 PoC(13장 1단계) 시험 호출. 연동 검증 화면 전용이며, 결과는 로그 태그 `DjiPoc`에도 남긴다.
 * 실제 비행 연동은 PoC 결과를 본 뒤 `DjiDroneGateway`로 만든다.
 */
object DjiPoc {
    private val camera = ComponentIndexType.LEFT_OR_MAIN

    class DjiException(e: IDJIError) : Exception(e.description() ?: e.errorCode())

    private fun log(text: String) = text.also { Log.i("DjiPoc", it) }

    private suspend fun done(call: (CommonCallbacks.CompletionCallback) -> Unit) = suspendCancellableCoroutine { c ->
        call(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = c.resume(Unit)
            override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
        })
    }

    private suspend fun <T> value(call: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit) = suspendCancellableCoroutine { c ->
        call(object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(t: T) = c.resume(t)
            override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
        })
    }

    private inline fun <T> step(name: String, call: () -> T): T =
        try { call() } catch (e: DjiException) { throw Exception("$name: ${e.message}") }

    /** 기체 연결 여부와 기종. */
    fun aircraft(): String {
        val keys = KeyManager.getInstance()
        val connected = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false)
        val type = keys.getValue(KeyTools.createKey(ProductKey.KeyProductType))
        val gps = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount))
        val gpsLevel = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel))
        val mode = keys.getValue(KeyTools.createKey(CameraKey.KeyCameraMode, camera))
        val photoMode = keys.getValue(KeyTools.createKey(CameraKey.KeyShootPhotoMode, camera))
        val sd = keys.getValue(KeyTools.createKey(CameraKey.KeyCameraSDCardState, camera))
        val sdFree = keys.getValue(KeyTools.createKey(CameraKey.KeySDCardRemainSpace, camera))
        return log(
            "기체: ${if (connected) "연결됨" else "연결 안 됨"}, 기종 $type, 시뮬레이터 ${SimulatorManager.getInstance().isSimulatorEnabled}, " +
                "GPS ${gps}개 $gpsLevel, 카메라 $mode/$photoMode, SD $sd(남은 ${sdFree}MB)",
        )
    }

    /** PoC ③: 촬영 → SD 최신 사진 → 미리보기·원본 다운로드 시간. 원본은 [dir]에 저장한다. */
    suspend fun shootAndDownload(dir: File): String {
        val t0 = System.currentTimeMillis()
        step("촬영") { value<EmptyMsg> { KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, camera), it) } }
        val tShot = System.currentTimeMillis()
        delay(1500) // 사진이 SD에 써질 시간
        val media = MediaDataCenter.getInstance().mediaManager
        media.setMediaFileDataSource(MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).setIndexType(camera).build())
        try {
            media.enable(null)
            step("목록") { done { media.pullMediaFileListFromCamera(PullMediaFileListParam.Builder().mediaFileIndex(-1).count(5).build(), it) } }
            val tList = System.currentTimeMillis()
            val file: MediaFile = media.mediaFileListData.data.maxByOrNull { it.fileIndex } ?: error("SD에 사진 없음")
            step("미리보기") { value { file.pullPreviewFromCamera(it) } }
            val tPreview = System.currentTimeMillis()
            dir.mkdirs()
            val out = File(dir, file.fileName)
            FileOutputStream(out).use { stream ->
                suspendCancellableCoroutine { c ->
                    file.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
                        override fun onStart() {}
                        override fun onProgress(total: Long, current: Long) {}
                        override fun onRealtimeDataUpdate(data: ByteArray, position: Long) = stream.write(data)
                        override fun onFinish() = c.resume(Unit)
                        override fun onFailure(error: IDJIError) = c.resumeWithException(DjiException(error))
                    })
                }
            }
            val tOriginal = System.currentTimeMillis()
            return log(
                "촬영 ${tShot - t0}ms, 목록 ${tList - tShot - 1500}ms(+대기 1.5초), 미리보기 ${tPreview - tList}ms, " +
                    "원본 ${file.fileName} ${out.length() / 1024}KB ${tOriginal - tPreview}ms",
            )
        } finally {
            media.disable(null)
        }
    }

    /** PoC ⑥ 준비: 시뮬레이터 켜기(프로펠러 제거 상태). 위치는 시험용 대전 좌표. */
    suspend fun simulator(on: Boolean): String {
        val sim = SimulatorManager.getInstance()
        if (on) done { sim.enableSimulator(InitializationSettings.createInstance(LocationCoordinate2D(36.3504, 127.3845), 15), it) }
        else done { sim.disableSimulator(it) }
        return log("시뮬레이터 ${if (sim.isSimulatorEnabled) "켜짐" else "꺼짐"}")
    }
}
