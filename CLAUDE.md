# CLAUDE.md

이 파일은 Claude Code가 이 저장소에서 작업할 때 참고하는 안내서입니다.

## 현재 단계: 구현 — 기체 없이 가능한 ⑤ 결과 화면까지 완료 (M3E 기체 없음)

- 설계서 v1.0이 **승인**되었습니다(2026-09-29, 현재 v1.3). 구현은 `docs/design/07-test-roadmap.md` 13장의 단계 순서를 따릅니다.
- **M3E 기체가 아직 없습니다.** 2~3단계(기반, 계획)를 `FakeDroneGateway`로 진행하고, 기체를 확보하면 1단계 PoC를 먼저 수행합니다. 기체가 필요한 항목(6.1 방식 B, 6.4 하드웨어 버튼 재개, LDM 오프라인 등록 등)은 PoC 전까지 가정으로만 둡니다.
- 1차 MVP 범위는 `docs/DESIGN.md` 2.4를 따르고, 범위 밖 기능은 만들지 않습니다.
- 설계와 다른 구현이 필요하면 코드를 바꾸기 전에 설계서를 먼저 수정합니다.
- 진행 현황은 `docs/design/07-test-roadmap.md` 13.0에 기록합니다. `:core`(모델, 포트, CSV, 경로 계획, 상태머신, `FakeDroneGateway`)와 Room(`PROJECT`, `POLE`), 가져오기 화면, 오프라인 지도(MBTiles 표시), 계획 화면(영역 그리기, 경로 미리보기, 핵심 파라미터 4개, 전주 개별 포함/제외, 계획 저장 `MISSION`·`SORTIE`·`VISIT`)과 ④ 비행 화면(`FakeDroneGateway`로 저장한 계획 실행, 실행 기록 저장, 신호음 알림), ⑤ 결과 화면(전주 목록, 이상만 필터, 사진과 탐지 박스, ZIP 내보내기), AI 탐지 연결(TFLite YOLO 탐지기, 앱 폴더 `models/`의 모델, 없으면 `FakeDetector`)은 완료했습니다. 전력 설비 탐지 모델은 아직 없고 개발용 COCO 모델(`tools/export_coco_model.sh`, AGPL이라 저장소 제외)로 검증합니다. 진행 순서: ① 결과 화면 `ViewModel` 전환과 1차 판독(F-19) ② 비행 화면 `ViewModel` 전환과 소티 완료 화면 ③ 미션 목록과 이어하기(F-20) ④ 기체 확보 뒤 DJI UX SDK FPV. 탐지는 객체 검출(조류 둥지, 이물질)만 하고, 2차 판독은 다른 플랫폼에서 합니다. VWorld 타일 내려받기는 약관 확인 전까지 보류합니다.

## 프로젝트 개요

**배전선로 전주 점검 드론 앱**입니다. DJI RC Pro Enterprise에 설치해 Mavic 3 Enterprise(M3E/M3T)를 운용합니다.

1. 전주 GPS 좌표(CSV/Excel, WGS84)를 가져옵니다.
2. 오프라인 지도(VWorld + MapLibre)에서 점검 영역(사각형/다각형)을 그립니다.
3. 영역 안의 전주를 배터리 소모가 가장 적은 순서로 방문하는 경로를 만들고, 배터리가 부족하면 여러 소티로 나눕니다.
4. 자동 비행: 전주 도착 → 촬영 → 이미지 다운로드 → YOLO(TFLite) 분석 → 알림음 → 5초 대기 → 다음 전주
5. RC Pro의 **하드웨어 일시정지 버튼**을 누르면 수동 조종으로 전환하고, 화면의 큰 **[재개]** 버튼을 누르면 다음 미점검 전주로 이동합니다.

자세한 내용은 `docs/DESIGN.md`(목차와 요구사항)와 `docs/design/`(장별 상세 설계)를 참고하세요. 장 번호(예: 6.4)는 전체에서 유일하므로 번호로 찾습니다. `:app`에는 프로젝트 목록·가져오기 화면, 지도 화면과, DJI SDK·TFLite 로드를 확인하는 검증 화면(`MainActivity`의 "연동 검증")이 있고, 도메인 로직은 `:core`에 있습니다.

