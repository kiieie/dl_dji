package com.example.myfirstapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val InspectionColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = InversePrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Background,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = Primary,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    outline = Outline,
    outlineVariant = OutlineVariant,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
)

/**
 * 밝은 절차 화면 색(10.1, 2026-11-04 사용자 결정: Pilot 2처럼 홈·라이브러리·비행 전 점검·체크리스트·설정·계획 패널·앨범은 밝게).
 * 바탕 [LightBackground] 회색, 카드 [LightSurface](surfaceContainerLow·Lowest) 흰색, 조작 칸 [LightControl](surfaceContainer) 회색.
 */
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = Color(0xFFDCE9FF),
    onPrimaryContainer = Color(0xFF00255C),
    inversePrimary = InversePrimary,
    secondary = Color(0xFF5C6066),
    onSecondary = OnPrimary,
    // 고른 칩·칸 나눔 버튼의 바탕(MD3 FilterChip·SegmentedButton 선택 = secondaryContainer): 조작 칸 회색이면 고른 것이 꺼진 것처럼 보였다(11-06 전압 칩)
    secondaryContainer = Color(0xFFDCE9FF),
    onSecondaryContainer = Color(0xFF00255C),
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = Color(0xFFD7F6E5),
    onTertiaryContainer = Color(0xFF0B5A31),
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightControl,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = Primary,
    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = OnPrimary,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightControl,
    surfaceContainerHigh = LightControlHigh,
    surfaceContainerHighest = LightControlHighest,
)

/** 앱 테마(10.1): 동적 색(Material You)은 쓰지 않는다. 현장에서 기기 배경화면에 따라 색이 바뀌면 안 된다. 기본은 어두운 색(카메라 뷰·지도). */
@Composable
fun MyFirstAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InspectionColorScheme, typography = Typography, content = content)
}

/**
 * 밝은 절차 화면(Pilot 2 홈·라이브러리·비행 전 점검·체크리스트, 2026-11-04 사용자 결정). 안쪽은 밝은 색 테마이고
 * 바탕([color], 기본 회색 바탕)과 글자색(검정)을 깐다. 화면 전체나 계획 패널처럼 한 부분을 감싼다.
 */
@Composable
fun LightScreen(modifier: Modifier = Modifier, color: Color? = null, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColorScheme, typography = Typography) {
        Surface(modifier, color = color ?: MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface, content = content)
    }
}
