# 성능 최적화 결과·개량 리포트 (2026-11-07)

계획: 측정 → 개량 → 검토 → 리포트. 앱 기준 커밋은 `3c41d2c`(before)이고, 이 리포트와 함께 커밋한 것이 after다.
핫스팟은 탐색 에이전트 3개(UI/메인 스레드, 탐지·IO, 시작·core·빌드)로 찾았고, 핵심은 코드로 다시 확인했다.

## 1. 요약

| 영역 | 지표 | Before | After | 변화 | 측정 |
|---|---|---|---|---|---|
| 비행 안전 | 사진 저장 중 RTH 명령 처리 | 저장이 끝날 때까지 대기 | 즉시 | 막힘 없음(실측 저장 약 20ms/장) | `:core` 테스트 `사진 저장이 느려도 RTH 명령은 바로 처리한다`(옛 코드는 실패) |
| 비행 화면 | 화면 상태 갱신 횟수 | DJI 키 콜백마다(약 25종) | 최대 10Hz | 콜백 수 → 10/s | 코드(`sample(100ms)`), RC 프레임은 아래 4장 |
| 비행 지도 | 궤적 GeoJSON 다시 올림 | 점이 늘 때마다, 다시 보기는 10Hz | 0.3초에 1번 | 다시 보기 약 1/3 | 코드 |
| 사진 다운로드 | 사진 1장 버퍼 쓰레기 | 약 20MB(32B부터 두 배씩) | 0(파일 크기만큼 미리 잡음) | -100% | 계산(10MB JPEG) |
| 배경 미리 받기 | 타일당 커밋·SELECT | 타일마다 1번씩(최대 2만 번) | 200장에 커밋 1번, 줌마다 SELECT 1번 | 커밋 -99.5% | 코드. 실제 시간은 `prefetchDone.ms`로 기록(4장) |
| 배경 미리 받기 | HTTPS 연결 | 타일마다 새 연결(`disconnect`) | keep-alive 재사용 | TLS 핸드셰이크 절감 | 코드 |
| 썸네일 | 같은 사진 다시 풀기 | 스크롤·전주 바꿀 때마다 | 메모리 캐시 16MB | 다시 풀기 0 | 코드 |
| 썸네일 | 동시 디코딩 | 제한 없음(IO 64) | 2장 | — | 코드 |
| 썸네일 | 메모리/장 | ARGB 약 330KB | RGB_565 약 165KB | -50% | 계산(1/16 크기) |
| ZIP 내보내기 | 사진 압축 | JPEG을 DEFLATE로 다시 압축 | 저장만(NO_COMPRESSION) | CPU 절감, 크기 거의 같음 | 코드 |
| 경로 계획 | 최악 시간 | (1 + 소티 수) × 2초 | 2초(전체 순회 1초 + 소티 재최적화 나머지) | 소티 31개면 64초 → 2초 | 코드, 아래 2장 |
| 계획 지도 | 촬영 지점 펼치기 | 경로 바뀔 때 2번 | 1번 | -50% | 코드 |
| 빌드 | 클린 빌드(캐시 있음) | 42초 | 9초 | -79% | 아래 3장 |
| 앨범 스크롤(RC) | CPU 평균 / 99p 프레임 | 291% / 18ms | 161% / 8.5ms | -45% / -53% | 아래 4장(2회 평균) |
| 앱 시작(RC) | TotalTime | 1466ms(debug) | 430ms(release) | -71% | 아래 4장 |
| 안정성(RC) | 켤 때 크래시 | 매번(3c41d2c 회귀) | 0 | 고침 | 아래 4장 |

## 2. 경로 계획 시간 (B-TSP, PC JVM)

`core/.../RoutePlannerPerfTest.kt`. 가로·세로 900m 안 무작위 전주, `FlightParams()` 기본값.

| 전주 수 | 소티 | Before | After |
|---|---|---|---|
| 150 | 6 | 46ms | 45ms |
| 300 | 10 | 59ms | 58ms |
| 1000 | 31 | 376ms | 366ms |

