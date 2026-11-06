package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning

/** 앱이 구분해야 하는 기체 비행 모드. 어댑터가 DJI 값을 이 값으로 바꾼다. */
enum class FlightMode { ON_GROUND, TAKING_OFF, MISSION, MISSION_PAUSED, MANUAL, GO_HOME, LANDING, UNKNOWN }

/** 기체 텔레메트리 한 샘플. [altitudeM]은 이륙 지점 기준 상대 고도다. */
data class Telemetry(
    val position: GeoPoint?,
    val altitudeM: Double,
    val speedMps: Double,
    val batteryPct: Double,
    val flightMode: FlightMode,
    val gpsLevel: Int,
    val connected: Boolean,
    val home: GeoPoint?,
    /** 기수(정북 0°, 시계 방향)와 짐벌 피치. 내비게이션 표시와 비행 궤적 기록에 쓴다. */
    val headingDeg: Double = 0.0,
    val gimbalPitchDeg: Double = 0.0,
    /** 짐벌 요(기수 기준, 오른쪽 +). AR 투영(10.5.2)이 기수에 더한다. M3E 짐벌은 거의 돌지 않아 보통 0. */
    val gimbalYawRelDeg: Double = 0.0,
    /** 착륙 직전(지면 약 0.5~2m) 기체가 조종사 확인을 기다림(착륙 보호). Pilot 2의 [착륙 확인]과 같다. */
    val landingConfirmNeeded: Boolean = false,
    /** Pilot 2 상단 바·내비게이션 표시용: 위성 수, 영상·조종 신호 품질(0~100, 모르면 null), 수직 속도(상승 +). */
    val satellites: Int = 0,
    val signalPct: Int? = null,
    val verticalSpeedMps: Double = 0.0,
    /** 조종기 GPS 위치(지도의 파란 점, 10.5.4). 모르면 null. */
    val rcPosition: GeoPoint? = null,
    /** 모터가 도는 중(땅에서 시동 포함). 충돌 경고 신호음은 모터가 돌거나 떠 있을 때만 낸다(10.5.3). */
    val motorsOn: Boolean = false,
    /** 기체 배터리 전압(V)·온도(℃): 상단 바 배터리와 팝업(10.5.1). 모르면 null. */
    val batteryVoltageV: Double? = null,
    val batteryTempC: Double? = null,
    /** 기체 배터리 경고 문턱(%, 낮음·심각). 모르면 null([Tuning.Battery]). */
    val lowBatteryPct: Int? = null,
    val criticalBatteryPct: Int? = null,
    /** 기체가 계산한 RTH에 필요한 배터리(%)와 남은 비행 시간(초)(배터리 띠 H·시간 라벨). 모르면 null(앱 추정). */
    val rthBatteryPct: Int? = null,
    /** 기체가 계산한 지금 높이에서 착륙에 필요한 배터리(%)(배터리 띠 빨강 끝). 모르면 null(심각 문턱). */
    val landBatteryPct: Int? = null,
    val remainingFlightSec: Int? = null,
    /** 풍속(m/s)·풍향(정북 0°, 시계 방향)(내비 WS). 모르면 null. */
    val windSpeedMps: Double? = null,
    val windDirDeg: Double? = null,
    /** 해발 고도(m, 내비 ASL). 모르면 null. */
    val altitudeAslM: Double? = null,
    /** RC 비행 모드 스위치 글자(N/S/F, 상단 바 "N 모드"). 모르면 null(N으로 보임). */
    val rcMode: Char? = null,
) {
    val isFlying: Boolean get() = flightMode != FlightMode.ON_GROUND && flightMode != FlightMode.UNKNOWN

    /**
     * GNSS 없이 비전으로 위치를 잡는 중(Pilot 2 "N 모드 - 비전 포지셔닝", 11-04 실내 녹화: 위성 0).
     * ponytail: GNSS 신호 등급만 본다. 비전도 없는 ATTI는 `KeyVisionPositioningLevel`로 가를 수 있다(실기 확인 뒤).
     */
    val visionPositioning: Boolean get() = connected && gpsLevel <= 1
    val lowPct: Int get() = lowBatteryPct ?: Tuning.Battery.LOW_PCT
    val criticalPct: Int get() = criticalBatteryPct ?: Tuning.Battery.CRITICAL_PCT

    companion object {
        val DISCONNECTED = Telemetry(null, 0.0, 0.0, 0.0, FlightMode.UNKNOWN, 0, false, null)
    }
}

