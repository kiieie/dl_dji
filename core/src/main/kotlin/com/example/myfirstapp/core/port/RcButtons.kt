package com.example.myfirstapp.core.port

/**
 * RC Pro 하드웨어 버튼(10장). Android 키 이벤트로는 오지 않고 DJI SDK 키로만 받을 수 있다(PoC ⑥).
 * 비행 화면 매핑: [PAUSE] 일시정지, [C1] 재개, [RTH] 복귀(모의 기체만, 실제 기체는 펌웨어가 처리). [C2]는 짐벌 중앙/아래 전환으로 정했고 기체 연결 뒤 구현한다.
 * [SHUTTER]는 기체가 직접 찍으므로 어댑터가 새 사진 알림을 셔터 사진(`DroneEvent.ShutterPhoto`)으로 가르고, 앱은 가까운 전주에 수동 사진으로 붙인다(6.4 RC 셔터).
 * 1차 판독 화면에서는 [C1]이 광각·망원 전환이고, 5D 버튼(FIVE_D_*)으로 전주·판정을 고른다. 방향은 안드로이드 앱으로 오지 않아(2026-10-29 확인) SDK로 받는다.
 */
enum class RcButton { PAUSE, C1, C2, RTH, SHUTTER, FIVE_D_UP, FIVE_D_DOWN, FIVE_D_LEFT, FIVE_D_RIGHT, FIVE_D_CENTER }
