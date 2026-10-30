# 증빙: 데이터 주권 점검(7장) (2026-10-31)

- 범위: 계획서 `docs/plan/2026-10-31.md` 17번. 코드·매니페스트·의존성을 읽어서 점검했다(정적 점검). RC 실기 트래픽 캡처는 하지 않았다(아래 "사용자 확인 항목").
- 기준 커밋: `main` 239308f. 판단 기준은 7.1(데이터는 기기 안에만, 사용자가 직접 내보낼 때만 밖으로)과 7.2~7.4.
- 판단: **허용**(외부 전송 없음 또는 설계상 허용), **임시 허용**(7.4처럼 설계서에 예외로 적힌 것), **조치 필요**(설계서와 어긋나거나 막아야 함), **불명**(근거 부족, 실측 필요).

## 1. 권한(병합 매니페스트)

앱 매니페스트(`app/src/main/AndroidManifest.xml`)는 권한을 하나도 선언하지 않는다. 모든 권한은 DJI SDK(`com.dji:dji-sdk-v5-aircraft:5.18.0`의 `AndroidManifest.xml:7` 등)에서 병합된다(`app/build/intermediates/merged_manifests/debug/.../AndroidManifest.xml`).

| 권한 | 출처 | 앱이 쓰는 곳 | 판단 |
|------|------|------|------|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | DJI SDK | DJI 등록, Cesium ion 위성 배경(아래 2장) | 허용(7.3). 단 7.3의 "오프라인 모드 스위치"는 아직 없음 → 2장 C-4 |
| `ACCESS_FINE/COARSE_LOCATION`, `BLUETOOTH*`, `READ_PHONE_STATE`, `KILL_BACKGROUND_PROCESSES`, 저장소 읽기·쓰기 | DJI SDK | 앱 코드에서 직접 쓰지 않음 | 허용(SDK 요구). 외부 전송은 SDK 쪽 문제라 2장 D-1~D-3에서 다룸 |
| `usesCleartextTraffic="false"` | DJI SDK | 평문 HTTP 금지(병합 결과에 남음) | 허용 |

## 2. 외부로 나갈 수 있는 경로

