package com.example.myfirstapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.example.myfirstapp.core.input.fiveD
import com.example.myfirstapp.core.model.ParamItem
import com.example.myfirstapp.core.model.step
import com.example.myfirstapp.ui.mission.FiveDHints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.R
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.TextSoft
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle
import kotlin.math.atan2
import kotlin.math.roundToInt
import com.example.myfirstapp.core.route.Viewpoints
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.myfirstapp.core.model.ShotMode
import android.graphics.RectF
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.PoleEntity
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.drone.djiCurrentLocation
import com.example.myfirstapp.drone.djiSimulatorOn
import com.example.myfirstapp.data.prefetchSatellite
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.poleDisplayName
import com.example.myfirstapp.core.route.RoutePlan
import com.example.myfirstapp.core.route.UnreachablePole
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.util.Locale
import org.maplibre.geojson.Polygon as GeoJsonPolygon

/** 소티별 경로 색. 전주(주황)·제외(회색)·영역(파랑)과 겹치지 않는 색을 돌려 쓴다(10.1 데이터 시각화 색). */
private val SORTIE_COLORS = listOf("#9C36B5", "#0C8599", "#C2255C", "#F59F00", "#3B5BDB")
private const val AREA_COLOR = "#1864AB"
private const val HOME_COLOR = "#FFC400"

// 전주 점 크기: 축소하면 작게(전체 1,000개가 서로 덜 겹치게), 확대하면 손가락으로 누르기 쉽게 키운다.
private val POLE_RADIUS = interpolate(linear(), zoom(), stop(11, 2.5f), stop(14, 4.5f), stop(16, 7f))
private val POLE_STROKE = interpolate(linear(), zoom(), stop(11, 0.5f), stop(14, 1.5f), stop(16, 2f))

/**
 * 계획 화면 ③(10.4.3 시안, Pilot 2 비행경로 편집 68·74쪽): 머리글 32dp(뒤로·프로젝트·저장 상태),
 * 지도 위 가운데 경로 정보 띠, 왼쪽 흰 그리기 도구(영역·이륙), 오른쪽 흰 지도 도구(전체 보기·레이어·지우기),
 * 오른쪽 240dp 패널(고른 전주, 촬영 방식, 고도·거리·피치(자동)·속도, [저장] [저장하고 수행]).
 * 경로는 바꿀 때마다 자동으로 다시 계산한다. 계획 로직은 [PlanViewModel]에 있고, 이 화면은 상태를 그리고 누른 것을 올려 보낸다.
 */
