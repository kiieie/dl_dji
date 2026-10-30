# 테스트 전략과 개발 로드맵

> [설계서 목차](../DESIGN.md)의 일부입니다. 장 번호(예: 6.4)는 전체 설계서 기준이며 다른 문서에서 그대로 참조합니다.

---

## 12. 테스트 전략

| 단계 | 대상 | 방법 |
|------|------|------|
| 단위 테스트 (JVM) | 좌표 변환, 다각형 판정, TSP, Split, 에너지 모델, 상태머신, CSV 파서 | JUnit, 기체 불필요. 상태머신은 `FakeDroneGateway`로 모든 전이 검증 |
| 통합 테스트 (에뮬레이터) | UI 흐름 전체 | `FakeDroneGateway` + 가짜 사진 |
| 시뮬레이터 | 실제 SDK 호출(KMZ 업로드, 시작, 정지, 재개) | MSDK V5 `SimulatorManager`로 실제 기체를 연결하고 **프로펠러를 제거한 상태**로 실행 |
| 필드 테스트 | 단계적 비행 | ① 전주 1개 → ② 전주 5개 1소티 → ③ 다중 소티(배터리 교체) → ④ 일시정지·재개·건너뛰기·RTH·통신 상실 시나리오 |
| 성능 | 추론 시간, 다운로드 시간, 구간 업로드 지연 | RC Pro 실측 |

---

## 13. 개발 로드맵

| 단계 | 내용 | 산출물 | 필요 장비 |
|------|------|--------|-----------|
| **0. 설계** (완료) | 본 문서 검토와 확정 | 확정 설계서(v1.0) | — |
| **1. PoC (기술 검증)** | M3E에서 ① KMZ 구간 미션 업로드와 시작 지연 ② stop 후 수동 조종 → 새 미션 재개 ③ 촬영 후 미리보기·원본 다운로드 시간 ④ LDM 등록(오프라인 라이선스로 앱 등록이 되는지, 등록·LDM 중 외부 트래픽 유무) ⑤ RC Pro에서 TFLite 전처리·추론·후처리 시간(C++ 전환 판단, 9.4) ⑥ **하드웨어 일시정지 버튼 감지 지연, 그리고 하드웨어 버튼으로 재개(C1/C2 매핑 또는 일시정지 버튼 재누름 감지)가 가능한지(1순위)** ⑦ RC Pro에 APK 설치와 adb 디버깅 가능 여부 ⑧ 미션 중 장애물 회피 동작 방식(제동/우회)과 하강 접근 시 동작 | PoC 보고서, 방식 A/B 결정 | **M3E + RC Pro Enterprise** |
| **2. 기반** | 멀티 모듈 구성, Room, CSV 가져오기, MapLibre와 오프라인 지도, `FakeDroneGateway` | 지도에서 전주 확인 | Mac + 에뮬레이터 |
| **3. 계획** | 영역 그리기, TSP, 소티 분할, 에너지 모델, 파라미터 UI | 경로 미리보기 | Mac + 에뮬레이터 |
| **4. 비행** | `DjiDroneGateway`, 상태머신, 비행 화면, 알림음, 복구 | 시뮬레이터 비행 성공 | M3E + RC Pro (프로펠러 제거, 시뮬레이터) |
| **5. 탐지·결과** | YOLO 파이프라인(범용 모델), 결과 화면, 내보내기 | 전체 흐름 완성 | RC Pro (추론 성능) |
| **6. 필드 테스트와 개선** | 단계적 실비행, 에너지 모델 보정 | 베타 | M3E + RC Pro + 예비 배터리 |
| **7. 확장** | 전용 모델 적용, DEM 고도 보정, Excel 가져오기, 주기 분석, Matrice 4E/4T 지원 | | |

### 13.0 진행 현황 (2026-10-28)

