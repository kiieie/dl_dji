package com.example.myfirstapp.ui.procedure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.R
import com.example.myfirstapp.core.executor.PreflightEvaluator
import com.example.myfirstapp.drone.AircraftSetting
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Inactive
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.InspectionApp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Pilot 2 비행 전 점검(10.5.5, 녹화 0242·p2s_pre) 한 화면: HMS 칸과 상태 줄 · 기체 암 참고 · 우리 점검 항목(녹/빨 점) ·
 * RTH·최고 고도 스테퍼, 신호 끊김, 최대 비행 거리, 스틱 모드, 홈포인트 [기체][조종기], 배터리 경고 두 손잡이, 장애물 회피, 방향별 감지 거리.
 * 설정은 기체 키로 읽고 쓴다([PreflightViewModel]). 넘어갈 수 없는 항목(연결·LDM)이 있으면 [다음]이 꺼진다.
 * [onNext]에는 경고(빨 점)가 있었는지 넘긴다(운용자가 보고 넘어감 → 실행기 [StartIgnoringWarnings]와 같은 뜻).
 */
@Composable
fun PreflightPage(vm: PreflightViewModel, onClose: () -> Unit, onNext: (warned: Boolean) -> Unit, nextLabel: String = "다음") {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.reloadValues() }
    LifecycleResumeEffect(vm) {
        vm.setActive(true)
        onPauseOrDispose { vm.setActive(false) }
    }
    val set = vm.settings
    fun v(a: AircraftSetting) = s.values[a.label]
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ProcedureHeader("비행 전 점검", onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBlock(s)
            CautionBanner("참고: 기체 암이 완전히 펼쳐졌는지 확인하세요. 기체 암이 펼쳐져 있는 경우 이 메시지를 무시하세요.")
            s.checks.filter { !it.ok && it.blocking }.forEach { BlockingBanner(it.problem) }
            OurChecks(s)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    NumberLine(set.rth, v(set.rth), vm)
                    NumberLine(set.maxAltitude, v(set.maxAltitude), vm)
                    SettingLine("홈포인트") {
                        HomeButton("기체 위치로", s.report?.connected == true) { vm.change(set.homeAircraft, Unit) }
                        HomeButton("조종기 위치로", s.report?.connected == true) { vm.change(set.homeRc, Unit) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    ChoiceLine(set.lostAction, v(set.lostAction), vm)
                    SettingLine(set.distanceLimitOn.label, AppConfig.Preflight.DISTANCE_RANGE_M.let { "(${it.first}~${it.last} m)" }) {
                        val on = v(set.distanceLimitOn) as? Boolean
                        Switch(checked = on == true, enabled = on != null, onCheckedChange = { vm.change(set.distanceLimitOn, it) })
                        val k = set.distanceLimit.kind as AircraftSetting.Kind.Number
                        StepperCells(v(set.distanceLimit) as? Int, k.step, null, k.range, enabled = on == true) { vm.change(set.distanceLimit, it) }
                    }
                    ChoiceLine(set.stickMode, v(set.stickMode), vm)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RangeLine("배터리 경고 맞춤 설정", set.battery, v(set.battery), vm, toggle = null) { (a, b) -> "매우 낮음: ${a.toInt()}%   낮음: ${b.toInt()}%" }
            SettingLine("장애물 회피") {
                val k = set.avoidance.kind as AircraftSetting.Kind.Choice
                Segmented(k.options, v(set.avoidance) as? Int) { vm.change(set.avoidance, it) }
            }
            set.sensing.forEach { (toggle, distance) ->
                RangeLine(toggle.label, distance, v(distance), vm, toggle = toggle to (v(toggle) as? Boolean)) { (a, b) -> "제동: ${m(a)}m   경고: ${m(b)}m" }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                s.message ?: if (s.simulated) "기체 연결 안 됨 · 모의 값" else if (s.warnings > 0) "▲ ${s.warnings}개 항목을 확인하세요" else "모든 항목 정상",
                Modifier.weight(1f), fontSize = FontSize.Body, color = if (s.warnings > 0 && s.message == null) Warning else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            OutlinedButton(onClick = onClose, modifier = Modifier.width(120.dp).height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) { Text("뒤로") }
            Button(
                onClick = { onNext(s.warnings > 0) }, enabled = s.report != null && !s.blocked,
                modifier = Modifier.width(200.dp).height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp),
            ) { Text(nextLabel, fontSize = FontSize.Title, fontWeight = FontWeight.Bold) }
        }
    }
}

private fun m(v: Double) = String.format(Locale.ROOT, "%.1f", v)

/**
 * 맨 위 줄(Pilot 2): 왼쪽 HMS 칸(확인할 항목이 없으면 녹 "표준", 있으면 주황 "경고 n"), 오른쪽 두 줄 상태:
 * 비행 모드 · 배터리 % V · 조종기 배터리 · 조종기 A / 홈포인트(미설정 주황) · SD 남은 용량.
 */
