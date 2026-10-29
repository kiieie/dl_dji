# 증빙: Cesium ion 위성 배경(7.4 임시 결정) (2026-10-29)

- 기기: RC Pro Enterprise(Wi-Fi 연결), 앱 `kr.co.dl_dji` 디버그 빌드, 기체 없음
- 토큰: `secrets/cesium.properties`의 `CESIUM_ION_TOKEN`(git 제외)

| 항목 | 결과 | 근거 |
|------|------|------|
| ion 자산 2(Bing Aerial) 타일 주소 받기 | 성공(로그 경고 없음) | `adb logcat -s PlanViewModel` |
| 계획 화면 배경 | 위성사진이 맨 아래에 깔림. MBTiles가 있으면 그 위를 덮으므로(합성 `test-grid`는 흰 격자로 덮었음) 시험용 지도는 앱 폴더에서 지움 | `1-plan-overview.png` |
| 해상도 | 서울 도심 확대(약 줌 18)에서 건물·나무·보도교가 선명 | `2-zoom.png` |

- 인터넷이 없으면 배경이 나오지 않고(전주·경로는 그대로), 지도를 볼 때 타일 좌표가 Cesium·Microsoft로 나간다(7.1 예외, 7.4).
