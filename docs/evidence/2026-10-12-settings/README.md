# 증빙: ⑥ 설정 화면 (2026-10-12)

- 대상(설계 10장 ⑥ 설정, 9.1 모델 위치)
  - `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`: 비행 기본값(F-09), 탐지 모델 관리(F-18), 오프라인 지도 관리, 상태 표시
  - `data/SettingsStore.kt`: DataStore(3.4). 비행 기본값은 `MISSION.flightParams`와 같은 JSON으로 저장하고, 고른 모델 폴더 이름도 저장한다.
  - `data/AppFiles.kt`: 지도 복사·목록(계획 화면에서 옮김), 모델 폴더 검사·가져오기(SAF 폴더 → 규격 검사 → `models/<version>/`)
  - `PlanViewModel`: 새 계획의 파라미터 초기값을 설정에서 읽는다.
  - `FlightViewModel`, `TfliteYoloDetector.loadPreferred`: 고른 모델을 먼저 쓰고, 없거나 규격이 맞지 않으면 이름이 가장 뒤인 모델을 쓴다.
- 환경: 에뮬레이터 `Pixel_8`(arm64, 2400×1080 가로). 모델은 개발용 COCO yolo11n(`tools/export_coco_model.sh`, AGPL이라 저장소 제외)

## 다시 돌리기

```bash
./gradlew installDebug
bash docs/evidence/2026-10-12-settings/smoke.sh     # 약 6분
```

## 시나리오와 결과

시험용 모델 폴더 3개를 `Download/models-in/`에 넣는다.
- `coco-yolo11n-fp16`: 정상
- `coco-old`: 같은 모델이고 `model.json`의 버전만 다르다.
- `broken`: 라벨이 10개뿐이라 출력 텐서와 맞지 않는다.

| 단계 | 확인한 것 | 화면 |
|------|-----------|------|
| 처음 열기 | 설계 기본값(50m, 8m/s), "모델이 없습니다. 모의 탐지기(fake-1)로 비행합니다." | `1-first-open.png` |
| 모델 가져오기 | 규격 불일치는 "실패: 출력 텐서…"로 거부한다. 정상 2개를 가져오면 이름이 가장 뒤인 `coco-yolo11n-fp16`이 사용 중이다. 줄을 눌러 `coco-old`를 고른다. | `2-models.png` |
| 지도 추가 | `test-grid.mbtiles` 492KB | `3-map-added.png` |
| 범위 오류 | 점검 고도 55m > 이동 고도 50m이면 오류 문구가 뜨고 [기본값 저장]이 꺼진다. | `4-param-error.png` |
| 저장 | 이동 60m, 속도 7m/s, 예비율 35%, 대기 8초, 렌즈 광각만, 다운로드 원본 | `5-defaults-saved.png` |
| 앱 재시작 | 값과 고른 모델이 남는다(DataStore). | `6-after-restart.png` |
| 새 계획 | 파라미터가 설정값으로 시작한다(이동 60m · 7m/s · 예비율 35%). | `7-plan-defaults.png` |
| 비행 화면 | 로그 `Detector: model=coco-old dir=coco-old` | `app.log` |
| 삭제 | 확인 대화상자를 거친다. [취소]를 누르면 그대로 남는다. 고른 모델을 지우면 `coco-yolo11n-fp16`이 다시 사용 중이 된다. 지도를 지우면 "지도 파일이 없습니다."가 뜬다. | `8-deleted.png` |

DB·로그·앱 폴더 대조(`verify.py`, 결과는 `verify.txt`): 6개 항목이 모두 일치했다.
- 저장한 미션의 `flightParams`가 설정값과 같다.
  - 60 / 30 / 7 / 35
  - 대기 8초
  - 렌즈 `["WIDE"]`
  - 다운로드 `ORIGINAL`
- 경로 계산 로그의 파라미터도 설정값과 같다.
- 비행 화면은 고른 모델을 썼다.
- 앱 폴더에는 `coco-yolo11n-fp16`만 남았다. 거부한 모델은 복사되지 않았고, 임시 폴더 `.import`도 없다.

## 회귀

- 계획 화면(지도 복사 함수 이동, 초기값), 비행 화면(모델 선택), 탐지기 로더가 바뀌었다.
- 기존 스모크 10개(`2026-09-29-*` 9개, `2026-10-10-recovery`)를 다시 돌려 모두 통과했다.
- 단위 테스트는 `:core` 49개, `:app` 1개가 통과했다.

## 시험 중 고친 것

- 저장 버튼과 범위 오류 문구를 카드 아래쪽에서 위쪽으로 옮겼다. 1080px 화면에서는 아래쪽이 화면 밖으로 밀려, 오류를 보지 못하고 저장도 못 했다.
- 다운로드 방식 버튼(미리보기/원본)의 글자가 접혀 보여서 줄 너비를 채우게 했다.
- 모델 줄 전체를 누르면 고르도록 했다. 장갑 조작에 맞추고, 줄 높이는 56dp다.

## 범위 밖(MVP 이후, 10장 ⑥)

프리셋, 에너지 모델 보정값 편집, 계획 화면 "고급" 패널, 알림음 음량·음성 켜기/끄기. LDM 상태는 기체를 연결한 뒤 확인하므로(PoC ④) 지금은 안내 문구만 보여 준다.
