package com.example.myfirstapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.R

/**
 * 우리 앱 표지(10.5.1): KEPCO 공식 심볼(CI)만 둔다(2026-11-06 사용자 결정, "KEPCO 점검" 글자·테두리 뺌). 모든 화면 머리글 왼쪽 위(뒤로 옆)에 둔다.
 * 화면 기능·배치는 Pilot 2와 같고 이 표지만 다르다(2026-11-04 사용자 결정).
 */
@Composable
fun AppBadge(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.kepco_symbol), contentDescription = "KEPCO", modifier = modifier.height(22.dp).padding(horizontal = 2.dp))
}
