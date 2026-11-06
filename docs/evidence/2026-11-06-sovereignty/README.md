# 증빙: 데이터 주권 재점검과 DJI Pilot 2 위험 (2026-11-06)

- 범위: 10-31 점검(`docs/evidence/2026-10-31-sovereignty`, 기준 239308f) 이후 242커밋이 쌓인 `main` c17eff8을 다시 본다. 코드·의존성(AAR)을 읽고, RC Pro(`5YSZL4F00223MH`, Android 10)에서 **읽기 전용 adb**로 실측했다. 설정 변경·앱 실행·외부 도구 설치는 하지 않았다.
- 측정 때 RC 상태: Wi-Fi 연결(사내 AP, 인터넷 됨), 우리 앱(`kr.co.dl_dji`, uid 10082) 화면 켜짐, 기체 연결, LDM 켜짐. 비행기 모드 꺼짐.
- 판정: **안 나감(확인됨)** / **나감**(무엇이 어디로) / **확인 불가**(이유).

## 1. 앱이 외부로 나갈 수 있는 경로(코드)

`grep -rnE "https?://|HttpURLConnection|OkHttp|Socket|WebView|Retrofit|URL\(|openConnection|java\.net|DownloadManager|ktor" app/src/main core/src/main` 결과, 네트워크 코드는 3개 파일뿐이다(나머지는 XML 네임스페이스).

| # | 경로 | 위치 | 받는 곳(운영 주체·나라) | 나가는 데이터 | 판정 |
|---|------|------|------|------|------|
| N-1 | Cesium ion 엔드포인트 | `data/CesiumImagery.kt:20`, `:54-63` | `api.cesium.com`(Cesium GS, 미국) | ion 토큰(URL 쿼리), IP, User-Agent. 위치 없음. **주소를 한 번 받으면 파일에 저장해 다시 묻지 않음**(`:39-46`) | 나감(설계상 허용, 7.4) |
| N-2 | Bing 메타데이터 | `data/CesiumImagery.kt:25` | ion이 주는 Bing 주소(보통 `dev.virtualearth.net`, Microsoft, 미국) | ion이 준 Bing 키, IP. 위치 없음 | 나감(7.4) |
| N-3 | 지도 화면 온라인 타일(MapLibre) | 스타일 `ui/MapScreen.kt:1005-1022`, 주소 `data/MapBackground.kt:41-53` | Bing 타일 서버(Microsoft CDN, `{quadkey}`) 또는 `api.vworld.kr`(국토교통부 VWorld, 국내) | **타일 좌표 = 화면에 띄운 영역**(계획 영역, 비행 미니맵의 기체·전주 주변), IP, VWorld 키. 전주 번호·사진·궤적은 없음 | 나감(7.4). 타일 좌표가 위치를 드러냄 |
| N-4 | **계획 저장 때 미션 영역 미리 받기**(10-31 이후 추가) | `data/SatelliteCache.kt:37-59`, `:87-136` | N-3과 같은 서버 | **전주 좌표 경계 상자 + 여백**의 최소~최대 줌 타일 전부를 차례로 요청 → 서버 쪽에서 요청 묶음으로 **점검 구역을 정확히 복원 가능**. 인터넷 없으면 `satellite-pending.txt`에 남겼다가 지도 열 때 이어 받음(`:61-81`) | 나감(7.4, 10-31 사용자 결정). 10-31 문서의 "나가는 것은 타일 좌표뿐"은 맞지만, 이 경로는 화면이 아니라 **미션 영역 전체**를 보낸다는 점을 명시 |
| N-5 | 배경 재시도 | `data/MapBackground.kt:56-64`(10초마다) | N-1·N-2 | 토큰이 있고 주소를 아직 못 받았을 때만 | 나감(위와 같음) |
| N-6 | RC 자체 위치 | `drone/DjiLocation.kt:63-80` | 없음 | 새 위치는 GPS 공급자만 씀. NETWORK 공급자는 **마지막 값 읽기만**(요청 안 함) | 안 나감 |
| N-7 | MapLibre 13.6.1 원격 측정 | AAR `classes.jar`에 telemetry·events 클래스 없음. `libmaplibre.so`의 URL은 `api.mapbox.com`·`api.maptiler.com`·`demotiles.maplibre.org`(기본 TileServerOptions)뿐 | — | 우리 스타일은 `mapbox://`·`maptiler://`·glyphs·sprite를 쓰지 않음(`ui/MapScreen.kt:1022`) → 이 주소로 갈 일 없음 | 안 나감(정적 근거) |
| N-8 | TFLite·GPU delegate, Room, DataStore | — | 없음 | 모델·DB 모두 기기 안 | 안 나감 |
| N-9 | Android 백업 | `AndroidManifest.xml:10`(`allowBackup="false"`) | — | 10-31 A-1 조치 유지 | 안 나감 |
| N-10 | 결과 ZIP 내보내기 | `data/MissionExport.kt`(SAF) | 사용자가 고른 곳 | 아래 3.2 참고(공용 저장소에 남음) | 안 나감(네트워크 아님) |

