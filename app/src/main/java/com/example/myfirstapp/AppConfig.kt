package com.example.myfirstapp

import com.example.myfirstapp.core.config.Tuning
import com.example.myfirstapp.core.model.GeoCategory
import com.example.myfirstapp.core.model.GeoSummary
import com.example.myfirstapp.core.model.HmsIssue
import com.example.myfirstapp.core.model.HmsLevel

/**
 * 앱(:app) 운용 조정값을 한곳에 모은다(2026-10-31). 값은 옮기기 전과 같다.
 * 도메인 조정값은 :core `Tuning`, 화면 크기·글자는 `Dimens`·`FontSize`에 둔다. 모의 기체·PoC 값은 그 파일에 남긴다.
 */
object AppConfig {
    object Timeouts {
        /** DJI 키 값 하나 읽기(기체 점검·상태·지금 위치). */
        const val KEY_READ_MS = 2_000L

        /** 조종기 안드로이드 GPS 새 위치 한 번 기다림(계획 이륙 지점, 기체·DJI 조종기 GPS가 없을 때). */
        const val DEVICE_LOCATION_MS = 10_000L

        /** 실제 기체 연결부(DjiDroneGateway)의 키 값 하나 읽기. */
        const val GATEWAY_KEY_READ_MS = 3_000L

        /** 기체 설정 화면의 설정 값 한꺼번에 읽기. */
        const val SETTINGS_READ_MS = 3_000L

        /** 이만큼 넘게 끊겨야 연결 끊김으로 본다(6.4). */
        const val LINK_LOST_MS = 3_000L

        /** 미션 완료·멈춤 뒤 늦게 온 사진도 지점 사진으로 받는 유예. */
        const val PHOTO_GRACE_MS = 5_000L

        /**
         * 카메라 명령(렌즈·줌·사진/영상·녹화·촬영) 하나의 응답 기다림. 넘으면 실패로 보고 화면 값을 되돌린다
         * (2026-11-03 현장: 콜백이 안 오면 뒤 명령이 모두 멈췄음).
         */
        const val CAMERA_CMD_MS = 5_000L

        /** 재개 전 카메라(녹화 정지·사진 모드·망원 배율 되돌림)를 모두 합쳐 기다리는 최대 시간. 넘으면 그대로 재개한다(어댑터가 단일 사진을 다시 맞춤). 타이머는 따로 끝날 때까지 기다린다(6.4). */
        const val RESUME_CAMERA_MS = 5_000L

        /** 화면 수동 촬영 뒤 새 사진 알림 기다림. */
        const val NEW_PHOTO_MS = 5_000L

        /** SD 목록 받기(미디어 모드 없이). 넘으면 미디어 모드를 켠다. */
        const val SD_LIST_MS = 10_000L

        /** SD 목록 받기(미디어 모드). */
        const val SD_LIST_MEDIA_MODE_MS = 15_000L

        /** HTTP 연결·읽기(위성 주소·타일 받기). */
        const val HTTP_MS = 10_000

        /** 배경 지도 주소를 못 받았을 때 다시 해 보는 간격. */
        const val BACKGROUND_RETRY_MS = 10_000L

        /** 앱 재시작 복구 때 SDK 등록·기체 연결을 기다리는 시간(6.4). */
        const val RECOVER_CONNECT_MS = 20_000L
    }

    object Polling {
        /** 홈 화면 기체 상태 다시 읽기 간격. */
        const val HOME_AIRCRAFT_MS = 2_000L

        /** 계획 화면 지금 위치(기체·조종기) 다시 읽기 간격. 이륙 지점을 못 정했으면 찾을 때까지 계속 읽는다(10.5.5). */
        const val PLAN_LOCATION_MS = 2_000L

        /** 떠 있는 동안 비행 궤적 기록 간격(4.4). */
        const val TRACK_MS = 1_000L

