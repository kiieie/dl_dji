package com.example.myfirstapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.model.Annotation
import com.example.myfirstapp.core.model.AnnotationColor
import com.example.myfirstapp.core.model.AnnotationKind
import com.example.myfirstapp.core.model.Annotations
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.toAnnotation
import com.example.myfirstapp.data.toEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 핀포인트·지도 주석 상태(10.4.7). 모든 지도(계획·비행·미니맵·수동 비행·다시 보기)와 카메라 뷰 핀포인트가 같이 쓴다. */
data class AnnotationUiState(
    val items: List<Annotation> = emptyList(),
    /** 그리는(고치는) 중인 선·영역. id 0이면 새것. */
    val draft: Annotation? = null,
    /** 그리는 중 고른 꼭짓점(점 삭제 대상). null이면 마지막 점. */
    val vertex: Int? = null,
    /** 새 핀포인트 색(핀포인트 길게 누름 설정). */
    val pinColor: AnnotationColor = AnnotationColor.BLUE,
    /** 지도에서 누른 핀포인트(카드). */
    val selected: Long? = null,
    /** 핀포인트 목록 패널. [expanded]는 펼친(색·이름을 고치는) 핀포인트. */
    val listOpen: Boolean = false,
    val expanded: Long? = null,
    /** 잠깐 보이는 안내(위치 레코딩 실패, 추가함). */
    val notice: String? = null,
) {
    val pins: List<Annotation> get() = items.filter { it.kind == AnnotationKind.PIN }
}

