package com.example.myfirstapp.ui.theme

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * 화면 규격(10.1, 10.4 시안). 터치 대상·버튼·머리글·줄 높이처럼 여러 화면이 같은 뜻으로 쓰는 크기만 둔다.
 * 2·3·4·6·8dp 여백과 한 곳에서만 쓰는 배치 숫자는 화면 코드에 그대로 둔다.
 */
object Dimens {
    // 터치 대상(장갑 터치, 10.4.2)
    val TouchSmall = 32.dp
    val TouchMedium = 36.dp
    val TouchLarge = 48.dp

    // 버튼 높이
    val ButtonChip = 30.dp
    val ButtonStd = 36.dp
    val ButtonPrimary = 44.dp

    // 머리글 높이
    val HeaderList = 40.dp
    val HeaderCamera = 32.dp
    /** 카메라 뷰 상단 바 32dp + 배터리 띠 3dp. 그 아래부터 영상 위 요소를 둔다. */
    val TopBar = 35.dp
    /** 목록 안 구획 머리글("프로젝트 · 최신순" 등). */
    val HeaderSection = 30.dp

    // 줄 높이
    val RowList = 36.dp
    val RowTable = 40.dp

    /** 미션 카드·시스템 상태 칸·판독 전주 목록 폭(시안 196dp). */
    val CardWidth = 196.dp
    /** 왼쪽 아래 지도 미리보기(매뉴얼 54쪽 15번). */
    val MiniMap = DpSize(132.dp, 92.dp)
    /** 꺾쇠로 키운 지도 미리보기(10.5.4). 아래 가운데 내비 원판(300dp)과 겹치지 않는 폭. */
    val MiniMapLarge = DpSize(216.dp, 150.dp)
    /** 지도 웨이포인트 번호 원(흰 원 파란 번호, 10.5.4). */
    val MarkerWaypoint = 16.dp
    /** 미니맵 위 "마지막 사진" 썸네일(9.6, 사진 4:3). */
    val LastPhoto = DpSize(120.dp, 90.dp)
    /** 계획 화면 파라미터 −/+ 버튼. */
    val Stepper = 32.dp

    /** 지도 홈 표지(노란 원 "H", 10.5.4). */
    val MarkerHome = 22.dp
    /** 지도 기체 표지(파란 화살촉, 10.5.4). */
    val MarkerAircraft = 28.dp
}