        /** FPV 영상 프레임 크기 다시 읽기 간격(사진·영상 모드를 바꾸면 비율이 바뀜, 10.5.2 격자 자리). */
        const val FPV_SIZE_MS = 1_000L

        /** 계획 입력이 바뀐 뒤 경로를 다시 계산하기 전 기다림. */
        const val REPLAN_DEBOUNCE_MS = 300L

        /** 사진 다운로드 때 SD 목록에서 찾아보는 횟수(막 찍힌 사진은 늦게 올라온다). */
        const val DOWNLOAD_TRIES = 5

        /** 사진 다운로드 때 받는 최신 SD 목록 개수. */
        const val DOWNLOAD_RECENT_COUNT = 10

        /** 사진 다운로드 때 못 찾으면 다시 보기 전 기다림. */
        const val DOWNLOAD_RETRY_MS = 1_000L

        /** SD 대조(6.4 복구)에 받는 최신 SD 목록 개수. */
        const val LIST_MEDIA_COUNT = 100

        /** 미리보기 사진 JPEG 품질. */
        const val PREVIEW_JPEG_QUALITY = 90

        /** 장애물이 가까울 때(`Tuning.Obstacle.LOG_M`) 운용 로그를 남기는 최소 간격(4.4). */
        const val OBSTACLE_LOG_MS = 1_000L

        /** RC 스틱 로그: 가운데에서 이만큼 벗어나야 움직임으로 본다. */
        const val STICK_DEADBAND = 30

        /** RC 스틱 로그: 움직이는 동안 남기는 간격. */
        const val STICK_LOG_MS = 500L
    }

    object Map {
        /** MapLibre 본 타일 캐시 크기(기본 50MB). */
        const val CACHE_BYTES = 1_000_000_000L

        /** 위성 미리 받기 최소 확대(7.4). */
        const val PREFETCH_MIN_ZOOM = 13

        /** 위성 미리 받기 최대 확대(7.4). */
        const val PREFETCH_MAX_ZOOM = 19

        /** 한 번에 미리 받을 타일 수 상한(약 25KB/장이면 500MB). 넘으면 최대 확대를 낮춘다. */
        const val PREFETCH_MAX_TILES = 20_000L

        /** 영역 둘레 여유(도, 약 300m): 이륙 지점·이동 고도 구간이 영역 밖으로 조금 나간다. */
        const val PREFETCH_PAD_DEG = 0.003

        /** 연속으로 이만큼 못 받으면 인터넷이 끊긴 것으로 보고 멈춘다. */
        const val PREFETCH_MAX_FAILS = 5

        /** 큰 지도에서 전주 라벨을 보이는 최소 확대. */
        const val LABEL_MIN_ZOOM = 15

        /** 점이 하나뿐일 때·지도 검색(전주·좌표·주석)·과녁으로 옮길 때의 확대(지금이 더 가까우면 그대로). */
        const val SINGLE_POINT_ZOOM = 17.0

        /**
         * 큰 지도에서 웨이포인트 번호·고도 라벨을 보이는 최소 확대(10.5.4). Pilot 2는 경로 전체가 보이는 확대에서도 보인다(p2s_2·p2s_7).
         * 11-04 3차: 15면 미션 전체 보기(약 13~14)에서 하나도 안 보여 낮춤. 어지러움은 겹침 숨김(소티 순서로 앞의 것만, FlightMap)이 막는다.
         */
        const val WAYPOINT_MIN_ZOOM = 11

        /** 지도 비행 궤적(10.5.4 주황 선): 앞 점에서 이만큼(m) 움직여야 점을 더한다. */
        const val TRACK_MIN_STEP_M = 2.0

        /** 지도 비행 궤적 점 상한. 넘으면 오래된 점부터 버린다(2m 간격이면 약 6km). */
        const val TRACK_MAX_POINTS = 3_000

        /** 지도 검색(10.5.7) 결과 목록 개수. */
        const val SEARCH_LIMIT = 8

