package com.example.myfirstapp.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.input.moveIndex
import com.example.myfirstapp.core.model.CaptureSource
import com.example.myfirstapp.data.AlbumMission
import com.example.myfirstapp.data.AlbumPhoto
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.detect.LastPhoto
import com.example.myfirstapp.detect.PreciseDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /** 선로별 보기(Finder식, 기본) / 미션별 보기. 마지막에 본 것을 설정에 남긴다. */
    val byLine: Boolean = true,
    val groups: List<AlbumGroup> = emptyList(),
    val selected: String? = null,
    val photos: List<AlbumPhoto> = emptyList(),
    val filter: AlbumFilter = AlbumFilter.ALL,
    /** 선로별 보기 나무(선로 → 전주 → 날짜, 맨 아래 기타)와 펼친 칸, 고른 칸. */
    val tree: List<TreeNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val nodeKey: String? = null,
    /** 크게 보는 파일 사진(전주에 붙지 않은 수동 사진·SD에서 받은 사진)과 정보. 없으면 null. */
    val viewer: PhotoInfo? = null,
    /** 뷰어 [탐지] 결과(9.6, 11-07, 저장 안 함)와 진행. 사진을 바꾸면 지운다. */
    val viewerDetect: LastPhoto? = null,
    val detecting: Boolean = false,
    /** 조종기(앱 폴더) 남은 공간(바이트, 다운로드 완료 탭 저장소). 모르면 null. */
    val freeBytes: Long? = null,
) {
    val group get() = groups.firstOrNull { it.key == selected } ?: groups.firstOrNull()
    val shown get() = filtered(photos)

    /** 왼쪽 나무에 보이는 줄(펼친 칸의 아이들 포함). */
    val rows get() = visibleRows(tree, expanded)
    val path get() = nodePath(tree, nodeKey).ifEmpty { listOfNotNull(tree.firstOrNull()) }
    val node get() = path.lastOrNull()

    /** 선로별 보기 오른쪽 격자: 고른 칸의 사진(필터 적용). 전주에 붙지 않은 수동 사진은 "탐지만"이면 뺀다. */
    val nodePhotos get() = node?.photos?.let(::filtered).orEmpty()
    val nodeFiles get() = if (filter == AlbumFilter.DETECTED) emptyList() else node?.files.orEmpty()

    private fun filtered(list: List<AlbumPhoto>) = when (filter) {
        AlbumFilter.ALL -> list
        AlbumFilter.DETECTED -> list.filter { it.alertCount > 0 }
        AlbumFilter.MANUAL -> list.filter { it.capture.source == CaptureSource.MANUAL.name }
    }
}

/**
 * 앨범(10.4.3 ⑦, 사진 모아 보기). 미션 사진은 DB, 전주에 붙지 않은 수동 비행 사진은 앱 폴더 `manual/<날짜>/`에서 읽는다.
 * 선로별 보기는 모든 미션 사진을 한 번에 읽어 나무로 묶는다.
 */
