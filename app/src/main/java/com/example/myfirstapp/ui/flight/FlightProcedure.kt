package com.example.myfirstapp.ui.flight

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.ui.procedure.ChecklistPage
import com.example.myfirstapp.ui.procedure.PreflightPage
import com.example.myfirstapp.ui.procedure.PreflightViewModel
import com.example.myfirstapp.ui.procedure.ProcedurePage
import com.example.myfirstapp.ui.theme.LightScreen

/**
 * 미션 절차 화면(10.5.5): [비행 시작]·상단 바 비행 상태 글자 → 비행 전 점검 → 웨이포인트 경로 체크리스트([업로드] → [시작]).
 * 점검은 실행기와 같은 기체·소티 판정을 쓴다. 뒤로 가기는 체크리스트면 점검으로, 점검이면 닫는다. 미션이 날기 시작하면 ViewModel이 닫는다.
 */
@Composable
internal fun ProcedurePages(s: FlightUiState, vm: FlightViewModel, onHms: () -> Unit) {
    val pre: PreflightViewModel = viewModel(key = "preflight", factory = PreflightViewModel.factory(report = vm::preflightReport, checksOf = vm::checksFor))
    BackHandler { if (s.page == ProcedurePage.CHECKLIST) vm.backToPreflight() else vm.closeProcedure() }
    // 절차 화면은 밝은 바탕(Pilot 2, 2026-11-04 사용자 결정). 카메라 뷰 대신 그린다([FlightContent], 11-06)
    if (s.page != null) LightScreen { ProcedureContent(s, vm, pre, onHms) }
}

@Composable
private fun ProcedureContent(s: FlightUiState, vm: FlightViewModel, pre: PreflightViewModel, onHms: () -> Unit) {
    when (s.page) {
        ProcedurePage.PREFLIGHT -> PreflightPage(pre, onClose = vm::closeProcedure, onNext = vm::confirmPreflight, onHms = onHms)
        ProcedurePage.CHECKLIST -> {
            val p by pre.state.collectAsStateWithLifecycle()
            // 체크리스트에도 상태 줄·차단 배너가 있어 점검 값을 계속 읽는다(PreflightPage가 없으므로 여기서 켬)
            androidx.lifecycle.compose.LifecycleResumeEffect(pre) {
                pre.setActive(true)
                onPauseOrDispose { pre.setActive(false) }
            }
            s.params?.let { params ->
                ChecklistPage(
                    pre = p, summary = vm.checklistSummary(), params = params, exec = s.exec, onChange = vm::updateChecklist,
                    onBack = vm::backToPreflight, onClose = vm::closeProcedure, onUpload = vm::upload, onStart = vm::startUploaded, onCancelUpload = vm::cancelUpload,
                )
            }
        }
        null -> Unit
    }
}
