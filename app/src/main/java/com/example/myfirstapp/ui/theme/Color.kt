package com.example.myfirstapp.ui.theme

import androidx.compose.ui.graphics.Color

// 10.2 Pilot 2 토큰(2026-10-30 시안 확정): 검정 바탕, 회색 4단계(패널·컨트롤·선택·구분선), 채운 버튼 파랑 하나.
// 흰 글자 대비 4.5:1 이상을 지킨다(버튼 #0A6CFF, 이상 버튼 #D70015). 다크 테마 하나만 쓴다.
val Primary = Color(0xFF0A6CFF)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFF0D2440)
val OnPrimaryContainer = Color(0xFFFFFFFF)
val Secondary = Color(0xFFC8C8C8)
val OnSecondary = Color(0xFF000000)
val SecondaryContainer = Color(0xFF1C1C1C)
val OnSecondaryContainer = Color(0xFFFFFFFF)
val Tertiary = Color(0xFF19D46E)
val OnTertiary = Color(0xFF000000)
val TertiaryContainer = Color(0xFF0B3A22)
val OnTertiaryContainer = Color(0xFF3FE08A)
val Error = Color(0xFFFF3B30)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFD70015)
val OnErrorContainer = Color(0xFFFFFFFF)
val Background = Color(0xFF000000)
val OnBackground = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFF1C1C1C)
val OnSurfaceVariant = Color(0xFFA6A6A6)
val Outline = Color(0xFF444444)
val OutlineVariant = Color(0xFF222222)
val SurfaceContainerLowest = Color(0xFF000000)
val SurfaceContainerLow = Color(0xFF121212)
val SurfaceContainer = Color(0xFF1C1C1C)
val SurfaceContainerHigh = Color(0xFF2A2A2A)
val SurfaceContainerHighest = Color(0xFF333333)
val InverseSurface = Color(0xFFFFFFFF)
val InverseOnSurface = Color(0xFF000000)
val InversePrimary = Color(0xFF0A6CFF)

/** 선·아이콘·선택 막대용 파랑(채운 버튼 바탕은 [Primary]). */
val Accent = Color(0xFF1F8CFF)
/** 보조 글자(값 옆 단위, 덜 중요한 값). */
val TextSoft = Color(0xFFC8C8C8)
/** 경고(장애물 감속 전 단계, 부분 완료, 저장 전). */
val Warning = Color(0xFFFF9F0A)
/** 일시정지 버튼, 이륙 지점 H, RTH 테두리. */
val Caution = Color(0xFFFFC400)
