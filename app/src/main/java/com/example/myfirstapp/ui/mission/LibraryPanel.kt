package com.example.myfirstapp.ui.mission

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.R
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.data.MissionSummary
import com.example.myfirstapp.InspectionApp
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.MENU_PROPERTIES
import com.example.myfirstapp.ui.home.RoutePreview
import com.example.myfirstapp.ui.procedure.durationLabel
import com.example.myfirstapp.ui.theme.Accent
import com.example.myfirstapp.ui.theme.CardBg
import com.example.myfirstapp.ui.theme.Caution
import com.example.myfirstapp.ui.theme.Dimens
import com.example.myfirstapp.ui.theme.FontSize
import java.io.File

/** 비행 화면 라이브러리 패널의 ViewModel: 라이브러리 화면과 같은 [MissionListViewModel](처음 [projectId]로 거름). 패널을 열 때 만든다. */
@Composable
internal fun libraryPanelViewModel(dao: ProjectDao, projectId: Long?): MissionListViewModel {
    val files = LocalContext.current.getExternalFilesDir(null)
    return viewModel(
        key = "library-panel",
        factory = MissionListViewModel.factory(
            dao, projectId, missionDir = { pid, mid -> files?.let { File(it, "projects/$pid/missions/$mid") } }, copyToFolder = { _, _ -> 0 },
            mapThumbs = (LocalContext.current.applicationContext as InspectionApp).mapThumbs,
        ),
    )
}

/**
 * 비행 화면 안 라이브러리 패널(10.5.8, Pilot 2 지도 뷰 경로 라이브러리): 큰 지도 오른쪽 [Dimens.SidePanel]. 필터(프로젝트·상태(즐겨찾기 포함)·정렬),
 * 카드(경로 그림 · ☆/★ 즐겨찾기 · 이름 · 거리·예상 시간·기체 · 상태·전주), 카드를 누르면 지도에 그 경로([MissionListUiState.previewRoute]),
 * 아래 [열기](카드 주 동작: 비행·이어하기·결과, 없으면 계획), ⋯ 편집(다시 날기: 계획 화면, 저장하면 새 미션)·삭제.
 * [canLeave]가 거짓(떠 있음)이면 미리보기와 즐겨찾기만 하고 열기·편집·삭제는 막는다(비행 화면을 떠나면 실행기가 멈춤). 지금 미션([current])은 열기·삭제하지 않는다.
 */
@Composable
internal fun LibraryPanel(s: MissionListUiState, vm: MissionListViewModel, current: Long?, canLeave: Boolean, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier.width(Dimens.SidePanel).background(CardBg)) {
        Box(Modifier.fillMaxWidth().height(Dimens.HeaderList)) {
            Text("라이브러리", Modifier.align(Alignment.Center), fontSize = FontSize.Title, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd).size(Dimens.TouchMedium).semantics { contentDescription = "라이브러리 닫기" }) {
                Text("✕", fontSize = FontSize.Title)
            }
        }
        LibraryFilters(s, s.projects, vm)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(s.shown, key = { it.id }) { m -> PanelCard(m, s, vm, selected = m.id == s.selectedId, canEdit = canLeave && m.id != current) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val sel = s.missions.firstOrNull { it.id == s.selectedId }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    sel == null -> "경로를 누르면 지도에 보입니다"
                    sel.id == current -> "지금 미션입니다"
                    !canLeave -> "비행 중에는 미리보기만 됩니다"
                    else -> "${sel.projectName} #${sel.id}"
                },
                Modifier.weight(1f), fontSize = FontSize.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = { sel?.let(vm::open) }, enabled = sel != null && canLeave && sel.id != current,
                modifier = Modifier.height(Dimens.ButtonStd), shape = RoundedCornerShape(4.dp),
            ) { Text("열기", fontSize = FontSize.Body, fontWeight = FontWeight.Bold) }
        }
    }
    LibraryDialogs(s, vm)
}

/** 패널 카드 72dp: 경로 그림, 이름·★, 거리·예상 시간·기체(Pilot 2 카드), 상태·전주. ☆ 즐겨찾기는 언제나, ⋯ 편집·삭제는 [canEdit]일 때. */
@Composable
private fun PanelCard(m: MissionSummary, s: MissionListUiState, vm: MissionListViewModel, selected: Boolean, canEdit: Boolean) {
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val sum = s.summaries[m.id]
    Surface(
        onClick = { vm.select(m) }, shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.5.dp, Accent) else null, modifier = Modifier.fillMaxWidth().height(72.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoutePreview(s.routes[m.id].orEmpty(), GeoPoint(m.homeLat, m.homeLon), Modifier.size(72.dp), labels = false) { w, h, pad -> vm.mapThumb(m, w, h, pad) }
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_route), contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(12.dp))
                    Text("${m.projectName} #${m.id}", Modifier.weight(1f, fill = false), fontSize = FontSize.Body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOf(sum?.let { distanceLabel(it.distanceM) } ?: "--", sum?.let { durationLabel(it.durationSec) } ?: "--", "M3E").joinToString(" · "),
                    fontSize = FontSize.Caption, color = soft, maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val c = statusColor(m.status)
                    Text(STATUS_TITLE[m.status] ?: m.status, fontSize = FontSize.Micro, color = c, modifier = Modifier.background(c.copy(alpha = 0.15f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
                    Text("전주 ${m.finishedCount}/${m.poleCount}", fontSize = FontSize.Caption, color = soft, maxLines = 1)
                }
            }
            Text(
                if (m.favorite) "★" else "☆",
                Modifier.size(Dimens.TouchSmall).clickable(onClickLabel = if (m.favorite) "즐겨찾기 해제" else "즐겨찾기") { vm.toggleFavorite(m) }.padding(top = 4.dp),
                color = if (m.favorite) Caution else soft, fontSize = FontSize.Title,
            )
            PanelMenu(m, s.menuFor == m.id, canEdit, vm)
        }
    }
}

/** ⋯: 편집(다시 날기) · 삭제. 떠 있거나 지금 미션이면 막는다. */
@Composable
private fun PanelMenu(m: MissionSummary, open: Boolean, enabled: Boolean, vm: MissionListViewModel) {
    Box {
        IconButton(onClick = { vm.setMenu(m) }, enabled = enabled, modifier = Modifier.size(Dimens.TouchSmall).semantics { contentDescription = "편집·삭제" }) {
            Text("⋯", fontSize = FontSize.Number)
        }
        DropdownMenu(open, onDismissRequest = { vm.setMenu(null) }, properties = MENU_PROPERTIES) {
            DropdownMenuItem(text = { Text("편집(새 계획)") }, onClick = { vm.copy(m) })
            DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, onClick = { vm.askDelete(m) })
        }
    }
}

private fun distanceLabel(m: Double) = if (m < 1000) "%.0f m".format(m) else "%.2f km".format(m / 1000)