@Composable
fun MapScreen(
    projectId: Long,
    dao: ProjectDao,
    onBack: () -> Unit,
    onStartFlight: (missionId: Long) -> Unit,
    replanFrom: Long? = null,
) {
    val context = LocalContext.current
    val vm: PlanViewModel = viewModel(
        factory = PlanViewModel.factory(
            dao, projectId, replanFrom, File(context.getExternalFilesDir(null), "maps").apply { mkdirs() },
            (context.applicationContext as InspectionApp).settings, ::djiCurrentLocation, ::djiSimulatorOn,
            { points, name -> prefetchSatellite(context.applicationContext, points, name) },
        ),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val inArea = s.inArea
    // 화면 배치 상태(UI 전용): 배경 지도 보이기, 전체 보기 요청
    var showBackground by rememberSaveable { mutableStateOf(true) }
    var fitRequest by remember { mutableStateOf(0) }

    LaunchedEffect(s.flyMissionId) {
        s.flyMissionId?.let { vm.onFlightOpened(); onStartFlight(it) }
    }
    // 영역·이륙 지점을 찍는 중 뒤로 가기는 화면을 떠나지 않고 도구만 취소한다(10.3)
    BackHandler(enabled = s.tool != Tool.NONE, onBack = vm::cancelTool)
    // RC 5D(10.4.3): ↕ 미션 설정 항목, ↔ 값. 5D ←는 여기서 뒤로가 아니다(MainActivity 허용 목록에서 뺌)
    val rc = (context.applicationContext as InspectionApp).rcButtons
    LaunchedEffect(vm) { rc.fiveD().collect(vm::on5d) }

    Column(Modifier.fillMaxSize()) {
        PlanHeader(s, replanFrom, onBack)
        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                s.poles?.let {
                    PoleMap(
                        poles = it,
                        mapFiles = s.mapFiles,
                        onlineTiles = s.onlineTiles,
                        overlay = Overlay(s.vertices, s.home, s.plan, s.excludedPoints, s.params, s.poles?.firstOrNull { p -> p.poleId == s.selected }?.let { p -> GeoPoint(p.lat, p.lon) }),
                        focus = s.focus,
                        fitRequest = fitRequest,
                        showBackground = showBackground,
                        onPoleClick = vm::selectPole,
                        onMapTap = if (s.tool == Tool.NONE) null else vm::onMapTap,
                    )
                }
                Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapTool("영역 그리기", active = s.tool == Tool.AREA, onClick = vm::startArea) { tint ->
                        Icon(painterResource(R.drawable.ic_area), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                    MapTool("이륙 지점", active = s.tool == Tool.HOME, onClick = vm::startHome) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Caution), contentAlignment = Alignment.Center) {
                            Text("H", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 52.dp, end = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteInfoBand(s, inArea.size)
                    HintChip(s, inArea.size, vm)
                }
                Column(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapTool("전체 보기", onClick = { fitRequest++ }) { tint -> Icon(painterResource(R.drawable.ic_fit), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) }
                    MapTool(if (showBackground) "배경 끄기" else "배경 켜기", active = !showBackground, onClick = { showBackground = !showBackground }) { tint ->
                        Icon(painterResource(R.drawable.ic_layers), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                    MapTool("지우기", enabled = s.vertices.isNotEmpty(), onClick = vm::clearArea) { tint ->
                        Icon(painterResource(R.drawable.ic_erase), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                }
            }
            SidePanel(s, inArea, vm)
        }
    }
}

/** 머리글 32dp(지도·카메라 화면, 10.4.2): 뒤로, 프로젝트 이름, 새 계획/다시 계획, 저장 상태. */
@Composable
private fun PlanHeader(s: PlanUiState, replanFrom: Long?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(32.dp).background(Color.Black).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_back), contentDescription = "뒤로", modifier = Modifier.size(15.dp))
        }
        Text(s.projectName, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        Text(replanFrom?.let { "미션 #$it 남은 전주" } ?: "새 계획", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        val saved = s.savedMissionId
        val c = if (saved == null) Warning else MaterialTheme.colorScheme.tertiary
        Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c))
            Text(if (saved == null) "저장 전" else "저장됨 · 미션 #$saved", fontSize = 11.sp, color = c, maxLines = 1)
        }
    }
}

/** 흰 지도 도구 36dp(10.4.3). 쓰는 중인 도구는 파랑 테두리와 파랑 아이콘. */
@Composable
private fun MapTool(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, color = Color.White, shape = RoundedCornerShape(4.dp),
        border = if (active) BorderStroke(2.dp, Accent) else null,
        modifier = Modifier.size(36.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { icon(if (!enabled) Color(0xFFB0B0B0) else if (active) Accent else Color.Black) }
    }
}

/** 지도 위 가운데 경로 정보 띠(Pilot 2 74쪽): 경로 길이, 예상 시간, 전주, 사진, 소티. */
@Composable
private fun RouteInfoBand(s: PlanUiState, inAreaCount: Int) {
    val plan = s.plan
    val poles = plan?.sorties?.sumOf { it.poles.size }
    val cells = listOf(
        "경로 길이" to (plan?.let { distance(it.sorties.sumOf { so -> so.estDistanceM }) } ?: "-"),
        "예상 시간" to (plan?.let { mmss(it.sorties.sumOf { so -> so.estDurationSec }) } ?: "-"),
        "전주" to (poles?.toString() ?: if (s.vertices.size >= 3) "${inAreaCount - s.excluded.size}" else "-"),
        "사진" to (plan?.let { "${it.sorties.sumOf { so -> so.poles.sumOf(s.params::photosPerPole) }}" } ?: "-"),
        "소티" to (plan?.let { "${it.sorties.size}" } ?: "-"),
    )
    Row(Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.75f)).height(IntrinsicSize.Min)) {
        cells.forEachIndexed { i, (label, value) ->
            if (i > 0) VerticalDivider(color = Color(0xFF333333))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 11.sp, color = TextSoft, maxLines = 1)
                Text(value, style = numberStyle(15.sp), maxLines = 1)
            }
        }
    }
}