| 항목 | 상태 | 비고 |
|------|------|------|
| `:core` 모듈(JVM) 추가, `:app`이 참조 | 완료 | |
| 도메인 모델·포트 인터페이스 | 완료 | 3.3 패키지 구성 |
| CSV 가져오기 파서(UTF-8/CP949, 헤더 자동 인식, 검증) | 완료 | 단위 테스트 7개 |
| 경로 계획(투영, 다각형, TSP, Split, 에너지 모델) | 완료 | 단위 테스트 10개, 300개 3초 이내 확인 |
| 상태머신 `InspectionExecutor` + `FakeDroneGateway` | 완료 | 단위 테스트 18개(정상, 일시정지·재개, 재시도, 실패, 연결 끊김, RTH 권고, 다중 소티) |
| Room(`PROJECT`, `POLE`, 가져오기 트랜잭션) | 완료 | `:app` `data/AppDatabase.kt`, 계측 테스트 2개. 나머지 4.2 엔티티는 3·4단계에서 추가 |
| 가져오기 화면(① 프로젝트 목록 + ② 가져오기) | 완료 | SAF 파일 선택, 오류 목록, 미리보기, 저장·삭제. 에뮬레이터에서 수동 확인. 컬럼 매핑 화면은 MVP 이후 |
| MapLibre 오프라인 지도(MBTiles 표시, 전주 개별 점 표시. 클러스터링은 사용자 요청으로 뺌) | 완료 | `ui/MapScreen.kt`. 네트워크를 끈 상태에서 MBTiles 표시 확인. 시험 데이터는 `tools/make_test_data.py`(합성 격자 타일) |
| VWorld 타일 내려받기(F-04) | 보류 | 개발 인증키 발급(2026-09-29, 만료 2027-03-29, `secrets/`). 남은 확인: ① 타일 저장·오프라인 사용이 약관상 허용되는지 VWorld에 서면 문의(미정 2) ② 인증키 활용 API에 WMTS/TMS 추가 ③ 운용 전 운영키 신청. 그동안 MBTiles 파일을 SAF로 추가 |
| 영역 그리기, 경로 미리보기(계획 화면 ③) | 완료 | `ui/MapScreen.kt`. 다각형 그리기(되돌리기), 이륙 지점, 소티별 경로 색과 예상치, 도달 불가 수. 앱 결과를 CSV 재계산으로 대조(`docs/evidence/2026-09-29-plan`) |
| 핵심 파라미터 4개 UI, 전주 개별 포함/제외(F-06), 계획 저장(`MISSION`·`SORTIE`·`VISIT`) | 완료 | 소티별 재최적화(5.4) 추가. 앱 DB를 꺼내 대조(`docs/evidence/2026-09-29-plan-save`). 기본값 저장(DataStore)과 "고급" 패널은 ⑥ 설정 화면에서 |
| ④ 비행 화면(`FakeDroneGateway`, 저장한 `MISSION` 실행, 알림음) | 완료 | `ui/FlightScreen.kt`, `data/RoomRecorder.kt`(`CAPTURE`·`DETECTION`·`FLIGHT_EVENT`). 모의 비행 후 DB·이미지·알림음 대조(`docs/evidence/2026-09-29-flight`). 알림은 신호음으로 대신. `DjiDroneGateway`는 PoC 뒤 |
| ⑤ 결과 화면(전주 목록·사진 보기·이상만 보기), 내보내기 | 완료 | `ui/ResultScreen.kt`, `data/MissionExport.kt`. ZIP을 DB·기기 사진과 대조(`docs/evidence/2026-09-29-result`). 모의 기체 JPEG과 모의 탐지기(`FakeDetector`)로 확인. 결과 지도·원본 일괄 다운로드는 4단계 뒤 |
| AI 탐지 연결(범용 모델) | 완료 | `detect/TfliteYoloDetector.kt`, `:core` `detect/YoloDecoder.kt`(레터박스 복원, 클래스별 NMS, 단위 테스트 3개), `DETECTION.alert`. COCO yolo11n(`tools/export_coco_model.sh`)으로 앱 결과를 PC 기준값과 대조(`docs/evidence/2026-09-29-detect`). 에뮬레이터 추론 약 110ms. 전력 설비 모델과 RC Pro 측정은 뒤에 |
| 결과 화면 `ViewModel` 전환, "검출" 문구, 1차 판독(F-19, `REVIEW`), 내보내기 판정 열 | 완료 | `ui/result/`. Android 개발 규칙(MVVM·UDF) 첫 적용. 판정 3종과 CSV·JSON을 DB와 대조(`docs/evidence/2026-09-29-result`) |
| 비행 화면 `ViewModel` 전환, 소티 완료 화면(보조자 1차 판독 진입) | 완료 | `ui/flight/`. 기체는 `droneFactory`로 주입. Navigation Compose로 판독 화면에 다녀와도 실행기 유지(`docs/evidence/2026-09-29-sortie`) |
| 미션 목록(①-1), 이어하기와 날짜 확인(F-20) | 완료 | `ui/mission/`, `:core` `needsResumeConfirm`. [남은 전주로 새 계획]은 기존 미션을 `PARTIAL`로 닫음(`docs/evidence/2026-09-29-missions`) |
| 프로젝트·계획 화면 `ViewModel` 전환 | 완료 | `ui/ProjectViewModel.kt`, `ui/PlanViewModel.kt`. CSV 읽기·저장·삭제, 영역·경로 계산·계획 저장·지도 파일 복사를 화면 밖으로 옮김. 지도 그리기(MapLibre)만 화면에 남김. 기존 스모크 9개로 회귀 확인 |
| ⑥ 설정 화면(비행 기본값 DataStore, 모델 관리 F-18, 지도 관리, 상태) | 완료 | 10장 ⑥ 설정. `ui/settings/`, `data/SettingsStore.kt`. 프리셋·에너지 보정값 편집은 MVP 이후(`docs/evidence/2026-10-12-settings`) |
| 비행 화면 DJI 배치(FPV 전체, 위쪽 상태바, 오른쪽 아래 미니맵) | 완료(10-29) | 공식 샘플 UX SDK 기본 화면을 RC Pro에서 캡처해 참고(`docs/evidence/reports/2026-10-29-dji-sample`, git 제외). FPV 자리는 검은 화면, 미니맵은 위성 배경 지도(`ui/flight/FlightMap.kt`), 버튼 반투명(`docs/evidence/2026-10-29-flight-layout`) |
| RC Pro 버튼 매핑(비행 화면) | 완료(모의 기체) | RC Pro에서 C1 재개·일시정지 버튼 확인(`docs/evidence/2026-10-29-flight-layout/rckey.log`). 일시정지 → 일시정지, C1 → 재개, RTH → RTH(모의만), C2 → 짐벌(기체 뒤). SDK 초기화·등록을 `InspectionApp`으로 옮김. 포트 `core/port/RcButtons.kt`, 구현 `drone/DjiRcButtons.kt` |
| 1차 판독 5D 버튼, 큰 사진 배치, 판정 배지 | 완료(10-29) | 5D는 SDK 키로 받고 겹침은 `core/input/FiveDGate.kt`로 걸러 냄(단위 테스트 6개). 사진 전체 화면 + 반투명 패널(`docs/evidence/2026-10-29-review-5d`) |
| FPV 영상(비행 화면) | 완료(10-30) | UX SDK는 Maven에 없어 쓰지 않고 `CameraStreamManager` 스트림을 `SurfaceView`에 그린다(`drone/DjiFpv.kt`) |
| 실제 기체 연결 `DjiDroneGateway`(방식 A) | 완료(10-30, 시뮬레이터) | 소티 KMZ 업로드·시작·일시정지·재개, 지점·사진 이벤트, 사진마다 원본 다운로드·탐지·저장, RTH·착륙 확인, 비행 궤적 기록. 대평선 미션으로 한 바퀴 확인(`docs/evidence/2026-10-30-dji-gateway`). 다음: Pilot 2식 화면(06 10장 대응표) |
| DJI Pilot 2식 화면·기체 설정 | 완료(10-30) | 홈(타일·기체 상태 카드·카메라 뷰), 카메라 뷰(상단 바·내비 계기·렌즈·셔터·짐벌·지도), 계획 오른쪽 패널(촬영 방식·경로 정보), 비행 전 점검 체크리스트, 설정 탭(비행 제어·감지·조종기·영상 전송·배터리·짐벌·카메라·일반, SDK로 읽고 씀), 앱 아이콘·이름 "전주 점검". 지도 레이어·중앙 복귀 버튼은 남음 |
| 사용성 개편·운용 로그 | 진행 중(10-30) | 10.0 원칙: 홈(최근 미션·기체 칩·수동 카메라 뷰 분리), 비행경로 마스터-디테일·미션 삭제 완료. 4.4 운용 로그(조작·RC 입력 전부·충돌·비정상 종료 logcat) 완료. 10-30: 전 화면 Pilot 2 맞춤(전체 화면, 글자 0.8배, 계획 68·74쪽, 카메라 뷰 54~57쪽, 경로 알약 [Ⅱ 수동 비행], 설정 목록, 앨범) 완료, 비행 중 판독·검출음 제거(`docs/evidence/2026-10-30-pilot2-ui`). 남음: [Ⅱ] 시뮬레이터 시험, 캘리브레이션 안내, 지형 추적·핀홀 거리 PoC |
| 설비 엑셀·선로 방향·Pilot 2 시안 | 진행 중(10-30) | 엑셀(.xlsx) 직접 가져오기·선로번호·전주규격·설치기기·지선주 제외(4.5, RC Pro에서 대평선 1,643개 확인, `docs/evidence/2026-10-30-excel`), 선로번호로 선로 방향·지점 이름(4.5.6), 4방향 사선 전주마다 4점·기수 관심점(5.6), RC `<`·`○` 키, 위성 캐시, 판독 확대·객체 고르기 완료. Claude Design 시안 8장 확정(10.4), 테마·글꼴 적용. 남음: 화면별 다시 그리기, 목록 5D·스크롤바, 앨범·기체 점검 화면, 사선 경로 시뮬레이터 시험 |
| RC 셔터·수동 비행 사진을 가까운 전주에 연결 | 완료(10-30, 모의 기체) | 6.4 RC 셔터. 어댑터가 새 사진 알림마다 지점·셔터를 가름(`MediaClassifier`, SD 목록 안 씀) → `ShutterPhoto` → 가장 가까운 남은 전주 `MANUAL`(완료 판정에 안 셈). 미션·수동 비행 모두 전주 20m 안일 때만 연결(`SHUTTER_POLE_LINK_M`, `:core` `nearestWithin`, 10-30 50m → 20m). 실제 기체 시험은 남음 |
| 실시간 지형 추적 기본(5.6, 6.3) | 완료(10-30, 단위 테스트) | `FlightParams.terrainFollow`(기본 켬, 미션 JSON 저장), KMZ 고도 모드 `realTimeFollowSurface`, 계획 화면 스위치, 켤 때 사선 하한 24.5m. **현장 PoC 전, 시뮬레이터에서는 지형이 평평해 확인 불가** |
| 방식 A 이동 고도 구간(5.6) | 완료(10-30, 단위 테스트) | 60m 넘는 구간·첫 구간·복귀는 이동 고도로 올라갔다 내려옴(`WpmlWriter.waypoints`), 촬영 지점 번호는 `shotIndex`로 유지, 에너지에 오르내림 추가. **시뮬레이터 확인 필요**(고도 변화, 지점·사진 번호, `safely` 이륙) |
| RC Pro 실기기 설치·DJI SDK 등록 | 완료 | 2026-10-28. 앱 ID를 DJI 앱 키에 맞춰 `kr.co.dl_dji`로 변경(`namespace`는 그대로), `Helper.install`·`registerApp` 추가 후 등록 성공. 모의 비행 전체 흐름 확인(`docs/evidence/2026-10-28-rcpro`) |
| PoC ⑦ APK 설치·adb | 완료 | RC Pro(Android 10, Snapdragon 865)에 USB adb로 설치·디버깅 가능. 시스템 파일 선택 창은 adb 탭에 반응하지 않으나 손가락으로는 정상 |
| PoC ⑤ TFLite 시간(RC Pro) | 완료 | COCO yolo11n fp16, CPU 4스레드, M3E 원본 사진(7MB): 전처리 약 185ms, 추론 약 205ms, 후처리 약 4ms, 합계 약 0.4초(목표 1초). C++ 전환 불필요(9.4). GPU delegate 미적용 |
| PoC ⑥ 하드웨어 버튼 | 완료(10-30, 시뮬레이터) | SDK 키로만 감지(Android 키 이벤트 아님). 미션 중 일시정지 버튼 → 37ms 만에 `INTERRUPTED`(`USER_BREAK`), C1 → `resumeMission` → 중단 웨이포인트부터 재개(`docs/evidence/2026-10-30-aircraft-poc`) |
| PoC ① 미션 업로드·시작 | 완료(10-30, 시뮬레이터) | 현장 KMZ M1(웨이포인트 68개) 업로드 1.2초, 시작 명령 0.4초, 이륙 포함 웨이라인 실행까지 25.6초. 수동 조종 뒤 C1 재개는 중단 지점 복귀 후 진행. 6.1 방식 A를 1차 기본으로 변경(방식 B는 선택지) |
| PoC ④ LDM | 완료(10-30) | 라이선스 로드 → `enableLDM` → 등록 성공, Wi-Fi를 끄고도 등록, 앱 외부 연결 없음(`docs/evidence/2026-10-30-aircraft-poc`). 첫 설치 상태 오프라인 등록은 남음 |
| PoC ③ 촬영·다운로드 | 완료(10-30, 근거리) | M3E 원본 약 8.7MB 1.4~2.1초, 미리보기 약 0.2초(시뮬레이터, 조종기 옆). 새 사진은 `KeyNewlyGeneratedMediaFile`로 집는다. 비행 거리에서 다시 잼 |
| 배경 지도 Cesium ion 위성(온라인, 임시) | 완료(10-29) | 7.4 임시 결정(Google 대체). ion 자산 2(Bing Aerial)를 MapLibre 래스터로. RC Pro에서 확인, `docs/evidence/2026-10-29-cesium` |
| 앱 재시작 복구(DB·SD 대조, 모의 기체) | 완료 | 6.4 복구. `InspectionExecutor`의 `recovery`, `DroneGateway.listMedia`, `ui/flight/FlightViewModel`. 비행 중 앱을 강제 종료하고 다시 켜 `Paused` 복귀와 재개를 확인(`docs/evidence/2026-10-10-recovery`). 실제 기체의 SD 목록과 시각 차이는 PoC에서 |

