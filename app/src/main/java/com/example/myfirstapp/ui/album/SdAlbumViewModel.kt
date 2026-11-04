package com.example.myfirstapp.ui.album

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.SdPhoto
import com.example.myfirstapp.core.model.sdDays
import com.example.myfirstapp.drone.DjiSdAlbum
import com.example.myfirstapp.drone.SdStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앨범 기체 앨범 탭 상태(10.4.9). */
data class SdAlbumUiState(
    /** 열 수 없는 이유(기체 연결 안 됨·비행 중). 있으면 목록을 읽지 않는다. */
    val blocked: String? = null,
    val loading: Boolean = false,
    val photos: List<SdPhoto> = emptyList(),
    /** 정렬 신형-구형(기본) / 구형-신형. */
    val newestFirst: Boolean = true,
    val storage: SdStorage = SdStorage(),
    /** 앱에 이미 받은 SD 파일 이름. */
    val downloaded: Set<String> = emptySet(),
    /** 받는 중인 파일 이름. */
    val busy: Set<String> = emptySet(),
    val notice: String? = null,
    val viewing: SdPhoto? = null,
    /** 받은 횟수: 바뀌면 다운로드 완료 탭을 다시 읽는다. */
    val savedCount: Int = 0,
) {
    val days get() = sdDays(photos, newestFirst)
}

/**
 * 앨범 기체 앨범 탭(10.4.9, Pilot 2 [기체 앨범]): 기체 SD 사진을 날짜로 묶어 보이고, 고른 사진(또는 하루치)을 앱으로 받는다.
 * 받은 사진은 전주에 붙지 않은 사진처럼 `manual/<촬영 날짜>/<SD 파일 이름>`에 둔다(다운로드 완료 탭 맨 아래 "기타"). 떠 있으면 열지 않는다.
 */
class SdAlbumViewModel(private val appFilesDir: File?) : ViewModel() {
    private val _state = MutableStateFlow(SdAlbumUiState())
    val state: StateFlow<SdAlbumUiState> = _state.asStateFlow()
    private var noticeJob: Job? = null

    /** 탭을 열 때(다시 열 때 포함) 목록과 저장소를 읽는다. */
    fun refresh() {
        val blocked = DjiSdAlbum.blocked()
        _state.update { it.copy(blocked = blocked, loading = blocked == null) }
        if (blocked != null) return
        viewModelScope.launch {
            val storage = DjiSdAlbum.storage()
            val r = DjiSdAlbum.list()
            val photos = r.getOrDefault(emptyList())
            val have = withContext(Dispatchers.IO) { photos.filter { target(it).exists() }.map { it.name }.toSet() }
            _state.update { it.copy(loading = false, photos = photos, storage = storage, downloaded = have) }
            r.onFailure { e -> notice("SD 목록을 읽지 못했습니다: ${e.message}") }
        }
    }

    fun setNewestFirst(on: Boolean) = _state.update { it.copy(newestFirst = on) }

    suspend fun thumbnail(p: SdPhoto): ImageBitmap? = DjiSdAlbum.thumbnail(p)?.asImageBitmap()
    suspend fun preview(p: SdPhoto): ImageBitmap? = DjiSdAlbum.preview(p)?.asImageBitmap()

    fun open(p: SdPhoto) = _state.update { it.copy(viewing = p) }
    fun close() = _state.update { it.copy(viewing = null) }

    /** 받기: 이미 받았거나 받는 중이면 건너뛴다. 하루치는 [download]를 차례로(SD 잠금이 하나씩 받게 한다). */
    fun download(list: List<SdPhoto>) {
        val todo = list.filter { it.name !in _state.value.downloaded && it.name !in _state.value.busy }
        if (todo.isEmpty()) return notice("이미 받은 사진입니다")
        DjiSdAlbum.blocked()?.let { notice(it); return }
        _state.update { it.copy(busy = it.busy + todo.map { p -> p.name }) }
        viewModelScope.launch {
            var ok = 0
            todo.forEach { p ->
                val r = DjiSdAlbum.download(p, target(p))
                _state.update { s -> s.copy(busy = s.busy - p.name, downloaded = if (r.isSuccess) s.downloaded + p.name else s.downloaded) }
                if (r.isSuccess) ok++
            }
            _state.update { it.copy(savedCount = it.savedCount + 1) }
            notice(if (ok == todo.size) "${ok}장 받음(다운로드 완료 › 기타)" else "${todo.size}장 중 ${ok}장 받음")
        }
    }

    /** SD에서 지우기(뷰어가 확인을 물은 뒤). 앱에 받은 사본은 그대로 둔다. */
    fun delete(p: SdPhoto) {
        viewModelScope.launch {
            DjiSdAlbum.delete(p).fold(
                { _state.update { s -> s.copy(photos = s.photos - p, viewing = s.viewing?.takeIf { it != p }) }; notice("기체 SD에서 지웠습니다: ${p.name}") },
                { e -> notice("지우지 못했습니다: ${e.message}") },
            )
        }
    }

    /** 받은 사진 자리: 전주에 붙지 않은 사진과 같은 `manual/<촬영 날짜>/`, 이름은 SD 파일 이름 그대로(다시 받지 않게). */
    private fun target(p: SdPhoto) = File(appFilesDir, "manual/${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(p.takenAtMs))}/${p.name}")

    private fun notice(text: String) {
        _state.update { it.copy(notice = text) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch { delay(AppConfig.Album.NOTICE_MS); _state.update { it.copy(notice = null) } }
    }

    companion object {
        fun factory(appFilesDir: File?) = viewModelFactory { initializer { SdAlbumViewModel(appFilesDir) } }
    }
}
