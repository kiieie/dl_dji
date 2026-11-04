package com.example.myfirstapp.core.config

/**
 * 현장 조정값 한곳 정리(2026-10-31). 코드에 숫자를 박지 않고 여기서 고친다.
 * 각 값의 주석에 근거와 현장 실측이 필요한지를 적는다. 설정 화면에서 바꾸는 비행 기본값은 [com.example.myfirstapp.core.model.FlightParams] 필드에 있다.
 */
object Tuning {

    /** 사진 파일 주인 가르기(6.4, `MediaClassifier`). */
    object Photo {
        /** 앱 촬영(화면·RC 셔터) 뒤 이 시간 안에 온 둘째 파일(광각·망원 동시 저장)은 그 촬영 것으로 본다. 현장 실측 필요. */
        const val MULTI_FILE_MS = 1_500L

        /**
         * 촬영 지점에 도착한 뒤 이 시간 안에 온 파일은 앞 지점의 늦은 파일일 수 있다. 렌즈를 알고(파일 종류) 앞 지점에 그 렌즈 자리가 빈 때만 앞 지점으로 본다.
         * 11-03 현장: 지점 자기 사진이 도착 0.5~0.8초 뒤에 와서 시간만으로는 가를 수 없다(렌즈를 모르는 `_V`면 지금 지점 것).
         * ponytail: 파일 이름의 지점 번호(`fileSuffix`)는 KMZ에 넣지만 실제 이름에 붙지 않았다(10-31·11-03). 붙으면 그것으로 대조한다.
         */
        const val LATE_FILE_MS = 1_000L

        /**
         * 방식 A KMZ에서 렌즈마다 촬영 동작을 따로 둔다(광각 → 망원, 2026-11-04). 2026-11-03 현장에서 `payloadLensIndex` `wide,zoom` 한 동작은 파일 1장(`_V`)만 남겼다.
         * false면 옛 방식(한 동작에 렌즈를 쉼표로)이다. 실내 기체 시험에서 두 방식을 비교한 뒤 정한다(13.0).
         */
        const val ACTION_PER_LENS = true

        /** 기체가 돌려준 줌 배율이 단계(1·2·4·7·14·28배)와 이 비율 안이면 그 단계로 본다(6.98 → 7, 10.4.3 줌 +/−). */
        const val ZOOM_SNAP_RATIO = 0.05

        /** 셔터 사진(미션 중 RC 셔터, 수동 비행 셔터)을 전주에 붙이는 최대 거리(6.4, 2026-10-30 사용자 결정 50m → 20m). */
        const val SHUTTER_POLE_LINK_M = 20.0
    }

    /** 실행 상태머신(6.4, `ExecutorConfig` 기본값). */
    object Executor {
        /** 도착 후 처리(촬영~분석) 전체 타임아웃(6.5). 방식 B용 초기값. */
        const val ARRIVAL_TIMEOUT_MS = 60_000L

        /** 구간 업로드·시작 재시도 횟수(6.2). */
        const val LEG_RETRIES = 3

        /** 구간 업로드·시작 재시도 간격(6.2). */
        const val LEG_RETRY_DELAY_MS = 2_000L

        /** RTH 권고 후 무응답이면 자동 RTH까지의 시간(5.5). 운용 정책값. */
        const val RTH_PROMPT_SEC = 10

        /** 복구·재연결 때 기체 미션 일시정지를 기다리는 시간. 현장 실측 필요. */
        const val PAUSE_TIMEOUT_MS = 5_000L
    }

    /** 장애물 경고 등급(`ObstacleTracker`). DJI Pilot 2 기본과 비슷하게 잡았다. 현장 확인 필요. */
    object Obstacle {
        /** 이 거리 이하면 주의. */
        const val CAUTION_M = 10.0

        /** 이 거리 이하면 위험. */
        const val DANGER_M = 5.0

        /** 한 번 오른 등급은 이만큼 더 멀어져야 내린다(깜박임 방지). */
        const val HYSTERESIS_M = 1.0

        /** 어느 방향(위·아래 포함)이든 이 거리 안이면 운용 로그에 `obstacle`을 남긴다(4.4, 11-03 현장에서 판정 근거가 없었음). */
        const val LOG_M = 15.0

        /** 내비 원판 감지 범위(10.5.3, Pilot 2): 이 안의 장애물은 초록 부채꼴. 기체 경고·제동 거리 손잡이 상한과 같다. */
        const val SENSING_M = 16.0
    }

    /** Pilot 2 카메라 뷰 표시 규칙(10.5.1~10.5.3, `FlightDisplay`). */
    object Display {
        /** 내비 원판 가장자리 거리(m): 홈 H·조종기 점이 이보다 멀면 가장자리에 붙는다(Pilot 2 매뉴얼). */
        const val NAV_RIM_M = 16.0

        /** 조종기 점은 기체에서 이보다 멀 때만 보인다(Pilot 2 매뉴얼 5m). */
        const val NAV_RC_MIN_M = 5.0

        /**
         * 줌 배율 1당 광각 대비 배율. Pilot 2는 망원 배율을 광각 기준으로 쓴다(ZOOM 7.0X ≈ 망원 162mm / 광각 24mm 환산, 11-04 녹화의
         * 줌 영역 사각형이 2.0X에서 화면 폭의 절반, 7.0X에서 약 1/7). 실기 화면과 다르면 고친다.
         */
        const val ZOOM_RATIO_PER_WIDE = 1.0
    }