- PC에서는 한도(2초)에 닿지 않아 시간 차이는 측정 오차 안이다. 고친 것은 최악의 경우다. 소티마다 한도를 따로 쓰던 것을, 전체 순회는 한도의 절반까지, 소티 재최적화는 남은 시간을 함께 쓰게 했다(리뷰 지적: 하나를 통째로 나누면 큰 문제에서 소티 재최적화가 시간을 못 얻음). 2-opt 한 바퀴 안에서도 마감 시간을 본다.
- 재최적화 결과는 원래 순서보다 나쁘면 버린다. 그래서 시간이 모자라도 경로 품질은 전보다 나빠지지 않는다.
- 이웃 리스트 2-opt는 하지 않았다. 1000개도 0.4초라 필요가 없다.

## 3. 빌드 시간 (B-BUILD, 같은 PC에서 before·after를 번갈아 측정)

| 경우 | Before | After |
|---|---|---|
| 클린 빌드, 빌드 캐시 안 씀(`--no-build-cache`) | 80초 | 80초 |
| 한 줄 바꾼 뒤 증분 빌드 | 7초 | 7초 |
| `clean` 뒤 다시 빌드(캐시 있음) | 42초 | **9초** |

`gradle.properties`: `org.gradle.caching=true`, `org.gradle.parallel=true`, `-Xmx4g -XX:+UseParallelGC`.

## 4. RC 실측 (RC Pro V80.05.00.11, 기체 없음, 2026-11-07)

- before는 `3c41d2c`이고 after는 이번 수정이다. 둘 다 아래 크래시 수정을 넣고 같은 날 번갈아 쟀다.
- 원시 출력은 `raw/`에 있다. 측정 스크립트는 `build/perfm.sh`(저장소 밖)이다.

**측정 중 발견한 회귀(3c41d2c)**
- 증상: 앱을 켤 때마다 죽었다. 11-07 오전에만 7번이다.
- 원인
  - 3c41d2c는 MSDK 기록을 삭제한 뒤에 SDK를 초기화하도록 초기화를 뒤로 미뤘다.
  - 그 사이에 홈 화면이 `LDMManager`·`DeviceHealthManager`를 건드렸다.
  - 이 매니저들은 `SDKManager is not initialized` 오류로 클래스 초기화에 실패한 채 남는다.
  - 이어서 SDK가 초기화하며 같은 클래스를 쓰다가 `NoClassDefFoundError`가 난다.
- 고침
  - 기록 삭제를 `onCreate`에서 바로 하고 SDK를 곧이어 초기화한다.
  - 삭제는 지난 세션 기록(약 130KB)뿐이라 7~8ms 걸린다.
  - 결과: 크래시 0, `crash +0`(6회 시작마다)

**시작 시간**: `am start -W` TotalTime. 6회 중 첫 회는 빼고 5회 중앙값이다.

| 빌드 | TotalTime |
|---|---|
| before debug | 1466ms |
| after debug | 1465ms |
| **after release**(debug 키 서명, R8 끔) | **430ms (-71%)** |

참고: 3c41d2c 그대로는 470ms였다. SDK 초기화가 뒤로 밀려서 빨랐던 것이고, 대신 앱이 죽었다.

**앨범 스크롤**(B-ALBUM)
- 방법: 다운로드 완료 사진 751장을 4열 격자로 연다. 아래로 10번, 위로 10번 넘긴다(`input swipe` 250ms).
- 측정: 그동안 `top`으로 5초 간격 5회 CPU 평균, `gfxinfo`로 프레임을 잰다.
- 2회 측정했고, 순서를 바꿔도 결과가 같았다.

