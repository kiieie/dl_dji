# 기체 PoC ④ LDM, ③ 촬영·다운로드 (2026-10-30)

RC Pro Enterprise + M3E(프로펠러 제거, 실내, 시뮬레이터). 앱 "연동 검증" 화면의 시험 버튼(`drone/DjiPoc.kt`)으로 확인했다. 로그 태그 `DjiSdk`, `DjiPoc`.

## ④ LDM
- 순서: `SDKManager.init` → `INITIALIZE_COMPLETE` → `loadLocalLDMLicenseContent(라이선스)` → `enableLDM` → `registerApp` (`InspectionApp.enableLdm`).
- 결과: `✅ LDM 켜짐(라이선스 로드 true, 사용 true)`, 이어서 `✅ DJI SDK 등록 성공`.
- RC Pro Wi-Fi를 끄고(`svc wifi disable`) 앱을 다시 켜도 등록 성공.
- 앱 소켓(`/proc/net/tcp*`, `udp*`, uid 10082): 127.0.0.1 루프백 1개와 UDP 9003/9004 대기뿐, 외부 주소 연결 없음.
- 아직 안 한 것: 앱 데이터를 지운 첫 설치 상태에서의 오프라인 등록, Wi-Fi를 켠 상태의 장시간 트래픽 감시.

## ③ 촬영·다운로드
- 기체 `DJI_MAVIC_3_ENTERPRISE_SERIES` 연결, 시뮬레이터 GPS 15개 LEVEL_5.
- 실내에서 시뮬레이터 없이 촬영하면 `CANNOT_START_TASK_ON_WEAK_GPS`. SD 카드가 없을 때도 같은 오류가 난다(상태 `NOT_INSERTED`). 넣은 직후에는 `INVALID_FILE_SYSTEM`이었다가 잠시 뒤 `NORMAL`.

| 회 | 촬영 명령 | 목록(1.5초 대기 뒤) | 미리보기 | 원본 |
|---|---|---|---|---|
| 1 | 45ms | 661ms | 266ms | 8,696KB 1,398ms |
| 2 | 59ms | 90ms | 200ms | 8,696KB 2,062ms (앞 사진을 다시 받음) |
| 3 | 51ms | 675ms | 186ms | 8,856KB 1,377ms |

- 원본(약 8.7MB) 1.4~2.1초, 미리보기 약 0.2초. 기체가 조종기 바로 옆이라 링크가 가장 좋은 조건이다. 비행 거리에서 다시 잰다.
- 2회는 목록에 새 사진이 아직 없어 앞 사진을 받았다. 실제 연동은 `CameraKey.KeyNewlyGeneratedMediaFile`로 새 파일을 집어야 한다.
- 결론: 원본을 받아 분석해도 전주당 시간 예산(20초) 안에 든다.
