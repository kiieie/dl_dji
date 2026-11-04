# CLAUDE.md

이 파일은 Claude Code가 이 저장소에서 작업할 때 참고하는 안내서입니다.

## 현재 단계: 구현 — 실제 기체 연결(방식 A)과 DJI Pilot 2식 화면 완료, 실비행 시험 전

- 설계서 v1.0이 **승인**되었습니다(2026-09-29, 현재 v1.3). 구현은 `docs/design/07-test-roadmap.md` 13장의 단계 순서를 따릅니다.
- **2026-10-30**: PoC ①③④⑥ 완료(시뮬레이터), 6.1 방식 A(소티 KMZ 하나로 계속 비행, 사진마다 뒤에서 다운로드·탐지) 확정, `drone/DjiDroneGateway.kt`로 실제 기체 연결, FPV(`drone/DjiFpv.kt`), Room 2(촬영 지점·비행 궤적), 촬영 방식 옵션(전주 위/4방향 사선, 5.6, `core/route/Viewpoints.kt`·`WpmlWriter.kt`), Pilot 2식 홈·카메라 뷰·비행 전 점검·기체 설정(`ui/home`, `ui/flight`, `ui/settings/SettingsHub.kt`). 현장 시험 자료 `docs/drone_dl_test`(.zip)는 실제 전주 좌표라 git 제외. 같은 날 저녁: 배전 설비 엑셀(.xlsx) 직접 가져오기와 선로번호 활용(4.5, `core/csv/XlsxReader.kt`, `core/route/LineNumbers.kt`), 4방향 사선 기본·전주마다 4점(5.6), Claude Design 시안 확정(10.4, `docs/design/10-ui-pilot2.md`)과 테마(Barlow 글꼴). 같은 날 밤: 시안 8화면(홈·비행경로·계획·자동/수동 카메라 뷰·판독·앨범 `ui/album`·기체 점검 `drone/DjiCheck.kt`) 다시 그리기 완료, 카메라 뷰 공통 틀 `ui/flight/CameraChrome.kt`, 줌 +/−·사진/영상 녹화(`CameraControl.kt`, 실기체 줌 확인), RC 셔터·수동 비행 사진을 가까운 전주 `MANUAL`로(20m). 실시간 지형 추적 기본(`realTimeFollowSurface`)·먼 구간 이동 고도는 진행 중, 현장 PoC 필요.
- **2026-10-31**(계획 `docs/plan/2026-10-31.md`, 기체 꺼 둔 채 RC·모의 기체로): 조작(5D 확대 `core/input` `fiveD()`, RTH 1초, ⋯ 메뉴 비포커스 팝업 `MENU_PROPERTIES`), 지형 추적 시작 거절 → 이륙 기준 확인 창, 광각+망원 동시·설비 조준 사진(`_E`), 사진 순서(`MediaClassifier` 늦은 파일), 재시작 복구 보강, 미션 상태 `PARTIAL`(남은 전주 있음)·`REPLACED`(새 계획으로 대체), LDM 꺼지면 시작 막음, 충돌 경고(`core/model/Obstacles.kt`), 속성 갱신(4.5.7), lint 0, 데이터 주권 점검(`docs/evidence/2026-10-31-sovereignty`). Room 7. 같은 날 시뮬레이터 회귀(`docs/evidence/2026-10-31-sim`): 복구·옛 미션 정리·촬영 동작 구분 고침, 위성 배경 배포 사용·미션 영역 MBTiles 미리 받기(7.4), 비행 지도 전주번호·기호, 운용 로그 비행 궤적. 오후: 배경 지도 선택(Bing/VWorld 위성·일반, 7.4), 비행 지도 전주 라벨(선로번호·설비 기호), 위쪽 바 RC 배터리, 땅에서 미션 시작 실패는 대기로, 조정값·화면 규격 정리(`Tuning`·`AppConfig`·`Dimens`·`PoleCodes`, RC 화면 픽셀 비교 차이 0%). **남은 현장 확인**: 광각+망원 동시 촬영(시뮬레이터는 촬영마다 파일 1개 `_V`), 앱 강제 종료 복구, 장애물 거리 단위, 지형 추적.
- **RC Pro Enterprise 실기기를 쓰고 있고 M3E 기체도 확보할 수 있습니다**(2026-10-28). RC Pro에서 DJI SDK 등록, PoC ⑤(TFLite 약 0.4초/장)·⑦(adb) 완료, ⑥ 하드웨어 버튼은 기체 없이 감지까지 확인했습니다(`docs/evidence/2026-10-28-rcpro`). 다음은 기체를 연결해 PoC ④ LDM, ⑥ 미션 중 일시정지·C1 재개(프로펠러 제거, 시뮬레이터), ①~③입니다. 기체가 필요한 항목(6.1 방식 B 등)은 PoC 전까지 가정으로 둡니다.
- 1차 MVP 범위는 `docs/DESIGN.md` 2.4를 따르고, 범위 밖 기능은 만들지 않습니다.
- 설계와 다른 구현이 필요하면 코드를 바꾸기 전에 설계서를 먼저 수정합니다.
- 진행 현황은 `docs/design/07-test-roadmap.md` 13.0에 기록합니다. `:core`(모델, 포트, CSV, 경로 계획, 상태머신, `FakeDroneGateway`)와 Room(`PROJECT`, `POLE`), 가져오기 화면, 오프라인 지도(MBTiles 표시), 계획 화면(영역 그리기, 경로 미리보기, 핵심 파라미터 4개, 전주 개별 포함/제외, 계획 저장 `MISSION`·`SORTIE`·`VISIT`)과 ④ 비행 화면(`FakeDroneGateway`로 저장한 계획 실행, 실행 기록 저장, 신호음 알림), ⑤ 결과 화면(전주 목록, 이상만 필터, 사진과 탐지 박스, ZIP 내보내기), AI 탐지 연결(TFLite YOLO 탐지기, 앱 폴더 `models/`의 모델, 없으면 `FakeDetector`)은 완료했습니다. 설비 탐지 모델(사용자 학습, 9.6 두 단계 탐지, Room 8 `CAPTURE.detectModel`)을 2026-11-04에 붙였고, 개발용 COCO 모델(`tools/export_coco_model.sh`, AGPL이라 저장소 제외)도 그대로 쓸 수 있습니다. RC 속도 측정은 앱 안에서 합니다(`adb shell am start -n kr.co.dl_dji/com.example.myfirstapp.MainActivity --es bench all`, 결과 `files/logs/bench-*.txt`). 진행 순서: ① 결과 화면 `ViewModel` 전환과 1차 판독(F-19, 완료) ② 비행 화면 `ViewModel` 전환과 소티 완료 화면(완료) ③ 미션 목록과 이어하기(F-20, 완료) ④ 프로젝트·계획 화면 `ViewModel` 전환(완료) ⑤ 앱 재시작 복구(6.4, 모의 기체, 완료) ⑥ 설정 화면(비행 기본값, 모델·지도 관리, 완료) ⑦ 비행 화면 DJI 배치(FPV 전체·상단 상태바·위성 미니맵, 반투명 버튼)와 RC 버튼 매핑(일시정지, C1 재개, C2 짐벌은 기체 뒤)은 2026-10-29 완료(RC Pro에서 C1 재개·일시정지 버튼·위성 미니맵 확인), FPV 영상은 기체 확보 뒤 DJI UX SDK로. 탐지는 객체 검출(조류 둥지, 이물질)만 하고, 2차 판독은 다른 플랫폼에서 합니다. 배경 지도는 ⑥ 설정에서 Bing 위성(Cesium ion, 기본) / VWorld 위성(라벨 켬·끔) / VWorld 일반 중 고르고, 계획을 저장하면 미션 영역을 `maps/`의 지도별 MBTiles 캐시로 미리 받습니다(7.4, 2026-10-31. 토큰 `secrets/cesium.properties`, 키 `secrets/vworld.properties`).