@Composable
private fun StatusBlock(s: PreflightUiState) {
    val r = s.report
    val app = LocalContext.current.applicationContext as InspectionApp
    val rc by app.rcBattery.collectAsStateWithLifecycle()
    val gpu by app.gpuPrep.status.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val ok = s.warnings == 0
        Row(
            Modifier.width(180.dp).height(52.dp).background(if (ok) Tertiary else Warning, RoundedCornerShape(4.dp)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (ok) "표준" else "경고 ${s.warnings}", Modifier.weight(1f), fontSize = FontSize.Number, fontWeight = FontWeight.Bold, color = Color.Black)
            Text("HMS ›", fontSize = FontSize.Label, color = Color.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StatusCell("기체", s.extras.flightMode?.let { "$it 모드" } ?: "--")
                StatusCell("배터리", listOfNotNull(r?.let { "${it.batteryPct.toInt()}%" }, s.extras.voltageV?.let { "${m(it)}V" }).joinToString(" ").ifEmpty { "--" })
                StatusCell("조종기", rc?.let { "${it.pct}%" } ?: "--")
                StatusCell("", "조종기 A")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val home = r?.homeRecorded == true
                StatusCell("홈포인트", if (r == null) "--" else if (home) "기록됨" else "미설정", color = if (home) Color.White else Warning)
                StatusCell("SD", r?.let { "${m(it.sdFreeMb / 1024.0)}G" } ?: "--")
                StatusCell("GNSS", r?.let { "${it.gpsSatellites}" } ?: "--", color = if (r != null && r.gpsSatellites == 0) Error else Color.White)
                // 9.7 두 단계 열기: 지속 성능 GPU 캐시. 만드는 중이면 이륙 전에 끝나기를 기다리는 편이 낫다(멈출 수 없음)
                gpu.text?.let { StatusCell("모델 GPU", it.removePrefix("모델 GPU "), color = if (gpu.ready) Color.White else Warning) }
            }
        }
    }
}

/** 우리 점검(6.7): 배터리(지금·예상 사용·예비)는 한 줄 전체, 나머지는 두 줄로. */
@Composable
private fun OurChecks(s: PreflightUiState) {
    if (s.checks.isEmpty()) return
    val battery = s.checks.firstOrNull { it.label == PreflightEvaluator.BATTERY }
    battery?.let { CheckLine(it, Modifier.fillMaxWidth()) }
    val rest = s.checks - listOfNotNull(battery).toSet()
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        rest.chunked((rest.size + 1) / 2).forEach { col ->
            Column(Modifier.weight(1f)) { col.forEach { CheckLine(it, Modifier.fillMaxWidth()) } }
        }
    }
}

@Composable
private fun NumberLine(a: AircraftSetting, value: Any?, vm: PreflightViewModel) {
    val k = a.kind as AircraftSetting.Kind.Number
    SettingLine(a.label, "(${k.range.first}~${k.range.last} ${k.unit})") {
        StepperCells(value as? Int, k.step, k.big, k.range) { vm.change(a, it) }
    }
}

@Composable
private fun ChoiceLine(a: AircraftSetting, value: Any?, vm: PreflightViewModel) {
    SettingLine(a.label) { ChoiceField((a.kind as AircraftSetting.Kind.Choice).options, value as? Int) { vm.change(a, it) } }
}

/** 홈포인트 버튼(Pilot 2 [기체 H][조종기 A]). */
@Composable
private fun HomeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(Dimens.TouchMedium).border(1.dp, if (enabled) Color.White else Inactive, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = FontSize.Body, color = if (enabled) Color.White else Inactive) }
}

/**
 * 두 손잡이 줄(배터리 경고, 방향별 감지): 이름, (감지면 켜기 아이콘), 값 글, 슬라이더. 손을 떼면 기체에 쓴다.
 * 낮은 쪽이 빨강(매우 낮음·제동), 높은 쪽이 주황(낮음·경고)인 Pilot 2 막대와 같다.
 */
@Composable
private fun RangeLine(
    label: String, a: AircraftSetting, value: Any?, vm: PreflightViewModel,
    toggle: Pair<AircraftSetting, Boolean?>?, text: (Pair<Double, Double>) -> String,
) {
    val k = a.kind as AircraftSetting.Kind.Range
    val now = (value as? Pair<*, *>)?.let { (it.first as? Double ?: return@let null) to (it.second as? Double ?: return@let null) }
    var drag by remember(now) { mutableStateOf(now?.let { it.first.toFloat()..it.second.toFloat() }) }
    SettingLine(label) {
        toggle?.let { (t, on) ->
            Box(
                Modifier.width(56.dp).height(Dimens.ButtonChip).border(1.dp, if (on == true) Accent else Inactive, RoundedCornerShape(4.dp))
                    .clickable(enabled = on != null, onClickLabel = "$label ${if (on == true) "끄기" else "켜기"}") { vm.change(t, on != true) },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_shield), contentDescription = null, modifier = Modifier.size(16.dp), tint = if (on == true) Accent else Inactive) }
            Box(Modifier.width(40.dp))
        }
        Text(drag?.let { text(it.start.toDouble() to it.endInclusive.toDouble()) } ?: "--", Modifier.width(210.dp), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        val range = drag
        if (range == null) Box(Modifier.width(220.dp)) else RangeSlider(
            value = range, onValueChange = { drag = it }, valueRange = k.range.start.toFloat()..k.range.endInclusive.toFloat(),
            onValueChangeFinished = { drag?.let { vm.change(a, it.start.toDouble() to it.endInclusive.toDouble()) } },
            enabled = toggle?.second != false, modifier = Modifier.width(220.dp),
            colors = SliderDefaults.colors(activeTrackColor = Warning, thumbColor = Color.White, inactiveTrackColor = Error),
        )
    }
}

/** 홈 [카메라 뷰 입력]·수동 비행 상단 바의 비행 전 점검(10.5.5): 미션 없는 판정([PreflightEvaluator.manual]). [onNext]가 null이면 [다음] 대신 닫기만. */
@Composable
fun PreflightScreen(onClose: () -> Unit, onNext: (() -> Unit)?) {
    val vm: PreflightViewModel = viewModel(factory = PreflightViewModel.factory())
    PreflightPage(vm, onClose, onNext = { onNext?.invoke() ?: onClose() }, nextLabel = if (onNext != null) "카메라 뷰 입력" else "확인")
}
