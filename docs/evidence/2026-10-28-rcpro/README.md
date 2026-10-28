# 증빙: RC Pro 실기기 첫 시험, DJI SDK 등록, PoC ⑤·⑥·⑦ (2026-10-28)

- 기기: DJI RC Pro Enterprise(`rm510`), Android 10(API 29), Snapdragon 865(`kona`), 펌웨어 V80.05.00.11, 1920×1080 가로. 기체는 연결하지 않음
- 개발 PC: Windows 11, Android Studio 2026.2.1(JBR), USB adb
- 앱: `kr.co.dl_dji`(이날 `com.example.myfirstapp`에서 변경, DJI 앱 키와 일치)

## 결과

| 항목 | 결과 | 근거 |
|------|------|------|
| PoC ⑦ 설치·adb | `installDebug`로 설치, 크래시 없음 | `0-first-screen.png` |
| 모의 비행 전체 흐름 | CSV 1,000개 가져오기 → 영역(전주 10개) → 계획 저장 → 비행 → 미션 완료 | `1-plan.png`, `2-flight.png` |
| DJI SDK 등록 | "등록 성공". `Helper.install`(Application)과 `INITIALIZE_COMPLETE` 뒤 `registerApp()`이 빠져 있던 것을 추가. 옛 키(패키지 `pf_Inspection`)는 점이 없는 이름이라 Android 앱 ID로 쓸 수 없어 새 키 발급 | 연동 검증 화면 |
| PoC ⑤ TFLite | 36회: 전처리 182~203ms, 추론 201~215ms, 후처리 4~9ms. 1장 약 0.4초 | `detect.log` |
| PoC ⑥ RC 버튼 | 일시정지, C1, C2, RTH의 눌림·뗌을 SDK 키로 모두 감지. 동시 누름도 구분 | `rckey.log` |

### PoC ⑤ 조건

- 모델: 개발용 COCO yolo11n fp16(`tools/export_coco_model.sh`, AGPL이라 저장소 제외), CPU 4스레드, GPU delegate 없음
- 사진: RC Pro의 Pilot 2 앨범에 있던 M3E 원본 JPEG 2장(약 7MB)을 `fake_photos/`에 넣고 전주 18개 모의 비행. 전주마다 2회 추론
- 에뮬레이터 측정(약 110ms)은 추론만이었고, 이번 값은 실기기 전체 시간이다.

### PoC ⑥ 조건과 남은 확인

- 연동 검증 화면에서 SDK 등록 뒤 `KeyManager.listen`으로 `RemoteControllerKey.KeyPauseButtonDown`, `KeyCustomButton1Down`, `KeyCustomButton2Down`, `KeyGoHomeButtonDown`을 감시해 로그 태그 `RcKey`로 남김
- 같은 시간 `getevent`에는 이 버튼들이 나오지 않았다. Android 키 이벤트가 아니라 SDK로만 받을 수 있다.
- 남은 것(기체 필요, 프로펠러 제거·시뮬레이터): 미션 중 일시정지 시 기체 제동과 이벤트 도착, 버튼→SDK 지연 실측, C1 재개 흐름

## RC Pro에서 본 점

- 시스템 파일 선택 창(`documentsui`)의 목록은 adb `input tap`에 반응하지 않았다(방향키·Enter로는 됨). 손가락으로는 정상이라 앱 문제는 아니다.
- 지도에서 탭을 1초 간격으로 빠르게 넣으면 일부가 더블탭(확대)으로 처리되어 꼭짓점이 빠졌다. 2.5초 간격이면 정상.
- 기기는 Wi-Fi(사내망)에 연결되어 있었고 음소거 상태였다. 등록 시험은 온라인으로 했다(LDM 미적용).
- Windows Git Bash에서 `adb push ... /sdcard/...`는 경로가 바뀌므로 `MSYS_NO_PATHCONV=1`을 쓴다.
