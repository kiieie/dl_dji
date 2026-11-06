package com.example.myfirstapp.ui.theme

import androidx.compose.ui.graphics.Color

// 10.2 Pilot 2 토큰(2026-10-30 시안 확정): 검정 바탕, 회색 4단계(패널·컨트롤·선택·구분선), 채운 버튼 파랑 하나.
// 흰 글자 대비 4.5:1 이상을 지킨다(버튼 #0A6CFF, 이상 버튼 #D70015). 카메라 뷰·지도는 이 어두운 색,
// 절차 화면(홈·라이브러리·비행 전 점검·체크리스트·설정·계획 패널·앨범)은 아래 밝은 색([LightScreen], 2026-11-04 사용자 결정)을 쓴다.
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

// 밝은 절차 화면(Pilot 2 홈·라이브러리·비행 전 점검·체크리스트, 10.1): 회색 바탕에 흰 카드, 검은 글자, 회색 조작 칸, 파랑 강조.
val LightBackground = Color(0xFFF2F3F5)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1A1A1A)
val LightOnSurfaceVariant = Color(0xFF6B6F76)
/** 조작 칸 바탕(Pilot 2 스테퍼 값·고르기 칸 #EEE). */
val LightControl = Color(0xFFEEEFF1)
/** 스테퍼 ±칸(Pilot 2 #E4E4E4). */
val LightControlHigh = Color(0xFFE2E3E6)
val LightControlHighest = Color(0xFFD6D8DC)
val LightOutline = Color(0xFFC3C6CB)
val LightOutlineVariant = Color(0xFFE3E5E8)
/** 홈 [카메라 뷰 입력](Pilot 2 짙은 회색 버튼). 파랑 채운 버튼은 우리 [미션 비행 시작] 하나. */
val DarkButton = Color(0xFF45484D)
/** 홈 타일(비행 경로·앨범) 그림이 없을 때 바탕(Pilot 2 사진 타일의 회색 톤). 위에 흰 제목. */
val HomeTileFallback = Color(0xFF8E949B)
/** 경로 그림 바탕(Pilot 2 라이브러리 썸네일의 지도 색). 카드가 그림과 글로 갈라져 보이지 않게 연한 지도 색을 깐다. */
val MapThumbBg = Color(0xFFF1ECE2)
/** 경로 그림에 지도 바탕(10.4.10)이 있을 때 녹색 경로 밑에 까는 어두운 테두리(밝은 길·논 위에서도 보이게). */
val MapThumbCasing = Color(0x99000000)

/** 우리 앱 표지 고유색(왼쪽 위 `KEPCO 점검` 테두리, 10.5.1). Pilot 2와 구별하는 유일한 색. */
val Brand = Color(0xFF8B5CF6)

/** 영상 위 카드·버튼 바탕(10.4 시안 #121212 90%). */
val CardBg = Color(0xE6121212)
/** 영상 위 회색 버튼 바탕(Pilot 2 ZOOM 버튼·세로 줌, 시안 Flight3 rgba(40,40,40,.9)). */
val ControlBg = Color(0xE6282828)
/** 영상 위 반투명 판(Pilot 2 노출 띠): 검은 영상 위에서도 판이 보이는 회색 60%. */
val OverlayPlate = Color(0x99383838)
/** 내비 원판 바탕(Pilot 2 채운 어두운 원판). */
val NavDiscBg = Color(0xE6161616)
/** 지도가 큰 화면일 때 미션 칸 바탕(위성 위에서도 읽히게 더 짙게). */
val CardBgOpaque = Color(0xF5121212)
/** 꺼진 아이콘·신호 막대·셔터 테두리. */
val Inactive = Color(0xFF555555)
/** 상태를 모를 때의 점, 고르지 않은 칸 글자. */
val Muted = Color(0xFF8A8A8A)
/** 탐지 있음 글자(판독·앨범 필터, 탐지 이름). */
val DetectedText = Color(0xFFFF6B61)
/** 사진 위 설비 상자(경고 라벨이 아닌 탐지, 9.6): 표시만 하는 회색. */
val FacilityBox = Color(0xFFB4B4B4)
/** 사진 위 검출 대상 상자(경고 라벨). */
val DetectedBox = Color(0xFFFF5449)

// 지도(MapLibre) 색: 스타일 식에 문자열로 넘긴다.
/** 점검 영역 면·선·꼭짓점 테두리. */
const val MAP_AREA = "#1864AB"
/** 이륙 지점 H([Caution]과 같은 값). */
const val MAP_HOME = "#FFC400"
/** 비행 지도 계획 경로 선과 RTH 중 기체 → H 선(Pilot 2 녹색, 10.5.4). */
const val MAP_ROUTE = "#19D46E"
/** 비행 지도 실제 비행 궤적(Pilot 2 주황, 10.5.4). */
const val MAP_TRACK = "#FF9F0A"

/** 비행 지도의 남은(대기) 전주: 날아간 궤적(주황)과 헷갈리지 않게 밝은 회색에 어두운 테두리(2026-11-04 C묶음 대조). */
const val MAP_POLE_PENDING = "#E6E6E6"
const val MAP_POLE_PENDING_STROKE = "#212529"
/** 이륙 지점 점의 어두운 테두리. */
const val MAP_STROKE_DARK = "#212529"
/** 기체 화살표(Pilot 2 기체 표지, 홈 노랑과 구별되는 파랑). */
const val MAP_AIRCRAFT = "#1C7CFF"
/** 조종기 위치 점(Pilot 2 조종기 표지). */
const val MAP_RC = "#4DABF7"
/** 기체 화살표·조종기 점 테두리. */
const val MAP_STROKE_LIGHT = "#FFFFFF"

// GEO 구역 지도 레이어(10.4.9): DJI Fly Safe·Pilot 2 지도 색. 면은 반투명, 테두리는 같은 색
const val MAP_GEO_RESTRICTED = "#DE4329"
const val MAP_GEO_AUTHORIZATION = "#1088F2"
const val MAP_GEO_ENHANCED_WARNING = "#EE8815"
const val MAP_GEO_WARNING = "#FFCC00"
const val MAP_GEO_ALTITUDE = "#979797"
const val MAP_GEO_REGULATORY = "#37C4DB"
const val MAP_GEO_APPROVED = "#1BAB5A"

/** 홈 기체 그림(우리 선 그림, DJI 그림은 쓰지 않음): 몸체·팔 짙은 회색, 프로펠러 끝 주황(Pilot 2 홈 그림의 색). */
val AircraftBody = Color(0xFF3A3D42)
val AircraftPropTip = Color(0xFFFF7A2F)
