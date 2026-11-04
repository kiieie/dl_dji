package com.example.myfirstapp.ui.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.executor.VisitView
import com.example.myfirstapp.core.input.FiveDGate
import com.example.myfirstapp.core.input.moveIndex
import com.example.myfirstapp.core.model.FlightMode
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Telemetry
import com.example.myfirstapp.core.model.VisitStatus
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.core.replay.Playback
import com.example.myfirstapp.core.replay.ReplayTrack
import com.example.myfirstapp.core.replay.TrackSample
import com.example.myfirstapp.data.AlbumPhoto
import com.example.myfirstapp.data.MapBackground
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.OpLog
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.TrackPointRow
import com.example.myfirstapp.data.flightParamsFromJson
import com.example.myfirstapp.data.listMbtiles
import com.example.myfirstapp.data.mapBackgroundRetrying
import com.example.myfirstapp.data.pickMapSource
import com.example.myfirstapp.ui.flight.FlightUiState
import kotlinx.coroutines.Dispatchers
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

/** 사진 표지(10.4.6): 지도 위치, 찍은 시각, 파일. 전주에 붙은 사진이면 [판독]으로 갈 미션. */
data class ReplayPhoto(val position: GeoPoint, val takenAt: Long, val path: String?, val label: String, val projectId: Long? = null, val missionId: Long? = null)

/** 미션 다시 보기에서 고를 소티(궤적이 있는 것). */
data class ReplaySortie(val id: Long, val seq: Int)

data class ReplayUiState(
    val loading: Boolean = true,
    val title: String = "",
    /** 미션의 궤적이 있는 소티. 둘 이상이면 위에서 고른다. */
    val sorties: List<ReplaySortie> = emptyList(),
    val sortieId: Long? = null,
    val playback: Playback = Playback(0, AppConfig.Replay.SPEEDS),
    /** 비행 지도와 같은 상태: 시각 t의 기체(telemetry), t까지 궤적(track), 이륙 지점, 소티 전주·계획 경로, 배경. */
    val map: FlightUiState = FlightUiState(loading = false),
    /** 전체 궤적(흐리게). */
    val fullTrack: List<GeoPoint> = emptyList(),
    val photos: List<ReplayPhoto> = emptyList(),
    /** 재생 위치의 벽시계 시각. 이때까지 찍은 사진은 진하게. */
    val wallAt: Long = 0,
    /** 크게 보는 사진. */
    val viewing: ReplayPhoto? = null,
)

/**
 * 비행 다시 보기(10.4.6, Pilot 2 비행 기록 리플레이): 자동 비행 소티(TELEMETRY_SAMPLE) 또는 수동 비행(MANUAL_TRACK) 궤적을
 * 비행 지도 위에 시간 막대로 다시 본다. 재생·배속·찾기와 t의 상태 계산은 `:core` [ReplayTrack]·[Playback].
 * [missionId]면 그 미션의 소티([sortieId], 없으면 첫 소티), [sessionId]면 수동 비행.
 */
