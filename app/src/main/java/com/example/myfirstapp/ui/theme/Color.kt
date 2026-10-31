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

/** 영상 위 카드·버튼 바탕(10.4 시안 #121212 90%). */
val CardBg = Color(0xE6121212)
/** 꺼진 아이콘·신호 막대·셔터 테두리. */
val Inactive = Color(0xFF555555)
/** 상태를 모를 때의 점, 고르지 않은 칸 글자. */
val Muted = Color(0xFF8A8A8A)
/** 탐지 있음 글자(판독·앨범 필터, 탐지 이름). */
val DetectedText = Color(0xFFFF6B61)

// 지도(MapLibre) 색: 스타일 식에 문자열로 넘긴다.
/** 점검 영역 면·선·꼭짓점 테두리. */
const val MAP_AREA = "#1864AB"
/** 이륙 지점 H([Caution]과 같은 값). */
const val MAP_HOME = "#FFC400"
/** 미니맵 경로 선. */
const val MAP_ROUTE = "#E9ECEF"
/** 이륙 지점 점의 어두운 테두리. */
const val MAP_STROKE_DARK = "#212529"