private fun distance(m: Double) = if (m < 1000) "${m.toInt()} m" else "${fmt(m / 1000)} km"
private fun mmss(sec: Double) = sec.toInt().let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }

/** 띠 아래 안내: 지금 할 일 한 줄(10.0 순서 안내)과 그리는 중의 [되돌리기]·[완료]·[취소]. */
@Composable
private fun HintChip(s: PlanUiState, inAreaCount: Int, vm: PlanViewModel) {
    val text = when {
        s.tool == Tool.AREA -> "지도를 눌러 꼭짓점 추가 (${s.vertices.size}개 · 전주 ${inAreaCount}개)"
        s.tool == Tool.HOME -> "지도를 눌러 이륙 지점(H)을 정하세요"
        s.vertices.size < 3 -> "① 왼쪽 [영역]으로 점검 영역을 그리세요"
        s.home == null -> "② [H]로 이륙 지점을 정하세요"
        s.planning -> "경로 계산 중"
        s.plan == null -> s.paramErrors.firstOrNull() ?: "영역 안에 넣을 전주가 없습니다"
        s.savedMissionId == null -> "③ 경로를 확인하고 [저장하고 수행]"
        else -> null
    }
    val message = s.message
    if (text == null && message == null) return
    Surface(color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                text?.let { Text(it, fontSize = 12.sp, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                when (s.tool) {
                    Tool.AREA -> {
                        ChipButton("되돌리기", enabled = s.vertices.isNotEmpty(), onClick = vm::undoVertex)
                        ChipButton("완료", enabled = s.vertices.size >= 3, filled = true, onClick = vm::finishArea)
                    }
                    Tool.HOME -> ChipButton("취소", onClick = vm::cancelTool)
                    Tool.NONE -> Unit
                }
            }
            message?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ChipButton(label: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.height(30.dp), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 10.dp),
        colors = if (filled) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surfaceContainer, Color.White),
    ) { Text(label, fontSize = 12.sp) }
}

/**
 * 오른쪽 패널 240dp(10.4.3): 고른 전주 → 미션 설정(촬영 방식, 항목 줄) → [저장] [저장하고 수행].
 * 가운데는 넘치면 스크롤한다(이동 고도·예비율·소티별 정보는 아래쪽).
 */
@Composable
private fun SidePanel(s: PlanUiState, inArea: List<PoleEntity>, vm: PlanViewModel) {
    Column(Modifier.width(240.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        val pole = s.poles?.firstOrNull { it.poleId == s.selected }
        if (pole != null) {
            PoleSection(pole, isExcluded = pole.poleId in s.excluded, canExclude = inArea.any { it.id == pole.id }, vm)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        val guys = inArea.count { it.guy && it.poleId in s.excluded }
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull("미션 설정", "지선주 ${guys}개 기본 제외".takeIf { guys > 0 }).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            ParamRows(s.params, pole, enabled = s.tool == Tool.NONE, focus = s.paramFocus, simulator = s.simulator, onChange = vm::updateParams)
            s.paramErrors.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
            s.plan?.let { PlanSummary(it, s.planMs, s.params) }
        }
        FiveDHints(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp), listOf("5D ↕" to "항목", "5D ↔" to "값"))
        val ready = s.plan != null && s.plan.sorties.isNotEmpty() && !s.saving
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { vm.savePlan() }, enabled = ready && s.savedMissionId == null, modifier = Modifier.width(72.dp).height(40.dp),
                shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.surfaceContainer, Color.White),
            ) { Text(if (s.savedMissionId != null) "저장됨" else "저장", fontSize = 12.sp) }
            Button(
                onClick = { vm.savePlan(fly = true) }, enabled = ready, modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(0.dp),
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (s.savedMissionId != null) "수행" else "저장하고 수행", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 고른 전주(Pilot 2 개별 웨이포인트 설정 자리): 이름·설치기기, 속성 한 줄, 경로 넣기/빼기(F-06). */
@Composable
private fun PoleSection(pole: PoleEntity, isExcluded: Boolean, canExclude: Boolean, vm: PlanViewModel) {
    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (isExcluded) Color.Gray else Warning).border(2.dp, Caution, CircleShape))
            Text(poleDisplayName(pole.poleId, pole.lineName, pole.lineNo), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            pole.equipment?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 11.sp, color = Caution, maxLines = 1, modifier = Modifier.border(1.dp, Caution, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            }
            IconButton(onClick = { vm.selectPole(null) }, modifier = Modifier.size(36.dp).semantics { contentDescription = "닫기" }) { Text("✕", fontSize = 12.sp, color = TextSoft) }
        }
        // 설비 엑셀 속성(4.5): 전산화번호 · 규격 · 설치년월 · 지선주
        val attrs = listOfNotNull(
            pole.poleId, pole.lengthM?.let { "${it.toInt()}M" },
            pole.installedYm?.takeIf { it.length >= 6 }?.let { "${it.take(4)}-${it.substring(4, 6)} 설치" }, "지선주".takeIf { pole.guy }, pole.note?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        Text(attrs, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            !canExclude -> Text("영역 밖 전주", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> ChipButton(if (isExcluded) "경로에 다시 넣기" else "경로에서 빼기") { vm.toggleExcluded(pole.poleId) }
        }
    }
}