| # | 항목 | 위치(파일:줄) | 외부로 나가는 데이터 | 판단 | 조치 |
|---|------|------|------|------|------|
| C-1 | Cesium ion 엔드포인트 요청 | `data/CesiumImagery.kt:20`(`getJson` `:58-67`, `HttpURLConnection`) | `api.cesium.com`(미국)에 **ion 토큰**(URL 쿼리), 기기 IP, `User-Agent`(Dalvik·안드로이드 버전·기기 모델), 요청 시각. 위치는 없음 | 임시 허용(7.4) | 현장 배포 전 VWorld·정사영상 MBTiles로 교체(7.4에 이미 기록). 토큰이 APK `BuildConfig`에 평문으로 들어가므로 배포 APK에는 토큰 없이 빌드 |
| C-2 | Bing 메타데이터 요청 | `data/CesiumImagery.kt:25` | ion이 준 Bing 서버(Microsoft)에 **Bing 키**, IP, `User-Agent`. 위치는 없음 | 임시 허용(7.4) | C-1과 같음 |
| C-3 | 위성 타일 요청(MapLibre) | 주소 생성 `ui/MapScreen.kt:767-770`(`styleJson`), 호출 `ui/PlanViewModel.kt:120`, `ui/flight/FlightViewModel.kt:143`, `ui/flight/ManualFlightViewModel.kt:78` | Microsoft 타일 서버에 **타일 좌표(quadkey = 줌·x·y)**. 즉 계획 화면에서 본 영역, 비행 화면 미니맵이 맞춘 **점검 영역(전주·이륙 지점 주변, `FlightMap.kt:85-92`)** 이 드러남. IP·`User-Agent`도 함께 | 임시 허용(7.4, 설계서에 "타일 좌표가 Cesium·Microsoft로 전송"으로 기록됨) | C-1과 같음. 그때까지 현장에서는 RC Wi-Fi를 끄고 운용(7.2 운용 지침) |
| C-4 | 인터넷이 없을 때 10초마다 재시도 | `data/CesiumImagery.kt:48-53`(`onlineSatelliteTilesRetrying`) | 화면이 열려 있는 동안 연결이 생기면 곧바로 C-1~C-3이 나감 | 조치 필요(7.3과 어긋남) | 7.3은 "설정에서 오프라인 모드를 켜면 앱 내 모든 네트워크 호출 차단"인데 스위치가 없다. 토큰 없이 빌드하면 0건(`:40`, `:49`)이므로 **현장 배포 빌드는 토큰 없이** 만들거나, 설정에 "온라인 배경 끄기"를 추가(설계서 7.3 먼저 갱신) |
| C-5 | MapLibre 타일 캐시 | `ui/MapScreen.kt:697`, `:709`(1GB) | 없음(기기 안 캐시). 이미 본 타일은 오프라인에서 캐시로 보임 | 허용 | 없음 |
| C-6 | MBTiles 지도 | `ui/MapScreen.kt:772`(`mbtiles://` 로컬 파일) | 없음 | 허용 | 없음 |
| D-1 | DJI SDK 초기화 → LDM → 등록 순서 | `InspectionApp.kt:109-132`(`init` → `INITIALIZE_COMPLETE`(`:128`) → `enableLdm`(`:81-107`: `loadLocalLDMLicenseContent` → `enableLDM`) → `registerApp`) | LDM이 켜진 뒤 등록하므로 등록 트래픽은 라이선스로 오프라인 처리(PoC ④, `docs/evidence/2026-10-30-aircraft-poc`: Wi-Fi 끈 채 등록 성공, 앱 소켓은 루프백·UDP 9003/9004뿐) | 허용 | 없음(7.2 순서와 일치) |
| D-2 | `SDKManager.init`부터 `INITIALIZE_COMPLETE`까지 | `InspectionApp.kt:112` | 이 구간은 LDM이 아직 꺼져 있다(5.5부터 `init` 뒤에만 `enableLDM` 가능, 7.2). 이때 SDK가 서버에 나가는지 공식 문서·샘플에 근거 없음 | 불명 | 사용자 확인 항목 U-1 |
| D-3 | LDM 실패 시에도 등록 계속 | `InspectionApp.kt:84-92`, `:101-105`(`then()`) | 라이선스가 없거나 로드·켜기에 실패하면 LDM 없이 `registerApp` → **DJI 서버로 앱 키 인증 트래픽**, 이후 SDK의 온라인 기능(비행제한구역 DB 갱신, 로그 업로드 등)이 열린 상태로 운용됨 | 조치 필요 | 7.2 선택지 ②(최초 1회 온라인 등록)를 의도한 동작이지만, 현장에서 LDM 실패를 모르고 비행할 수 있다. 검증 화면에만 상태가 보인다(`ldmStatus`). 비행 전 점검(8장)에 "LDM 켜짐" 항목을 넣어 실패면 시작 차단 또는 경고(설계서 7.2·8장 먼저 갱신) |
| D-4 | WPMZ 초기화(KMZ 검사기) | `drone/DjiWpmz.kt:8`, 호출 `InspectionApp.kt:111`(SDK `init`보다 먼저) | 정적 근거상 없음: `WPMZManager.init`은 AAR 안의 `assets/geoid_data/WW15MGH.DAC`(EGM96 지오이드)를 앱 DJI 폴더로 복사하고 `GeoidManager.openGeoid96M150`이 `RandomAccessFile`로 연다(`wpmzsdk-1.0.5.1` `classes.jar`를 `javap`로 확인). `classes.jar`에 `java.net`·OkHttp 참조 없음. 네이티브(`libwpmz_jni.so`, `libPPAL.so`)의 URL 문자열은 KML·GML·GPX XML 네임스페이스뿐이고 `curl_easy` 심볼 없음 | 허용(정적 근거). 실측은 U-1에 포함 | 없음 |
| D-5 | Geoid 초기화 | D-4와 같음(별도 `GeoidManager` 호출은 앱에 없음) | 지오이드 데이터는 AAR에 들어 있어 내려받지 않음 | 허용 | 없음 |
| D-6 | DJI 비행제한구역 DB 내려받기 콜백 | `InspectionApp.kt:131`(`onDatabaseDownloadProgress`, 빈 구현) | LDM이 켜지면 온라인 갱신 불가(7.2). LDM 실패 시는 D-3 | 불명(D-3에 종속) | U-1에서 함께 확인 |
| A-1 | Android 자동 백업 | `app/src/main/AndroidManifest.xml:7-9`(`allowBackup="true"`), `res/xml/data_extraction_rules.xml`(`<cloud-backup>` 비어 있음), `res/xml/backup_rules.xml`(비어 있음) | 기기에 Google 계정·백업이 켜져 있으면 앱 데이터(Room DB: 전주 좌표·비행 궤적·탐지 결과, DataStore 설정, `files/`)가 **Google 드라이브로 자동 업로드**될 수 있다. 외부 저장소(`getExternalFilesDir`: 사진·운용 로그)는 대상 아님 | 조치 필요(7.1 위반 가능) | `android:allowBackup="false"`로 바꾸거나 `data_extraction_rules.xml`·`backup_rules.xml`에서 모든 도메인을 `exclude`. 동작 변경이라 이번 점검에서는 고치지 않음. RC Pro에 Google 계정이 없으면 실제 위험은 낮다(U-3) |
| L-1 | 운용 로그·크래시 로그 | `data/OpLog.kt:13`, `:23`(외부 앱 폴더 `logs/`) | 없음(기기 안 파일). 이전 실행 비정상 종료 시 앱 자신의 logcat을 파일로 남길 뿐 | 허용 | 없음 |
| E-1 | 결과 ZIP 내보내기 | `data/MissionExport.kt` | 사용자가 SAF로 고른 위치에만 씀 | 허용(7.1 "사용자가 직접 내보낼 때") | 없음 |
| T-1 | TFLite 탐지 | `detect/TfliteYoloDetector.kt` | 없음(앱 폴더 `models/`의 모델을 기기에서 실행) | 허용 | 없음 |
| X-1 | 그 밖의 네트워크 코드 | `app`·`core` 전체 grep(`http://`, `https://`, `HttpURLConnection`, `URL(`, `openConnection`, `OkHttp`, `Retrofit`, `Socket`, `WebView`, `ktor`, `java.net`) | `CesiumImagery.kt` 말고 없음(나머지 `http` 문자열은 주석·XML 네임스페이스) | 허용 | 없음 |
| X-2 | 외부 분석·크래시·푸시 SDK | `gradle/libs.versions.toml`, `app/build.gradle.kts:79-118` | 없음(Firebase·중국계 지도·분석 SDK 없음). MapLibre는 원격 측정이 없는 오픈소스 포크 | 허용 | 없음 |