    /**
     * 기체 배터리 경고 문턱(%) 기본값: 기체 값(`KeyLowBatteryWarningThreshold` 등)을 못 읽을 때(모의 기체 포함) 쓴다.
     * 11-03 현장 기체(Pilot 2 비행 전 점검 배터리 경고 손잡이) 값.
     */
    object Battery {
        const val LOW_PCT = 43
        const val CRITICAL_PCT = 22
    }

    /** 비행 전 점검 판정(6.7, `PreflightEvaluator`). */
    object Preflight {
        /** 최소 GPS 위성 수. */
        const val MIN_GPS_SATELLITES = 12

        /** 최소 GPS 신호 등급. */
        const val MIN_GPS_LEVEL = 4

        /** SD 카드 최소 여유 공간(MB). */
        const val MIN_SD_FREE_MB = 1024L

        /** 배터리 온도 허용 범위(℃). 저온에서는 배터리를 예열해야 한다. 초기값이며 필드 테스트로 조정한다. */
        val BATTERY_TEMP_RANGE = 15.0..50.0
    }

    /** RC 5D 버튼 겹침 걸러 내기(`FiveDGate` 기본값). RC Pro 실측(2026-10-29). */
    object FiveD {
        /** 누름을 내보내기 전 기다리는 시간. 그 사이 가운데↔방향이 겹치면 둘 다 버린다(실측: 방향이 0~100ms 먼저 옴). */
        const val SETTLE_MS = 150L

        /** 가운데를 내보낸 뒤 이 시간 안의 방향은 버린다(뗄 때 기울어짐, 실측 약 210ms 뒤). */
        const val AFTER_CENTER_MS = 300L
    }

    /**
     * 에너지 모델(5.5, `EnergyParams` 기본값과 `FakeDroneGateway`).
     * 2026-11-03 현장(M3E, 운용 로그 1 Hz + Pilot 2 화면 기록, `tools/energy_fit.py`, `docs/evidence/2026-11-03-field/energy.md`)으로 보정했다.
     * 실측보다 약 5% 많게 잡는다(배터리 %가 정수이고 자료가 미션 2회·Pilot 2 2회뿐이다).
     */
    object Energy {
        /** 순항 소모율(%/s). 30분 기준. 실측 3 m/s 0.052~0.053, 미션(5~8 m/s, 짧은 이동 섞임) 회귀 0.0545. */
        const val CRUISE_PCT_PER_SEC = 100.0 / (30 * 60)

        /** 호버 소모율(%/s). 31분 기준. 실측 수동 호버 약 0.050, 회귀 0.0514. */
        const val HOVER_PCT_PER_SEC = 100.0 / (31 * 60)

        /** 전주 1개 처리 시간(촬영 + 다운로드 + 분석 + 대기, 방식 B). */
        const val VISIT_SEC = 20.0

        /** 구간 KMZ 생성·업로드·시작 지연(PoC ①에서 실측해 교체). */
        const val LEG_OVERHEAD_SEC = 10.0

        /** 상승·하강 속도. 이착륙과 점검 고도 하강·재상승 비용 계산에 쓴다. */
        const val VERTICAL_SPEED_MPS = 3.0

        /** 계획 예산에서 빼는 RTH 여유(%). */
        const val RTH_MARGIN_PCT = 5.0

        /**
         * 6.1 방식 A(소티 미션 하나로 계속 비행)의 촬영 지점 하나 처리 시간: 멈춤·짐벌·촬영 후 출발.
         * 2026-10-30 시뮬레이터 실측 지점 간격 약 6.5초(이동 포함). 다운로드·분석은 비행과 병렬이라 넣지 않는다.
         * 2026-11-03 현장: 짧은 지점 사이 가감속까지 이 값이 맡아 미션 시간 오차 −5%(#19)·+5%(#20)로 맞아 그대로 둔다.
         */
        const val SHOT_SEC = 6.0
    }

    /** 비행 안전 한도(6.3, 5.6). */
    object Safety {
        /** 법정 최대 고도(m). */
        const val MAX_ALTITUDE_M = 150.0

        /** RTH 고도 = 이동 고도 + 이 값(6.3). */
        const val RTH_MARGIN_M = 10.0

        /** 배터리 예비율 최솟값(%). */
        const val MIN_RESERVE_PCT = 20.0

        /** 배전 전선 높이(전주 16m + 가공 1m, 5.6). 현장 확인 필요. */
        const val WIRE_HEIGHT_M = 17.0

        /** 사선 촬영 때 전선과 지킬 최소 간격(5.6). */
        const val WIRE_CLEARANCE_M = 10.0

        /** 이동 고도 지점을 끼우는 최소 높이 차(m). 같은 자리·같은 높이 지점이 겹치면 기체가 거절할 수 있다. */
        const val MIN_CLIMB_M = 2.0

        /** KMZ 이륙 안전 고도 최솟값(m, WPML `takeOffSecurityHeight`). */
        const val TAKEOFF_SECURITY_M = 20.0

        /** 체크리스트에서 고르는 안전한 이륙 고도 범위(m, Pilot 2 체크리스트·WPML 상한 1500m). */
        val TAKEOFF_SECURITY_RANGE_M = 2.0..1500.0
    }

    /** 경로 계획·전주 가져오기. */
    object Route {
        /** TSP 탐색 시간 한도(ms). */
        const val TSP_TIME_LIMIT_MS = 2_000L

        /** 선로번호 이웃이 이보다 멀면 실제 이웃이 아니라고 본다(대평선 본선 이웃 7~89m). */
        const val MAX_NEIGHBOR_M = 150.0

        /** 전주 좌표 유효 위도 범위(대한민국). */
        val LAT_RANGE = 32.0..39.5

        /** 전주 좌표 유효 경도 범위(대한민국). */
        val LON_RANGE = 124.0..132.0
    }
}
