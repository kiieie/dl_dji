package com.example.myfirstapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.R
import com.example.myfirstapp.ui.theme.Brand
import com.example.myfirstapp.ui.theme.FontSize

/**
 * 우리 앱 표지(10.5.1): 흰 바탕에 KEPCO 공식 심볼과 "KEPCO 점검", 고유색 테두리. 모든 화면 머리글 왼쪽 위(뒤로 옆)에 둔다.
 * 화면 기능·배치는 Pilot 2와 같고 이 표지만 다르다(2026-11-04 사용자 결정).
 */
@Composable
fun AppBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.height(22.dp).background(Color.White, RoundedCornerShape(4.dp)).border(1.5.dp, Brand, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(painterResource(R.drawable.kepco_symbol), contentDescription = null, modifier = Modifier.size(14.dp))
        Text("KEPCO 점검", fontSize = FontSize.Caption, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
    }
}
