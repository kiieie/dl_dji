package com.example.myfirstapp.core.port

/**
 * RC Pro 하드웨어 버튼(10장). Android 키 이벤트로는 오지 않고 DJI SDK 키로만 받을 수 있다(PoC ⑥).
 * 비행 화면 매핑: [PAUSE] 일시정지, [C1] 재개, [RTH] 복귀(모의 기체만, 실제 기체는 펌웨어가 처리). [C2]는 짐벌 중앙/아래 전환으로 정했고 기체 연결 뒤 구현한다.
 */
enum class RcButton { PAUSE, C1, C2, RTH }
