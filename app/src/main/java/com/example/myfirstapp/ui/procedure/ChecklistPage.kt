package com.example.myfirstapp.ui.procedure

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.executor.ExecState
import com.example.myfirstapp.core.executor.ExecutorUiState
import com.example.myfirstapp.core.model.FinishAction
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.RcLostAction
import com.example.myfirstapp.core.route.RouteSummary
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.numberStyle
import java.util.Locale

/** 체크리스트 버튼 상태. */
private val BUSY = setOf(ExecState.PreflightCheck, ExecState.PreparingLeg, ExecState.Takeoff)

/**
 * Pilot 2 웨이포인트 경로 체크리스트(10.5.5, 녹화 p2s_5·p2s_6): 빨간 차단 배너 · 상태 줄(배터리 % V, 조종기, SD) ·
 * 요약(거리 · 예상 시간 · 웨이포인트 · 사진) · 안전한 이륙 고도, 시작 위치로 상승, 비행 임무 속도, 완료 동작, 신호 끊김 조치, 폴더 만들기.
 * 두 단계: [비행 임무 업로드](점검 → 업로드, 실행기 [ExecState.Uploaded]) → [시작]. 값은 올리기 전에만 바꾼다.
 */
@Composable
fun ChecklistPage(
    pre: PreflightUiState,
    summary: RouteSummary?,
    params: FlightParams,
    exec: ExecutorUiState,
    onChange: (FlightParams) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onUpload: () -> Unit,
    onStart: () -> Unit,
    onCancelUpload: () -> Unit,
) {
    val state = exec.state
    val editable = state == ExecState.Idle || state == ExecState.SortieDone
    val rc by (LocalContext.current.applicationContext as InspectionApp).rcBattery.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ProcedureHeader("웨이포인트 경로 체크리스트", onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pre.checks.filter { !it.ok && it.blocking }.forEach { BlockingBanner(it.problem) }
            params.validate().forEach { BlockingBanner(it) }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val r = pre.report
                StatusCell("배터리", listOfNotNull(r?.let { "${it.batteryPct.toInt()}%" }, pre.extras.voltageV?.let { String.format(Locale.ROOT, "%.1fV", it) }).joinToString(" ").ifEmpty { "--" })
                StatusCell("조종기", rc?.let { "${it.pct}%" } ?: "--")
                StatusCell("SD", r?.let { String.format(Locale.ROOT, "%.1f G", it.sdFreeMb / 1024.0) } ?: "--")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                listOf(
                    (summary?.let { "${it.distanceM.toInt()} m" } ?: "--") to "거리",
                    (summary?.let { duration(it.durationSec) } ?: "--") to "예상 시간",
                    (summary?.waypoints?.toString() ?: "--") to "웨이포인트",
                    (summary?.photos?.toString() ?: "--") to "사진",
                ).forEach { (value, label) ->
                    Column(Modifier.weight(1f).padding(start = 24.dp)) {
                        Text(value, style = numberStyle(20.sp))
                        Text(label, fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    val auto = maxOf(Tuning.Safety.TAKEOFF_SECURITY_M, params.shotAltitudeM)
                    val r = Tuning.Safety.TAKEOFF_SECURITY_RANGE_M.let { it.start.toInt()..it.endInclusive.toInt() }
                    SettingLine("안전한 이륙 고도", "(${r.first}~${r.last} m)") {
                        StepperCells((params.takeoffSecurityM ?: auto).toInt(), 10, 100, r, editable) { onChange(params.copy(takeoffSecurityM = it.toDouble())) }
                    }
                    SettingLine("시작 위치로 상승") {
                        Switch(checked = params.climbToStart, enabled = editable, onCheckedChange = { onChange(params.copy(climbToStart = it)) })
                    }
                    SettingLine("비행 경로 완료 동작") {
                        ChoiceField(FinishAction.entries.map { it.label }, params.finishAction.ordinal, enabled = editable) { onChange(params.copy(finishAction = FinishAction.entries[it])) }
                    }
                    SettingLine("폴더 만들기") { FolderField(params.photoFolder.orEmpty(), editable) { onChange(params.copy(photoFolder = it)) } }
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.height(46.dp))
                    val sr = AppConfig.Preflight.SPEED_RANGE_MPS.let { it.start.toInt()..it.endInclusive.toInt() }
                    SettingLine("비행 임무 속도", "(${sr.first}~${sr.last} m/s)") {
                        StepperCells(params.speedMps.toInt(), 1, null, sr, editable) { onChange(params.copy(speedMps = it.toDouble())) }
                    }
                    SettingLine("신호 끊김 조치") {
                        ChoiceField(RcLostAction.entries.map { it.label }, params.rcLostAction.ordinal, enabled = editable) { onChange(params.copy(rcLostAction = RcLostAction.entries[it])) }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                exec.message.takeIf { state == ExecState.Idle || state == ExecState.Uploaded }.orEmpty(),
                Modifier.weight(1f), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
            if (state == ExecState.Uploaded) {
                OutlinedButton(onClick = onCancelUpload, modifier = Modifier.width(140.dp).height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) { Text("업로드 취소") }
                Button(onClick = onStart, modifier = Modifier.width(280.dp).height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) {
                    Text("시작", fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
                }
            } else {
                val busy = state in BUSY
                OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.width(140.dp).height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp)) { Text("뒤로") }
                Button(
                    onClick = onUpload, enabled = editable && !pre.blocked && params.validate().isEmpty() && summary != null,
                    modifier = Modifier.width(280.dp).height(Dimens.ButtonPrimary), shape = RoundedCornerShape(4.dp),
                ) { Text(if (busy) "업로드 중…" else "비행 임무 업로드", fontSize = FontSize.Title, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun duration(sec: Double) = sec.toInt().let { "${it / 60} 분 ${it % 60} 초" }

/** 폴더 이름 칸(Pilot 2 회색 입력 칸). */
@Composable
private fun FolderField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    BasicTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        textStyle = TextStyle(color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = FontSize.Label),
        cursorBrush = SolidColor(Color.White), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.width(240.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(Dimens.TouchMedium).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}
