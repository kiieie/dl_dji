package com.example.myfirstapp.ui.result

import com.example.myfirstapp.data.OpLog
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.input.FiveDGate
import com.example.myfirstapp.core.model.ReviewVerdict
import com.example.myfirstapp.core.model.TargetLabels
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.CaptureEntity
import com.example.myfirstapp.data.DetectionEntity
import com.example.myfirstapp.data.ExportResult
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.ResultRow
import com.example.myfirstapp.data.ReviewEntity
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.exportMission
import com.example.myfirstapp.detect.PreciseDetection
import com.example.myfirstapp.detect.labelNames
import com.example.myfirstapp.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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

/** 판독 전주 목록 폭(dp)을 [Dimens.ResultListMin]~[Dimens.ResultListMax]로 묶는다. */
fun clampListWidth(dp: Float) = dp.coerceIn(Dimens.ResultListMin.value, Dimens.ResultListMax.value)

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
    /** 크게 보는 사진(광각·망원). 화면 칩과 RC C1이 바꾼다. */
    val photoIndex: Int = 0,
    val draft: ReviewDraft = ReviewDraft(),
    val tagOptions: List<String> = TargetLabels.names.keys.toList(),
    val message: String? = null,
    /** 라벨 → 화면 이름(model.json `displayNames`, 9.6). 없는 라벨은 검출 대상 이름 또는 라벨 그대로. */
    val labelNames: Map<String, String> = emptyMap(),
    /** 착륙 뒤 정밀 탐지 진행("정밀 탐지 37/120"). 남은 사진이 없으면 null. */
    val precise: String? = null,
    /** 왼쪽 전주 목록 폭(dp). 가운데 막대를 끌어 바꾸고 설정에 남긴다. */
    val listWidthDp: Float = Dimens.ResultListDefault.value,
) {
    val statusCounts: Map<String, Int> = rows.groupingBy { STATUS_TITLE[it.status] ?: it.status }.eachCount()

    /** 설비(경고 라벨이 아닌 탐지) 수. 표시만 하고 검출로 세지 않는다(9.6). */
    val facilityCount: Int = rows.sumOf { it.facilityCount }

    fun labelTitle(label: String) = labelNames[label] ?: TargetLabels.title(label)

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
    /** RC Pro 5D 버튼(10장): 위·아래 전주, 왼쪽·오른쪽 판정, 가운데 저장 후 다음. */
    private val rcButtons: Flow<RcButton> = emptyFlow(),
    /** 앱 폴더 `models/`(상자 이름). */
    private val modelsDir: File? = null,
    /** 착륙 뒤 정밀 탐지(9.6): 진행을 보이고, 사진이 끝날 때마다 결과를 다시 읽는다. */
    private val precise: PreciseDetection? = null,
    /** 목록 폭을 읽고 남긴다. */
    private val settings: SettingsStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ResultUiState(missionId = missionId))
    val state: StateFlow<ResultUiState> = _state.asStateFlow()
    private val _events = Channel<ResultEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var captures: Map<Long, List<CaptureEntity>> = emptyMap()
    private var detections: Map<Long, List<DetectionEntity>> = emptyMap()
    private var exported: ExportResult? = null

    init {
        viewModelScope.launch { rcButtons.collect(::on5d) }
        settings?.let { st -> viewModelScope.launch { st.resultListWidth.first()?.let { w -> _state.update { it.copy(listWidthDp = clampListWidth(w)) } } } }
        viewModelScope.launch {
            val names = modelsDir?.let { withContext(Dispatchers.IO) { labelNames(it) } }.orEmpty()
            _state.update { it.copy(labelNames = names) }
            load()
        }
        precise?.let { p ->
            viewModelScope.launch {
                combine(p.progress, p.pauseReason) { g, why ->
                    if (g.remaining <= 0) null else "정밀 탐지 ${g.done}/${g.total}" + if (why != null && !g.running) " · 쉼($why)" else ""
                }.collect { text -> _state.update { it.copy(precise = text) } }
            }
            // 정밀 탐지가 사진 하나를 끝낼 때마다(또는 다 끝나면) 상자를 다시 읽는다
            viewModelScope.launch { p.progress.map { it.done }.distinctUntilChanged().drop(1).collect { load() } }
        }
    }

    /** 결과·사진·탐지·판독을 읽는다. 고른 전주가 있으면 그 사진도 새로 고친다. */
    private suspend fun load() {
        val mission = dao.mission(missionId) ?: return _state.update { it.copy(loading = false, message = "미션을 찾을 수 없습니다") }
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
        _state.value.selected?.let { sel ->
            val index = _state.value.photoIndex
            select(sel.visitId, keepDraft = true)
            showPhoto(index)
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

    /** 전주를 고른다. [keepDraft]면(정밀 탐지 뒤 다시 읽기) 고치던 판정을 그대로 둔다. */
    fun select(visitId: Long, keepDraft: Boolean = false) = _state.update { s ->
        val row = s.rows.firstOrNull { it.visitId == visitId } ?: return@update s
        val photos = captures[visitId].orEmpty().map { c -> PhotoUi(c, detections[c.id].orEmpty()) }
        // 판독한 적이 있으면 그 판정을, 없으면 AI가 검출한 대상을 미리 켠다
        val draft = if (keepDraft) s.draft else s.reviews[visitId]?.let { r ->
            ReviewDraft(ReviewVerdict.valueOf(r.verdict), r.tags.split(';').filter { it.isNotEmpty() }.toSet(), r.memo.orEmpty())
        } ?: ReviewDraft(tags = photos.flatMap { p -> p.boxes.filter { it.alert }.map { it.label } }.toSet())
        s.copy(selected = row, photos = photos, photoIndex = 0, draft = draft)
    }

    /** 5D 입력 정리: 가운데와 방향이 겹치면 둘 다 버린다(`FiveDGate`, 실측값은 현장에서 손에 맞게 고친다). */
    private val gate = FiveDGate()

    /** RC 버튼(10장 ⑤): C1은 광각·망원 전환, 5D는 [gate]를 거친다. 그 밖의 버튼(일시정지 등)은 비행 화면 몫이다. */
    private fun on5d(b: RcButton) {
        Log.i("RcKey", "result $b")
        if (b == RcButton.C1) return nextPhoto()
        if (!b.name.startsWith("FIVE_D_")) return
        apply5d(gate.press(b, now()))
        viewModelScope.launch {
            delay(gate.settleMs)
            apply5d(gate.tick(now()))
        }
    }

    private fun apply5d(r: FiveDGate.Result) {
        if (r.conflict) _state.update { it.copy(message = "5D 입력이 겹쳐 무시했습니다. 다시 누르세요") }
        r.actions.forEach { b ->
            when (b) {
                RcButton.FIVE_D_UP -> moveSelection(-1)
                RcButton.FIVE_D_DOWN -> moveSelection(1)
                RcButton.FIVE_D_LEFT -> moveVerdict(-1)
                RcButton.FIVE_D_RIGHT -> moveVerdict(1)
                RcButton.FIVE_D_CENTER -> saveReviewAndNext()
                else -> Unit
            }
        }
    }

    /** 가운데 막대 끌기: 목록 폭을 [deltaDp]만큼 바꾼다. 놓으면 [saveListWidth]로 남긴다. */
    fun dragListWidth(deltaDp: Float) = _state.update { it.copy(listWidthDp = clampListWidth(it.listWidthDp + deltaDp)) }

    fun saveListWidth() {
        val w = _state.value.listWidthDp
        settings?.let { viewModelScope.launch { it.setResultListWidth(w) } }
    }

    fun showPhoto(i: Int) = _state.update { it.copy(photoIndex = i.coerceIn(0, (it.photos.size - 1).coerceAtLeast(0))) }

    /** 다음 사진(광각 → 망원 → 광각). */
    fun nextPhoto() = _state.update { if (it.photos.isEmpty()) it else it.copy(photoIndex = (it.photoIndex + 1) % it.photos.size) }

    fun setVerdict(v: ReviewVerdict) = _state.update { it.copy(draft = it.draft.copy(verdict = v)) }

    /** 5D 버튼 위·아래(10장): 지금 보이는 목록에서 이전·다음 전주를 고른다. 끝에서는 멈춘다. */
    fun moveSelection(delta: Int) {
        val s = _state.value
        if (s.shown.isEmpty()) return
        val i = s.shown.indexOfFirst { it.visitId == s.selected?.visitId }
        select(s.shown[if (i < 0) 0 else (i + delta).coerceIn(0, s.shown.lastIndex)].visitId)
    }

    /** 5D 버튼 왼쪽·오른쪽: 판정을 옆으로 옮긴다. 고른 판정이 없으면 첫 판정부터. */
    fun moveVerdict(delta: Int) = _state.update {
        val all = ReviewVerdict.entries
        val i = it.draft.verdict?.let(all::indexOf)
        it.copy(draft = it.draft.copy(verdict = if (i == null) all.first() else all[(i + delta).coerceIn(0, all.lastIndex)]))
    }

    fun toggleTag(label: String) = _state.update {
        val tags = it.draft.tags
        it.copy(draft = it.draft.copy(tags = if (label in tags) tags - label else tags + label))
    }

    fun setMemo(memo: String) = _state.update { it.copy(draft = it.draft.copy(memo = memo)) }

    /** 판정을 저장하고 지금 보이는 목록의 다음 전주로 넘어간다. */
    fun saveReviewAndNext() {
        OpLog.log("review", "save", mapOf("visit" to _state.value.selected?.visitId, "verdict" to _state.value.draft.verdict?.name))
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
        OpLog.log("review", "export")
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
        fun factory(
            dao: ProjectDao, projectId: Long, missionId: Long, exportDir: File,
            rcButtons: Flow<RcButton>, precise: PreciseDetection, settings: SettingsStore, openOutput: (Uri) -> OutputStream?,
        ) = viewModelFactory {
            initializer {
                ResultViewModel(
                    dao, projectId, missionId, exportDir, openOutput, rcButtons = rcButtons, modelsDir = File(exportDir, "models"),
                    precise = precise, settings = settings,
                )
            }
        }
    }
}
