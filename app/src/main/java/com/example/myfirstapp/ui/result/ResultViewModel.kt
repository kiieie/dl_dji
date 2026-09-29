package com.example.myfirstapp.ui.result

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.model.ReviewVerdict
import com.example.myfirstapp.core.model.TargetLabels
import com.example.myfirstapp.data.CaptureEntity
import com.example.myfirstapp.data.DetectionEntity
import com.example.myfirstapp.data.ExportResult
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ResultRow
import com.example.myfirstapp.data.ReviewEntity
import com.example.myfirstapp.data.exportMission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** 결과 목록 필터(10장 ⑤). */
enum class ResultFilter(val title: String) { ALL("전체"), DETECTED("검출"), UNREVIEWED("미판독"), RETAKE("재촬영") }

/** 사진 한 장과 그 위에 그릴 탐지 상자. */
data class PhotoUi(val capture: CaptureEntity, val boxes: List<DetectionEntity>)

/** 판정 패널에서 고치는 중인 1차 판독. */
data class ReviewDraft(val verdict: ReviewVerdict? = null, val tags: Set<String> = emptySet(), val memo: String = "")

data class ResultUiState(
    val loading: Boolean = true,
    val missionId: Long = 0,
    val modelVersion: String? = null,
    val rows: List<ResultRow> = emptyList(),
    val reviews: Map<Long, ReviewEntity> = emptyMap(),
    val filter: ResultFilter = ResultFilter.ALL,
    val selected: ResultRow? = null,
    val photos: List<PhotoUi> = emptyList(),
    val draft: ReviewDraft = ReviewDraft(),
    val tagOptions: List<String> = TargetLabels.names.keys.toList(),
    val message: String? = null,
) {
    val statusCounts: Map<String, Int> = rows.groupingBy { STATUS_TITLE[it.status] ?: it.status }.eachCount()

    fun count(f: ResultFilter) = rows.count { matches(it, f) }

    val shown: List<ResultRow> = rows.filter { matches(it, filter) }

    fun matches(r: ResultRow, f: ResultFilter) = when (f) {
        ResultFilter.ALL -> true
        ResultFilter.DETECTED -> r.detectCount > 0
        ResultFilter.UNREVIEWED -> r.status == "DONE" && r.visitId !in reviews
        ResultFilter.RETAKE -> reviews[r.visitId]?.verdict == ReviewVerdict.RETAKE.name
    }

    companion object {
        val STATUS_TITLE = mapOf("DONE" to "완료", "SKIPPED" to "건너뜀", "FAILED" to "실패", "PENDING" to "미점검", "IN_PROGRESS" to "미점검")
    }
}

/** 한 번만 처리하는 화면 동작: 저장 위치 고르기(SAF). */
sealed interface ResultEvent {
    data class PickSaveTarget(val fileName: String) : ResultEvent
}

/**
 * 결과 화면 ⑤와 1차 판독(F-19). 화면은 [state]만 그리고 사용자 입력을 이 클래스의 함수로 올려 보낸다(UDF).
 * [openOutput]은 SAF로 고른 위치의 출력 스트림을 연다(Context를 들고 있지 않으려고 주입한다).
 */