## 프로젝트 개요

**배전선로 전주 점검 드론 앱**입니다. DJI RC Pro Enterprise에 설치해 Mavic 3 Enterprise(M3E/M3T)를 운용합니다.

1. 전주 GPS 좌표(CSV/Excel, WGS84)를 가져옵니다.
2. 오프라인 지도(VWorld + MapLibre)에서 점검 영역(사각형/다각형)을 그립니다.
3. 영역 안의 전주를 배터리 소모가 가장 적은 순서로 방문하는 경로를 만들고, 배터리가 부족하면 여러 소티로 나눕니다.
4. 자동 비행: 소티 KMZ 하나로 전주마다 촬영하고 바로 다음 전주로 감. 사진 다운로드·YOLO(TFLite) 탐지는 뒤에서 저장만 하고, 판독은 미션 뒤 결과 화면에서(2026-10-30)
5. RC Pro의 **하드웨어 일시정지 버튼**(또는 화면 경로 알약의 **[Ⅱ 수동 비행]**)을 누르면 수동 조종으로 전환하고, C1 또는 화면의 큰 **[재개]** 버튼을 누르면 이어서 비행합니다. 화면은 DJI Pilot 2와 같게 만듭니다(전체 화면, 768×432dp).

자세한 내용은 `docs/DESIGN.md`(목차와 요구사항)와 `docs/design/`(장별 상세 설계)를 참고하세요. 장 번호(예: 6.4)는 전체에서 유일하므로 번호로 찾습니다. `:app`에는 프로젝트 목록·가져오기 화면, 지도 화면과, DJI SDK·TFLite 로드를 확인하는 검증 화면(`MainActivity`의 "연동 검증")이 있고, 도메인 로직은 `:core`에 있습니다.