        /** 경로 그림 지도 바탕(10.4.10): 범위가 이보다 좁으면(전주 1개) 이만큼(m) 넓혀 맞춘다. */
        const val THUMB_MIN_SPAN_M = 150.0

        /** 경로 그림 지도 바탕: 알맞은 확대에 타일이 없으면 아래·위로 이만큼 단계까지 찾는다. */
        const val THUMB_ZOOM_STEPS = 3

        /**
         * 경로 그림 지도 바탕: 칸의 타일이 없으면(미리 받은 영역 밖) 이만큼 아래 확대까지 조상 타일을 늘려 메운다(5단계 = 32배, 흐려도 빈 색보다 낫다).
         * 미리 받기 최소 확대([PREFETCH_MIN_ZOOM])보다 아래는 사용자 MBTiles에만 있다.
         */
        const val THUMB_ANCESTOR_STEPS = 5

        /** 경로 그림 지도 바탕 한 장에 그리는 타일 수 상한. 넘는 확대는 건너뛴다(큰 영역을 높은 확대로 그리지 않게). */
        const val THUMB_MAX_TILES = 64

        /** 경로 그림 지도 바탕을 동시에 만드는 수. */
        const val THUMB_PARALLEL = 2

        /** 경로 그림 지도 바탕 JPEG 품질(앱 임시 폴더 `thumbs/`). */
        const val THUMB_JPEG_QUALITY = 85
    }

    /** 핀포인트·지도 주석(10.4.7). */
    object Annotation {
        /** 새 핀포인트·선·영역 이름 머리말(Pilot 2는 기기 이름 "M3T-1 8"). 번호는 셋이 함께 쓴다. */
        const val NAME_PREFIX = "M3E"

        /** 핀포인트 안내(위치 레코딩 실패·추가함)를 보이는 시간. */
        const val NOTICE_MS = 3_000L

        /** 미션 ZIP `annotations.geojson`(4.3): 미션 전주·이륙 지점을 둘러싼 상자에서 이만큼(m) 안에 점이 있는 주석만 넣는다. */
        const val EXPORT_MARGIN_M = 500.0
    }

    /** 비행 다시 보기(10.4.6). */
    object Replay {
        /** 궤적 두 점 사이가 이보다 멀면(기록 끊김) 보간하지 않고 머물며, 재생 시간도 이만큼으로 줄인다. 기록 간격은 [Polling.TRACK_MS]. */
        const val HOLD_GAP_MS = 3_000L

        /** 재생 배속(누를 때마다 차례로). */
        val SPEEDS = listOf(1, 4, 16)

        /** 재생 화면 갱신 간격. */
        const val FRAME_MS = 100L

        /** RC 5D ↔ 한 번에 옮기는 재생 시간. */
        const val SEEK_STEP_MS = 10_000L
    }

    object Storage {
        /** 조종기 쪽 저장 공간 기준(6.5). SD 여유 기준(6.7)과 같게 1GB. */
        const val MIN_FREE_BYTES = 1L shl 30

        /** 운용 로그 보관 일수(4.4). */
        const val LOG_KEEP_DAYS = 30

        /** 가져오기 파일 크기 상한(10MB). */
        const val IMPORT_MAX_BYTES = 10L * 1024 * 1024
    }

    /** 앨범 기체 앨범 탭(10.4.9). */
    object Album {
        /** 한 번에 읽는 SD 최신 파일 수(렌즈별 파일 포함). 더 오래된 것은 Pilot 2에서 본다. */
        const val SD_LIST_COUNT = 300

        /** 썸네일·미리보기 하나를 기다리는 시간. */
        const val SD_THUMB_MS = 8_000L

        /** 원본 하나를 받는 시간 상한(약 20MB JPEG). */
        const val SD_DOWNLOAD_MS = 120_000L

        /** 안내문(받음·지움·막힘)을 보이는 시간. */
        const val NOTICE_MS = 3_000L