class ReplayViewModel(
    private val dao: ProjectDao,
    private val appFilesDir: File?,
    private val settings: SettingsStore?,
    private val missionId: Long?,
    sortieId: Long?,
    private val sessionId: Long?,
) : ViewModel() {
    private val _state = MutableStateFlow(ReplayUiState())
    val state: StateFlow<ReplayUiState> = _state.asStateFlow()
    private var track = ReplayTrack(emptyList(), AppConfig.Replay.HOLD_GAP_MS)

    /** 지우개(10.5.4)를 누른 시각: 궤적을 이때부터 그린다. */
    private var trackFrom = 0L

    init {
        OpLog.log("replay", "open", mapOf("mission" to missionId, "sortie" to sortieId, "session" to sessionId))
        viewModelScope.launch {
            val files = appFilesDir?.let { listMbtiles(File(it, "maps")) }.orEmpty()
            _state.update { it.copy(map = it.map.copy(mapFiles = files)) }
            val bg = settings?.let { mapBackgroundRetrying(it) } ?: MapBackground.NONE
            _state.update { it.copy(map = it.map.copy(background = bg)) }
        }
        viewModelScope.launch {
            when {
                sessionId != null -> loadSession(sessionId)
                missionId != null -> {
                    val sorties = dao.sortieRecords().filter { it.missionId == missionId }.mapNotNull { r -> r.sortieId?.let { ReplaySortie(it, r.seq ?: 0) } }.sortedBy { it.seq }
                    _state.update { it.copy(sorties = sorties) }
                    (sortieId ?: sorties.firstOrNull()?.id)?.let { loadSortie(missionId, it) } ?: _state.update { it.copy(loading = false) }
                }
                else -> _state.update { it.copy(loading = false) }
            }
        }
        // 재생 시계: 실제로 지난 시간만큼 나아간다
        viewModelScope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(AppConfig.Replay.FRAME_MS)
                val now = System.currentTimeMillis()
                if (_state.value.playback.playing) render(_state.value.playback.tick(now - last))
                last = now
            }
        }
    }

    private suspend fun loadSortie(missionId: Long, sortieId: Long) {
        _state.update { it.copy(loading = true, sortieId = sortieId, viewing = null) }
        val m = dao.mission(missionId) ?: return _state.update { it.copy(loading = false) }
        val rows = dao.missionVisits(missionId).filter { it.sortieId == sortieId }
        val samples = dao.telemetry(sortieId).map { trackSample(it.at, it.lat, it.lon, it.altRel, it.speed, it.heading, it.gimbalPitch, it.batteryPct, it.flightMode) }
        val photos = dao.sortiePhotos(sortieId)
        val seq = rows.firstOrNull()?.seq ?: _state.value.sorties.firstOrNull { it.id == sortieId }?.seq
        show(
            samples, home = GeoPoint(m.homeLat, m.homeLon), photos = photos,
            title = "${dao.projectName(m.projectId).orEmpty()} · 미션 #$missionId 소티 ${seq ?: "-"}",
        ) { map ->
            map.copy(
                poles = rows.map { it.toPole() }, params = flightParamsFromJson(m.flightParams),
                exec = map.exec.copy(
                    visits = rows.mapIndexed { i, r ->
                        VisitView(0, i + 1, r.toPole(), VisitStatus.entries.firstOrNull { it.name == r.status } ?: VisitStatus.PENDING, 0)
                    },
                ),
            )
        }
    }

    private suspend fun loadSession(sessionId: Long) {
        val samples = dao.manualTrack(sessionId).map { trackSample(it.at, it.lat, it.lon, it.altRel, it.speed, it.heading, it.gimbalPitch, it.batteryPct, it.flightMode) }
        val from = samples.firstOrNull()?.at ?: 0L
        val to = samples.lastOrNull()?.at ?: 0L
        // 전주에 붙지 않은 수동 사진은 앱 폴더 `manual/<날짜>/manual_<찍은 시각>_…jpg`(수동 비행 화면 저장 규칙)
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
        val loose = withContext(Dispatchers.IO) {
            setOf(day.format(Date(from)), day.format(Date(to))).flatMap { d -> File(appFilesDir, "manual/$d").listFiles().orEmpty().toList() }
                .mapNotNull { f -> f.name.split('_').getOrNull(1)?.toLongOrNull()?.takeIf { it in from..to }?.let { f to it } }
        }
        // 이륙 지점은 기록하지 않아 첫 위치(뜬 직후)로 갈음한다
        show(samples, home = samples.firstNotNullOfOrNull { it.telemetry.position }, photos = dao.photosBetween(from, to), title = "수동 비행", loose = loose) { it }
    }

    /** 궤적·사진을 지도에 올리고 처음부터 멈춘 채로 둔다. */
    private fun show(
        samples: List<TrackSample>, home: GeoPoint?, photos: List<AlbumPhoto>, title: String, loose: List<Pair<File, Long>> = emptyList(),
        extra: (FlightUiState) -> FlightUiState,
    ) {
        track = ReplayTrack(samples, AppConfig.Replay.HOLD_GAP_MS)
        trackFrom = 0L
        val start = track.startAt
        val date = if (samples.isEmpty()) "" else " · " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(start))
        // 사진 위치는 찍을 때 기체 위치, 없으면 그 시각의 궤적 위치
        fun at(lat: Double?, lon: Double?, t: Long) = if (lat != null && lon != null) GeoPoint(lat, lon) else track.stateAt(t)?.position
        val marks = photos.mapNotNull { p ->
            at(p.capture.lat, p.capture.lon, p.capture.takenAt)?.let { ReplayPhoto(it, p.capture.takenAt, p.capture.filePath, p.displayName, p.projectId, p.missionId) }
        } + loose.mapNotNull { (f, t) -> at(null, null, t)?.let { ReplayPhoto(it, t, f.path, f.name) } }
        _state.update {
            it.copy(
                loading = false, title = title + date, photos = marks.sortedBy { p -> p.takenAt },
                fullTrack = samples.mapNotNull { s -> s.telemetry.position },
                playback = Playback(track.durationMs, AppConfig.Replay.SPEEDS),
                map = extra(it.map.copy(home = home)),
            )
        }
        render(_state.value.playback)
    }

    /** 재생 위치의 기체·궤적을 지도 상태에 넣는다. */
    private fun render(p: Playback) {
        val wall = track.wallAt(p.posMs)
        _state.update {
            it.copy(playback = p, wallAt = wall, map = it.map.copy(telemetry = track.stateAt(wall), track = track.pathBetween(trackFrom, wall)))
        }
    }

    fun togglePlay() = render(_state.value.playback.toggle())
    fun nextSpeed() = _state.update { it.copy(playback = it.playback.nextSpeed()) }
    fun seek(posMs: Long) = render(_state.value.playback.seek(posMs))
    fun selectSortie(id: Long) {
        if (id == _state.value.sortieId) return
        missionId?.let { m -> viewModelScope.launch { loadSortie(m, id) } }
    }

    fun openPhoto(p: ReplayPhoto) = _state.update { it.copy(viewing = p) }
    fun closePhoto() = _state.update { it.copy(viewing = null) }

    /** 지도 지우개: 지금까지 그린 궤적을 지우고 여기부터 다시 그린다. */
    fun clearTrack() {
        trackFrom = _state.value.wallAt
        render(_state.value.playback)
    }

    /** 지도 레이어(10.5.7): 배경을 바꾸고 설정에도 남긴다. */
    fun setMapSource(source: MapSource) {
        val st = settings ?: return
        viewModelScope.launch { st.pickMapSource(source).let { bg -> _state.update { it.copy(map = it.map.copy(background = bg)) } } }
    }

    /** RC 5D: ↔ 찾기([AppConfig.Replay.SEEK_STEP_MS]), ● 재생·멈춤. 사진을 크게 보는 동안은 받지 않는다. */
    fun on5d(r: FiveDGate.Result) = r.actions.forEach { b ->
        if (_state.value.viewing != null) return@forEach
        val pos = _state.value.playback.posMs
        when (b) {
            RcButton.FIVE_D_LEFT -> seek(pos - AppConfig.Replay.SEEK_STEP_MS)
            RcButton.FIVE_D_RIGHT -> seek(pos + AppConfig.Replay.SEEK_STEP_MS)
            RcButton.FIVE_D_CENTER -> togglePlay()
            else -> Unit
        }
    }

    companion object {
        fun factory(dao: ProjectDao, appFilesDir: File?, settings: SettingsStore, missionId: Long?, sortieId: Long?, sessionId: Long?) =
            viewModelFactory { initializer { ReplayViewModel(dao, appFilesDir, settings, missionId, sortieId, sessionId) } }
    }
}

