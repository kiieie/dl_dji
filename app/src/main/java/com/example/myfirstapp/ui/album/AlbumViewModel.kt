package com.example.myfirstapp.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.input.moveIndex
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.data.AlbumMission
import com.example.myfirstapp.data.AlbumPhoto
import com.example.myfirstapp.data.ProjectDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 앨범 왼쪽 목록 한 줄: 미션, 또는 전주에 붙지 않은 수동 비행 사진 하루치(`manual/<날짜>/`). */
sealed interface AlbumGroup {
    val key: String
    data class Mission(val m: AlbumMission) : AlbumGroup { override val key get() = "m${m.missionId}" }
    data class ManualDay(val day: String, val files: List<File>) : AlbumGroup { override val key get() = "d$day" }
}

enum class AlbumFilter(val title: String) { ALL("전체"), DETECTED("탐지만"), MANUAL("수동 촬영") }

data class AlbumUiState(
    val loading: Boolean = true,
    val groups: List<AlbumGroup> = emptyList(),
    val selected: String? = null,
    val photos: List<AlbumPhoto> = emptyList(),
    val filter: AlbumFilter = AlbumFilter.ALL,
) {
    val group get() = groups.firstOrNull { it.key == selected } ?: groups.firstOrNull()
    val shown get() = when (filter) {
        AlbumFilter.ALL -> photos
        AlbumFilter.DETECTED -> photos.filter { it.alertCount > 0 }
        AlbumFilter.MANUAL -> photos.filter { it.capture.source == CaptureSource.MANUAL.name }
    }
}

/** 앨범(10.4.3 ⑦, 사진 모아 보기). 미션 사진은 DB, 전주에 붙지 않은 수동 비행 사진은 앱 폴더 `manual/<날짜>/`에서 읽는다. */
class AlbumViewModel(private val dao: ProjectDao, private val appFilesDir: File?) : ViewModel() {
    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 사진이 없는 미션(계획만 한 것)은 앨범에 두지 않는다
            val missions = dao.albumMissions().filter { it.photoCount > 0 }.map { AlbumGroup.Mission(it) }
            val days = withContext(Dispatchers.IO) {
                File(appFilesDir, "manual").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty()
                    .map { d -> AlbumGroup.ManualDay(d.name, d.listFiles { f -> f.extension.equals("jpg", true) }.orEmpty().sortedByDescending { it.name }) }
                    .filter { it.files.isNotEmpty() }
            }
            // 최신순: 미션은 계획 시각, 수동 하루치는 그날 끝으로 본다
            val groups = (missions + days).sortedByDescending { g ->
                when (g) { is AlbumGroup.Mission -> g.m.createdAt.toString(); is AlbumGroup.ManualDay -> dayKey(g.day) }
            }
            _state.update { it.copy(loading = false, groups = groups) }
            groups.firstOrNull()?.let(::select)
        }
    }

    // 정렬 열쇠: 미션 시각(ms)과 날짜(yyyyMMdd)를 같은 자리수 문자열로
    private fun dayKey(day: String) = runCatching {
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).parse(day)!!.time + 86_399_999
    }.getOrDefault(0L).toString()

    fun select(g: AlbumGroup) {
        _state.update { it.copy(selected = g.key, photos = emptyList()) }
        if (g is AlbumGroup.Mission) viewModelScope.launch {
            val photos = dao.albumPhotos(g.m.missionId)
            _state.update { if (it.selected == g.key) it.copy(photos = photos) else it }
        }
    }

    fun setFilter(f: AlbumFilter) = _state.update { it.copy(filter = f) }

    /** 5D ↕: 왼쪽 목록에서 위·아래 줄을 고른다. 끝에서는 멈춘다. */
    fun move(delta: Int) {
        val s = _state.value
        s.groups.getOrNull(moveIndex(s.groups.indexOf(s.group), delta, s.groups.size))?.takeIf { it != s.group }?.let(::select)
    }

    companion object {
        fun factory(dao: ProjectDao, appFilesDir: File?) = viewModelFactory { initializer { AlbumViewModel(dao, appFilesDir) } }
    }
}