## 핵심 제약 사항

- **데이터 주권**: 비행, 영상, 위치 데이터가 외부(특히 중국) 서버로 나가면 안 됩니다. 중국계 지도·SDK(AMap, Baidu 등)와 외부 분석·크래시 SDK(Firebase 등)는 사용하지 않습니다. DJI SDK는 **LDM(Local Data Mode)** 을 켜고 사용합니다.
- **오프라인 우선**: 현장에서는 인터넷 없이 동작해야 합니다. 지도는 MBTiles로 미리 내려받습니다.
- **대상 기체**: 1차는 Mavic 3 Enterprise 계열만 지원합니다(Matrice 4E/4T는 추후 확장). 조종 장치는 RC Pro Enterprise(가로 1920×1080)입니다.
- **개발 장비**: M3E 기본 구성에 RC Pro Enterprise가 포함되어 있습니다. 기체가 없어도 지도, 경로 계획, 상태머신은 에뮬레이터와 `FakeDroneGateway`로 개발할 수 있습니다. 실제 기체가 필요한 작업은 PoC와 비행 연동 단계입니다(`docs/design/07-test-roadmap.md` 13.1).
- **탐지 모델**: 사용자가 학습한 설비 탐지 모델(YOLO11l, COS·LP·SUS·LA, 2026-11-04)이 있습니다. 원본 가중치는 `models_src/`(AGPL·사내 데이터라 저장소 제외), 변환은 `tools/export_facility_model.py`(fp16 tflite 640·960·1280, `build/models/`)입니다. 설비는 이상이 아니라 표시만 합니다(`alertLabels: []`, 회색 상자). 탐지는 두 단계입니다(9.6): 비행 중 최근 1장(640+가운데 50%, `detect/LiveDetection`), 착륙 뒤 정밀(1280, `detect/PreciseDetection`, `InspectionApp` 수명). 앱은 `docs/design/05-detection.md` 9.1의 모델 규격(model.tflite + labels.txt + model.json, `role`·`displayNames`)만 따르고, 모델은 교체할 수 있게 설계합니다.

## 참고 자료

- **DJI Mobile SDK V5 공식 샘플**: https://github.com/dji-sdk/Mobile-SDK-Android-V5
  - DJI SDK 관련 코드를 작성할 때는 이 저장소의 샘플 코드(`SampleCode-V5/`)를 먼저 참고합니다. API 사용법, 초기화 순서(`Application` 클래스, `Helper.install`), 권한, 매니페스트 설정을 공식 방식에 맞춥니다.
  - 사용 중인 SDK 버전(5.18.0)과 샘플 저장소의 버전이 일치하는지 확인합니다. 차이가 있으면 해당 버전의 태그/릴리스를 기준으로 합니다.
  - 주요 API: `WPMZManager`(KMZ 생성), `WaypointMissionManager`(업로드, 시작, 정지), `MediaDataCenter`(사진 다운로드), `CameraStreamManager`(영상 프레임), `LDMManager`(로컬 데이터 모드), `SimulatorManager`(시뮬레이터)

## 기술 스택