## 핵심 제약 사항

- **데이터 주권**: 비행, 영상, 위치 데이터가 외부(특히 중국) 서버로 나가면 안 됩니다. 중국계 지도·SDK(AMap, Baidu 등)와 외부 분석·크래시 SDK(Firebase 등)는 사용하지 않습니다. DJI SDK는 **LDM(Local Data Mode)** 을 켜고 사용합니다.
- **오프라인 우선**: 현장에서는 인터넷 없이 동작해야 합니다. 지도는 MBTiles로 미리 내려받습니다.
- **대상 기체**: 1차는 Mavic 3 Enterprise 계열만 지원합니다(Matrice 4E/4T는 추후 확장). 조종 장치는 RC Pro Enterprise(가로 1920×1080)입니다.
- **개발 장비**: M3E 기본 구성에 RC Pro Enterprise가 포함되어 있습니다. 기체가 없어도 지도, 경로 계획, 상태머신은 에뮬레이터와 `FakeDroneGateway`로 개발할 수 있습니다. 실제 기체가 필요한 작업은 PoC와 비행 연동 단계입니다(`docs/design/07-test-roadmap.md` 13.1).
- **탐지 모델**: 아직 없습니다. 앱은 `docs/design/05-detection.md` 9.1의 모델 규격(model.tflite + labels.txt + model.json)만 따르고, 모델은 교체할 수 있게 설계합니다.

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
- 설계상 추가 예정: DataStore (`docs/design/01-architecture.md` 3.4). Hilt는 1차에서 제외하고 수동 DI를 씁니다.

## 자주 쓰는 명령

터미널에는 Java가 없으므로 Android Studio의 JBR을 씁니다: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

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
├── AndroidManifest.xml          # DJI API 키(meta-data) 포함
├── java/com/example/myfirstapp/
│   ├── InspectionApp.kt         # Application, 수동 DI(DB 생성)
│   ├── MainActivity.kt          # 프로젝트 화면 진입 + 연동 검증 화면(DJI/TFLite)
│   ├── data/AppDatabase.kt      # Room DB(4.2 엔티티, TELEMETRY_SAMPLE 제외)와 ProjectDao
│   ├── data/RoomRecorder.kt     # 실행 기록 포트(InspectionRecorder)의 Room 구현
│   ├── ui/ProjectScreen.kt      # ① 프로젝트 목록 + ② CSV 가져오기(SAF)
│   ├── ui/MapScreen.kt          # ③ 계획(MapLibre, MBTiles, 전주 개별 점, 영역 그리기, 파라미터, 전주 제외, 경로 미리보기, 계획 저장)
│   ├── ui/FlightScreen.kt       # ④ 비행(모의 기체, 상태머신 실행, 신호음 알림)
│   ├── ui/ResultScreen.kt       # ⑤ 결과(전주 목록, 이상만 필터, 사진과 탐지 박스, 내보내기)
│   ├── data/MissionExport.kt    # 미션 결과 ZIP(result.csv + mission.json + images/)
│   ├── detect/TfliteYoloDetector.kt # YOLO TFLite 탐지기(9.1 모델 폴더 검사, 레터박스, 추론)
│   └── ui/theme/                # Compose 테마 (Color, Theme, Type)
├── keepRules/rules.keep         # R8 keep 규칙 (AGP 9 방식)
└── res/
core/src/                        # 순수 Kotlin(JVM) 도메인 모듈
├── main/kotlin/com/example/myfirstapp/core/
│   ├── model/  port/  csv/      # 도메인 모델, 포트 인터페이스, CSV 파서
│   ├── route/                   # 투영, 다각형, TSP, 소티 분할, 에너지 모델
│   ├── executor/                # InspectionExecutor 상태머신, 비행 전 점검 판정
│   ├── detect/                  # YoloDecoder (YOLO 출력 해석, NMS)
│   └── fake/                    # FakeDroneGateway (모의 기체), FakeDetector (모의 탐지기)
└── test/                        # JUnit + kotlinx-coroutines-test
tools/make_test_data.py          # 시험용 전주 CSV와 합성 MBTiles 생성(python3 + Pillow)
tools/export_coco_model.sh       # 개발용 COCO yolo11n 모델 폴더 생성(build/models/)
tools/yolo_ref.py                # 같은 모델을 PC에서 돌린 탐지 기준값(스모크 대조용)
secrets/                         # git 제외: DJI LDM 라이선스, VWorld 인증키
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
- **DJI API 키**: `AndroidManifest.xml`의 `com.dji.sdk.API_KEY`에 하드코딩되어 있습니다. 키는 `applicationId`(`com.example.myfirstapp`)에 묶여 있으므로 패키지명을 바꾸면 키도 새로 발급해야 합니다.