/** 사진 촬영 모드(10.5.7 Pilot 2 촬영 모드 메뉴): 단일 · 타이머(카메라 인터벌) · 파노라마 · SMART(저조도 사진). */
enum class ShootMode(val label: String) { SINGLE("단일"), TIMER("타이머"), PANORAMA("파노라마"), SMART("SMART") }

/** 기체가 받는 사진 모드와 타이머 간격(초, 오름차순). 간격을 모르면 빈 목록(앱 기본 간격). */
data class PhotoModes(val modes: Set<ShootMode>, val intervalsSec: List<Double> = emptyList()) {
    companion object {
        /** 기체에 묻기 전(또는 못 읽음): Pilot 2 기본 두 모드. */
        val BASIC = PhotoModes(setOf(ShootMode.SINGLE, ShootMode.TIMER))
    }
}

/**
 * 촬영 모드를 고를 수 없는 이유(10.5.7 안내문). 고를 수 있으면 null.
 * 파노라마는 이 앱에서 찍지 않는다: 기체가 제자리에서 돌며 여러 장을 찍어 전주 점검 사진이 아니고, 타일을 전주에 붙일 수 없다. 땅에서는 Pilot 2 문구 그대로.
 */
fun shootModeBlock(mode: ShootMode, supported: Set<ShootMode>, flying: Boolean): String? = when {
    mode == ShootMode.PANORAMA -> if (flying) "파노라마 촬영 불가(이 앱은 지원하지 않음, Pilot 2에서 촬영)" else "기체 비행 전. 파노라마 촬영 불가"
    mode !in supported -> "이 기체는 ${mode.label} 촬영을 지원하지 않습니다"
    else -> null
}

/** 초점 모드(Pilot 2 노출 띠 AFS·AFC·MF, 누르면 차례로 바뀜). */
enum class FocusMode {
    AFS, AFC, MF;

    fun next(): FocusMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 카메라 노출·초점·저장 값(10.5.2 노출 띠): 기체 값을 글자로(ISO "100", 셔터 "1/640", 조리개 "2.8", EV "0" / "-0.3").
 * 모르는 값은 null(화면은 "--"). [photosLeft]는 남은 사진 장수, [recordSecLeft]는 남은 녹화 시간(초).
 */
data class CameraStatus(
    val iso: String? = null,
    val shutter: String? = null,
    val aperture: String? = null,
    val ev: String? = null,
    val aeLocked: Boolean = false,
    val focus: FocusMode? = null,
    val photosLeft: Int? = null,
    val recordSecLeft: Int? = null,
    /** 기체 카메라의 사진 모드(`KeyCameraMode`). 영상·모름이면 null. 화면을 열 때 촬영 모드 메뉴 값을 여기에 맞춘다(10.5.7). */
    val shootMode: ShootMode? = null,
    /** 망원 줌 배율(`KeyCameraZoomRatios` 알림, RC 다이얼로 바꿔도 온다). 모르면 null(화면은 앱이 보낸 값). 2026-11-06: 화면 8.0X인데 기체 7.0이었음. */
    val zoomRatio: Double? = null,
    /** 기체가 녹화 중인지(`KeyIsRecording` 알림). 모르면 null. 화면 녹화 표시는 이 값을 따른다(2026-11-06: 앱 추정과 기체가 어긋나 멈춤이 실패). */
    val recording: Boolean? = null,
)

/** 계획 화면의 지금 위치(10.5.5): 기체(연결되고 위성이 충분할 때)와 기수, 조종기 GPS. 모르면 null. */
data class LivePositions(val aircraft: GeoPoint? = null, val headingDeg: Double = 0.0, val rc: GeoPoint? = null) {
    /** 자동 이륙 지점 후보와 출처(운용 로그 값): 기체 → DJI 조종기 GPS → 조종기 안드로이드 GPS([device]) 중 있는 첫 것. */
    fun homeCandidate(device: GeoPoint?): Pair<GeoPoint, String>? =
        aircraft?.let { it to "aircraft" } ?: rc?.let { it to "rc" } ?: device?.let { it to "device" }

    companion object {
        private val HOME_RANK = listOf("device", "rc", "aircraft")

        /** 자동으로 정한 이륙 지점(출처 [current])을 [candidate] 출처로 올릴지: 더 믿을 만할 때만(기체 > DJI 조종기 > 안드로이드). */
        fun upgradesHome(current: String?, candidate: String): Boolean = HOME_RANK.indexOf(candidate) > HOME_RANK.indexOf(current)
    }
}