// ponytail: 모든 사진 줄을 메모리에 올림(수만 장이면 느려짐). 그때는 나무를 SQL GROUP BY로, 사진은 고른 칸만 읽기
class AlbumViewModel(
    private val dao: ProjectDao, private val appFilesDir: File?, private val settings: SettingsStore, private val precise: PreciseDetection? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    init {
        reload()
    }

    /** 목록을 다시 읽는다(처음, 기체 앨범에서 받은 뒤, 사진을 지운 뒤). 고른 칸·펼친 칸은 남아 있으면 그대로 둔다. */
    fun reload() {
        viewModelScope.launch {
            // 사진이 없는 미션(계획만 한 것)은 앨범에 두지 않는다
            val missions = dao.albumMissions().filter { it.photoCount > 0 }.map { AlbumGroup.Mission(it) }
            val days = withContext(Dispatchers.IO) {
                File(appFilesDir, "manual").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty()
                    .map { d -> AlbumGroup.ManualDay(d.name, d.listFiles { f -> f.extension.equals("jpg", true) }.orEmpty().sortedByDescending { it.name }) }
                    .filter { it.files.isNotEmpty() }
            }
            // 최신순(11-09): 미션은 마지막 사진 시각, 수동 하루치는 그날 마지막 파일 시각
            val groups = (missions + days).sortedByDescending { g ->
                when (g) { is AlbumGroup.Mission -> g.m.lastTakenAt ?: g.m.createdAt; is AlbumGroup.ManualDay -> g.files.maxOf { it.lastModified() } }
            }
            val tree = lineTree(dao.albumAllPhotos(), days)
            val first = tree.firstOrNull()?.key
            val byLine = settings.albumByLine.first()
            val free = appFilesDir?.usableSpace
            _state.update { s ->
                val keep = s.nodeKey?.takeIf { nodePath(tree, it).isNotEmpty() }
                s.copy(
                    loading = false, byLine = byLine, groups = groups, tree = tree, freeBytes = free,
                    nodeKey = keep ?: first, expanded = if (keep != null) s.expanded else setOfNotNull(first),
                )
            }
            (groups.firstOrNull { it.key == _state.value.selected } ?: groups.firstOrNull())?.let(::select)
        }
    }

    /**
     * 파일 사진을 크게 본다(10.4.9 뷰어): EXIF 촬영 시각·위치·고도, 파일 이름 렌즈, 20m 안 가까운 전주(모든 프로젝트, 셔터 사진 붙이기와 같은 거리).
     * EXIF가 없으면 파일 시각.
     */
    fun open(f: File) {
        _state.update { it.copy(viewer = PhotoInfo(f, f.lastModified(), emptyList()), viewerDetect = null) }
        viewModelScope.launch {
            val info = photoInfo(f, dao.allPoles())
            _state.update { if (it.viewer?.file == f) it.copy(viewer = info) else it }
        }
    }

    fun closeViewer() = _state.update { it.copy(viewer = null, viewerDetect = null) }

    /** 뷰어 [탐지]: 보고 있는 사진 1장을 정밀 모델로 탐지해 상자를 겹쳐 보인다(저장 안 함). */
    fun detectViewing() {
        val f = _state.value.viewer?.file ?: return
        val p = precise ?: return
        if (_state.value.detecting) return
        _state.update { it.copy(detecting = true) }
        viewModelScope.launch {
            val r = runCatching { p.detectOne(f) }.getOrElse { e -> LastPhoto(f.path, emptyList(), 1f, "탐지 실패: ${e.message}", emptyMap()) }
            _state.update { if (it.viewer?.file == f) it.copy(viewerDetect = r, detecting = false) else it.copy(detecting = false) }
        }
    }

    /** 뷰어 [삭제](확인 뒤): 앱 사본만 지운다. 기체 SD 사진은 기체 앨범 탭에서만 지운다. */
    fun deleteViewing() {
        val f = _state.value.viewer?.file ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { f.delete() }
            OpLog.log("album", "deleteFile", mapOf("name" to f.name, "ok" to ok))
            _state.update { it.copy(viewer = null) }
            reload()
        }
    }

    fun select(g: AlbumGroup) {
        _state.update { it.copy(selected = g.key, photos = emptyList()) }
        if (g is AlbumGroup.Mission) viewModelScope.launch {
            val photos = dao.albumPhotos(g.m.missionId)
            _state.update { if (it.selected == g.key) it.copy(photos = photos) else it }
        }
    }

    fun setFilter(f: AlbumFilter) = _state.update { it.copy(filter = f) }

    fun setByLine(on: Boolean) {
        _state.update { it.copy(byLine = on) }
        viewModelScope.launch { settings.setAlbumByLine(on) }
    }

    /** 나무 칸 누름(5D ●도 같음): 새로 고르면 펼치고, 고른 칸을 다시 누르면 접거나 편다. */
    fun tapNode(key: String) = _state.update { s ->
        val open = s.node?.key != key || key !in s.expanded
        s.copy(nodeKey = key, expanded = if (open) s.expanded + key else s.expanded - key)
    }

    /** 5D ↕: 왼쪽 목록(미션 목록 또는 보이는 나무 줄)에서 위·아래 줄을 고른다. 끝에서는 멈춘다. */
    fun move(delta: Int) {
        val s = _state.value
        if (s.byLine) {
            val rows = s.rows
            rows.getOrNull(moveIndex(rows.indexOfFirst { it.key == s.node?.key }, delta, rows.size))?.let { n -> _state.update { it.copy(nodeKey = n.key) } }
        } else s.groups.getOrNull(moveIndex(s.groups.indexOf(s.group), delta, s.groups.size))?.takeIf { it != s.group }?.let(::select)
    }

    companion object {
        fun factory(dao: ProjectDao, appFilesDir: File?, settings: SettingsStore, precise: PreciseDetection) =
            viewModelFactory { initializer { AlbumViewModel(dao, appFilesDir, settings, precise) } }
    }
}