        /** 홈 앨범 타일(10.5.5): 파일이 남아 있는 사진을 찾아볼 최근 DB 사진 수. */
        const val HOME_RECENT_DB_PHOTOS = 20
    }

    object Checks {
        /** 이보다 낮으면 배터리 경고(%). */
        const val BATTERY_MIN_PCT = 30

        /** 이보다 적으면 GNSS 위성 부족. */
        const val MIN_SATELLITES = 8

        /** SD 여유가 이보다 적으면 경고(MB). */
        const val SD_MIN_FREE_MB = 2048

        /** 지금 위치에 기체 GPS를 쓰는 최소 위성 수. */
        const val LOCATION_MIN_SATELLITES = 6

        /** 조종기 안드로이드 GPS의 마지막 위치를 쓰는 최대 나이(이륙 지점·조종기 점·[조종기 위치로], 10.5.5). */
        const val LOCATION_MAX_AGE_MS = 120_000L
    }

    object Sound {
        /** 신호음 볼륨(0~100). */
        const val VOLUME = 80

        /** 전주 도착 신호음 길이. */
        const val ARRIVED_MS = 150

        /** 점검 정상 신호음 길이. */
        const val INSPECTION_OK_MS = 300

        /** 이상 탐지 신호음 길이. */
        const val ANOMALY_MS = 600

        /** 일시정지 신호음 길이. */
        const val PAUSED_MS = 300

        /** 재개 신호음 길이. */
        const val RESUMED_MS = 300

        /** 배터리 경고 신호음 길이. */
        const val BATTERY_WARNING_MS = 1500

        /** 오류 신호음 길이. */
        const val ERROR_MS = 400

        /** 충돌 경고 짧은 삑 길이. */
        const val OBSTACLE_BEEP_MS = 80

        /** 충돌 위험 삑 간격. */
        const val OBSTACLE_DANGER_GAP_MS = 250L

        /** 충돌 주의 삑 간격. */
        const val OBSTACLE_CAUTION_GAP_MS = 800L
    }

    object Detector {
        /** TFLite 추론 스레드 수. */
        const val THREADS = 4

        /** model.json에 없을 때 쓰는 탐지 점수 기준(9.1). */
        const val SCORE = 0.35

        /** model.json에 없을 때 쓰는 NMS IoU 기준(9.1). */
        const val IOU = 0.5

        /**
         * 비행 중 최근 1장 탐지(9.6)는 사진 가운데 이 비율만 잘라 비행 중 모델(640)에 넣는다. 같은 픽셀 밀도라 작은 설비도 잡힌다
         * (11-03 사진 80장 PC 대조: 640+가운데 0.5 재현율 0.88, 640 전체 0.05). 1이면 자르지 않는다.
         */
        const val LIVE_CROP_FRACTION = 0.5f

        /**
         * 지속 성능 GPU 캐시 만들기(9.7 두 단계 열기, `GpuCachePrep`)를 시작하기 전에 쉴 이유·열린 탐지기 없이 그대로여야 하는 시간(ms).
         * 착륙·화면 닫기 직후 정밀 탐지·비행 중 모델이 먼저 열 틈을 준다. 시작하면 멈출 수 없다(640 60초·1280 7분).
         */
        const val GPU_BUILD_SETTLE_MS = 30_000L
    }

    /** 착륙 뒤 정밀 탐지(9.6, `PreciseDetection`). */
    object Precise {
        /** 이 일수 안에 계획한 미션의 사진만 처리한다(오래된 미션까지 돌리지 않게). */
        const val RECENT_DAYS = 7

        /** 조종기 배터리가 이보다 낮으면 쉰다(%). */
        const val MIN_RC_BATTERY_PCT = 20

        /** 발열 상태(`PowerManager.THERMAL_STATUS_*`)가 이 값 이상이면 쉰다. 3 = SEVERE. 비행 중 1장 탐지도 같은 값으로 쉰다(9.7 C). */
        const val THERMAL_PAUSE_STATUS = 3

