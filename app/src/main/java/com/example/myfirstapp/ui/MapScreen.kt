package com.example.myfirstapp.ui

import com.example.myfirstapp.core.route.Viewpoints
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.myfirstapp.core.model.ShotMode
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.heightIn
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
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.route.RoutePlan
import com.example.myfirstapp.core.route.UnreachablePole
import org.maplibre.android.MapLibre
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
 * 계획 화면 ③(10장): DJI Pilot 2 비행경로 편집(매뉴얼 68·74쪽)과 같은 배치.
 * 지도 왼쪽 위 저장·경로 정보, 왼쪽 세로 도구(영역·이륙·되돌리기·지우기), 지도 오른쪽 가장자리 지도 버튼(61쪽 중앙 복귀·레이어),
 * 오른쪽 전체 높이 설정 패널(» 로 접음), 왼쪽 아래 [▶ 수행]. 경로는 바꿀 때마다 자동으로 다시 계산한다.
 * 계획 로직은 [PlanViewModel]에 있고, 이 화면은 상태를 그리고 누른 것을 올려 보낸다.
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
            (context.applicationContext as InspectionApp).settings, ::djiCurrentLocation,
        ),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val inArea = s.inArea
    // 화면 배치 상태(UI 전용): 패널 펼침, 배경 지도 보이기, 전체 보기 요청
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    var showBackground by rememberSaveable { mutableStateOf(true) }
    var fitRequest by remember { mutableStateOf(0) }

    LaunchedEffect(s.flyMissionId) {
        s.flyMissionId?.let { vm.onFlightOpened(); onStartFlight(it) }
    }
    // 전주를 누르면 오른쪽 패널에 그 전주 설정이 뜬다(Pilot 2 개별 웨이포인트 설정)
    LaunchedEffect(s.selected) { if (s.selected != null) panelOpen = true }

    Row(Modifier.fillMaxSize()) {
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
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SaveInfoCard(s, inArea.size, onBack, onSave = { vm.savePlan() })
                ToolBar(s, onArea = vm::startArea, onHome = vm::startHome, onUndo = vm::undoVertex, onClear = vm::clearArea)
            }
            HintChip(s, inArea.size, vm, Modifier.align(Alignment.TopStart).padding(start = 72.dp, top = 64.dp, end = 64.dp))
            MapButtons(
                showBackground,
                onFit = { fitRequest++ },
                onToggleBackground = { showBackground = !showBackground },
                modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp),
            )
            ExecutePill(s, onExecute = { vm.savePlan(fly = true) }, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
            // 패널 접기·펴기(Pilot 2 »)
            MapIconButton(if (panelOpen) "»" else "«", if (panelOpen) "패널 접기" else "패널 펴기", Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                panelOpen = !panelOpen
            }
        }
        if (panelOpen) SidePanel(s, inArea, vm)
    }
}

private const val PANEL_WIDTH_DP = 256

/** 지도 위 버튼 바탕(10.1: 지도 위 정보는 반투명 어두운 패널). */
@Composable
private fun overlayColor() = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)

