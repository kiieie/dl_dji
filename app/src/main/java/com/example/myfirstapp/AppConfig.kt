package com.example.myfirstapp

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

        /** 점이 하나뿐일 때 맞추는 확대. */
        const val SINGLE_POINT_ZOOM = 17.0

        /** 내비 원판 가장자리 거리(m). 홈 표시를 이 거리에 맞춰 놓는다. */
        const val NAV_RANGE_M = 200.0
    }

    object Storage {
        /** 조종기 쪽 저장 공간 기준(6.5). SD 여유 기준(6.7)과 같게 1GB. */
        const val MIN_FREE_BYTES = 1L shl 30

        /** 운용 로그 보관 일수(4.4). */
        const val LOG_KEEP_DAYS = 30

        /** 가져오기 파일 크기 상한(10MB). */
        const val IMPORT_MAX_BYTES = 10L * 1024 * 1024
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
    }

    object Rth {
        /** RTH 버튼을 이만큼 길게 눌러야 귀환한다. */
        const val HOLD_MS = 1_000L
    }
}