/**
 * 미션 설정(6.3, 5.6): 촬영 방식 두 버튼과 40dp 항목 줄. 사선은 비행 고도·전주 거리·짐벌 피치(전주 높이로 자동)·속도,
 * 전주 위는 점검 고도·속도. 아래에 이동 고도·예비율. 범위 검증은 [FlightParams.validate]. 영역·이륙 지점을 찍는 동안에는 잠근다.
 */
@Composable
private fun ParamRows(p: FlightParams, pole: PoleEntity?, enabled: Boolean, focus: ParamItem?, simulator: Boolean, onChange: (FlightParams) -> Unit) {
    fun step(item: ParamItem): (Int) -> Unit = { onChange(p.step(item, it)) }
    Row(
        Modifier.focusMark(focus == ParamItem.SHOT_MODE).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ShotMode.entries.forEach { mode ->
            val on = p.shotMode == mode
            Surface(
                onClick = { onChange(p.copy(shotMode = mode)) }, enabled = enabled, modifier = Modifier.weight(1f).height(32.dp),
                shape = RoundedCornerShape(4.dp), color = if (on) Accent.copy(alpha = 0.16f) else Color.Transparent,
                border = BorderStroke(1.dp, if (on) Accent else MaterialTheme.colorScheme.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (mode == ShotMode.OVERHEAD) "전주 위" else "4방향 사선", fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Color.White else TextSoft)
                }
            }
        }
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        if (p.shotMode == ShotMode.DIAGONAL4) {
            ParamRow("비행 고도", "${p.diagonalAltitudeM.toInt()}", "m", enabled, focus == ParamItem.DIAGONAL_ALTITUDE, step(ParamItem.DIAGONAL_ALTITUDE))
            ParamRow("전주 거리", "${p.diagonalDistanceM.toInt()}", "m", enabled, focus == ParamItem.DIAGONAL_DISTANCE, step(ParamItem.DIAGONAL_DISTANCE))
            // 피치는 고도·거리·조준 높이(전주 지상고, 없으면 기본 조준 높이)로 정해진다(5.6)
            val aim = pole?.let { Viewpoints.aimHeight(it.toPole(), p) } ?: p.aimHeightM
            val pitch = -Math.toDegrees(atan2(p.diagonalAltitudeM - aim, p.diagonalDistanceM))
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("짐벌 피치", Modifier.weight(1f), fontSize = 12.sp, color = TextSoft)
                Text("${pitch.roundToInt()}°", style = numberStyle(15.sp))
                Text(" 전주 높이로 자동", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        } else {
            ParamRow("점검 고도", "${p.inspectAltitudeM.toInt()}", "m", enabled, focus == ParamItem.INSPECT_ALTITUDE, step(ParamItem.INSPECT_ALTITUDE))
        }
        ParamRow("속도", "${p.speedMps.toInt()}", "m/s", enabled, focus == ParamItem.SPEED, step(ParamItem.SPEED))
        ParamRow("이동 고도", "${p.transitAltitudeM.toInt()}", "m", enabled, focus == ParamItem.TRANSIT_ALTITUDE, step(ParamItem.TRANSIT_ALTITUDE))
        ParamRow("배터리 예비율", "${p.batteryReservePct.toInt()}", "%", enabled, focus == ParamItem.BATTERY_RESERVE, step(ParamItem.BATTERY_RESERVE))
        Row(Modifier.fillMaxWidth().height(40.dp).focusMark(focus == ParamItem.TERRAIN_FOLLOW), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("실시간 지형 추적", fontSize = 12.sp, color = TextSoft)
                if (simulator) Text("시뮬레이터: 지형 추적 끔", fontSize = 10.sp, color = Warning)
            }
            Switch(checked = p.terrainFollow, onCheckedChange = { onChange(p.copy(terrainFollow = it)) }, enabled = enabled)
        }
    }
}

