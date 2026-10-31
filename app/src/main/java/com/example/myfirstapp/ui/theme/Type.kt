package com.example.myfirstapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.R

/** 영문·숫자는 Barlow(Pilot 2와 비슷한 DIN 계열, OFL, 앱에 넣음). 한글은 글꼴에 없어 시스템 글꼴로 이어진다. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

/** 수치(고도, 속도, 배터리, 개수, 시각) 전용 좁은 글꼴. 자릿수가 바뀌어도 폭이 덜 흔들린다. */
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
)

/** 화면에서 반복해 쓰는 글자 크기(10.2). 한 곳에서만 쓰는 크기(H 표지 6sp, 큰 수치 20·24sp 등)는 화면 코드에 둔다. */
object FontSize {
    val Micro = 10.sp
    val Caption = 11.sp
    val Body = 12.sp
    val Label = 13.sp
    val Title = 14.sp
    val Number = 15.sp
    /** 지도 전주 번호(큰 지도). */
    val MapLabel = 16.5.sp
    /** 지도 전주 번호(미니맵). */
    val MapLabelMini = 13.5.sp
}

/** 수치 글자 스타일(10.2). */
fun numberStyle(size: TextUnit = FontSize.Title,weight: FontWeight = FontWeight.SemiBold) =
    TextStyle(fontFamily = BarlowCondensed, fontWeight = weight, fontSize = size)

/**
 * 글자 크기(10.0): RC Pro 화면은 768×432dp뿐이라 Material 기본 크기로는 Pilot 2보다 크게 보이고 줄이 넘친다.
 * 기본 단계를 그대로 두되 크기를 약 0.8배로 줄인다(본문 13sp, 제목 18sp). 가장 작은 글자도 11sp 이상으로 둔다.
 */
private fun TextUnit.scaled() = (value * 0.8f).coerceAtLeast(11f).sp

private fun TextStyle.pilot(lh: Double) = copy(fontFamily = Barlow, fontSize = fontSize.scaled(), lineHeight = lh.em)

val Typography = Typography().run {
    copy(
        displayLarge = displayLarge.pilot(1.2),
        displayMedium = displayMedium.pilot(1.2),
        displaySmall = displaySmall.pilot(1.2),
        headlineLarge = headlineLarge.pilot(1.25),
        headlineMedium = headlineMedium.pilot(1.25),
        headlineSmall = headlineSmall.pilot(1.25),
        titleLarge = titleLarge.pilot(1.3),
        titleMedium = titleMedium.pilot(1.3),
        titleSmall = titleSmall.pilot(1.3),
        bodyLarge = bodyLarge.pilot(1.4),
        bodyMedium = bodyMedium.pilot(1.4),
        bodySmall = bodySmall.pilot(1.35),
        labelLarge = labelLarge.pilot(1.3),
        labelMedium = labelMedium.pilot(1.3),
        labelSmall = labelSmall.pilot(1.3),
    )
}