        /** 다 끝낸 뒤 모델을 닫기 전에 새 사진을 기다리는 시간(ms, 9.7 O5). 착륙 뒤 한 장씩 받는 사진마다 다시 열지 않게. */
        const val IDLE_CLOSE_MS = 60_000L
    }

    /** 모델 속도 측정(9.6, 검증 화면·adb `--es bench all`). */
    object Bench {
        /** 재기 전에 버리는 실행 수(GPU 셰이더 준비 등). */
        const val WARMUP = 2

        /** 모델·delegate·자르기마다 재는 실행 수. */
        const val RUNS = 10

        /** 비행 중 모델 연속 실행 수(발열 확인). */
        const val SUSTAINED_RUNS = 30

        /** 연속 실행 중 발열 상태를 남기는 간격(회). */
        const val THERMAL_EVERY = 5

        /** 가운데 영역 디코딩(9.7 O1)과 옛 전체 디코딩의 상자가 같다고 보는 IoU 하한과 점수 차 상한. */
        const val BOX_MATCH_IOU = 0.9f
        const val BOX_MATCH_SCORE = 0.02f
    }

    /** 비행 전 점검·웨이포인트 경로 체크리스트(10.5.5, Pilot 2 같은 범위). */
    object Preflight {
        /**
         * RTH·최고 고도 범위(m): Pilot 2 비행 전 점검 "(20~1500 m)". 기체가 붙어 있으면 범위 키(`KeyGoHomeHeightRange`·`KeyHeightLimitRange`)로 읽고,
         * 이 값은 모의 기체·읽기 실패 때 쓴다(2026-11-04). 범위 밖 값은 기체가 거절하거나 고쳐 다시 읽는다. 법정 상한 150m은 우리 점검이 본다.
         */
        val ALTITUDE_RANGE_M = 20..1500

        /** 최대 비행 거리 범위(m). 기체 값은 `KeyDistanceLimitRange`, 이 값은 모의 기체·읽기 실패 때. */
        val DISTANCE_RANGE_M = 15..8000

        /** 배터리 경고 손잡이 범위(%): 매우 낮음 ~ 낮음. */
        val BATTERY_WARN_RANGE = 10.0..50.0

        /** 장애물 제동·경고 거리 손잡이 범위(m). */
        val SENSING_RANGE_M = 0.5..16.0

        /** 상태 줄(배터리·홈·SD·점검 항목) 다시 읽기 간격. */
        const val STATUS_MS = 2_000L


        /** 기체가 없을 때(모의 기체) 비행 전 점검 설정의 처음 값. 이름은 `PreflightSettings` 항목 이름. */
        val FAKE_VALUES: kotlin.collections.Map<String, Any> = mapOf(
            "RTH 고도" to 100, "최고 고도" to 120, "신호 끊김 조치" to 0, "최대 비행 거리" to false, "최대 비행 거리 값" to 5000,
            "조종 스틱 모드" to 1, "배터리 경고" to (Tuning.Battery.CRITICAL_PCT.toDouble() to Tuning.Battery.LOW_PCT.toDouble()), "장애물 회피" to 0,
            "수평 감지" to true, "수평 감지 거리" to (10.0 to 13.8), "상향 감지" to true, "상향 감지 거리" to (1.5 to 7.0),
            "하향 감지" to true, "하향 감지 거리" to (2.0 to 6.3),
        )
    }

    /** ⋯ 기체 설정 탭(10.5.6, `djiAircraftSettingTabs`). */
    object AircraftSettings {
        /** 수평 장애물 제동·경고 거리(m): Pilot 2 "장애물 제동 거리 (1.0m-10.0m)", 경고 1.1~16 m(11-04 녹화). 값 칸 단위 [DISTANCE_STEP_M]. */
        val BRAKE_RANGE_M = 1.0..10.0
        val WARN_RANGE_M = 1.1..16.0
        const val DISTANCE_STEP_M = 0.1