/** 기록 한 줄(TELEMETRY_SAMPLE·MANUAL_TRACK 같은 열)을 궤적 점으로. 위성·연결은 기록하지 않는다. */
private fun trackSample(at: Long, lat: Double?, lon: Double?, alt: Double, speed: Double, heading: Double, gimbal: Double, battery: Int, mode: String) =
    TrackSample(
        at,
        Telemetry(
            position = if (lat != null && lon != null) GeoPoint(lat, lon) else null, altitudeM = alt, speedMps = speed, batteryPct = battery.toDouble(),
            flightMode = FlightMode.entries.firstOrNull { it.name == mode } ?: FlightMode.UNKNOWN, gpsLevel = 0, connected = true, home = null,
            headingDeg = heading, gimbalPitchDeg = gimbal,
        ),
    )

/** 비행 기록 목록 한 줄(10.4.6): 자동 비행 소티 또는 수동 비행, 시작 시각·비행 시간(끊긴 구간은 줄임)·거리. */
data class FlightRecord(
    val sortieId: Long? = null,
    val sessionId: Long? = null,
    val missionId: Long? = null,
    val title: String,
    val startAt: Long,
    val durationMs: Long,
    val distanceM: Double,
)

/** [selected]는 RC 5D로 고른 줄(-1이면 아직 없음). */
data class FlightRecordsUiState(val loading: Boolean = true, val records: List<FlightRecord> = emptyList(), val selected: Int = -1)

