package com.example.myfirstapp.ui.procedure

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.core.model.HmsIssue
import com.example.myfirstapp.core.model.HmsLevel
import com.example.myfirstapp.core.model.hmsLevel
import com.example.myfirstapp.ui.scrollbar
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.Error
import com.example.myfirstapp.ui.theme.FontSize
import com.example.myfirstapp.ui.theme.Tertiary
import com.example.myfirstapp.ui.theme.Warning
import com.example.myfirstapp.ui.theme.numberStyle

/** HMS 등급 글(Pilot 2 "표준", 경고·심각은 그 등급 항목 수). 홈 칸·비행 전 점검 칸·목록이 같이 쓴다. */
internal fun hmsWord(level: HmsLevel, count: Int) = when (level) {
    HmsLevel.NORMAL -> "표준"
    HmsLevel.CAUTION -> "경고 $count"
    HmsLevel.SERIOUS -> "심각 $count"
}

/** HMS 등급 색: 표준 녹 · 경고 주황 · 심각 빨강. */
internal fun hmsColor(level: HmsLevel) = when (level) {
    HmsLevel.NORMAL -> Tertiary
    HmsLevel.CAUTION -> Warning
    HmsLevel.SERIOUS -> Error
}

/**
 * HMS 목록(10.5.5, 밝은 바탕): 홈 HMS 칸과 비행 전 점검 "표준 HMS ›"에서 연다. 왼쪽 전체 등급 칸 · 펌웨어(로컬 버전만) · 기본 정비 기록 ·
 * [기체 점검 ›](⋯ 상태 점검), 오른쪽 모듈 줄(정상 / 경고 n / 심각 n)과 그 아래 항목(제목 · 설명 · 코드).
 * Pilot 2 HMS 상세는 녹화가 없어(10.5.9) 매뉴얼식 모듈 목록으로 그렸다.
 */
@Composable
fun HmsScreen(onClose: () -> Unit, onAircraftCheck: () -> Unit) {
    val vm: HmsViewModel = viewModel(factory = HmsViewModel.factory())
    val s by vm.state.collectAsStateWithLifecycle()
    val level = hmsLevel(s.issues)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ProcedureHeader("HMS", onClose)
        Row(Modifier.weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(236.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(4.dp), color = hmsColor(level), contentColor = Color.White) {
                    Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                        Text(hmsWord(level, s.issues.count { it.level == level }), fontSize = FontSize.Number, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Text(if (s.connected) "기체·조종기 HMS" else "기체 연결 안 됨 · 조종기 HMS만", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoLine("기체 펌웨어", s.device.aircraftFw)
                InfoLine("조종기 펌웨어", s.device.rcFw)
                Text("업데이트 확인은 인터넷이 필요해 하지 않습니다(데이터 주권). 업데이트는 출동 전 Pilot 2에서.", fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoLine("기본 정비 기록", s.device.maintenance)
                OutlinedButton(onClick = onAircraftCheck, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonStd), shape = RoundedCornerShape(4.dp)) { Text("기체 점검 ›") }
            }
            val list = rememberLazyListState()
            LazyColumn(Modifier.weight(1f).fillMaxHeight().scrollbar(list), state = list, contentPadding = PaddingValues(end = 10.dp)) {
                s.modules.forEach { (module, issues) ->
                    item(key = module) { ModuleRow(module, issues) }
                    items(issues) { IssueRow(it) }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = FontSize.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "--", style = numberStyle(FontSize.Body))
    }
}

/** 모듈 줄 40dp: 이름, 오른쪽 상태(정상 녹 · 알림 · 경고 n · 심각 n). */
@Composable
private fun ModuleRow(name: String, issues: List<HmsIssue>) {
    val level = hmsLevel(issues)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().height(Dimens.RowTable), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f), fontSize = FontSize.Label, fontWeight = FontWeight.Bold)
        Box(Modifier.size(7.dp).clip(CircleShape).background(hmsColor(level)))
        Text(
            when { issues.isEmpty() -> "정상"; level == HmsLevel.NORMAL -> "알림 ${issues.size}"; else -> hmsWord(level, issues.count { it.level == level }) },
            fontSize = FontSize.Body, color = if (level == HmsLevel.NORMAL) MaterialTheme.colorScheme.onSurfaceVariant else hmsColor(level),
        )
    }
}

/** 항목: 등급 점, 제목(굵게), 설명, 코드(작게). */
@Composable
private fun IssueRow(i: HmsIssue) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(if (i.level == HmsLevel.NORMAL) MaterialTheme.colorScheme.outline else hmsColor(i.level)))
        Column(Modifier.weight(1f)) {
            Text(i.title.ifEmpty { "제목 없음" }, fontSize = FontSize.Body, fontWeight = FontWeight.SemiBold)
            if (i.description.isNotEmpty()) Text(i.description, fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(i.code, fontSize = FontSize.Micro, color = MaterialTheme.colorScheme.outline)
        }
    }
}