| 빌드 | CPU 평균 | 끊긴 프레임 | 90p | 99p |
|---|---|---|---|---|
| before debug | 288% / 294% | 1.00% / 1.65% | 9ms | 17 / 19ms |
| after debug | 163% / 159% | 0.00% / 0.17% | 5ms | 8 / 9ms |
| after release | 143% | 0.09% | 5ms | 7ms |

- 효과: 썸네일 캐시와 동시 디코딩 2장 제한으로 **CPU -45%, 99p 프레임 시간 -53%**이다.
- 이유: before는 넘길 때마다 20MP JPEG을 제한 없이 동시에 풀었다.

**비행 다시 보기**(지도·궤적 부담)
- 방법: #19 소티 1(실제 11-03 비행)을 4배로 30초 재생했다.

| 빌드 | CPU 평균 | 끊긴 프레임 | 90p | 99p |
|---|---|---|---|---|
| before debug | 115% | 0.27% | 8ms | 13ms |
| after debug | 121% | 0.14% | 9ms | 14ms |
| after release | 111% | 0.07% | 6ms | 10ms |

- 차이는 측정 오차 안이다.
- 이유는 두 가지다.
  - 지도는 MapLibre GL 스레드가 그려 `gfxinfo`에 잡히지 않는다.
  - CPU 대부분은 앱을 켠 뒤 뒤에서 도는 일(정밀 탐지 GPU 준비 등)이다.
- 궤적 0.3초 제한으로 궤적 끝이 기체에서 떨어지지 않는 것은 화면으로 확인했다.

**빌드 형식 결정**(B-DEBUG): release가 시작 -71%, 프레임 90·99p도 낮다. 20% 기준을 넘으므로 **현장 배포는 release(`installRelease`)** 로 한다.
- 서명: debug 키라 debug 위에 데이터를 지우지 않고 덮어 설치된다.
- R8: 계속 끈다.
- 잃는 것: `run-as`(앱 DB 직접 보기). 운용 로그·사진은 외부 폴더라 그대로 볼 수 있다.

**기체 연결 측정**(M3E 지상, 모터 끔, 같은 날 오후, 원시 `raw/cam-*.txt`·`raw/save-after-release.txt`)
- 카메라 뷰(FPV 켜짐) 30초, before-debug와 after-debug를 5회씩 번갈아 잼.

| 지표 | before-debug | after-debug | after-release |
|---|---|---|---|
| CPU 평균 | 100% (98~105) | 101% (95~112) | 60%, 93% |
| 30초 프레임 수 | 약 1540 | 약 1540 | 약 1540 |
| 프레임 50p 5ms 회차 / 18ms 회차 | 3 / 2 | 2 / 3 | 0 / 2 |

- 차이는 오차 안이다. 프레임 수는 FPV 영상이 정한다(약 51fps).
- 50p가 5ms와 18ms 두 갈래로 나뉘는 것은 두 빌드 모두에서 나왔다. 온도는 정상(CPU 27℃)이었다. 앱 코드와 무관한 RC 쪽 상태로 본다.
- 지상에서 기체가 멈춰 있으면 DJI 키 값이 거의 안 바뀐다. 그래서 샘플링 효과는 비행(시뮬레이터) 중에만 드러난다.
- B-SAVE(RC 화면 셔터 5장, 8.6MB `_V`): 받기 2.24~2.39초, 파일 저장 14~24ms.
  - 예전에 실행기 메시지 루프를 막던 것은 저장 부분이다. 그 시간이 장마다 약 20ms(미션 사진은 DB 갱신이 더해짐)였으므로 평소 이득은 작다.
  - SD·DB가 느려질 때 명령이 밀리지 않게 하는 안전 개선으로 본다.

- 시뮬레이터 비행(프로펠러 제거, 스틱으로 계속 움직임, 8m/s·44m, 각 1회, `raw/simfly-*.txt`): CPU before 120% / after 125%, 30초 프레임 1056 / 1149, 99p 22 / 23ms. 차이는 오차 안이다.
  - 텔레메트리 샘플링의 이득은 FPV·탐지 부담에 묻혀 드러나지 않는다.
  - 이 측정 중 사용자 결정(11-07): 비행 중 자동 탐지를 빼고 누를 때만 탐지한다(9.6). 비행 중 GPU 추론이 사라진다.

