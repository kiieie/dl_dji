# 배전선로 전주 점검 드론 앱

DJI RC Pro Enterprise 조종기에 설치해 Mavic 3 Enterprise(M3E/M3T)로 배전선로 전주를 자동 점검하는 안드로이드 앱입니다.
전주 좌표를 가져와 점검 영역을 그리면, 배터리를 가장 적게 쓰는 순서로 경로를 만들고 여러 소티로 나눠 자동 비행합니다.
전주마다 사진을 찍어 기기 안에서 YOLO(TFLite)로 객체를 검출하고, 검출되면 알림음을 냅니다.

> **현재 상태**: 기체(M3E)를 아직 확보하지 않아 모의 기체(`FakeDroneGateway`)로 개발하고 있습니다.
> 실제 기체 연동(DJI SDK 비행, LDM, UX SDK 영상)은 기체 확보 뒤 PoC 단계에서 진행합니다.
> 진행 현황은 [docs/design/07-test-roadmap.md](docs/design/07-test-roadmap.md) 13.0에 있습니다.

## 목차

1. [주요 기능](#주요-기능)
2. [화면 흐름](#화면-흐름)
3. [핵심 제약](#핵심-제약)
4. [기술 스택](#기술-스택)
5. [저장소 구조](#저장소-구조)
6. [다른 컴퓨터에서 쓰는 법](#다른-컴퓨터에서-쓰는-법)
7. [앱에 데이터 넣기](#앱에-데이터-넣기)
8. [시험](#시험)
9. [코드 규칙](#코드-규칙)
10. [문서](#문서)

## 주요 기능

| 기능 | 내용 | 상태 |
|---|---|---|
| 전주 가져오기 | CSV(UTF-8, CP949 자동 판별). 열: 전주번호, 위도, 경도, 선로명, 비고(WGS84) | 완료 |
| 오프라인 지도 | MapLibre + MBTiles(앱 폴더 `maps/`). 인터넷 없이 표시 | 완료 |
| 점검 계획 | 다각형 영역, 이륙 지점, 고도·속도·예비율, 전주 개별 제외, 경로·소티 미리보기, 계획 저장 | 완료 |
| 경로 계산 | 에너지 모델 기반 TSP, 배터리·가시권(VLOS) 기준 소티 분할, 도달 불가 전주 표시 | 완료 |
| 자동 비행 | 전주 도착 → 촬영 → 다운로드 → 검출 → 알림음 → 5초 대기 → 다음 전주. 일시정지·재개·건너뛰기·RTH | 모의 기체로 완료 |
| 객체 검출 | YOLO TFLite(모델 교체형, 9.1 규격). 대상: 조류 둥지, 이물질. 모델이 없으면 모의 탐지기 | 완료(개발용 COCO 모델로 검증) |
| 2인 운용 | 소티 완료 화면: 조종사는 배터리·기체 점검, 보조자는 1차 판독 | 완료 |
| 결과와 1차 판독 | 필터(검출·미판독·재촬영), 판정(검출 맞음·검출 없음·재촬영), 대상 칩, 메모, ZIP 내보내기(CSV + JSON + 사진) | 완료 |
| 이어하기 | 남은 전주로 다음 소티. 날짜가 바뀌면 [이어서 비행] / [남은 전주로 새 계획]을 묻기 | 완료 |
| 실제 기체 연동 | DJI MSDK V5 웨이포인트, 사진 다운로드, LDM, UX SDK FPV | 기체 확보 뒤 |

2차 판독(이상 여부 판단)은 내보낸 ZIP으로 다른 플랫폼에서 합니다. 앱은 객체 검출까지만 합니다.

## 화면 흐름

```
① 프로젝트 목록 + ② CSV 가져오기
      │ 프로젝트 선택
      ▼
①-1 미션 목록 ──[+ 새 계획]──▶ ③ 계획(지도, 영역, 경로, 계획 저장)
      │ [비행 시작] / [이어하기]                 │ [비행 시작]
      ▼                                         ▼
④ 비행(조종사) ──소티 끝──▶ 소티 완료 ──[1차 판독 시작]──▶ ⑤ 결과·1차 판독
      ▲                       │ [다음 소티 시작]
      └───────────────────────┘
```

## 핵심 제약

- **데이터 주권**: 비행·영상·위치 데이터가 외부 서버로 나가면 안 됩니다. 중국계 지도·SDK와 외부 분석·크래시 SDK(Firebase 등)는 쓰지 않습니다. DJI SDK는 LDM(Local Data Mode)으로 씁니다.
- **오프라인 우선**: 현장에는 인터넷이 없습니다. 지도는 MBTiles로 미리 넣습니다.
- **비밀 정보는 저장소에 넣지 않습니다**: DJI API 키, DJI LDM 라이선스, VWorld 인증키는 `secrets/`(git 제외)에만 둡니다.
- **탐지 모델은 저장소에 넣지 않습니다**: 개발용 COCO 모델(Ultralytics yolo11n)은 AGPL-3.0이라 저장소와 배포 앱에 넣지 않습니다. 각 컴퓨터에서 스크립트로 만듭니다.

## 기술 스택

| 분류 | 사용 기술 |
|---|---|
| 언어·빌드 | Kotlin 2.2.10, Java 17, Gradle 9.7(래퍼), AGP 9.4.1, 버전 카탈로그 `gradle/libs.versions.toml` |
| UI | Jetpack Compose(BOM 2026.02.01), Material Design 3(다크 테마 하나, DJI 조종 앱 톤), Navigation Compose 2.9 |
| 구조 | MVVM + Clean Architecture, `StateFlow` 단방향 데이터 흐름, 수동 DI |
| 데이터 | Room 2.8.5(KSP) |
| 지도 | MapLibre Native Android 13.6.1(OpenGL) + MBTiles |
| 드론 | DJI Mobile SDK V5 5.18.0 |
| 탐지 | TensorFlow Lite 2.17(CPU 4스레드) |
| SDK 수준 | `compileSdk`/`targetSdk` 35, `minSdk` 24, ABI `armeabi-v7a`·`arm64-v8a`만 |

## 저장소 구조

```
app/                    안드로이드 앱(:app)
  src/main/java/com/example/myfirstapp/
    MainActivity.kt     화면 이동(Navigation Compose)과 연동 검증 화면
    data/               Room DB, 실행 기록, 결과 내보내기, 모의 사진
    detect/             TFLite YOLO 탐지기
    sound/              알림음
    ui/                 화면별 ViewModel + Compose 화면(프로젝트, 미션, 계획, 비행, 결과), 테마
core/                   순수 Kotlin(JVM) 도메인 모듈(:core): 모델, 포트, CSV, 경로 계획, 상태머신, 모의 기체·탐지기
tools/
  make_test_data.py     시험용 전주 CSV와 합성 MBTiles 생성
  export_coco_model.sh  개발용 COCO yolo11n TFLite 모델 폴더 생성(AGPL, 저장소 제외)
  yolo_ref.py           같은 모델을 PC에서 돌린 검출 기준값(스모크 대조용)
docs/
  DESIGN.md             설계서 목차와 요구사항
  design/               장별 상세 설계(01-architecture ~ 07-test-roadmap)
  evidence/             작업별 증빙(스크린샷, README, 다시 돌릴 수 있는 스모크 스크립트)
.design-sync/           Claude Design용 토큰 묶음 생성 스크립트와 사용 규칙
secrets/                (git 제외) 비밀 정보. 아래 "비밀 파일 만들기" 참고
CLAUDE.md               Claude Code 작업 안내서(규칙, 명령, 알려진 이슈)
```

## 다른 컴퓨터에서 쓰는 법

### 1. 준비물

| 항목 | 내용 |
|---|---|
| Android Studio | 최신 안정판. 들어 있는 JBR(Java 17 이상)로 Gradle을 돌립니다 |
| Android SDK | Platform 35, Build-Tools, Platform-Tools(`adb`). Android Studio SDK Manager에서 설치 |
| 에뮬레이터(선택) | **ARM64 시스템 이미지**가 필요합니다(앱이 ARM ABI만 포함). Apple Silicon Mac에서 Pixel 8(API 35, 2400×1080) 이미지를 씁니다. 인텔·AMD PC의 x86_64 에뮬레이터에서는 설치되지 않으니 실제 ARM 기기를 쓰세요 |
| 실제 기기(선택) | DJI RC Pro Enterprise 또는 안드로이드 ARM 기기(USB 디버깅 켬) |
| Python 3(선택) | 시험 데이터·모델 생성, 스모크 시험용. `pip install pillow` |
| Git + SSH | 저장소 받기·올리기 |

### 2. 저장소 받기

공개 저장소라 받기만 할 때는 로그인이 필요 없습니다.

```bash
git clone https://github.com/kiieie/dl_dji.git
cd dl_dji
```

**올리기(push)도 할 컴퓨터**는 SSH 배포 키를 씁니다. GitHub 계정 로그인이 안 되는 컴퓨터(예: SSH로만 접속하는 Mac mini)에서도 됩니다.

```bash
# 1) 이 컴퓨터 전용 키 만들기(비밀번호 없이)
ssh-keygen -t ed25519 -N "" -C "<컴퓨터 이름> dl_dji deploy" -f ~/.ssh/dl_dji_deploy

# 2) ~/.ssh/config에 별칭 추가
cat >> ~/.ssh/config <<'EOF'

Host github-dl_dji
  HostName github.com
  User git
  IdentityFile ~/.ssh/dl_dji_deploy
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config

# 3) 공개 키 출력 → 다른 기기의 브라우저에서 등록
cat ~/.ssh/dl_dji_deploy.pub
```

브라우저에서 https://github.com/kiieie/dl_dji/settings/keys → **Add deploy key** → 공개 키를 붙여 넣고 **Allow write access**를 체크한 뒤 저장합니다. 그다음:

```bash
git remote set-url origin git@github-dl_dji:kiieie/dl_dji.git
ssh -T git@github-dl_dji      # "successfully authenticated"가 나오면 성공
git push
```

배포 키는 이 저장소에만 쓸 수 있습니다. 컴퓨터를 더 이상 쓰지 않으면 같은 화면에서 그 키를 지우세요.

### 3. 비밀 파일 만들기

`secrets/`는 git에 올라가지 않으므로 컴퓨터마다 직접 만듭니다. 값은 관리자에게 따로(메신저·USB 등) 받으세요. **이 파일들을 커밋하거나 문서·이슈에 붙여 넣지 마세요.**

```bash
mkdir -p secrets
```

| 파일 | 내용 | 쓰는 곳 |
|---|---|---|
| `secrets/dji.properties` | `DJI_API_KEY=<DJI 개발자 사이트에서 받은 앱 키>` | `app/build.gradle.kts`가 읽어 매니페스트 `com.dji.sdk.API_KEY`에 넣음 |
| `secrets/license_pf_Inspection.txt` | DJI LDM 오프라인 라이선스 원문 | 기체 PoC에서 LDM 등록에 사용(예정) |
| `secrets/vworld.properties` | `VWORLD_API_KEY=<VWorld 오픈API 인증키>` | VWorld 타일 내려받기(약관 확인 전까지 보류, 현재 코드에서 쓰지 않음) |

`dji.properties`가 없어도 빌드는 됩니다. 이 경우 Gradle이 `secrets/dji.properties 없음` 경고를 내고, 앱에서 DJI SDK 등록만 실패합니다(모의 기체 기능은 정상). DJI 앱 키는 패키지명 `com.example.myfirstapp`에 묶여 있으므로 패키지명을 바꾸면 키를 새로 받아야 합니다.

### 4. 빌드와 설치

Android Studio로 열면 `local.properties`(SDK 경로)가 자동으로 생깁니다. 터미널만 쓰는 경우 직접 만드세요.

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties        # macOS 기본 위치
# Windows 예: sdk.dir=C\:\\Users\\<사용자>\\AppData\\Local\\Android\\Sdk
```

터미널에 Java가 없으면 Android Studio의 JBR을 씁니다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS
# Windows(PowerShell): $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
```

```bash
./gradlew :core:test        # 도메인 단위 테스트(기기 불필요)
./gradlew assembleDebug     # 디버그 APK: app/build/outputs/apk/debug/
./gradlew installDebug      # 연결된 기기·에뮬레이터에 설치
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 씁니다.

### 5. 처음 실행 확인

1. 앱을 열면 ① 프로젝트 화면이 나옵니다.
2. 아래 "앱에 데이터 넣기"로 시험용 CSV와 지도를 기기에 넣고, [CSV 파일 선택]으로 프로젝트를 만듭니다.
3. 프로젝트 → [+ 새 계획] → 영역 그리기 → 이륙 지점 → [경로 계산] → [계획 저장] → [비행 시작] 순서로 모의 비행을 해 봅니다.
4. 기체 없이 실행하면 비행 화면에 모의 RC 조작 줄(일시정지, 재개, 배터리 감소)이 보입니다.

## 앱에 데이터 넣기

앱 전용 폴더는 `/sdcard/Android/data/com.example.myfirstapp/files/`입니다. 아래 예시는 `adb`가 PATH에 있다고 가정합니다(macOS 기본 위치 `~/Library/Android/sdk/platform-tools/adb`).

### 시험용 전주 CSV와 지도

```bash
python3 tools/make_test_data.py build/testdata 1000     # poles-1000.csv, test-grid.mbtiles 생성
adb push build/testdata/poles-1000.csv /sdcard/Download/
adb push build/testdata/test-grid.mbtiles /sdcard/Download/
```

- CSV: 앱 ② 가져오기의 [CSV 파일 선택]에서 `Download/poles-1000.csv`를 고릅니다.
- 지도: 계획 화면의 [지도 파일 추가]에서 `.mbtiles`를 고르면 앱 폴더 `maps/`로 복사됩니다.
- 시험 지도는 좌표 격자만 그린 합성 타일입니다(실제 지도는 VWorld 약관 확인 뒤).

### 탐지 모델(선택)

모델이 없으면 모의 탐지기가 사진 5장마다 한 번 "조류 둥지"를 검출한 것으로 처리합니다. 실제 TFLite 경로를 시험하려면 개발용 COCO 모델을 만듭니다(약 3분, Python 3.9 이상).

```bash
bash tools/export_coco_model.sh        # build/models/coco-yolo11n-fp16/{model.tflite, labels.txt, model.json}
adb push build/models/coco-yolo11n-fp16 /sdcard/Android/data/com.example.myfirstapp/files/models/
```

- 모델 폴더 규격(9.1): `model.tflite` + `labels.txt` + `model.json`. 앱은 `models/` 아래 규격에 맞는 폴더 중 이름순으로 마지막 것을 씁니다.
- 이 모델은 AGPL-3.0입니다. 저장소에 커밋하거나 배포 앱에 넣지 마세요(`*.tflite`는 git 제외).

### 모의 기체 사진(선택)

앱 폴더 `fake_photos/`에 JPEG을 넣으면 모의 기체가 이름순으로 돌려 가며 "촬영"합니다. 없으면 그려 낸 사진을 씁니다.

```bash
adb push 1-sample.jpg /sdcard/Android/data/com.example.myfirstapp/files/fake_photos/
```

## 시험

| 종류 | 명령 | 필요 조건 |
|---|---|---|
| 도메인 단위 테스트(45개) | `./gradlew :core:test` | 없음 |
| Room 계측 테스트(6개) | `./gradlew connectedAndroidTest` | 기기·에뮬레이터. 끝나면 앱이 지워지므로 다시 `installDebug` |
| 화면 스모크(9개) | `bash docs/evidence/<날짜-작업>/smoke.sh` | Apple Silicon Mac, 에뮬레이터 Pixel 8(2400×1080), 디버그 앱 설치, `python3` + Pillow |
| Lint | `./gradlew lint` | 없음 |

스모크 스크립트는 화면을 눌러 가며 스크린샷과 로그를 증빙 폴더에 남기고, 앱 DB를 꺼내 결과를 대조합니다. `adb` 위치가 다르면 `ADB=/경로/adb bash .../smoke.sh`처럼 지정합니다. 각 폴더의 `README.md`에 시험 조건과 확인 항목이 있습니다. 테스트 보고서(`reports/`)는 git에서 제외됩니다.

각 스크립트는 앱 데이터를 지우고 필요한 데이터를 스스로 준비하므로 하나씩 돌려도 됩니다(`result`는 안에서 `flight`를 먼저 돌림). 전체 순서: `import-screen`, `map`, `plan`, `plan-save`, `flight`, `result`, `detect`(모델 필요), `sortie`, `missions`.

## 코드 규칙

- 문서·주석·UI 문자열은 한국어로 씁니다.
- 도메인 로직(경로 계획, 상태머신)은 Android·DJI에 의존하지 않는 `:core`에 두고 단위 테스트를 씁니다.
- DJI SDK 호출은 `DroneGateway` 뒤에, 탐지는 `Detector` 뒤에만 둡니다.
- 화면마다 `ViewModel`이 `StateFlow<UiState>`를 내보내고 화면은 이벤트만 올려 보냅니다. Compose 안에 DB 조회·저장, 파일 처리, 판정 같은 로직을 두지 않습니다. `LiveData`는 쓰지 않습니다.
- Material Design 3를 따르고 색·글자는 `MaterialTheme` 역할로만 지정합니다(지도·사진 위 데이터 색만 예외).
- 의존성 버전은 `libs.versions.toml`에 추가합니다. 외부로 데이터를 보내는 라이브러리는 넣지 않습니다.
- 설계와 다른 구현이 필요하면 설계서를 먼저 고칩니다. 문서 한 파일은 200줄을 넘기지 않습니다.

자세한 규칙과 알려진 이슈는 [CLAUDE.md](CLAUDE.md)에 있습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | 설계서 목차, 개요, 요구사항, 확정·미정 사항 |
| [docs/design/01-architecture.md](docs/design/01-architecture.md) | 시스템 구성, 모듈, 데이터·엔티티 |
| [docs/design/02-route-planning.md](docs/design/02-route-planning.md) | 경로 계획(TSP, 소티 분할, 에너지 모델) |
| [docs/design/03-flight-execution.md](docs/design/03-flight-execution.md) | 비행 실행(상태머신, 일시정지·재개) |
| [docs/design/04-security-safety.md](docs/design/04-security-safety.md) | 데이터 보안(LDM), 오프라인 지도, 안전 |
| [docs/design/05-detection.md](docs/design/05-detection.md) | 탐지 모델 규격과 파이프라인 |
| [docs/design/06-ui-sound.md](docs/design/06-ui-sound.md) | 화면 설계, 알림음 |
| [docs/design/07-test-roadmap.md](docs/design/07-test-roadmap.md) | 테스트 전략, 로드맵, 진행 현황 |
| [docs/evidence/](docs/evidence/) | 작업별 시험 증빙 |

DJI SDK 코드는 공식 샘플 [Mobile-SDK-Android-V5](https://github.com/dji-sdk/Mobile-SDK-Android-V5)(5.18.0)를 기준으로 작성합니다.