## 3. 조치 필요 요약

1. **C-4 오프라인 모드 스위치 없음**: 토큰이 들어간 빌드는 인터넷이 생기는 순간 Cesium·Microsoft로 토큰·IP·점검 영역 타일 좌표를 보낸다. 현장 배포 APK는 `secrets/cesium.properties` 없이 빌드하는 것을 운용 규칙으로 하고, 필요하면 설정에 "온라인 배경 끄기"를 둔다.
2. **D-3 LDM 실패를 비행 전에 알 수 없음**: 라이선스 누락·로드 실패 시 LDM 없이 등록·운용된다. 비행 전 점검에 LDM 상태를 넣는다.
3. **A-1 자동 백업**: `allowBackup="true"`에 제외 규칙이 없어 Google 백업으로 DB가 나갈 수 있다. 백업을 끄거나 모두 제외한다.

## 4. 사용자 확인 항목(실기·외부 확인 필요)

| # | 확인할 것 | 방법(예) |
|---|------|------|
| U-1 | 첫 설치(앱 데이터 삭제) 상태에서 앱을 켤 때 `SDKManager.init`~`registerApp` 구간과 비행 중에 DJI·기타 외부 주소로 나가는 연결이 없는지(D-2, D-4, D-6) | RC Pro에 PCAPdroid(루트 없이 VPN 방식) 설치 → 앱 `kr.co.dl_dji`만 캡처, Wi-Fi 켠 상태로 앱 시작·계획·모의 비행·결과 내보내기. **Cesium 토큰 없이 빌드한 APK**로 해서 C-1~C-3을 빼고 본다. 설치 여부는 사용자 결정 |
| U-2 | 토큰이 든 빌드에서 실제로 나가는 곳이 `api.cesium.com`과 Bing(`*.virtualearth.net` 등)뿐인지 | U-1과 같은 캡처를 토큰 빌드로 한 번 더 |
| U-3 | RC Pro에 Google 계정·백업이 있는지(A-1 위험 크기) | RC Pro 설정 → 계정, 백업 |
| U-4 | RC Pro 시스템 LDM(조종기 설정)과 현장 Wi-Fi 끔이 운용 지침에 들어갔는지(7.2 끝 항목) | 운용 지침 문서 확인 |

## 5. 근거 명령

```bash
# 병합 매니페스트 권한과 출처
grep -n "uses-permission" app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
grep -n "INTERNET" app/build/intermediates/manifest_merge_blame_file/debug/processDebugMainManifest/manifest-merger-blame-debug-report.txt
# 앱 코드의 네트워크 호출
grep -rnE "https?://|HttpURLConnection|OkHttp|Socket|WebView|Retrofit|URL\(|openConnection|java\.net" app/src core/src
# WPMZ AAR(gradle 캐시의 wpmzsdk-1.0.5.1): 자산·바이트코드·네이티브 문자열
ls assets/geoid_data                      # WW15MGH.DAC
javap -c -p com/dji/wpmzsdk/manager/WPMZManager.class        # init: copyAssetsDirToSDCard → GeoidManager.openGeoid96M150
grep -aoE "https?://[A-Za-z0-9./_-]+" jni/arm64-v8a/libwpmz_jni.so jni/arm64-v8a/libPPAL.so   # XML 네임스페이스뿐
grep -ac "curl_easy" jni/arm64-v8a/libPPAL.so              # 0
```

## 5. 후속 조치(같은 날 메인 작업)

- A-1: `android:allowBackup="false"`로 바꿈(앱 데이터가 Google 백업으로 나가지 않음).
- D-3: 비행 전 점검에서 LDM이 꺼져 있으면 경고를 확인해도 시작할 수 없게 함(`PreflightEvaluator` 막는 항목). 앱 등록은 그대로.
- C-4: 사용자 결정(2026-10-31) — 배포에도 Cesium 위성 배경을 쓰고(토큰 포함), 받은 타일은 캐시해 다시 받지 않으며 계획 저장 때 미션 영역을 미리 받는다(설계서 7.4). 스위치는 두지 않는다. 나가는 것은 토큰·IP·타일 좌표뿐.
