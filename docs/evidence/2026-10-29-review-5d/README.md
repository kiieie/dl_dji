# 증빙: 1차 판독 화면 5D 버튼과 큰 사진 배치 (2026-10-29)

- 기기: RC Pro Enterprise, 앱 `kr.co.dl_dji` 디버그, 미션 #1(모의 기체, 개발용 COCO 모델)
- 판독 11/11은 시험 중 5D 가운데 누름으로 저장된 것이다(모의 데이터).

| 항목 | 결과 | 근거 |
|------|------|------|
| 5D 원시 신호 | 장치 "DJI embedded joystick": 방향 `ABS_HAT0X/Y`(±1), 가운데 `BTN_THUMBL` | `5d-getevent.log` |
| 앱 수신(안드로이드) | 가운데만 `KEYCODE_BUTTON_THUMBL`로 온다. 방향(조이스틱 움직임)은 장치에서 나와도 앱 창에 오지 않는다 | `5d-raw-vs-app.log`(원시 신호), 진단 로그 `Input5D` |
| 앱 수신(SDK) | `RemoteControllerKey.KeyFiveDimensionPressedStatus`로 다섯 방향 모두 받음. 그래서 5D는 SDK로 받는다(`drone/DjiRcButtons.kt`) | `RcKey` 로그 |
| 겹침 | 가운데 98ms 전 방향, 같은 ms 방향+가운데, 가운데 210ms 뒤 방향을 관찰. `core/input/FiveDGate.kt`(대기 150ms, 겹치면 둘 다 버리고 안내, 가운데 뒤 300ms 방향 버림), 단위 테스트 6개 | `FiveDGateTest` |
| 판독 매핑 | 위·아래 전주, 왼쪽·오른쪽 판정, 가운데 저장 후 다음. 목록이 고른 전주로 스크롤 | 사용자 확인 |
| 큰 사진 | 사진 전체 화면, 목록·판정 패널 반투명(55%), [광각]/[망원] 전환, 사진 탭으로 패널 숨김 | `1-review-panels.png` |
| 판정 배지 | 패널을 숨겨도 사진 아래 정보 상자에 판정(색)·저장 여부 표시 | `2-review-photo-only.png` |
