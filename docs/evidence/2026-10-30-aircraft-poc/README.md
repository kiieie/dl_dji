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

## ① 미션 업로드·시작, ⑥ 미션 중 일시정지·C1 재개
- 현장 KMZ `M1_137L-A.kmz`(DJI Pilot 2로 만든 대평선 137L-A, 웨이포인트 68개, 이륙 안전고도 30m)를 그대로 썼다. 시뮬레이터 위치는 M1 시작점 근처.
- 업로드 1,174ms(`UPLOADING` → `READY`), 시작 명령 414ms, `ENTER_WAYLINE` +0.4초, `EXECUTING` +25.6초(지상에서 이륙·상승 포함), 웨이포인트 간격 약 6초.
- RC 일시정지 버튼: 앱 감지(`RcKey PAUSE`) 뒤 37ms 만에 `INTERRUPTED`(이유 `USER_BREAK`). 펌웨어가 바로 멈춘다.
- C1 → 앱이 `resumeMission` 호출: 118ms 만에 `RECOVERING`, 약 5초 뒤 `EXECUTING`으로 **중단한 웨이포인트(7)부터** 이어 간다. 두 번 반복해 같은 결과.
- 시뮬레이터에서는 모터가 돌지 않는다.
- 로그: `mission.log`
- 일시정지 → 스틱으로 수동 이동 → C1(`mission-manual.log`): `RECOVERING`이 약 17.5초 걸린 뒤 `EXECUTING`(수동 이동 없을 때 약 5초). 기체가 **중단 지점으로 되돌아간 뒤** 웨이라인을 이어 간다. 방식 A로는 "현재 위치에서 다음 전주로" 재개가 안 되므로 6.1 방식 B(구간 미션)를 유지한다. 위치 기록은 이번에 남기지 않았다(검증 화면 표시만).

## 앱이 만든 KMZ, 4방향 사선(5.6) 시뮬레이터 비행
- 현장 전주 데이터(대평선, 기기에만 넣음)로 계획한 미션 #5 소티 1: 전주 10개 → 촬영 지점 40개, KMZ 6KB(`WpmlWriter`).
- 업로드 1,411ms, 시작 392ms, `EXECUTING` +21초(이륙 포함). 업로드 오류 없음(모든 지점 "멈춘 뒤 회전").
- 지점마다 도착 → 사진 1장(`KeyNewlyGeneratedMediaFile`), 지점 간격 약 6.5초. 처음 12지점 로그(`diagonal4.log`):
  - 기수가 한 전주에서 47° → 137° → -133° → -43°로 90°씩 돌며 전주를 향한다.
  - 짐벌 -43.4~-43.5°(계산값 -43.5°). 1장은 -52.5°로 읽혔다(짐벌이 아직 도는 중에 읽은 값으로 보임, 사진 EXIF로 다시 확인).
  - 첫 전주(1566S112) 둘레 4지점이 전주에서 약 10m.
- 남은 확인: SD 파일 이름의 `fileSuffix`(전주번호_지점), 실제 사진 구도(실비행), 사진마다 원본 다운로드·탐지 연결.
