package com.example.myfirstapp.drone

import android.graphics.Bitmap
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.SdPhoto
import com.example.myfirstapp.data.OpLog
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.DateTime
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Calendar
import kotlin.coroutines.resume

/** 기체 SD 저장소(앨범 머리글, Pilot 2 "M3E 사용됨 26.6GB / 238.0GB"): MB와 남은 사진 장수. 모르면 null. */
data class SdStorage(val totalMb: Long? = null, val freeMb: Long? = null, val photosLeft: Int? = null)

/**
 * 앨범 기체 앨범 탭(10.4.9): 기체 SD의 사진을 보고 앱으로 받거나 SD에서 지운다. SD 목록·받기는 미션 사진 받기와 같은 잠금([DjiMedia.lock])이라
 * 서로 목록을 바꾸지 않는다. 떠 있는 동안은 열지 않는다(미션 사진 받기가 먼저). 공유·클라우드 기능은 부르지 않는다(7.2 데이터 주권).
 */
object DjiSdAlbum {
    private val camera = ComponentIndexType.LEFT_OR_MAIN

    /** 마지막으로 읽은 목록의 SDK 파일(이름 → 파일). 썸네일·받기·지우기에 쓴다. */
    @Volatile private var files: Map<String, MediaFile> = emptyMap()

    /** 기체 앨범을 열 수 없는 이유(Pilot 2 안내문). 열 수 있으면 null. */
    fun blocked(): String? = when {
        !djiAircraftConnected() -> "기체 연결 안 됨. 기체 앨범을 볼 수 없습니다."
        runCatching { KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying), false) }.getOrDefault(false) ->
            "비행 중. 기체 앨범을 볼 수 없습니다(미션 사진 받기가 먼저)."
        else -> null
    }

    suspend fun storage(): SdStorage = SdStorage(
        readKey(CameraKey.KeySDCardTotalSpace, camera)?.toLong(),
        readKey(CameraKey.KeySDCardRemainSpace, camera)?.toLong(),
        readKey(CameraKey.KeySDCardAvailablePhotoCount, camera),
    )

    /** SD 최신 사진([AppConfig.Album.SD_LIST_COUNT]개 안). 영상은 SD에만 두므로(10.4.3) 사진(JPEG)만. */
    suspend fun list(): Result<List<SdPhoto>> = runCatching {
        DjiMedia.lock.withLock {
            // 비행 화면을 거치지 않고 앨범을 열 수도 있어 목록 출처를 여기서도 정한다(어댑터와 같은 값)
            DjiMedia.media.setMediaFileDataSource(MediaFileListDataSource.Builder().setLocation(CameraStorageLocation.SDCARD).setIndexType(camera).build())
            DjiMedia.pullRecent(AppConfig.Album.SD_LIST_COUNT)
            val photos = DjiMedia.media.mediaFileListData.data.filter { it.fileType == MediaFileType.JPEG }
            files = photos.associateBy { it.fileName }
            photos.map { SdPhoto(it.fileIndex, it.fileName, millis(it.date), it.fileSize) }
        }
    }.onFailure { OpLog.log("error", "sdAlbumList", mapOf("message" to it.message)) }

    /** 썸네일(작은 그림, SDK가 파일에 남겨 두면 다시 받지 않는다). */
    suspend fun thumbnail(p: SdPhoto): Bitmap? = files[p.name]?.let { f ->
        f.thumbNail ?: runCatching { DjiMedia.lock.withLock { djiValue<Bitmap>(AppConfig.Album.SD_THUMB_MS) { f.pullThumbnailFromCamera(it) } } }.getOrNull()
    }

    /** 뷰어 미리보기(화면 크기 그림). */
    suspend fun preview(p: SdPhoto): Bitmap? = files[p.name]?.let { f ->
        runCatching { DjiMedia.lock.withLock { djiValue<Bitmap>(AppConfig.Album.SD_THUMB_MS) { f.pullPreviewFromCamera(it) } } }.getOrNull()
    }

    /** 원본을 [to]에 받는다(임시 파일에 쓴 뒤 이름 바꿈, 실패하면 지움). */
    suspend fun download(p: SdPhoto, to: File): Result<File> = runCatching {
        val f = files[p.name] ?: error("SD 목록에 없음: ${p.name}")
        val part = File(to.path + ".part")
        DjiMedia.lock.withLock {
            withContext(Dispatchers.IO) { to.parentFile?.mkdirs() }
            part.outputStream().buffered().use { out ->
                withTimeout(AppConfig.Album.SD_DOWNLOAD_MS) {
                    suspendCancellableCoroutine { c ->
                        f.pullOriginalMediaFileFromCamera(0, object : MediaFileDownloadListener {
                            override fun onStart() {}
                            override fun onProgress(total: Long, current: Long) {}
                            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) = out.write(data)
                            override fun onFinish() = c.resume(Result.success(Unit))
                            override fun onFailure(error: IDJIError) = c.resume(Result.failure(DjiException(error)))
                        })
                        c.invokeOnCancellation { f.stopPullOriginalMediaFileFromCamera(null) }
                    }.getOrThrow()
                }
            }
        }
        if (!part.renameTo(to)) error("저장 실패: ${to.name}")
        OpLog.log("media", "sdAlbumDownload", mapOf("name" to p.name, "bytes" to to.length()))
        to
    }.onFailure { File(to.path + ".part").delete(); OpLog.log("error", "sdAlbumDownload", mapOf("name" to p.name, "message" to it.message)) }

    /** SD에서 지운다(되돌릴 수 없음, 화면이 먼저 확인을 묻는다). */
    suspend fun delete(p: SdPhoto): Result<Unit> = runCatching {
        val f = files[p.name] ?: error("SD 목록에 없음: ${p.name}")
        DjiMedia.lock.withLock { djiDone(AppConfig.Timeouts.CAMERA_CMD_MS) { DjiMedia.media.deleteMediaFiles(listOf(f), it) } }
        files = files - p.name
        OpLog.log("media", "sdAlbumDelete", mapOf("name" to p.name))
    }

    private fun millis(d: DateTime?): Long = if (d == null) 0 else Calendar.getInstance().apply {
        set(d.year, d.month - 1, d.day, d.hour, d.minute, d.second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
