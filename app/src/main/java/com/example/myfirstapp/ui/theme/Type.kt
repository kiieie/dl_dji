package com.example.myfirstapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 글자 크기(10.0): RC Pro 화면은 768×432dp뿐이라 Material 기본 크기로는 Pilot 2보다 크게 보이고 줄이 넘친다.
 * 기본 단계를 그대로 두되 크기를 약 0.8배로 줄인다(본문 13sp, 제목 18sp). 가장 작은 글자도 11sp 이상으로 둔다.
 */
private fun TextUnit.scaled() = (value * 0.8f).coerceAtLeast(11f).sp

val Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontSize = displayLarge.fontSize.scaled(), lineHeight = 1.2.em),
        displayMedium = displayMedium.copy(fontSize = displayMedium.fontSize.scaled(), lineHeight = 1.2.em),
        displaySmall = displaySmall.copy(fontSize = displaySmall.fontSize.scaled(), lineHeight = 1.2.em),
        headlineLarge = headlineLarge.copy(fontSize = headlineLarge.fontSize.scaled(), lineHeight = 1.25.em),
        headlineMedium = headlineMedium.copy(fontSize = headlineMedium.fontSize.scaled(), lineHeight = 1.25.em),
        headlineSmall = headlineSmall.copy(fontSize = headlineSmall.fontSize.scaled(), lineHeight = 1.25.em),
        titleLarge = titleLarge.copy(fontSize = titleLarge.fontSize.scaled(), lineHeight = 1.3.em),
        titleMedium = titleMedium.copy(fontSize = titleMedium.fontSize.scaled(), lineHeight = 1.3.em),
        titleSmall = titleSmall.copy(fontSize = titleSmall.fontSize.scaled(), lineHeight = 1.3.em),
        bodyLarge = bodyLarge.copy(fontSize = bodyLarge.fontSize.scaled(), lineHeight = 1.4.em),
        bodyMedium = bodyMedium.copy(fontSize = bodyMedium.fontSize.scaled(), lineHeight = 1.4.em),
        bodySmall = bodySmall.copy(fontSize = bodySmall.fontSize.scaled(), lineHeight = 1.35.em),
        labelLarge = labelLarge.copy(fontSize = labelLarge.fontSize.scaled(), lineHeight = 1.3.em),
        labelMedium = labelMedium.copy(fontSize = labelMedium.fontSize.scaled(), lineHeight = 1.3.em),
        labelSmall = labelSmall.copy(fontSize = labelSmall.fontSize.scaled(), lineHeight = 1.3.em),
    )
}