/** 5D로 고른 항목 표시: 파랑 테두리, 패널이 스크롤되면 보이는 곳으로 끌어온다. */
@Composable
private fun Modifier.focusMark(focused: Boolean): Modifier {
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bring.bringIntoView() }
    return bringIntoViewRequester(bring).then(if (focused) Modifier.border(1.dp, Accent, RoundedCornerShape(4.dp)) else Modifier)
}

/** 항목 한 줄 40dp: 이름, [−] 값 [+](32dp). 버튼 설명은 스모크 스크립트가 찾는 이름이다("속도 낮추기" 등). */
@Composable
private fun ParamRow(label: String, value: String, unit: String, enabled: Boolean, focused: Boolean, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).focusMark(focused), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, color = TextSoft, maxLines = 1)
        StepButton("−", "$label 낮추기", enabled) { onStep(-1) }
        Text(
            buildAnnotatedString {
                append(value)
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)) { append(unit) }
            },
            Modifier.width(46.dp), style = numberStyle(15.sp), textAlign = TextAlign.Center, maxLines = 1,
        )
        StepButton("+", "$label 높이기", enabled) { onStep(1) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun StepButton(sign: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Box(contentAlignment = Alignment.Center) { Text(sign, fontSize = 15.sp, color = if (enabled) Color.White else MaterialTheme.colorScheme.outline) } }
}

/** [onStep]에는 −1 또는 +1이 넘어간다(설정 화면에서 쓰는 큰 스테퍼). */
@Composable
internal fun Stepper(label: String, value: String, modifier: Modifier, enabled: Boolean = true, onStep: (Int) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        val name = label.substringBefore(" (")
        listOf(-1 to "−", 1 to "+").forEach { (d, sign) ->
            OutlinedButton(
                onClick = { onStep(d) },
                enabled = enabled,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "$name ${if (d < 0) "낮추기" else "높이기"}" },
                contentPadding = PaddingValues(0.dp),
            ) { Text(sign) }
        }
    }
}

