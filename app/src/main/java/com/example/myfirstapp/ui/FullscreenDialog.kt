package com.example.myfirstapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 전체 화면(10.0)을 유지하는 대화상자. 대화상자는 창이 따로라 앱의 바 숨김을 이어받지 못하고,
 * 열리는 순간 상태바·내비바가 나타나 뒤 화면이 밀린다. 대화상자 창에서도 바를 숨긴다.
 */
@Composable
fun FullscreenAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    // RC 5D ←는 SDK로 와서 대화상자 창이 아니라 액티비티 뒤로 가기로 들어온다. 뒤 화면 대신 대화상자를 닫게 한다
    BackHandler(onBack = onDismissRequest)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            HideSystemBarsInDialog()
            confirmButton()
        },
        dismissButton = dismissButton,
        title = title,
        text = text,
    )
}

@Composable
private fun HideSystemBarsInDialog() {
    val view = LocalView.current
    // 다른 창(파일 선택 등)에서 돌아오면 바가 다시 나오므로 대화상자 창이 포커스를 받을 때마다 숨긴다
    DisposableEffect(view) {
        val window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window
        fun hide() = window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        hide()
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { if (it) hide() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
}

/**
 * ⋯ 메뉴(DropdownMenu) 팝업: 포커스를 가져가는 팝업 창이 뜨면 숨긴 시스템 바가 나타나 화면이 밀린다(2026-10-31 RC 캡처).
 * 포커스를 가져가지 않게 해 바를 숨긴 채로 둔다. 바깥을 누르면 닫히고, 열린 동안 5D는 각 화면이 막는다.
 */
val MENU_PROPERTIES = androidx.compose.ui.window.PopupProperties(focusable = false)