**측정하지 못한 것**
| 항목 | 이유 |
|---|---|
| 배경 미리 받기 | 인터넷과 새 영역이 필요하다. 다음에 받을 때 `prefetchDone.ms`로 남는다. |

## 5. 쓴 최적화 기법과 위치

| 기법 | 적용 | 파일 |
|---|---|---|
| 샘플링(conflation) | 텔레메트리 → 화면 상태 100ms | `ui/flight/FlightViewModel.kt`, `ManualFlightViewModel.kt` (`AppConfig.Polling.UI_FRAME_MS`) |
| 갱신 간격 제한 | 지도 궤적 다시 그리기 300ms | `ui/flight/FlightMap.kt` (`AppConfig.Map.TRACK_DRAW_MS`) |
| 지연 계산 | 전주 전체 목록은 처음 화면을 맞출 때만 만든다 | `FlightMap.kt` |
| 캐시 크기 맞춤 | `rememberTextMeasurer(cacheSize = 16)` | `ui/flight/NavDisplay.kt` |
| 일을 다른 코루틴으로 | 사진 저장을 메시지 루프 밖(사진 받기 코루틴·도착 단계)으로. 순서 유지, 단계가 취소돼도 받은 사진은 끝까지 저장(`NonCancellable`) | `core/.../InspectionExecutor.kt` |
| 배치 트랜잭션 | 타일 200장마다 커밋. 끊겨도 받은 것은 남긴다 | `data/SatelliteCache.kt` (`PREFETCH_COMMIT_TILES`) |
| 조회 묶기(N+1 제거) | 있는 타일을 줌마다 한 번에 읽어 `HashSet` | `SatelliteCache.kt` |
| 연결 재사용 | HTTP keep-alive(`disconnect` 뺌) | `SatelliteCache.kt` |
| 버퍼 미리 할당 | 다운로드 버퍼를 파일 크기만큼(상한 64MB) | `drone/DjiDroneGateway.kt` |
| 메모이제이션 + LRU | 썸네일 캐시(키에 파일 크기·수정 시각), 앨범·판독 썸네일·홈 앨범 타일 | `ui/album/AlbumScreen.kt` `PhotoThumbs` |
| 동시성 제한 | `Dispatchers.IO.limitedParallelism(2)` | `PhotoThumbs` |
| 메모리 형식 | 썸네일 RGB_565 | `PhotoThumbs` |
| 불필요한 작업 제거 | ZIP 안 JPEG 다시 압축 안 함, 경로 지점 펼치기 1번만 | `data/MissionExport.kt`, `ui/MapScreen.kt` |
| 시간 예산 | TSP 마감 시간 공유, 바퀴 안에서도 확인 | `core/route/RoutePlanner.kt`, `TspSolver.kt` |
| 빌드 캐시·병렬 | Gradle | `gradle.properties` |

## 6. 검토(리뷰)와 확인

- 빌드·테스트·lint: `./gradlew.bat :core:test :app:compileDebugKotlin :app:testDebugUnitTest lint` 통과. lint는 오류 0, 경고 96개로 before와 같다.
- 새 테스트 2개
  - `InspectionExecutorTest`: 사진 저장이 느려도 RTH 명령은 바로 처리하고, 받은 사진은 RTH 뒤에도 저장된다. 옛 코드에서 실패하는 것을 확인했다.
  - `RoutePlannerPerfTest`: 전주 1000개 계획이 한도 + 1초 안에 끝난다.
- 독립 리뷰(`code-review`, medium): 결과는 아래 7장.

## 7. 리뷰 결과

독립 리뷰 에이전트가 diff를 보고 10건을 지적했다. 9건은 확인한 뒤 고쳤고, 1건은 고치지 않았다.