## 코드 작성 규칙 (구현 단계부터 적용)

- 문서, 주석, UI 문자열은 한국어로 작성합니다.
- 문서 한 파일은 200줄을 넘기지 않습니다. 넘으면 주제별로 분리하고 `docs/DESIGN.md`의 목차 표를 갱신합니다(장 번호는 유지).
- Kotlin 공식 코드 스타일(`kotlin.code.style=official`)을 따릅니다.
- 도메인 로직(경로 계획, 상태머신)은 Android와 DJI에 의존하지 않는 JVM 모듈에 두고 단위 테스트를 작성합니다.
- DJI SDK 호출은 `DroneGateway` 인터페이스 뒤에만 둡니다.
- YOLO 탐지는 Kotlin + TFLite API로 구현하고 `Detector` 인터페이스 뒤에 둡니다. C++(NDK)는 PoC에서 측정해 목표 시간을 넘는 단계에만 적용합니다(`docs/design/05-detection.md` 9.4).
- 의존성 버전은 `libs.versions.toml`에 추가하고 `libs.*`로 참조합니다.
- 외부 네트워크로 데이터를 보내는 코드나 라이브러리를 추가하지 않습니다.

## Android 개발 규칙 (2026-09-29 사용자 지정)

- **아키텍처**: MVVM + Clean Architecture(`docs/design/01-architecture.md` 3.2). 화면마다 `ViewModel`이 `StateFlow<UiState>`를 내보내고, 화면은 이벤트를 `ViewModel` 함수로 올려 보내는 단방향 데이터 흐름(UDF)을 따릅니다.
- **금지**: Compose UI 안에 비즈니스 로직(DB 조회·저장, 실행기 조립, 파일 내보내기, 판정)을 두지 않습니다. `LiveData`는 쓰지 않습니다.
- **디자인**: Material Design 3를 엄격히 따르고, 톤앤매너는 DJI 조종 앱 방식(어두운 바탕, 흰 글자, 파랑 강조)을 씁니다. 세부 규칙은 `docs/design/06-ui-sound.md` 10.1입니다. `/design-sync`로 디자인 시스템을 연결하면 그것을 기준으로 합니다.

## 알려진 이슈 (현재 검증 코드)

- 기존 화면(`ProjectScreen`, `MapScreen`, `FlightScreen`, `ResultScreen`)은 DB 조회, 실행기 조립, 내보내기를 Compose 안에서 하고 색 값을 직접 씁니다. Android 개발 규칙에 맞게 화면별 `ViewModel`로 옮겨야 합니다.

- DJI SDK 초기화가 `Application`(`InspectionApp`)이 아니라 Activity(검증 화면)에서 이루어지고, LDM이 적용되어 있지 않습니다.
- `SDKManagerCallback` 콜백에서 Compose 상태를 직접 갱신합니다(스레드 안전성 확인 필요).
- `:app`의 테스트는 Room 계측 테스트 6개(`ProjectDaoTest`, 에뮬레이터 필요)와 템플릿 예제뿐입니다(`:core`는 단위 테스트 44개).
- TFLite·DJI 의존성이 `libs.*`가 아니라 문자열 좌표로 선언되어 있습니다(코드 규칙 위반, 정리 필요).
