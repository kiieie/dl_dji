package com.example.myfirstapp.drone

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.camera.CameraSDCardState
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.ldm.LDMManager
import kotlinx.coroutines.withTimeoutOrNull

/** 홈 화면 기체 상태 카드(Pilot 2 상태 관리 HMS 카드, 매뉴얼 53쪽)에 보이는 값. */
data class AircraftStatus(
    val connected: Boolean = false,
    val model: String? = null,
    val batteryPct: Int? = null,
    val satellites: Int? = null,
    val sdState: String? = null,
    val sdFreeMb: Int? = null,
    val ldm: Boolean = false,
    val simulator: Boolean = false,
) {
    /** 녹(이륙 가능)·주(주의)·빨(문제). 연결이 없으면 null. */
    val level: Level?
        get() = when {
            !connected -> null
            sdState != null && sdState != CameraSDCardState.NORMAL.name -> Level.ERROR
            issues.isNotEmpty() -> Level.WARN
            else -> Level.OK
        }

    /** 주의·문제 이유(Pilot 2 HMS처럼 무엇을 확인할지). */
    val issues: List<String>
        get() = buildList {
            if (sdState != null && sdState != CameraSDCardState.NORMAL.name) add("SD 카드 $sdState")
            if ((batteryPct ?: 100) < 30) add("배터리 낮음")
            if ((satellites ?: 0) < 8) add("GNSS 위성 부족")
            if (!ldm) add("LDM 꺼짐")
        }

    enum class Level { OK, WARN, ERROR }
}

/** SDK에서 기체 상태를 읽는다. SDK가 없거나 등록 전이면 연결 안 됨으로 본다. */
suspend fun djiAircraftStatus(): AircraftStatus = runCatching {
    val keys = KeyManager.getInstance()
    suspend fun <T> read(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = withTimeoutOrNull(2_000) {
        val key = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        runCatching { djiValue<T> { keys.getValue(key, it) } }.getOrNull()
    }
    val ldm = runCatching { LDMManager.getInstance().isLDMEnabled }.getOrDefault(false)
    if (!keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false)) return AircraftStatus(ldm = ldm)
    val camera = ComponentIndexType.LEFT_OR_MAIN
    AircraftStatus(
        connected = true,
        model = read(ProductKey.KeyProductType)?.name?.let(::modelName),
        batteryPct = read(BatteryKey.KeyChargeRemainingInPercent),
        satellites = read(FlightControllerKey.KeyGPSSatelliteCount),
        sdState = read(CameraKey.KeyCameraSDCardState, camera)?.name,
        sdFreeMb = read(CameraKey.KeySDCardRemainSpace, camera),
        ldm = ldm,
        simulator = SimulatorManager.getInstance().isSimulatorEnabled,
    )
}.getOrDefault(AircraftStatus())

internal fun modelName(type: String) = when (type) {
    "DJI_MAVIC_3_ENTERPRISE_SERIES" -> "Mavic 3 Enterprise"
    else -> type
}
