# 다른 컴퓨터에서 쓰는 법

[README](../README.md)에서 분리했습니다(2026-11-07). 준비물, 저장소 받기, 비밀 파일, 빌드·설치, 배포(NAS), 처음 실행 확인입니다.

## 1. 준비물

| 항목 | 내용 |
|---|---|
| Android Studio | 최신 안정판. 들어 있는 JBR(Java 17 이상)로 Gradle을 돌립니다 |
| Android SDK | Platform 35, Build-Tools, Platform-Tools(`adb`). Android Studio SDK Manager에서 설치 |
| 에뮬레이터(선택) | **ARM64 시스템 이미지**가 필요합니다(앱이 ARM ABI만 포함). Apple Silicon Mac에서 Pixel 8(API 35, 2400×1080) 이미지를 씁니다. 인텔·AMD PC의 x86_64 에뮬레이터에서는 설치되지 않으니 실제 ARM 기기를 쓰세요 |
| 실제 기기(선택) | DJI RC Pro Enterprise 또는 안드로이드 ARM 기기(USB 디버깅 켬) |
| Python 3(선택) | 시험 데이터·모델 생성, 스모크 시험용. `pip install pillow` |
| Git + SSH | 저장소 받기·올리기 |

## 2. 저장소 받기

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

## 3. 비밀 파일 만들기

`secrets/`는 git에 올라가지 않으므로 컴퓨터마다 직접 만듭니다. 값은 관리자에게 따로(메신저·USB 등) 받으세요. **이 파일들을 커밋하거나 문서·이슈에 붙여 넣지 마세요.**

```bash
mkdir -p secrets
```

| 파일 | 내용 | 쓰는 곳 |
|---|---|---|
| `secrets/dji.properties` | `DJI_API_KEY=<DJI 개발자 사이트에서 받은 앱 키>` | `app/build.gradle.kts`가 읽어 매니페스트 `com.dji.sdk.API_KEY`에 넣음 |
| `secrets/license_kr.co.dl_dji.txt` | DJI LDM 오프라인 라이선스 원문 | 기체 PoC에서 LDM 등록에 사용(예정) |
| `secrets/vworld.properties` | `VWORLD_API_KEY=<VWorld 오픈API 인증키>` | 배경 지도 VWorld(⑥ 설정에서 선택, 7.4). 없으면 배경 지도 없음(사용자 MBTiles만) |
| `secrets/nas.properties` | `NAS_DAV=https://<NAS>/Projects/R26IA06/배포/dl_dji` | `tools/release.sh --upload`(배포). 주소는 공개 저장소에 쓰지 않음 |

`dji.properties`가 없어도 빌드는 됩니다. 이 경우 Gradle이 `secrets/dji.properties 없음` 경고를 내고, 앱에서 DJI SDK 등록만 실패합니다(모의 기체 기능은 정상). DJI 앱 키는 패키지명(`applicationId`) `kr.co.dl_dji`에 묶여 있으므로 패키지명을 바꾸면 키를 새로 받아야 합니다.

## 4. 빌드와 설치

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
./gradlew installDebug      # 연결된 기기·에뮬레이터에 설치(개발용, run-as로 DB를 볼 수 있음)
./gradlew installRelease    # 현장용: 시작 약 3배 빠름(run-as 안 됨)
bash tools/release.sh --upload   # 배포: build/dist/dl_dji-<버전>.apk를 만들어 NAS에 올림, 최신은 R26IA06/dl.apk(아래 "배포")
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 씁니다.

## 배포 (2026-11-07)

- **배포 키**: `secrets/release.keystore`·`release.properties`(git 제외)가 있으면 debug·release 모두 이 키로 서명합니다. 어느 PC에서 빌드해도 RC에 데이터를 지우지 않고 덮어 설치됩니다. 키를 잃으면 업데이트가 안 되고 앱을 지웠다 다시 깔아야 하므로(데이터 삭제) NAS `R26IA06/배포/signing/`에 백업합니다(`--backup-key`). 새 PC는 `secrets/`에 이 두 파일을 복사합니다.
- **버전**: `versionCode`는 git 커밋 수, `versionName`은 `1.0.<커밋 수>`입니다. 커밋하면 저절로 올라갑니다.
- **NAS에 올리기**: NAS 주소를 `secrets/nas.properties`(`NAS_DAV=https://<NAS>/Projects/R26IA06/배포/dl_dji`, git 제외, 관리자에게 받음)에, 계정을 `~/.netrc`(`machine <NAS> login <아이디> password <암호>`)에 넣고 `bash tools/release.sh --upload`를 실행합니다. `R26IA06/배포/dl_dji/dl_dji-<버전>.apk`와 `.json`(커밋, sha256)을 올리고, 같은 이름이 있으면 덮어쓰지 않습니다. 그다음 NAS 안에서 그 파일을 **최신 고정 주소 `R26IA06/dl.apk`·`dl.json`으로 복사**합니다(이 두 파일만 덮어씀, 다시 올리지 않음). APK에는 DJI API 키가 들어 있으므로 공개 위치(GitHub 등)에 올리지 않습니다.
- **RC에 설치**: RC 브라우저에서 **<NAS>/Projects/R26IA06/dl.apk** 를 열고 NAS 계정으로 로그인해 받은 뒤 설치합니다. 이 주소는 늘 최신판입니다(처음 한 번 "출처를 알 수 없는 앱" 허용). 지난 버전은 `R26IA06/배포/dl_dji/`에 남습니다. PC가 옆에 있으면 `adb install -r build/dist/dl_dji-<버전>.apk`.
- **키를 바꿀 때(debug 키 → 배포 키, 2026-11-07에 한 번)**: 서명이 달라 덮어 설치되지 않습니다. 옛 debug 빌드에서 `adb exec-out run-as kr.co.dl_dji tar cf - -C /data/data/kr.co.dl_dji databases files shared_prefs > internal.tar`로 내부 데이터를 백업합니다. 그다음 `mv`로 `Android/data/kr.co.dl_dji/files`를 `/sdcard/`로 옮기고 앱을 지웁니다. 새 키 debug를 설치하고 `adb exec-in run-as kr.co.dl_dji tar xf - -C /data/data/kr.co.dl_dji < internal.tar`, 폴더를 되돌린 뒤 release를 설치합니다. 위치 권한은 다시 묻습니다.

## 5. 처음 실행 확인

1. 앱을 열면 ① 프로젝트 화면이 나옵니다.
2. 아래 "앱에 데이터 넣기"로 시험용 CSV와 지도를 기기에 넣고, [CSV 파일 선택]으로 프로젝트를 만듭니다.
3. 프로젝트 → [+ 새 계획] → 영역 그리기 → 이륙 지점 → [경로 계산] → [계획 저장] → [비행 시작] 순서로 모의 비행을 해 봅니다.
4. 기체 없이 실행하면 비행 화면에 모의 RC 조작 줄(일시정지, 재개, 배터리 감소)이 보입니다.