- 언어: Kotlin 2.2.10, Java 17 (`compileOptions`)
- UI: Jetpack Compose (Compose BOM 2026.02.01, Material3)
- 빌드: Gradle Kotlin DSL, AGP 9.4.1, 버전 카탈로그(`gradle/libs.versions.toml`)
- SDK: `compileSdk`/`targetSdk` 35, `minSdk` 24
- 현재 의존성: `com.dji:dji-sdk-v5-aircraft:5.18.0`(+ `-provided`는 `compileOnly`), `org.tensorflow:tensorflow-lite:2.17.0`, `tensorflow-lite-gpu`, `tensorflow-lite-support:0.4.4`
- `:core`: `kotlinx-coroutines-core` 1.10.2(테스트는 `kotlinx-coroutines-test`), Kotlin JVM 플러그인, JVM 17
- Room 2.8.5 + KSP 2.3.12(`:app`, `ksp(libs.androidx.room.compiler)`)
- MapLibre Native Android 13.6.1(`android-sdk-opengl`, Vulkan 기본 패키지 아님)
- DataStore Preferences 1.1.7(⑥ 설정, `data/SettingsStore.kt`, 3.4). Hilt는 1차에서 제외하고 수동 DI를 씁니다.

## 저장소와 push

- 원격: https://github.com/kiieie/dl_dji (공개). 새 컴퓨터 준비 절차는 `README.md`의 "다른 컴퓨터에서 쓰는 법"에 있습니다.
- push는 SSH 배포 키로 합니다(`~/.ssh/config`의 호스트 별칭 `github-dl_dji`, 원격 `git@github-dl_dji:kiieie/dl_dji.git`).
- **`main`만 push합니다.** 로컬 `feat/room-import-screen`과 `master`는 이력에 DJI API 키가 남아 있으므로 절대 push하지 않습니다.

## 자주 쓰는 명령

