# 증빙: 앱 재시작 복구 (2026-10-10)

- 대상(설계 6.4 복구)
  - `:core` `InspectionExecutor`의 `recoverSortie`: 시작하자마자 기체 상태로 복귀 상태를 정한다. 연결 없음이면 `LinkLost`, 비행 중이면 `Paused`, 착륙 상태면 `SortieDone`이다.
  - `:core` `recoveredCaptures`: SD 대조. 목표 전주 도착 뒤 SD에 저장되었지만 DB에 없는 사진을 고른다.
  - `DroneGateway.listMedia`: SD 사진 목록. 모의 기체는 메모리 SD를 쓴다.
  - `ui/flight/FlightViewModel`: 열린 소티(`startedAt` 있음, `endedAt` 없음)를 찾는다. 목표 전주에 사진이 있으면 `DONE`, 없으면 `PENDING`으로 바꾼 뒤 실행기를 복구 모드로 시작한다.
  - `MainActivity`: 앱을 켤 때 열린 소티가 있으면 미션 목록 → 비행 화면으로 바로 간다.
- 기체: `FakeDroneGateway`(모의 시간 ×5). 실제 기체는 앱이 죽어도 떠 있으므로, 복구할 때 모의 기체를 목표 전주 상공 30m에서 호버하는 상태로 만든다(`Config.airborneAt`).
- 환경: 에뮬레이터 `Pixel_8`(arm64, 2400×1080 가로)

## 다시 돌리기

```bash
./gradlew :core:test                                   # 복구 단위 테스트 4개 포함 49개
./gradlew installDebug
bash docs/evidence/2026-10-10-recovery/smoke.sh        # 약 3분
```

## 시나리오와 결과

전주 5개를 계획해 비행한다. 2개를 점검하고 3번째 전주로 이동하는 중에 `am force-stop`으로 앱 프로세스를 죽인다.

| 단계 | 확인한 것 | 화면 |
|------|-----------|------|
| 종료 직전 | 처리 2/5, 이동 중 | `1-before-kill.png` |
| 다시 켬 | 비행 화면이 바로 열림. "일시정지됨 — 수동 조종 가능", 안내 "앱이 다시 시작되었습니다…", 남은 전주 3개(처리 0/3) | `2-recovered-paused.png` |
| [재개] | 이륙 없이 3번째 전주로 감 → 남은 3개 점검 → 미션 완료 | `3-mission-done.png` |
| 결과 | 완료 5 · 건너뜀 0 · 실패 0 · 미점검 0 | `4-result.png` |

DB 대조(`verify.py`, 결과 `verify.txt`): 종료 직후 DB와 끝난 뒤 DB를 비교했고, 11개 항목이 모두 일치했다. 확인한 내용은 아래와 같다.
- 종료 직후 소티가 열린 채로 있었다. 방문 상태는 완료 2 · 진행 중 1 · 대기 2였다.
- 복구 전이는 `Idle → Paused`("앱 재시작 복구")로 기록되었다. 복구 뒤 이륙은 0회, 재개는 1회, 도착은 3회였다.
- 전주마다 사진이 2장씩이다. 다시 찍은 전주는 없다.
- 소티 종료가 기록되었고, 미션은 `DONE`, 마지막 상태는 `MissionDone`이다.

## 단위 테스트로만 확인한 것

- 착륙 상태 복구: `SortieDone`으로 들어가고 남은 전주는 다음 소티로 남는다.
- 연결 없음 복구: `LinkLost`로 들어가고, 재연결되면 `Paused`가 된다.
- SD 대조: 도착 전에 찍힌 사진과 이미 DB에 있는 사진은 빼고 고른다.
- 모의 기체의 SD는 앱 프로세스와 함께 사라진다. 그래서 에뮬레이터에서는 "SD에만 있는 사진" 경우를 만들 수 없다. 실제 기체의 SD 목록(`MediaDataCenter`)과 기체·조종기 시각 차이는 PoC에서 확인한다.