/**
 * 비행 기록(10.4.6, Pilot 2 프로필 → 비행 기록): 궤적이 있는 자동 비행 소티와 수동 비행을 최신순으로.
 * ponytail: 열 때마다 모든 궤적 점을 읽어 시간·거리를 센다. 기록이 많아 느려지면 소티·수동 비행을 닫을 때 거리를 저장한다.
 */
class FlightRecordsViewModel(private val dao: ProjectDao) : ViewModel() {
    private val _state = MutableStateFlow(FlightRecordsUiState())
    val state: StateFlow<FlightRecordsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val sorties = dao.sortieRecords()
            val sessions = dao.manualRecords()
            val sortiePts = dao.sortieTrackPoints()
            val manualPts = dao.manualTrackPoints()
            val records = withContext(Dispatchers.Default) {
                val st = tracks(sortiePts)
                val mt = tracks(manualPts)
                sorties.mapNotNull { r ->
                    val t = st[r.sortieId] ?: return@mapNotNull null
                    FlightRecord(r.sortieId, null, r.missionId, "${r.projectName.orEmpty()} · 미션 #${r.missionId} 소티 ${r.seq}", t.startAt, t.durationMs, t.distanceM())
                } + sessions.mapNotNull { r ->
                    val t = mt[r.sessionId] ?: return@mapNotNull null
                    FlightRecord(null, r.sessionId, null, listOfNotNull("수동 비행", r.projectName).joinToString(" · "), t.startAt, t.durationMs, t.distanceM())
                }
            }
            _state.update { FlightRecordsUiState(loading = false, records = records.sortedByDescending { it.startAt }) }
        }
    }

    /** RC 5D ↕(10.4.3): 고른 줄을 옮긴다(처음이면 첫 줄). */
    fun move(delta: Int) = _state.update { it.copy(selected = moveIndex(it.selected, delta, it.records.size)) }

    private fun tracks(points: List<TrackPointRow>) = points.groupBy { it.id }.mapValues { (_, pts) ->
        ReplayTrack(pts.map { trackSample(it.at, it.lat, it.lon, 0.0, 0.0, 0.0, 0.0, 0, "") }, AppConfig.Replay.HOLD_GAP_MS)
    }

    companion object {
        fun factory(dao: ProjectDao) = viewModelFactory { initializer { FlightRecordsViewModel(dao) } }
    }
}