### 13.1 개발 장비와 진행 순서

**장비 구매**

- Mavic 3 Enterprise 기본 구성에 **DJI RC Pro Enterprise가 포함**되어 있으므로 조종기를 따로 살 필요가 없다. 구매 전에 판매처에서 구성품을 확인한다.
- M3E는 일반 소비자용 조종기(RC-N 계열 + 스마트폰)와 호환되지 않는 것으로 알려져 있다. 따라서 개발과 운용 모두 RC Pro Enterprise를 기준으로 한다.
- 조종기 추가 구매는 개발과 필드 테스트를 동시에 나눠서 할 때만 검토한다.
- 필드 테스트용 예비 배터리(2개 이상)는 다중 소티를 검증하는 데 필요하다.

**기체 없이 가능한 개발**

| 작업 | 환경 |
|------|------|
| CSV 가져오기, Room DB | Mac + Android 에뮬레이터 |
| 오프라인 지도, 영역 그리기 | 에뮬레이터 또는 일반 Android 폰 |
| 경로 계획(TSP, 소티 분할, 에너지 모델) | JVM 단위 테스트 |
| 비행 화면, 상태머신 | `FakeDroneGateway` |
| YOLO 추론 대략 성능 | 일반 Android 폰 |

- M1 Mac의 Android 에뮬레이터는 ARM64라서 DJI SDK 라이브러리 로드까지는 가능하다. 실제 기체 연결과 비행 기능은 동작하지 않는다.

**권장 진행 순서**

1. 기체를 확보하기 전: 2~3단계(기반, 계획)를 `FakeDroneGateway`로 개발한다.
2. M3E 세트(RC Pro Enterprise 포함)를 확보하면 즉시 1단계 PoC를 수행한다. PoC 결과가 비행 제어 방식(6.1) 등 핵심 가정을 확정하므로 가능한 한 일찍 진행한다.
3. PoC 결과를 본 설계서에 반영한 뒤 4단계(실제 비행 연동)로 넘어간다.