/** 소티별 예상 시간·배터리(F-08)와 도달 불가 전주(5.4). 패널 스크롤 아래쪽. */
@Composable
private fun PlanSummary(plan: RoutePlan, planMs: Long, params: FlightParams) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        plan.sorties.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(SORTIE_COLORS[i % SORTIE_COLORS.size]))))
                Text(
                    "소티 ${i + 1} · ${s.poles.size}개 · ${mmss(s.estDurationSec)} · 배터리 ${s.estBatteryPct.toInt()}%",
                    fontSize = 11.sp, color = TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (plan.unreachable.isNotEmpty()) {
            val vlos = plan.unreachable.count { it.reason == UnreachablePole.Reason.VLOS_EXCEEDED }
            Text(
                "도달 불가 ${plan.unreachable.size}개 (가시권 ${params.vlosRadiusM.toInt()}m 초과 ${vlos}, 배터리 초과 ${plan.unreachable.size - vlos})",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
            )
        }
        Text("계산 ${planMs}ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

/** 지도 위에 그릴 계획 요소. */
private data class Overlay(
    val vertices: List<GeoPoint>, val home: GeoPoint?, val plan: RoutePlan?, val excluded: List<GeoPoint>, val params: FlightParams,
    /** 오른쪽 패널에 연 전주. 노란 고리로 표시한다(Pilot 2 선택한 웨이포인트). */
    val selected: GeoPoint?,
)

/**
 * MapLibre 지도. 배경은 앱 전용 폴더의 MBTiles를 모두 겹쳐 그린다(지역별 파일). 네트워크 소스는 쓰지 않는다.
 * ponytail: 글리프(폰트) 파일이 없어 지도 위 글자(전주번호)는 그리지 않는다. 전주번호는 탭하면 카드로 보여 준다.
 */
@Composable
private fun PoleMap(
    poles: List<PoleEntity>,
    mapFiles: List<File>,
    onlineTiles: List<String>,
    overlay: Overlay,
    focus: List<GeoPoint>,
    /** 바뀔 때마다 영역(없으면 전주 전체)에 맞춰 다시 보여 준다(Pilot 2 중앙 복귀). */
    fitRequest: Int,
    /** 배경 지도(위성·MBTiles)를 켜고 끈다(Pilot 2 레이어). */
    showBackground: Boolean,
    onPoleClick: (String?) -> Unit,
    onMapTap: ((GeoPoint) -> Unit)?,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val onClick by rememberUpdatedState(onPoleClick)
    val onTap by rememberUpdatedState(onMapTap)
    val mapView = rememberMapView()
    var fitted by remember { mutableStateOf(false) }
    var styleGen by remember { mutableStateOf(0) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.addOnMapClickListener { latLng ->
                onTap?.let {
                    it(GeoPoint(latLng.latitude, latLng.longitude))
                    return@addOnMapClickListener true
                }
                val p = map.projection.toScreenLocation(latLng)
                val touch = 24 * density // 장갑 낀 손가락 기준 48dp 영역
                val box = RectF(p.x - touch, p.y - touch, p.x + touch, p.y + touch)
                onClick(map.queryRenderedFeatures(box, "poles-point").firstOrNull()?.getStringProperty("poleId"))
                true
            }
        }
    }

    LaunchedEffect(poles, mapFiles, onlineTiles) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson(mapFiles, onlineTiles))) { style ->
                val features = poles.map {
                    Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("poleId", it.poleId) }
                }
                // 전주는 묶지 않고 하나하나 점으로 그린다(N-04). 1,000개 수준의 원 레이어는 MapLibre가 무리 없이 그린다.
                style.addSource(GeoJsonSource("poles", FeatureCollection.fromFeatures(features)))
                // 영역과 경로는 전주 아래에, 꼭짓점과 이륙 지점은 맨 위에 그린다.
                style.addSource(GeoJsonSource("draw"))
                style.addSource(GeoJsonSource("route"))
                style.addSource(GeoJsonSource("home"))
                style.addSource(GeoJsonSource("selected"))
                style.addSource(GeoJsonSource("excluded"))
                style.addLayer(
                    FillLayer("area-fill", "draw").withFilter(eq(geometryType(), literal("Polygon")))
                        .withProperties(fillColor(AREA_COLOR), fillOpacity(0.12f)),
                )
                style.addLayer(
                    LineLayer("area-line", "draw").withFilter(neq(geometryType(), literal("Point")))
                        .withProperties(lineColor(AREA_COLOR), lineWidth(3f)),
                )
                style.addLayer(
                    LineLayer("route-line", "route").withFilter(eq(geometryType(), literal("LineString")))
                        .withProperties(lineColor(get("color")), lineWidth(4f)),
                )
                style.addLayer(
                    CircleLayer("poles-point", "poles").withProperties(
                        circleColor("#D9480F"), circleRadius(POLE_RADIUS), circleStrokeColor("#FFFFFF"), circleStrokeWidth(POLE_STROKE),
                    ),
                )
                // 경로가 꺾이는 곳마다 방문 전주를 소티 색 점으로 찍는다(꺾이는 곳이 전주임을 보이게).
                style.addLayer(
                    CircleLayer("route-stop", "route").withFilter(eq(geometryType(), literal("Point"))).withProperties(
                        circleColor("#FFFFFF"), circleRadius(5f), circleStrokeColor(get("color")), circleStrokeWidth(3f),
                    ),
                )
                // 제외한 전주(F-06)는 원래 점 위에 회색으로 덮어 그린다.
                style.addLayer(
                    CircleLayer("excluded-point", "excluded").withProperties(
                        circleColor("#868E96"), circleRadius(POLE_RADIUS), circleStrokeColor("#FFFFFF"), circleStrokeWidth(POLE_STROKE),
                    ),
                )
                style.addLayer(
                    CircleLayer("area-vertex", "draw").withFilter(eq(geometryType(), literal("Point"))).withProperties(
                        circleColor("#FFFFFF"), circleRadius(6f), circleStrokeColor(AREA_COLOR), circleStrokeWidth(3f),
                    ),
                )
                style.addLayer(
                    CircleLayer("selected-ring", "selected").withProperties(
                        circleColor("#00000000"), circleRadius(14f), circleStrokeColor(HOME_COLOR), circleStrokeWidth(4f),
                    ),
                )
                // 이륙 지점은 Pilot 2 홈 포인트(H)처럼 노란 점
                style.addLayer(
                    CircleLayer("home-point", "home").withProperties(
                        circleColor(HOME_COLOR), circleRadius(10f), circleStrokeColor("#212529"), circleStrokeWidth(3f),
                    ),
                )
                styleGen++
                if (!fitted && poles.isNotEmpty()) {
                    fitted = true
                    map.moveCamera(fitCamera(poles.map { GeoPoint(it.lat, it.lon) }, density))
                }
            }
        }
    }

    // 영역을 다 그리면 그 영역으로 확대한다(경로를 알아볼 수 있게).
    LaunchedEffect(focus) {
        if (focus.size < 2) return@LaunchedEffect
        mapView.getMapAsync { map ->
            map.animateCamera(fitCamera(focus, density))
        }
    }

    val fitTargets by rememberUpdatedState(overlay.vertices.takeIf { it.size >= 3 } ?: poles.map { GeoPoint(it.lat, it.lon) })
    LaunchedEffect(fitRequest) {
        if (fitRequest == 0 || fitTargets.isEmpty()) return@LaunchedEffect
        mapView.getMapAsync { map -> map.animateCamera(fitCamera(fitTargets, density)) }
    }

    LaunchedEffect(showBackground, styleGen) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            style.layers.filter { it.id == "online" || it.id.startsWith("base") }
                .forEach { it.setProperties(visibility(if (showBackground) Property.VISIBLE else Property.NONE)) }
        }
    }

    LaunchedEffect(overlay, styleGen) {
        mapView.getMapAsync { map ->
            val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
            style.getSourceAs<GeoJsonSource>("draw")?.setGeoJson(drawFeatures(overlay.vertices))
            style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(routeFeatures(overlay.plan, overlay.params))
            style.getSourceAs<GeoJsonSource>("excluded")?.setGeoJson(
                FeatureCollection.fromFeatures(overlay.excluded.map { Feature.fromGeometry(it.toPoint()) }),
            )
            style.getSourceAs<GeoJsonSource>("selected")?.setGeoJson(
                FeatureCollection.fromFeatures(listOfNotNull(overlay.selected?.let { Feature.fromGeometry(it.toPoint()) })),
            )
            style.getSourceAs<GeoJsonSource>("home")?.setGeoJson(
                FeatureCollection.fromFeatures(listOfNotNull(overlay.home?.let { Feature.fromGeometry(it.toPoint()) })),
            )
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/**
 * [points]가 다 보이게 맞추는 카메라. 지도 위 버튼(왼쪽 도구 줄, 위쪽 저장 카드·안내, 오른쪽 지도 버튼, 아래 [수행])에 가리지 않게 여백을 둔다.
 */
private fun fitCamera(points: List<GeoPoint>, density: Float) =
    if (points.distinct().size < 2) CameraUpdateFactory.newLatLngZoom(LatLng(points.first().lat, points.first().lon), 17.0)
    else CameraUpdateFactory.newLatLngBounds(
        LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build(),
        (80 * density).toInt(), (140 * density).toInt(), (64 * density).toInt(), (88 * density).toInt(),
    )

private const val MAP_CACHE_BYTES = 1_000_000_000L
private var ambientCacheSized = false

/** 화면 수명에 맞춰 돌리는 MapLibre 지도 뷰(계획·비행 화면 공용). */
@Composable
internal fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        // 본 위성 타일을 넉넉히 남겨 현장(인터넷 없음)에서도 보이게 한다(기본 50MB)
        if (!ambientCacheSized) {
            ambientCacheSized = true
            OfflineManager.getInstance(context).setMaximumAmbientCacheSize(MAP_CACHE_BYTES, null)
        }
        // Compose 안에서는 SurfaceView보다 TextureView가 겹침·캡처 문제가 적다.
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)
            // 로고·출처는 오른쪽 아래(왼쪽 아래 [수행]·지도 전환 버튼과 겹치지 않게)
            .logoGravity(Gravity.BOTTOM or Gravity.END).attributionGravity(Gravity.BOTTOM or Gravity.END)
            .logoMargins(intArrayOf(0, 0, (36 * context.resources.displayMetrics.density).toInt(), 8)),).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