/** 왼쪽 위: 뒤로, 프로젝트 이름, 경로 정보 세 칸(매뉴얼 74쪽 매핑 면적·예상 시간 자리), [저장](68쪽 8번). */
@Composable
private fun SaveInfoCard(s: PlanUiState, inAreaCount: Int, onBack: () -> Unit, onSave: () -> Unit) {
    Surface(color = overlayColor(), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.height(48.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(s.projectName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
            VerticalDivider(Modifier.padding(horizontal = 6.dp, vertical = 10.dp))
            val plan = s.plan
            InfoCell("영역 전주", if (s.vertices.size >= 3) "${inAreaCount - s.excluded.size}개" else "-")
            InfoCell("예상 시간", plan?.let { "${fmt(it.sorties.sumOf { so -> so.estDurationSec } / 60)}분" } ?: "-")
            InfoCell("사진", plan?.let { "${it.sorties.sumOf { so -> so.poles.size } * perPole(s.params)}장" } ?: "-")
            VerticalDivider(Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
            TextButton(onClick = onSave, enabled = plan != null && s.savedMissionId == null && !s.saving, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (s.savedMissionId != null) "저장됨" else "저장")
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String) {
    Column(Modifier.padding(horizontal = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** 왼쪽 세로 도구(매뉴얼 68쪽 1~4번 자리). 지금 쓰는 도구는 강조색. */
@Composable
private fun ToolBar(s: PlanUiState, onArea: () -> Unit, onHome: () -> Unit, onUndo: () -> Unit, onClear: () -> Unit) {
    Surface(color = overlayColor(), shape = MaterialTheme.shapes.medium) {
        Column {
            ToolButton("⬠", "영역", selected = s.tool == Tool.AREA, onClick = onArea)
            ToolButton("H", "이륙", selected = s.tool == Tool.HOME, onClick = onHome)
            ToolButton("↶", "되돌리기", enabled = s.tool == Tool.AREA && s.vertices.isNotEmpty(), onClick = onUndo)
            ToolButton("✕", "지우기", enabled = s.vertices.isNotEmpty(), onClick = onClear)
        }
    }
}

@Composable
private fun ToolButton(glyph: String, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.size(width = 56.dp, height = 52.dp).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** 지도 오른쪽 가장자리(매뉴얼 61쪽 6번 중앙 복귀, 4번 레이어). */
@Composable
private fun MapButtons(showBackground: Boolean, onFit: () -> Unit, onToggleBackground: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapIconButton("◎", "전체 보기", onClick = onFit)
        MapIconButton(if (showBackground) "▦" else "□", if (showBackground) "배경 끄기" else "배경 켜기", onClick = onToggleBackground)
    }
}

@Composable
private fun MapIconButton(glyph: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = overlayColor(), shape = MaterialTheme.shapes.medium, modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) { Text(glyph, style = MaterialTheme.typography.titleMedium) }
    }
}

/** 도구 줄 오른쪽 안내: 지금 할 일 한 줄(10.0 순서 안내)과 그리는 중의 [완료]·[취소]. */
@Composable
private fun HintChip(s: PlanUiState, inAreaCount: Int, vm: PlanViewModel, modifier: Modifier) {
    val (text, action) = when {
        s.tool == Tool.AREA -> "지도를 눌러 꼭짓점 추가 (${s.vertices.size}개 · 전주 ${inAreaCount}개)" to ("완료" to vm::finishArea)
        s.tool == Tool.HOME -> "지도를 눌러 이륙 지점(H)을 정하세요" to ("취소" to vm::cancelTool)
        s.vertices.size < 3 -> "① 왼쪽 [영역]으로 점검 영역을 그리세요" to null
        s.home == null -> "② [이륙]으로 이륙 지점을 정하세요" to null
        s.planning -> "경로 계산 중" to null
        s.plan == null -> (s.paramErrors.firstOrNull() ?: "영역 안에 넣을 전주가 없습니다") to null
        s.savedMissionId == null -> "③ 경로를 확인하고 [▶ 수행]" to null
        else -> "저장됨 · 미션 #${s.savedMissionId}" to null
    }
    Surface(color = overlayColor(), shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                action?.let { (label, onClick) ->
                    Button(onClick = onClick, enabled = label != "완료" || s.vertices.size >= 3, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                }
            }
            s.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** 왼쪽 아래 [▶ 수행](매뉴얼 68쪽 9·10번): 저장하고 비행 전 점검으로. 옆에 소티 수와 경로 길이. */
@Composable
private fun ExecutePill(s: PlanUiState, onExecute: () -> Unit, modifier: Modifier) {
    val plan = s.plan
    val ready = plan != null && plan.sorties.isNotEmpty() && !s.saving
    Surface(color = overlayColor(), shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onExecute, enabled = ready, shape = CircleShape, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(56.dp).semantics { contentDescription = "수행" },
            ) { Text("▶", style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.padding(start = 10.dp, end = 14.dp)) {
                Text("수행", style = MaterialTheme.typography.labelLarge)
                Text(
                    plan?.let { "소티 ${it.sorties.size} · ${fmt(it.sorties.sumOf { so -> so.estDistanceM } / 1000)}km" } ?: "경로 없음",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

/** 오른쪽 패널(매뉴얼 68쪽 6·7번, 74쪽): 고른 전주 → 경로 설정 → 경로 정보. */
@Composable
private fun SidePanel(s: PlanUiState, inArea: List<PoleEntity>, vm: PlanViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(PANEL_WIDTH_DP.dp).fillMaxHeight()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            val pole = s.poles?.firstOrNull { it.poleId == s.selected }
            if (pole != null) {
                PoleSection(pole, isExcluded = pole.poleId in s.excluded, canExclude = inArea.any { it.id == pole.id }, vm)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            PanelTitle("경로 설정")
            ParamSteppers(s.params, enabled = s.tool == Tool.NONE, onChange = vm::updateParams)
            s.paramErrors.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
            s.plan?.let {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                PanelTitle("경로 정보")
                PlanSummary(it, s.planMs, s.params)
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))

/** 고른 전주(Pilot 2 개별 웨이포인트 설정 자리): 번호·선로·좌표, 경로 넣기/빼기(F-06). */
@Composable
private fun PoleSection(pole: PoleEntity, isExcluded: Boolean, canExclude: Boolean, vm: PlanViewModel) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pole.poleId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.selectPole(null) }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("✕") }
        }
        listOfNotNull(pole.lineName, pole.note).filter { it.isNotBlank() }.forEach {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(String.format(Locale.ROOT, "%.6f, %.6f", pole.lat, pole.lon), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            !canExclude -> Text("영역 밖 전주", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            isExcluded -> OutlinedButton(onClick = { vm.toggleExcluded(pole.poleId) }, modifier = Modifier.fillMaxWidth()) { Text("경로에 다시 넣기") }
            else -> OutlinedButton(onClick = { vm.toggleExcluded(pole.poleId) }, modifier = Modifier.fillMaxWidth()) { Text("경로에서 빼기") }
        }
    }
}

private fun perPole(p: FlightParams) = if (p.shotMode == ShotMode.DIAGONAL4) 4 else 1

/**
 * 경로 설정(6.3, Pilot 2 비행경로 설정): 촬영 방식(5.6)과 핵심 파라미터. 한 줄에 하나씩 이름·범위와 [−][+].
 * 장갑 낀 손가락으로 누를 수 있게 48dp 버튼. 범위 검증은 [FlightParams.validate]. 영역·이륙 지점을 찍는 동안에는 잠근다.
 */
@Composable
private fun ParamSteppers(p: FlightParams, enabled: Boolean, onChange: (FlightParams) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("촬영 방식", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ShotMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = p.shotMode == mode,
                    onClick = { onChange(p.copy(shotMode = mode)) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, ShotMode.entries.size),
                    icon = {},
                ) { Text(if (mode == ShotMode.OVERHEAD) "전주 위" else "4방향 사선", maxLines = 1) }
            }
        }
        if (p.shotMode == ShotMode.DIAGONAL4) {
            Stepper("사선 고도 (27m~)", "${p.diagonalAltitudeM.toInt()}m", Modifier.fillMaxWidth(), enabled) { onChange(p.copy(diagonalAltitudeM = p.diagonalAltitudeM + it)) }
            Stepper("전주 거리 (3~50m)", "${p.diagonalDistanceM.toInt()}m", Modifier.fillMaxWidth(), enabled) { onChange(p.copy(diagonalDistanceM = p.diagonalDistanceM + it)) }
        }
        Stepper("이동 고도 (30~120m)", "${p.transitAltitudeM.toInt()}m", Modifier.fillMaxWidth(), enabled) { onChange(p.copy(transitAltitudeM = p.transitAltitudeM + it * 5)) }
        Stepper("점검 고도 (이동 고도 이하)", "${p.inspectAltitudeM.toInt()}m", Modifier.fillMaxWidth(), enabled) { onChange(p.copy(inspectAltitudeM = p.inspectAltitudeM + it * 5)) }
        Stepper("속도 (2~15m/s)", "${p.speedMps.toInt()}m/s", Modifier.fillMaxWidth(), enabled) { onChange(p.copy(speedMps = p.speedMps + it)) }
        Stepper("예비율 (20%~)", "${p.batteryReservePct.toInt()}%", Modifier.fillMaxWidth(), enabled) { onChange(p.copy(batteryReservePct = p.batteryReservePct + it * 5)) }
    }
}

/** [onStep]에는 −1 또는 +1이 넘어간다. 버튼 설명(contentDescription)은 스모크 스크립트가 찾는 이름이다("이동 고도 높이기" 등, 괄호 범위는 뺀다). */
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

/** 소티별 예상 거리·시간·배터리(F-08)와 도달 불가 전주(5.4). */
@Composable
private fun PlanSummary(plan: RoutePlan, planMs: Long, params: FlightParams) {
    val visits = plan.sorties.sumOf { it.poles.size }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "전주 ${visits}개 · ${fmt(plan.sorties.sumOf { it.estDistanceM } / 1000)}km · ${fmt(plan.sorties.sumOf { it.estDurationSec } / 60)}분",
            style = MaterialTheme.typography.labelLarge,
        )
        // Pilot 2 경로 정보: 웨이포인트·사진 수(촬영 방식 5.6에 따라 전주당 1곳 또는 4곳)
        Text("웨이포인트 ${visits * perPole(params)}개 · 사진 ${visits * perPole(params)}장", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.sorties.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(SORTIE_COLORS[i % SORTIE_COLORS.size]))))
                Text(
                    "소티 ${i + 1} · ${s.poles.size}개 · ${fmt(s.estDurationSec / 60)}분 · 배터리 ${s.estBatteryPct.toInt()}%",
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (plan.unreachable.isNotEmpty()) {
            val vlos = plan.unreachable.count { it.reason == UnreachablePole.Reason.VLOS_EXCEEDED }
            Text(
                "도달 불가 ${plan.unreachable.size}개 (가시권 ${params.vlosRadiusM.toInt()}m 초과 ${vlos}, 배터리 초과 ${plan.unreachable.size - vlos})",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            )
        }
        Text("계산 ${planMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** 화면 수명에 맞춰 돌리는 MapLibre 지도 뷰(계획·비행 화면 공용). */
@Composable
internal fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
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