        /** 배터리 부족·심각한 부족 경고(%), 자체 방전 일수. */
        val LOW_BATTERY_RANGE = 15..50
        val SERIOUS_BATTERY_RANGE = 10..45
        val SELF_DISCHARGE_DAYS = 1..10

        /** 배터리 셀 전압 차이(mV)가 이보다 크면 "확인 필요": M3E가 셀 판정 키를 주지 않아 셀 전압으로 본다(2026-11-06). */
        const val CELL_SPREAD_WARN_MV = 100

        /** 짐벌 피치 평활도(Pilot 2 슬라이더 값 8), 피치 최대 속도(%, Pilot 2 50%). */
        val GIMBAL_SMOOTH_RANGE = 0..30
        val GIMBAL_SPEED_RANGE = 1..100

        /** 기체가 없을 때(모의 기체) 처음 값. 열쇠는 `AircraftSetting.id`(방향 칸이 있으면 "칸/이름"). 값은 11-04 Pilot 2 녹화 화면 값. */
        val FAKE_VALUES: kotlin.collections.Map<String, Any> = mapOf(
            "다중 비행 모드 활성화" to true, "비행 모드" to 0, "RTH 고도" to 100, "최고 고도" to 120, "최대 거리" to true, "최대 거리 값" to 5000,
            "신호 끊김 조치" to 0, "균형 선회" to false, "내 드론 찾기" to false,
            "장애물 회피" to 0, "수평/수평 장애물 회피" to true, "수평/장애물 제동 거리" to 10.0, "수평/장애물 경고 거리" to 13.8,
            "상방/상방 장애물 회피" to true, "상방/장애물 제동 거리" to 1.5, "상방/장애물 경고 거리" to 7.0,
            "하방/하방 장애물 회피" to true, "하방/장애물 제동 거리" to 2.0, "하방/장애물 경고 거리" to 6.3,
            "비전 포지셔닝" to true, "정밀 착륙" to true, "스틱 모드" to 1,
            "작동 주파수" to 2, "신호 강도" to "강함 · 100%", "신호 강도(dBm)" to "-60 dBm", "다운링크 대역폭" to "40MHz", "이미지 전송 비트 전송률" to "49.21Mbps",
            "배터리 용량" to "41%", "비행시간" to "00:00", "배터리" to "정상", "배터리 잔량" to "41%", "온도" to "17.3℃", "전압" to "15.07V",
            "충전 사이클 수" to "12", "상세 정보" to "셀 3.77 · 3.77 · 3.76 · 3.77 V · 완충 용량 4800mAh", "스마트 RTH" to false,
            "배터리 부족 경고" to Tuning.Battery.LOW_PCT, "심각한 배터리 부족 경고" to Tuning.Battery.CRITICAL_PCT, "자체 방전 일수" to 10,
            "짐벌 피치 평활도 시작/정지" to 8, "짐벌 피치 최대 속도" to 50,
            "사진 형식" to 0, "사진 비율" to 0, "짐벌 피치 위로 확장" to false, "SD 카드" to "NORMAL · 211GB 남음",
            "LED 설정" to true, "기기 이름" to "모의 기체", "정보" to "Mavic 3 Enterprise(모의)",
        )
    }

    /** HMS·GEO 구역(10.5.5). 모의 값은 기체 연결 안 됨이고 비행 화면 모의 RC 줄 [HMS]를 켰을 때만 보인다(`MockHealth`). */
    object Health {
        val FAKE_ISSUE = HmsIssue("0x16100001", "[모의] 나침반 간섭", "모의 기체 HMS 항목입니다. 비행 화면 모의 RC 줄 [HMS]로 끕니다.", HmsLevel.CAUTION)
        val FAKE_GEO = GeoSummary(120, 1, "[모의] 비행장", 3200.0)