| 지적 | 판단 | 조치 |
|---|---|---|
| 장애물 표시 100ms 제한이 마지막 값을 버림(값이 바뀔 때만 오면 옛 거리가 남음) | 맞음 | 장애물 제한을 되돌림(표시 정확성 우선) |
| 도착 단계 안 저장이 RTH 취소로 끊겨 받은 사진을 잃음 | 맞음 | `NonCancellable`로 끝까지 저장, 테스트에 확인 추가 |
| 타일 오류 응답(404·429)에서 연결이 안 닫힘 | 맞음 | 200이 아니면 `disconnect()` |
| 커밋 실패 시 `finally`가 다른 예외로 원래 오류를 가림 | 맞음 | `inTransaction()`일 때만 커밋 |
| 쓰는 중인 파일을 푼 썸네일이 캐시에 남음 | 맞음 | 캐시 키에 파일 크기·수정 시각 |
| 다운로드 버퍼 `toInt()` 넘침(2GB 이상) | 맞음(사진만 받지만 막음) | 상한 `AppConfig.Polling.DOWNLOAD_BUFFER_MAX` |
| 다시 보기 큰 그림이 RGB_565 썸네일 캐시를 씀 | 맞음 | 다시 보기는 원래 코드로 되돌림 |
| TSP 한도 공유로 소티 재최적화가 굶음 | 맞음 | 전체 순회 절반 + 나머지 |
| `cacheSize = 16` 숫자 박기(CLAUDE.md 규칙) | 맞음 | `AppConfig.CameraView.NAV_TEXT_CACHE` |
| 다시 보기 감싸기 함수·남는 import | 맞음 | 되돌림으로 해결 |
| 판독 썸네일 `16` 숫자 | 원래 있던 값 | 그대로 둠 |

## 8. 하지 않은 것과 이유

| 항목 | 이유 |
|---|---|
| R8(난독화·축소) 켜기 | DJI SDK 리플렉션과 `Helper.install` 위험이 있고, 전 기능 현장 회귀가 필요하다. |
| baseline profile 모듈 | macrobenchmark 모듈과 기기 수집이 필요하다. B-DEBUG 결과를 본 뒤 정한다. |
| 큰 뷰어·판독 그림 RGB_565 | 메모리는 절반이 되지만, 하늘 같은 그러데이션에 띠가 생겨 판독 품질에 영향이 있다. 썸네일에만 썼다. |
| 장애물 표시 갱신 제한 | 해 봤으나 리뷰에서 마지막 거리를 잃는 문제가 나와 되돌렸다. 필요하면 화면 쪽에서 `sample`(뒤 값 보존)로 다시 한다. |
| 다시 보기 사진 캐시 | 큰 그림(1/4)이라 썸네일 캐시를 밀어내고 RGB_565 띠가 생긴다. |
| 지도 라벨 `mutableStateOf` 비교 추가 | 기본 정책(structural equality)이 이미 같은 리스트면 다시 그리지 않는다. 탐색 결과 오판. |
| `visitKey` 키 바꾸기 | 화면 갱신을 10Hz로 묶은 뒤에는 이득이 작다. |
| 라벨 SymbolLayer 전환, 지도·카메라 `movableContentOf` | 화면 동작이 바뀐다. RC 측정에서 남으면 한다. |
| OpLog 파일 열기·포맷터 | 초당 1~2줄이라 이득이 µs 단위. 시작할 때 메인 스레드 파일 작업도 몇 ms. |
| XlsxReader 첫 시트만 풀기 | 가져오기는 드물고 파일은 10MB 이하. |
| Room 인덱스(migration 12) | 지금 데이터 양에서는 이득이 측정되지 않는다. |
| YoloDecoder argmax·NMS | 추론 시간에 비해 ms 단위. 탐지기 핵심은 이미 버퍼 재사용·`getPixels`·`inSampleSize`·GPU delegate로 되어 있다. |