/** 지도 주석 ViewModel(10.4.7): 기기 DB(`annotation`)만 읽고 쓴다. 화면(내비 항목)마다 하나를 지도·카메라 뷰가 함께 쓴다([annotationViewModel]). */
class AnnotationViewModel(private val dao: ProjectDao) : ViewModel() {
    private val ui = MutableStateFlow(AnnotationUiState())
    val state: StateFlow<AnnotationUiState> = combine(dao.observeAnnotations(), ui) { rows, u -> u.copy(items = rows.map { it.toAnnotation() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnotationUiState())
    private var noticeJob: Job? = null

    private fun notice(text: String) {
        noticeJob?.cancel()
        ui.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch { delay(AppConfig.Annotation.NOTICE_MS); ui.update { it.copy(notice = null) } }
    }

    private fun nextName() = Annotations.nextName(AppConfig.Annotation.NAME_PREFIX, state.value.items.map { it.name } + listOfNotNull(ui.value.draft?.name))

    private fun save(a: Annotation) = viewModelScope.launch { dao.upsertAnnotation(a.toEntity()) }

    /** 카메라 뷰·기체 카드 핀포인트(Pilot 2, 매뉴얼 p62): 지금 기체 위치·고도. 위성이 모자라면(비전 포지셔닝) 남기지 않는다. */
    fun pinAircraft(t: Telemetry?) {
        val at = t?.takeIf { !it.visionPositioning }?.position
        if (t == null || at == null) {
            notice("이용 가능한 위성이 충분하지 않습니다. 위치 레코딩 실패")
            OpLog.log("annotation", "pin-fail")
            return
        }
        addPin(at, t.altitudeM)
    }

    /** 지도 뷰 핀포인트(매뉴얼 p62): 지도 가운데 십자선에, 고도는 기체 지금 고도(모르면 비움). */
    fun pinAt(at: GeoPoint, altitudeM: Double?) = addPin(at, altitudeM)

    private fun addPin(at: GeoPoint, altitudeM: Double?) {
        val a = Annotation(kind = AnnotationKind.PIN, name = nextName(), color = ui.value.pinColor, points = listOf(at), altitudeM = altitudeM, createdAt = System.currentTimeMillis())
        save(a)
        notice("핀포인트 ${a.name} 추가")
        OpLog.log("annotation", "pin", mapOf("name" to a.name, "lat" to at.lat, "lon" to at.lon, "alt" to altitudeM))
    }

    fun setPinColor(c: AnnotationColor) = ui.update { it.copy(pinColor = c) }

    fun openList(open: Boolean, expand: Long? = null) = ui.update { it.copy(listOpen = open, expanded = expand, selected = null) }

    fun expand(id: Long?) = ui.update { it.copy(expanded = id) }

    fun select(id: Long?) = ui.update { it.copy(selected = id) }

    /** 지도에서 누른 주석: 핀포인트는 카드, 선·영역은 편집 패널. */
    fun tap(id: Long) {
        val a = state.value.items.firstOrNull { it.id == id } ?: return
        if (a.kind == AnnotationKind.PIN) select(id) else ui.update { it.copy(draft = a, vertex = null, selected = null) }
    }

    fun rename(id: Long, name: String) = state.value.items.firstOrNull { it.id == id }?.let { save(it.copy(name = name.trim().ifEmpty { it.name })) }

    fun recolor(id: Long, c: AnnotationColor) = state.value.items.firstOrNull { it.id == id }?.let { save(it.copy(color = c)) }

    fun delete(id: Long) {
        viewModelScope.launch { dao.deleteAnnotation(id) }
        ui.update { it.copy(selected = null, expanded = null) }
    }

    /** ⊗ 모든 주석 지우기(Pilot 2 지도 왼쪽). */
    fun clearAll() {
        viewModelScope.launch { dao.clearAnnotations() }
        ui.update { it.copy(draft = null, vertex = null, selected = null, expanded = null) }
        OpLog.log("annotation", "clear-all")
    }

    /** 선·영역 그리기 시작(지도 왼쪽). 그리던 것은 먼저 마친다. */
    fun startDraft(kind: AnnotationKind) {
        finishDraft()
        ui.update { it.copy(draft = Annotation(kind = kind, name = nextName()), vertex = null, selected = null) }
    }

    /** 지도를 누른 곳에 꼭짓점을 더한다(고른 꼭짓점이 있으면 그 뒤에). */
    fun addVertex(p: GeoPoint) = ui.update { u ->
        val d = u.draft ?: return@update u
        val at = (u.vertex ?: d.points.lastIndex) + 1
        u.copy(draft = d.copy(points = d.points.take(at) + p + d.points.drop(at)), vertex = at)
    }

    fun selectVertex(i: Int) = ui.update { it.copy(vertex = i) }

    /** [점 삭제]: 고른 꼭짓점(없으면 마지막). */
    fun deleteVertex() = ui.update { u ->
        val d = u.draft?.takeIf { it.points.isNotEmpty() } ?: return@update u
        val i = (u.vertex ?: d.points.lastIndex).coerceIn(d.points.indices)
        u.copy(draft = d.copy(points = d.points.filterIndexed { j, _ -> j != i }), vertex = (i - 1).takeIf { it >= 0 })
    }

    /** [선 지우기]·[영역 지우기]: 꼭짓점을 모두 지운다. 이대로 [확인]하면 저장된 주석은 지워진다. */
    fun clearDraft() = ui.update { u -> u.copy(draft = u.draft?.copy(points = emptyList()), vertex = null) }

    fun setDraftName(name: String) = ui.update { u -> u.copy(draft = u.draft?.let { d -> d.copy(name = name.trim().ifEmpty { d.name }) }) }

    fun setDraftColor(c: AnnotationColor) = ui.update { u -> u.copy(draft = u.draft?.copy(color = c)) }

    /** [확인]: 점이 충분하면 저장하고, 아니면 버린다(저장된 것을 비웠으면 지운다). */
    fun finishDraft() {
        val d = ui.value.draft ?: return
        ui.update { it.copy(draft = null, vertex = null) }
        when {
            d.complete -> {
                save(d.copy(createdAt = d.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
                OpLog.log("annotation", d.kind.name.lowercase(), mapOf("name" to d.name, "points" to d.points.size))
            }
            d.id != 0L -> viewModelScope.launch { dao.deleteAnnotation(d.id) }
        }
    }

    companion object {
        fun factory(dao: ProjectDao): ViewModelProvider.Factory = viewModelFactory { initializer { AnnotationViewModel(dao) } }
    }
}

/** 이 화면(내비 항목)의 주석 ViewModel. 큰 지도·미니맵·카메라 뷰 핀포인트가 같은 것을 쓴다. */
@Composable
internal fun annotationViewModel(): AnnotationViewModel {
    val app = LocalContext.current.applicationContext as InspectionApp
    return viewModel(key = "annotations", factory = AnnotationViewModel.factory(app.database.projectDao()))
}