매니페스트: 앱 자체는 위치 권한만 선언(`AndroidManifest.xml:5-6`, 10-31 이후 추가), `INTERNET` 등은 DJI SDK에서 병합. 병합 결과 `usesCleartextTraffic="false"`(평문 HTTP 금지), network security config 없음(logcat `No Network Security Config specified`).

## 2. DJI MSDK 5.18.0

### 2.1 순서(`InspectionApp.kt`)

`Helper.install`(:46) → `initWpmz` 스레드(:150) → `SDKManager.init`(:151) → `INITIALIZE_COMPLETE`(:169) → `enableLdm`(:119-145: `loadLocalLDMLicenseContent` → `enableLDM`, **면제 모듈 없음**) → `registerApp`(:171) → 등록 성공 뒤 `DjiFlySafe.watch()`(:156, 리스너만). LDM이 등록보다 먼저 켜진다. LDM 실패여도 등록은 하지만 비행 전 점검에서 **시작 막음**(`core/.../PreflightEvaluator.kt:69-70`, `drone/DjiCheck.kt:55`).

### 2.2 실측(오늘 logcat, pid 8130)

```
12:50:44.020 W/KeyStore ... dji.v5.network.OkHttpManager.<init> ... SDKManager.initDJINetworkManager   # 클라이언트 생성만
12:50:44.418 I/DjiSdk : ✅ LDM 켜짐(라이선스 로드 true, 사용 true)
12:50:44.804 E/DJIV5/UserAccountManager: [init] canNotVisitNetwork
12:50:45.062 E/V5_LTE : errorCode='LDM_NETWORK_ACCESS_FAILED' ... 'In Local Data Mode. Network access failed!'  (x3)
12:50:45.111 I/DjiSdk : ✅ DJI SDK 등록 성공
```

→ **LDM이 SDK의 계정·LTE 접속을 실제로 막고, 등록은 오프라인 라이선스로 성공**. `files/Analytics/`는 비어 있음(통계 파일 없음).

### 2.3 SDK 안에 들어 있는 서버 주소(정적, `dji-sdk-v5-aircraft-5.18.0.aar` 네이티브 문자열)

