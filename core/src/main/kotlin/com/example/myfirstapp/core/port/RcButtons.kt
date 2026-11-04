package com.example.myfirstapp.core.port

/**
 * RC Pro 하드웨어 버튼(10장). Android 키 이벤트로는 오지 않고 DJI SDK 키로만 받을 수 있다(PoC ⑥).
 * 비행 화면 매핑(10.5.10, 2026-11-04 Pilot 2 기본값과 같게): [PAUSE] 일시정지, [C1] 광각/줌 전환, [C2] 지도/카메라 전환, [RTH] 복귀(모의 기체만, 실제 기체는 펌웨어가 처리).
 * 미션 재개는 화면 미션 칸 ▶(또는 일시정지 중 5D 누름), 짐벌은 화면 짐벌 메뉴로 한다.
 * [SHUTTER]·[RECORD]는 앱이 찍고 녹화한다(2026-11-04, 11-03 현장에서 기체는 셔터만으로 찍지 않았음). 셔터 사진은 가까운 전주에 수동 사진으로 붙인다(6.4 RC 셔터).
 * 1차 판독 화면에서는 [C1]이 광각·망원 전환이고, 5D 버튼(FIVE_D_*)으로 전주·판정을 고른다. 방향은 안드로이드 앱으로 오지 않아(2026-10-29 확인) SDK로 받는다.
 */
enum class RcButton { PAUSE, C1, C2, RTH, SHUTTER, RECORD, FIVE_D_UP, FIVE_D_DOWN, FIVE_D_LEFT, FIVE_D_RIGHT, FIVE_D_CENTER }