        /**
         * 모의 GEO 지도 구역(지도 레이어 GEO 구역, 10.4.9): (이름, 종류, 기준점에서 동·북 m, 반지름 m, 높이 제한 m). 기준점은 기체 → H → 지도 대체 위치.
         * 종류마다 하나씩이라 색을 한눈에 대조한다.
         */
        val FAKE_ZONES = listOf(
            FakeZone("[모의] 비행장", GeoCategory.RESTRICTED, 900.0, 600.0, 300.0, 0),
            FakeZone("[모의] 비행장 고도", GeoCategory.RESTRICTED, 900.0, 600.0, 700.0, 120),
            FakeZone("[모의] 승인 구역", GeoCategory.AUTHORIZATION, -800.0, 500.0, 350.0, 0),
            FakeZone("[모의] 강화 경고", GeoCategory.ENHANCED_WARNING, -700.0, -600.0, 300.0, 0),
            FakeZone("[모의] 경고 구역", GeoCategory.WARNING, 700.0, -700.0, 400.0, 0),
        )

        data class FakeZone(val name: String, val category: GeoCategory, val eastM: Double, val northM: Double, val radiusM: Double, val limitM: Int)
    }

    /** 카메라 뷰(10.5.2). */
    object CameraView {
        /** 탭 초점 노란 사각형을 보이는 시간(☀ 노출 손잡이를 끄는 동안은 다시 셈). */
        const val FOCUS_MARK_MS = 2_000L

        /** 카메라 뷰 안내문(Pilot 2 토스트 "기체 비행 전. 파노라마 촬영 불가" 등, 10.5.7)을 보이는 시간. */
        const val NOTICE_MS = 2_500L

        /** 카메라 뷰 안내 줄(사진 저장됨·촬영 실패·홈포인트 지정 등, `TimedMessage`)을 보이는 시간. */
        const val MESSAGE_MS = 3_000L

        /** 촬영 모드 메뉴를 바깥 누름으로 닫은 뒤 이 시간 안의 모드 아이콘 누름은 다시 열지 않는다(닫으려고 누른 것, `CameraControl.openShootMenu`). */
        const val MENU_REOPEN_GUARD_MS = 500L

        /** 타이머 간격(초): 기체가 범위(`KeyIntervalModeParamRange`)를 알려 주지 않을 때. M3E 사양 JPEG 0.7~60초. */
        val TIMER_INTERVALS_SEC = listOf(0.7, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 15.0, 20.0, 30.0, 60.0)

        /** 처음 타이머 간격(Pilot 2 11-04 녹화 "2s"). */
        const val TIMER_DEFAULT_SEC = 2.0

        /** 인터벌 촬영 장수. ponytail: 255 = 멈출 때까지(MSDK V4 규칙)로 본다. 실기에서 255장 뒤 멈추면 다시 시작하게 바꾼다. */
        const val INTERVAL_COUNT_CONTINUOUS = 255

        /** 타이머를 멈춘 뒤 사진 모드로 되돌리기 시도 횟수·간격(ms): 찍던 사진 저장 중에는 거절된다(2026-11-06, 마지막 사진은 약 2초 뒤 저장). */
        const val LEAVE_INTERVAL_TRIES = 8
        const val LEAVE_INTERVAL_RETRY_MS = 500L

        /** ☀ 노출 손잡이: 한 단계(1/3 EV)와 범위(Pilot 2 ±3 EV). 끄는 거리는 [com.example.myfirstapp.ui.theme.Dimens.ZoomDragStep]마다 한 단계. */
        const val EV_STEP = 1.0 / 3
        const val EV_MAX = 3.0

        /** SMART(저조도) 사진은 여러 장을 합쳐 늦게 저장된다(Pilot 2 "3s"): 새 사진 알림을 이만큼 기다린다. */
        const val SMART_PHOTO_MS = 15_000L
    }
}