`account-api.dji.com`, `mydjiflight.dji.com`(비행 기록 동기화), `flysafe-api.dji.com`, `api/apigateway/djigo-hk/statistical-report.djiservice.org`, `active.dji.com`, `firmware-dl.djicorp.com`, `*.djigate.com`, `*.aasky.net`, `uom.receive.caacic.cn`(중국 민항 UOM 신고), NTP `time.apple.com`(`libntp_client.so`). 실시간 전송 라이브러리(Agora `libagora-rtc-sdk.so`, Volcengine `libmrtc_volc.so`, RTMP/RTSP/WebRTC)도 들어 있다. 앱은 `LiveStreamManager`·클라우드 API를 부르지 않는다. DJI 공식 설명: V5 LDM을 켜면 SDK는 네트워크 요청을 보내지 않고, 면제 모듈(RTK·잠금 해제 등)만 예외([ILDMManager](https://developer.dji.com/api-reference-v5/android-api/Components/ILDMManager/ILDMManager.html), [LDM 샘플](https://developer.dji.com/doc/mobile-sdk-tutorial/en/tutorials/ldm.html)).

## 3. RC 실측(읽기 전용)

### 3.1 연결과 바이트

- `/proc/net/tcp·tcp6·udp·udp6`(12:55, 이후 0.25초 간격 40회): uid 10082 소켓은 루프백(`127.0.0.1:40007`)과 미연결 UDP `0.0.0.0:9003/9004`뿐. **외부 IP와 맺은 연결 0개**. `192.168.50.x`(usb0)는 RC 내부 무선 보드 링크.
- `dumpsys netstats --full --uid`(Wi-Fi, 날짜별 합):

| uid | 앱 | 받음 | 보냄 | 날짜 |
|-----|----|------|------|------|
| 10082 | 우리 앱 | 123.8 MB | 26.1 MB | 10-29~11-06(10-31 43 MB, 11-03 32 MB 등: 타일 받기 시기와 일치) |
| 10067 | DJI Pilot 2 9.0.5.31 | 147.3 MB | **36.6 MB** | 05-29, 09-09~09-23(09-22 8.3 MB·09-23 17.6 MB 보냄), 10-20, **11-03 0.91 MB, 11-04 0.24 MB 보냄** |
| 0 | 시스템(root) | 13.4 MB | 14.0 MB | — |

- DNS: `dumpsys dnsresolver` 부팅 뒤 질의 68건, 앱별 이름은 기록 안 됨. 루트가 없어 `tcpdump` 불가(`You don't have permission to capture`), 그래서 **목적지 호스트별 분류는 못 했다**. 중국 IP 여부도 연결 단위로는 확인 불가(지금 순간에는 외부 연결 자체가 없음).
- **설명 안 되는 소량 송신**: `dumpsys netd trafficcontroller` 45초 비교에서 우리 uid가 wlan0로 **응답 없이** 약 4개/초·76바이트 패킷을 보냄(+13,680 B, 받음 0). `/proc/net/netstat` OutBcast·OutMcast 0, ARP에는 게이트웨이뿐 → 브로드캐스트가 아니라 게이트웨이로 가는 유니캐스트 UDP로 보이며, 9003/9004 소켓(SDK) 쪽으로 추정. 페이로드 48바이트로 NTP 요청 크기와 같지만 DNS 질의 수가 적어 확정 못 함. RC 라우팅에 DJI 내부 대역(`192.168.41/100/101/120.0/24 via usb0`)이 main 표에만 있어, SDK가 내부 대역으로 보낸 것이 앱 네트워크(wlan0) 표로 새는 가능성도 있다. 오늘 07:40~09:40 송신 1.2 MB(패킷 16,030개 대 수신 393개)가 이것. **확인 불가**(아래 U-1).

### 3.2 파일 위치(Pilot 2가 읽을 수 있는가)

Pilot 2(`com.dji.industry.pilot`, uid 10067)는 **시스템 앱**(`flags=[ SYSTEM ...]`, 제거 불가), `targetSdk=29` + `REQUEST_LEGACY_EXTERNAL_STORAGE`, 저장소 읽기·쓰기 `SYSTEM_FIXED` 허용, `INTERNET` 허용. Android 10에서는 이 조건이면 `/sdcard/Android/data/<다른 앱>/`도 읽을 수 있다(디렉터리 `sdcard_rw` 그룹, 파일 `rw-rw----`). 즉 **앱 외부 폴더는 Pilot 2로부터 격리되지 않는다**(Android 11부터 막힘).

| 위치 | 내용 | 쓰는 쪽 |
|------|------|------|
| `/data/data/kr.co.dl_dji/`(비공개) | Room DB(전주 좌표·궤적·판독), DataStore, 위성 주소, GPU 캐시 | 앱. Pilot 2도 못 읽음 |
| `/sdcard/Android/data/kr.co.dl_dji/files/projects/…/kmz/` | 소티 KMZ(전주 좌표) | `drone/DjiDroneGateway.kt:391-397`, 경로 `ui/flight/FlightViewModel.kt:674` |
| `…/files/projects/`(4.7 GB), `manual/`, `poc/` | 사진(GPS EXIF), 탐지 결과 | 앱 |
| `…/files/maps/` | 미리 받은 타일 MBTiles(=점검 구역) | `data/SatelliteCache.kt:90` |
| `…/files/logs/` | 운용 로그·logcat 사본 | `data/OpLog.kt:23` |
| `…/files/DJI/FlightRecord/` | **MSDK 비행 기록**(`DJIFlightRecord_*.txt`, `MCDatFlightRecords/*_FLY687·688.DAT`) | MSDK가 자동으로 씀 |
| `…/files/DJI/waypoint/<해시>/` | MSDK가 복사한 KMZ 38벌 | MSDK |
| `…/files/LOG/CSDK/Logs/`(2.7 GB) | MSDK 내부 로그 | MSDK |
| **공용** `/sdcard/Download/` | 현장 전주 CSV·XLSX, 현장 사진 폴더 | 사용자(adb push·복사) |
| **공용** `/sdcard/` 최상위 | 결과 ZIP(`poles-…_mission1.zip`, 157 MB) | 사용자가 SAF로 고른 곳 |
| **공용** `/sdcard/Movies/` | 화면 녹화(지도 화면 포함 가능) | 시스템 화면 녹화 |

우리 앱은 공용 저장소에 직접 쓰지 않는다(grep: `Environment.`·`MediaStore`·`/sdcard` 없음). 공용에 있는 것은 모두 사람이 옮긴 파일이다.

Pilot 2 자신의 흔적: `/sdcard/DJI/com.dji.industry.pilot/FlightRecord/MCDatFlightRecords/`의 최신 DAT는 `2026-11-04_09-29-12_FLY685.DAT`, 우리 앱 비행(FLY686~688)은 **없음** → DAT는 그때 연결된 앱이 실시간으로 기록하며, Pilot 2가 우리 비행 기록을 가져간 흔적은 없다. `/sdcard/DJI/Mission/KML/`(Pilot 2 경로 라이브러리)에 11-03 항목 5개가 있으나 Pilot 2에서 만든 것(우리 KMZ 이름 `sortie_*` 없음). `/sdcard/DJI/mediaAutoUpload/`(2024) 존재.

### 3.3 기체 쪽

- 우리가 올린 KMZ는 `pushKMZFileToAircraft`(`DjiDroneGateway.kt:407`)로 기체 저장소에 남는다(지우는 코드 없음).
- 기체는 자체 비행 기록(FC 로그)을 내장 저장소에 남긴다. DJI 설명: 고도·거리·속도 같은 비행 로그는 LDM과 무관하게 **기체에 저장**된다([heliguy 정리](https://heliguy.com/blogs/posts/data-security-with-dji-enterprise-drones)).
- SD 카드 사진에는 GPS EXIF가 들어 있다(우리 앱은 받아 오기만, `DjiSdAlbum.kt:109`에서 사용자가 지울 때만 삭제).

## 4. Pilot 2를 같은 RC에서 쓸 때

DJI 설명(Pilot 2 네트워크 보안 모드): 표준 모드는 인터넷을 쓰고, "비행 기록 동기화"를 켜면 계정·기체 일련번호·**위치·궤적·속도**를 DJI 서버로 보낸다. "로그 업로드"는 기기 로그를 보낸다(사용자가 고를 때). 제한 네트워크 모드는 지도·네트워크 RTK·타사 클라우드만 남기고, **로컬 데이터 모드는 모든 데이터 서비스를 닫는다**(모드 바꾸면 앱 재시작, 보안 코드로 잠글 수 있음). 출처: [heliguy](https://heliguy.com/blogs/posts/data-security-with-dji-enterprise-drones), [DJI Pilot 2 Data Security(2023-09-27, 표제)](https://xcopter.com/download/DJI_Pilot_2_Data_Security_20230927.pdf), [AirData 그룹](https://groups.google.com/g/airdata/c/-_4eGIhiwPA).

새는 길(가능성 순):
1. **Pilot 2 "로그 업로드"·HMS 진단 로그**: 기체 로그를 RC로 받아 DJI로 보낸다. 기체 로그에는 우리 앱으로 한 비행도 들어 있을 수 있다(기체 저장소는 앱 구분 없음). 확인 불가(Pilot 2 내부 동작).
2. **Pilot 2 비행 기록 동기화**: Pilot 2로 한 비행만 대상. 우리 앱 비행은 Pilot 2 폴더에 기록되지 않음(3.2) → 이 경로로 우리 비행이 나간 흔적은 없음.
3. **Pilot 2가 공용·다른 앱 폴더를 읽는 것**: 권한상 가능(3.2), 실제로 읽는지는 확인 불가.
4. Pilot 2 실측 송신: 11-03 0.91 MB, 11-04 0.24 MB(Wi-Fi). 내용·목적지 확인 불가. Pilot 2의 현재 네트워크 보안 모드는 비공개 데이터라 adb로 읽을 수 없음.

## 5. 판정

| 경로 | 판정 |
|------|------|
| 앱 코드 → Cesium·Bing·VWorld(N-1~N-5) | **나감**: 토큰·키·IP·타일 좌표. 계획 저장 때 **미션 영역 전체 타일**(N-4)이 Microsoft(미국) 또는 VWorld(국내)로. 전주 번호·사진·궤적·탐지 결과는 안 나감 |
| 앱 코드의 그 밖(위치·탐지·DB·백업·MapLibre 원격 측정) | **안 나감**(확인됨, 코드·AAR) |
| DJI MSDK 등록·계정·LTE(LDM 켠 뒤) | **안 나감**(확인됨, logcat `LDM_NETWORK_ACCESS_FAILED`, 외부 연결 0) |
| `SDKManager.init`~LDM 켜짐 0.4초 | **확인 불가**: OkHttp 클라이언트 생성만 보이고 요청 흔적 없음. 초 단위 패킷 기록 없음 |
| 우리 uid의 응답 없는 UDP 약 4개/초 | **확인 불가**: 목적지 미상(루트 없음). 받는 바이트 0이라 상대가 응답하지 않음 |
| 중국 서버로 가는 연결 | 측정 순간 **없음**. 과거 바이트의 목적지는 확인 불가 |
| Pilot 2로 우리 비행이 나감 | 흔적 **없음**(Pilot 2 폴더에 우리 FLY 번호 없음). 단 로그 업로드·공용 폴더 읽기는 **막혀 있지 않음** |

## 6. 대책(효과 큰 순)

1. **RC를 인터넷에 붙이지 않는다**(현장·사무실 모두 Wi-Fi 끔, 계획 저장·타일 받기는 그 일만 하고 끔). 모든 앱(Pilot 2·시스템 포함)에 듣는 유일한 확실한 수단. 우리 앱 지도 미리 받기도 이때만.
2. **Pilot 2를 로컬 데이터 모드로 두고 보안 코드로 잠금**, 비행 기록 동기화·로그 업로드·미디어 자동 업로드를 끔(사람이 Pilot 2 설정에서, 이번 점검은 상태를 못 읽음 → U-2).
3. **Pilot 2에서 "로그 업로드"·HMS 진단 보내기 금지**, 정비 문의는 DJI Assistant로 로그를 받아 내용 확인 뒤 사내 결정.
4. **공용 저장소 정리**: `/sdcard/Download`의 전주 CSV·XLSX·사진 폴더, `/sdcard`의 결과 ZIP, 화면 녹화는 가져온 뒤 지움(Pilot 2·모든 저장소 권한 앱이 읽을 수 있음).
5. **앱 외부 폴더의 MSDK 기록 정리**: `files/DJI/FlightRecord`, `DJI/waypoint`, `LOG/CSDK`는 Android 10에서 Pilot 2가 읽을 수 있음. 미션 뒤 지우는 설정이나 MSDK 로그 끄기 검토(코드 변경이라 설계서 7장 먼저).
6. **기체 정리**: 작업 뒤 기체 웨이라인 저장소의 KMZ 삭제, SD 사진은 받은 뒤 포맷. 기체 FC 로그는 앱에서 지울 수 없음(남는 위험).
7. Pilot 2 사용 중단이 가능하면 `pm disable-user`로 비활성화(시스템 앱이라 제거 불가, 사용자 결정 사항 — 이번에 하지 않음). 사내 AP 방화벽에서 RC MAC의 외부 통신 차단·DJI 도메인 차단도 보조 수단.

보장할 수 없는 것: Pilot 2·RC 펌웨어(시스템 uid 0, 14 MB 송신)의 내부 동작, 기체 FC 로그의 이후 경로, MSDK init~LDM 사이 0.4초, 위 소량 UDP의 목적지.

## 7. 사용자 확인 항목

| # | 할 일 |
|---|------|
| U-1 | PCAPdroid(VPN 방식, 루트 불필요) 또는 AP 쪽 캡처로 uid 10082의 UDP 목적지·NTP 여부와 앱 시작 직후 0.4초 확인(설치는 사용자 결정) |
| U-2 | Pilot 2 → 설정 → 보안: 네트워크 보안 모드, 비행 기록 동기화, 로그 업로드, 미디어 자동 업로드 상태를 화면으로 확인 |
| U-3 | 9월 22~23일 Pilot 2 송신 26 MB가 무엇이었는지(그때 로그 업로드·동기화를 했는지) 기억 확인 |

## 8. 근거 명령

```bash
adb shell dumpsys package com.dji.industry.pilot | grep -E "targetSdk|flags=|privateFlags|EXTERNAL_STORAGE|INTERNET"
adb shell cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6     # uid 열로 10082·10067 거름
adb shell dumpsys netstats --full --uid                                    # uid별 날짜 합
adb shell dumpsys netd trafficcontroller | grep -E ' (10082|10067|0) [01] ' # 45초 간격 두 번
adb shell grep IpExt /proc/net/netstat; adb shell cat /proc/net/arp
adb logcat -d | grep -E "DjiSdk|LDM_NETWORK|canNotVisitNetwork|OkHttpManager"
MSYS_NO_PATHCONV=1 adb shell ls -la /sdcard/ /sdcard/DJI /sdcard/Download /sdcard/Android/data/kr.co.dl_dji/files/DJI/FlightRecord
grep -aoE "https?://[A-Za-z0-9.-]+" jni/arm64-v8a/*.so | sort | uniq -c   # dji-sdk-v5-aircraft-5.18.0.aar, android-sdk-opengl-13.6.1.aar
```
