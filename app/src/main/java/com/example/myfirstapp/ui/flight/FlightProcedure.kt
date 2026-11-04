package com.example.myfirstapp.ui.flight

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.ui.procedure.PreflightPage
import com.example.myfirstapp.ui.procedure.PreflightViewModel
import com.example.myfirstapp.ui.procedure.ProcedurePage

/**
 * 미션 절차 화면(10.5.5): [비행 시작]·상단 바 비행 상태 글자 → 비행 전 점검. 점검은 실행기와 같은 기체·소티 판정을 쓴다.
 * 뒤로 가기는 절차 화면만 닫는다.
 */
@Composable
internal fun ProcedurePages(s: FlightUiState, vm: FlightViewModel) {
    val pre: PreflightViewModel = viewModel(key = "preflight", factory = PreflightViewModel.factory(report = vm::preflightReport, checksOf = vm::checksFor))
    BackHandler(onBack = vm::closeProcedure)
    when (s.page) {
        ProcedurePage.PREFLIGHT -> PreflightPage(pre, onClose = vm::closeProcedure, onNext = vm::confirmPreflight)
        ProcedurePage.CHECKLIST, null -> Unit
    }
}
