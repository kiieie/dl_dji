# 2026-10-31 조정값·화면 규격 한곳 정리 + RC 배터리

계획: `C:\Users\kiie\.claude\plans\resilient-weaving-sonnet.md`(승인). 값은 바꾸지 않고 위치만 옮겼다.

| 묶음 | 파일 | 내용 |
|---|---|---|
| 현장 조정값 | `core/config/Tuning.kt` | 사진 분류 시간, 셔터 연결 20m, 실행기 재시도·대기, 장애물 10/5/1m, 비행 전 점검, 5D, 배터리 소모 계수(모의 기체와 중복 제거), 안전 고도, 경로 |
| 앱 설정값 | `AppConfig.kt` | 시간 제한(키 읽기 2s는 3곳을 1곳으로), 주기, 지도 캐시·미리 받기, 저장, 점검 기준(30%·8개, 2곳을 1곳으로), 신호음, 탐지, RTH 1s |
| 화면 규격 | `ui/theme/Dimens.kt`, `Type.kt` `FontSize`, `Color.kt` | 터치 32/36/48, 버튼 30/36/44, 머리글 40/32/35, 줄 36/40, 카드 196, 글자 10~16.5sp, 테마 밖 색 |
| 전주 표지 | `core/model/PoleCodes.kt` | 고/저압, 설치기기 키워드, 지선주, 근입 깊이(**전문가 확인 대기**), 테스트로 지금 동작 고정 |
| RC 배터리 | `data/RcBattery.kt`, `CameraChrome` TopBar | 안드로이드 배터리(RC Pro)를 상단 바에 "RC" %로 표시, 30% 아래 빨강, 충전 중 ⚡ |

## 검증
- `:core:test` 145개 통과(PoleCodesTest 3개 추가), `:app:compileDebugKotlin`, lint 오류 0개.
- 정리 전(`before/`)과 정리 후(`after/`) RC 화면 6장을 픽셀 단위로 비교했다(차이 판정 기준 24/255).
  - 홈, 비행경로, 계획, 앨범, 설정: **차이 0%**.
  - 비행 화면: 0.34% 차이. 위쪽 바 오른쪽(x 1251~1809, y 20~60)에 RC 배터리가 들어가고 시각이 달라진 것뿐이다. 바뀐 위쪽 바 그림은 `after/04-flight.png`.
- 남은 예외: 위쪽 바는 RC 배터리를 ViewModel이 아니라 `InspectionApp.rcBattery`에서 직접 읽는다. 나중에 `FlightUiState`로 옮길 수 있다.
