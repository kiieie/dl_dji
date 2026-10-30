package com.example.myfirstapp.core.model

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
    /** 착륙 직전(지면 약 0.5~2m) 기체가 조종사 확인을 기다림(착륙 보호). Pilot 2의 [착륙 확인]과 같다. */
    val landingConfirmNeeded: Boolean = false,
    /** Pilot 2 상단 바·내비게이션 표시용: 위성 수, 영상·조종 신호 품질(0~100, 모르면 null), 수직 속도(상승 +). */
    val satellites: Int = 0,
    val signalPct: Int? = null,
    val verticalSpeedMps: Double = 0.0,
) {
    val isFlying: Boolean get() = flightMode != FlightMode.ON_GROUND && flightMode != FlightMode.UNKNOWN

    companion object {
        val DISCONNECTED = Telemetry(null, 0.0, 0.0, 0.0, FlightMode.UNKNOWN, 0, false, null)
    }
}
