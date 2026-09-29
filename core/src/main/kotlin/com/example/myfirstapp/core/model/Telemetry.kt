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
) {
    val isFlying: Boolean get() = flightMode != FlightMode.ON_GROUND && flightMode != FlightMode.UNKNOWN

    companion object {
        val DISCONNECTED = Telemetry(null, 0.0, 0.0, 0.0, FlightMode.UNKNOWN, 0, false, null)
    }
}