class ResultViewModel(
    private val dao: ProjectDao,
    private val projectId: Long,
    private val missionId: Long,
    private val exportDir: File,
    private val openOutput: (Uri) -> OutputStream?,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ResultUiState(missionId = missionId))
    val state: StateFlow<ResultUiState> = _state.asStateFlow()
    private val _events = Channel<ResultEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var captures: Map<Long, List<CaptureEntity>> = emptyMap()
    private var detections: Map<Long, List<DetectionEntity>> = emptyMap()
    private var exported: ExportResult? = null

    init {
        viewModelScope.launch {
            val mission = dao.mission(missionId) ?: return@launch _state.update { it.copy(loading = false, message = "미션을 찾을 수 없습니다") }
            captures = dao.missionCaptures(missionId).groupBy { it.visitId }
            detections = dao.missionDetections(missionId).groupBy { it.captureId }
            val found = detections.values.flatten().filter { it.alert }.map { it.label }
            _state.update {
                it.copy(
                    loading = false, modelVersion = mission.modelVersion, rows = dao.resultRows(missionId),
                    reviews = dao.latestReviews(missionId).associateBy { r -> r.visitId },
                    tagOptions = (TargetLabels.names.keys + found).distinct(),
                )
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** 필터를 바꾸면, 고른 전주가 새 목록에 없을 때 목록의 첫 전주를 고른다. */
    fun setFilter(f: ResultFilter) {
        _state.update { it.copy(filter = f) }
        val s = _state.value
        if (s.selected == null || s.shown.none { it.visitId == s.selected.visitId }) {
            s.shown.firstOrNull()?.let { select(it.visitId) } ?: _state.update { it.copy(selected = null, photos = emptyList()) }
        }
    }

    fun select(visitId: Long) = _state.update { s ->
        val row = s.rows.firstOrNull { it.visitId == visitId } ?: return@update s
        val photos = captures[visitId].orEmpty().map { c -> PhotoUi(c, detections[c.id].orEmpty()) }
        // 판독한 적이 있으면 그 판정을, 없으면 AI가 검출한 대상을 미리 켠다
        val draft = s.reviews[visitId]?.let { r ->
            ReviewDraft(ReviewVerdict.valueOf(r.verdict), r.tags.split(';').filter { it.isNotEmpty() }.toSet(), r.memo.orEmpty())
        } ?: ReviewDraft(tags = photos.flatMap { p -> p.boxes.filter { it.alert }.map { it.label } }.toSet())
        s.copy(selected = row, photos = photos, draft = draft)
    }

    fun setVerdict(v: ReviewVerdict) = _state.update { it.copy(draft = it.draft.copy(verdict = v)) }

    fun toggleTag(label: String) = _state.update {
        val tags = it.draft.tags
        it.copy(draft = it.draft.copy(tags = if (label in tags) tags - label else tags + label))
    }

    fun setMemo(memo: String) = _state.update { it.copy(draft = it.draft.copy(memo = memo)) }

    /** 판정을 저장하고 지금 보이는 목록의 다음 전주로 넘어간다. */
    fun saveReviewAndNext() {
        val s = _state.value
        val row = s.selected ?: return
        val verdict = s.draft.verdict ?: return _state.update { it.copy(message = "판정을 고르세요") }
        val next = s.shown.let { list -> list.getOrNull(list.indexOfFirst { it.visitId == row.visitId } + 1) }
        viewModelScope.launch {
            val review = ReviewEntity(0, row.visitId, verdict.name, s.draft.tags.joinToString(";"), s.draft.memo.ifBlank { null }, now())
            val id = dao.insertReview(review)
            _state.update { it.copy(reviews = it.reviews + (row.visitId to review.copy(id = id)), message = "${row.poleId} 판정 저장: ${verdict.title}") }
            if (next != null) select(next.visitId)
        }
    }

    /** 앱 폴더에 ZIP을 만든 뒤 저장 위치를 고르게 한다(F-16). */
    fun export() {
        viewModelScope.launch {
            val zip = File(exportDir, "projects/$projectId/missions/$missionId/export/$missionId.zip")
            runCatching { exportMission(dao, missionId, zip) }
                .onSuccess { r ->
                    exported = r
                    Log.i("Export", "zip=${r.zip.absolutePath} poles=${r.poleCount} images=${r.imageCount}")
                    _events.send(ResultEvent.PickSaveTarget("${dao.projectName(projectId) ?: "project"}_mission$missionId.zip"))
                }
                .onFailure { e -> _state.update { it.copy(message = "내보내기 실패: ${e.message}") } }
        }
    }

    /** 운용자가 고른 위치(SAF)로 ZIP을 복사한다. 취소하면 ZIP은 앱 폴더에 남는다. */
    fun onSaveTarget(uri: Uri?) {
        val r = exported ?: return
        if (uri == null) return _state.update { it.copy(message = "저장을 취소했습니다. ZIP은 앱 폴더에 남아 있습니다.") }
        viewModelScope.launch {
            val msg = runCatching {
                withContext(Dispatchers.IO) { requireNotNull(openOutput(uri)).use { out -> r.zip.inputStream().use { it.copyTo(out) } } }
            }.fold({ "내보냈습니다: 전주 ${r.poleCount}개, 사진 ${r.imageCount}장" }, { "저장하지 못했습니다: ${it.message}" })
            Log.i("Export", "saved uri=$uri")
            _state.update { it.copy(message = msg) }
        }
    }

    companion object {
        fun factory(dao: ProjectDao, projectId: Long, missionId: Long, exportDir: File, openOutput: (Uri) -> OutputStream?) =
            viewModelFactory { initializer { ResultViewModel(dao, projectId, missionId, exportDir, openOutput) } }
    }
}