터미널에는 Java가 없으므로 Android Studio의 JBR을 씁니다: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`(macOS), `C:\Program Files\Android\Android Studio\jbr`(Windows, `gradlew.bat`). Windows Git Bash에서 `adb push`·`adb shell`에 `/sdcard/...` 경로를 쓸 때는 `MSYS_NO_PATHCONV=1`을 붙입니다.

```bash
./gradlew :core:test              # 도메인 단위 테스트 (기체·에뮬레이터 불필요)
./gradlew assembleDebug           # 디버그 APK 빌드
./gradlew installDebug            # 연결된 기기에 설치
./gradlew test                    # 로컬 단위 테스트 (app/src/test)
./gradlew connectedAndroidTest    # 기기 계측 테스트 (app/src/androidTest)
./gradlew lint                    # Android Lint
```

## 디렉터리 구조 (현재)

```
app/src/main/
├── AndroidManifest.xml          # DJI API 키 자리표시자(meta-data, 값은 secrets/dji.properties)
├── java/com/example/myfirstapp/
│   ├── InspectionApp.kt         # Application, 수동 DI(DB 생성)
│   ├── MainActivity.kt          # 화면 이동(Navigation Compose) + 연동 검증 화면(DJI/TFLite)
│   ├── data/AppDatabase.kt      # Room DB(4.2 엔티티, TELEMETRY_SAMPLE 제외)와 ProjectDao
│   ├── data/RoomRecorder.kt     # 실행 기록 포트(InspectionRecorder)의 Room 구현
│   ├── ui/ProjectScreen.kt      # ① 프로젝트 목록 + ② CSV 가져오기(SAF), 로직은 ProjectViewModel
│   ├── ui/mission/              # ①-1 미션 목록과 이어하기(MissionListViewModel + MissionListScreen)
│   ├── ui/MapScreen.kt          # ③ 계획, 로직은 PlanViewModel(MapLibre, MBTiles, 전주 개별 점, 영역 그리기, 파라미터, 전주 제외, 경로 미리보기, 계획 저장)
│   ├── ui/flight/               # ④ 비행과 소티 완료 화면(FlightViewModel + FlightScreen + FlightMap 미니맵, 모의 기체)
│   ├── drone/DjiRcButtons.kt    # RC Pro 버튼(일시정지, C1, C2, RTH, 5D)을 DJI KeyManager로 받기
│   ├── ui/settings/             # ⑥ 설정(SettingsViewModel + SettingsScreen): 비행 기본값, 모델·지도 관리
│   ├── data/SettingsStore.kt    # 설정 값(DataStore): 비행 기본값 JSON, 비행 중·정밀 모델
│   ├── data/AppFiles.kt         # 앱 폴더의 지도(maps/)·모델(models/) 파일 관리
│   ├── data/CesiumImagery.kt    # Cesium ion(Bing Aerial) 온라인 위성 타일 주소 받기(7.4 임시)
│   ├── sound/ToneNotifier.kt    # 알림음(신호음, 11장)
│   ├── data/FakePhotos.kt       # 모의 기체 사진
│   ├── ui/result/               # ⑤ 결과와 1차 판독(ResultViewModel + ResultScreen, MVVM·UDF)
│   ├── data/MissionExport.kt    # 미션 결과 ZIP(result.csv + mission.json + images/)
│   ├── detect/TfliteYoloDetector.kt # YOLO TFLite 탐지기(9.1 모델 폴더 검사, GPU delegate·CPU, 축소 디코딩, 단계별 시간)
│   ├── detect/                  # ModelSpec(model.json), LiveDetection(비행 중 1장), PreciseDetection(착륙 뒤), ModelBench(속도 측정)
│   └── ui/theme/                # Compose 테마 (Color, Theme, Type)
├── keepRules/rules.keep         # R8 keep 규칙 (AGP 9 방식)
└── res/
core/src/                        # 순수 Kotlin(JVM) 도메인 모듈
├── main/kotlin/com/example/myfirstapp/core/
│   ├── model/  port/  csv/      # 도메인 모델, 포트 인터페이스, CSV 파서
│   ├── route/                   # 투영, 다각형, TSP, 소티 분할, 에너지 모델
│   ├── executor/                # InspectionExecutor 상태머신, 비행 전 점검 판정
│   ├── input/                   # FiveDGate (5D 버튼 겹침 걸러 내기)
│   ├── detect/                  # YoloDecoder (YOLO 출력 해석, NMS), TwoStage (최근 1장·정밀 순서·모델 고르기, 9.6)
│   └── fake/                    # FakeDroneGateway (모의 기체), FakeDetector (모의 탐지기)
└── test/                        # JUnit + kotlinx-coroutines-test
tools/make_test_data.py          # 시험용 전주 CSV와 합성 MBTiles 생성(python3 + Pillow)
tools/export_coco_model.sh       # 개발용 COCO yolo11n 모델 폴더 생성(build/models/)
tools/yolo_ref.py                # 같은 모델을 PC에서 돌린 탐지 기준값(스모크 대조용)
secrets/                         # git 제외: DJI LDM 라이선스, VWorld 인증키, DJI API 키(dji.properties), Cesium ion 토큰(cesium.properties)
.design-sync/                    # Claude Design용 토큰 묶음 생성(build.mjs, conventions.md). 출력 ds-bundle/은 git 제외
README.md                        # 프로젝트 소개, 다른 컴퓨터에서 빌드·실행하는 법
docs/
├── evidence/                    # 작업별 증빙(스크린샷, README, 스모크 스크립트). reports/는 git 제외
├── DESIGN.md                    # 설계서 목차, 개요, 요구사항, 확정·미정 사항
└── design/                      # 장별 상세 설계 (01-architecture ~ 07-test-roadmap)
```

모듈은 1차에 `:app` + `:core`(JVM) + `:drone:dji` 세 개로 시작합니다. 세부 목표 구조는 `docs/design/01-architecture.md` 3.3을 참고하세요.

## 빌드 설정 주의 사항

- **ARM 실기기 필요**: `abiFilters`가 `armeabi-v7a`, `arm64-v8a`로 고정되어 있습니다. DJI SDK는 x86_64 에뮬레이터에서 동작하지 않습니다. 에뮬레이터 개발은 `FakeDroneGateway`(설계)로 합니다.
- **네이티브 라이브러리 충돌 방지**: `packaging`의 `pickFirsts`(`libc++_shared.so`, `libcrypto.so`)와 `useLegacyPackaging = true`는 DJI SDK 연동에 필요하므로 지우지 마세요.
- **TensorFlow 중복 제외**: DJI SDK와 TFLite 보조 라이브러리에서 `org.tensorflow` 그룹을 `exclude`하여 버전 충돌을 막고 있습니다. 의존성을 추가할 때 이 패턴을 유지하세요.
- **Release 빌드 난독화 비활성화**: `optimization { enable = false }`. 활성화할 경우 DJI/TFLite 클래스에 대한 keep 규칙을 `keepRules/rules.keep`에 먼저 추가해야 합니다.
- **DJI API 키**: `secrets/dji.properties`(git 제외)의 `DJI_API_KEY`를 `app/build.gradle.kts`가 읽어 매니페스트 자리표시자 `${DJI_API_KEY}`에 넣습니다. 파일이 없으면 빈 키로 빌드되고 SDK 등록만 실패합니다. 키는 `applicationId`(`kr.co.dl_dji`)에 묶여 있으므로(코드 패키지 `namespace`는 `com.example.myfirstapp` 그대로) 패키지명을 바꾸면 키도 새로 발급해야 합니다.

## 코드 작성 규칙 (구현 단계부터 적용)

- 문서, 주석, UI 문자열은 한국어로 작성합니다.
- 문서 한 파일은 200줄을 넘기지 않습니다. 넘으면 주제별로 분리하고 `docs/DESIGN.md`의 목차 표를 갱신합니다(장 번호는 유지).
- Kotlin 공식 코드 스타일(`kotlin.code.style=official`)을 따릅니다.
- 도메인 로직(경로 계획, 상태머신)은 Android와 DJI에 의존하지 않는 JVM 모듈에 두고 단위 테스트를 작성합니다.
- DJI SDK 호출은 `DroneGateway` 인터페이스 뒤에만 둡니다.
- YOLO 탐지는 Kotlin + TFLite API로 구현하고 `Detector` 인터페이스 뒤에 둡니다. C++(NDK)는 PoC에서 측정해 목표 시간을 넘는 단계에만 적용합니다(`docs/design/05-detection.md` 9.4).
- 의존성 버전은 `libs.versions.toml`에 추가하고 `libs.*`로 참조합니다.
- 조정값은 코드에 숫자로 박지 않습니다(2026-10-31 사용자 지정): 현장 조정값은 `:core` `core/config/Tuning.kt`, 앱 설정값(시간 제한·주기·캐시·점검 기준·신호음·탐지)은 `:app` `AppConfig.kt`, 화면 크기·글자·색은 `ui/theme/Dimens.kt`·`Type.kt`(`FontSize`)·`Color.kt`, 전주 표지(고/저압·설비·지선주·근입) 규칙은 `core/model/PoleCodes.kt`(전문가 확인 대기). 2~8dp 여백과 한 번만 쓰는 배치 숫자는 그대로 둡니다.
- 외부 네트워크로 데이터를 보내는 코드나 라이브러리를 추가하지 않습니다.

## Android 개발 규칙 (2026-09-29 사용자 지정)

- **아키텍처**: MVVM + Clean Architecture(`docs/design/01-architecture.md` 3.2). 화면마다 `ViewModel`이 `StateFlow<UiState>`를 내보내고, 화면은 이벤트를 `ViewModel` 함수로 올려 보내는 단방향 데이터 흐름(UDF)을 따릅니다.
- **금지**: Compose UI 안에 비즈니스 로직(DB 조회·저장, 실행기 조립, 파일 내보내기, 판정)을 두지 않습니다. `LiveData`는 쓰지 않습니다.
- **디자인**: Material Design 3를 엄격히 따르고, 톤앤매너는 DJI 조종 앱 방식(어두운 바탕, 흰 글자, 파랑 강조)을 씁니다. 세부 규칙은 `docs/design/06-ui-sound.md` 10.1입니다. `/design-sync`로 디자인 시스템을 연결하면 그것을 기준으로 합니다.

## 알려진 이슈 (현재 검증 코드)

- 연동 검증 화면(`MainActivity`의 `VerificationPanel`)은 PoC용이라 `ViewModel` 없이 Compose 상태를 씁니다. 지도 위 전주·경로 색은 데이터 시각화 색으로 직접 씁니다(10.1).
- DJI SDK 초기화·등록(`init` → `INITIALIZE_COMPLETE` → `registerApp`)은 `InspectionApp`이 앱 시작 때 합니다(2026-10-29). LDM은 라이선스 로드 → `enableLDM` 순서로 켜고(7.2), 꺼져 있으면 비행 시작을 막습니다(2026-10-31). RC 버튼은 `drone/DjiRcButtons.kt`(포트 `core/port/RcButtons.kt`)가 SDK 등록 뒤 받아 비행 화면에 매핑합니다(일시정지, C1 재개, RTH는 모의만). 검증 화면은 SDK 상태와 버튼 누름(로그 태그 `RcKey`)만 보여 줍니다.
- `:app`의 테스트는 Room 계측 테스트 6개(`ProjectDaoTest`, 에뮬레이터 필요)와 템플릿 예제뿐입니다(`:core`는 단위 테스트 약 130개).