internal fun GeoPoint.toPoint(): Point = Point.fromLngLat(lon, lat)

/** 그리는 중인 영역: 꼭짓점 3개 이상이면 다각형, 2개면 선, 그리고 꼭짓점 점들. */
private fun drawFeatures(vertices: List<GeoPoint>): FeatureCollection {
    val pts = vertices.map { it.toPoint() }
    val shape = when {
        pts.size >= 3 -> Feature.fromGeometry(areaPolygon(vertices))
        pts.size == 2 -> Feature.fromGeometry(LineString.fromLngLats(pts))
        else -> null
    }
    return FeatureCollection.fromFeatures(listOfNotNull(shape) + pts.map { Feature.fromGeometry(it) })
}

/** 점검 영역을 닫힌 GeoJSON 다각형으로. `MISSION.areaGeoJson`에도 이 형태로 저장한다. */
internal fun areaPolygon(vertices: List<GeoPoint>): GeoJsonPolygon =
    vertices.map { it.toPoint() }.let { GeoJsonPolygon.fromLngLats(listOf(it + it.first())) }

/** 소티마다 이륙 지점 → 실제 촬영 지점들(5.6, 4방향이면 4패스) → 이륙 지점으로 닫힌 선 하나와 방문 전주 점들. 기체가 날 경로와 같다. */
private fun routeFeatures(plan: RoutePlan?, params: FlightParams): FeatureCollection = FeatureCollection.fromFeatures(
    if (plan == null) emptyList() else plan.sorties.flatMapIndexed { i, s ->
        val color = SORTIE_COLORS[i % SORTIE_COLORS.size]
        val line = listOf(plan.home) + Viewpoints.expand(s.poles, params, plan.home).map { it.position } + plan.home
        (listOf(LineString.fromLngLats(line.map { it.toPoint() })) + s.poles.map { it.position.toPoint() })
            .map { g -> Feature.fromGeometry(g).apply { addStringProperty("color", color) } }
    },
)

/** 온라인 위성(7.4 임시)을 맨 아래에, 그 위에 MBTiles를 겹친다. */
internal fun styleJson(mapFiles: List<File>, onlineTiles: List<String>): String {
    val online = onlineTiles.takeIf { it.isNotEmpty() }?.let { urls ->
        val tiles = urls.joinToString(",") { "\"$it\"" }
        """"online":{"type":"raster","tiles":[$tiles],"tileSize":256,"maxzoom":19,"attribution":"© Microsoft Bing, Cesium ion"}"""
    }
    val sources = listOfNotNull(online) + mapFiles.mapIndexed { i, f -> """"base$i":{"type":"raster","url":"mbtiles://${f.absolutePath}","tileSize":256}""" }
    val layers = listOf("""{"id":"background","type":"background","paint":{"background-color":"#E6EAE7"}}""") +
        listOfNotNull(online?.let { """{"id":"online","type":"raster","source":"online"}""" }) +
        mapFiles.indices.map { """{"id":"base$it","type":"raster","source":"base$it"}""" }
    return """{"version":8,"sources":{${sources.joinToString(",")}},"layers":[${layers.joinToString(",")}]}"""
}
