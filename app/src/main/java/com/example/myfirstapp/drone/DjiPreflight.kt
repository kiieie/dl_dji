package com.example.myfirstapp.drone

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.flightModeLetter
import com.example.myfirstapp.core.port.PreflightReport
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.flightcontroller.ControlChannelMapping
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.sdk.keyvalue.value.flightcontroller.RemoteControllerFlightMode
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.ldm.LDMManager
import kotlinx.coroutines.withTimeoutOrNull

/** 비행 전 점검 상태 줄의 추가 값(10.5.5): 비행 모드 글자(N/S/T/A, `flightModeLetter`)와 기체 배터리 전압(V). */
data class PreflightExtras(val flightMode: Char? = null, val voltageV: Double? = null)

/** RC 비행 모드 스위치 글자. M3E의 N은 SDK에서 P다. */
internal fun RemoteControllerFlightMode?.letter(): Char? = when (this) {
    RemoteControllerFlightMode.P -> 'N'
    RemoteControllerFlightMode.S -> 'S'
    RemoteControllerFlightMode.F -> 'F'
    RemoteControllerFlightMode.T -> 'T'
    RemoteControllerFlightMode.A -> 'A'
    else -> null
}

/** 스위치 F 자리 모드(⋯ 비행 모드 T/N/S · A/N/S, `KeyCustomFunctionMode`): T 또는 A. ponytail: 키 뜻은 이름으로 짐작, 실기에서 Pilot 2 설정과 대조 */
internal fun ControlChannelMapping?.fLetter(): Char? = when (this) {
    ControlChannelMapping.TRIPOD -> 'T'
    ControlChannelMapping.ATTITUDE, ControlChannelMapping.ATTITUDE_GENTLE, ControlChannelMapping.ATTITUDE_NORMAL, ControlChannelMapping.ATTITUDE_SPORT -> 'A'
    else -> null
}

/** GNSS 신호 등급(0~5). */
internal fun gpsLevelOf(g: GPSSignalLevel?) = when (g) {
    GPSSignalLevel.LEVEL_1 -> 1
    GPSSignalLevel.LEVEL_2 -> 2
    GPSSignalLevel.LEVEL_3 -> 3
    GPSSignalLevel.LEVEL_4 -> 4
    GPSSignalLevel.LEVEL_5, GPSSignalLevel.LEVEL_10 -> 5
    else -> 0
}

internal suspend fun <T> readKey(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = withTimeoutOrNull(AppConfig.Timeouts.KEY_READ_MS) {
    val key = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
    runCatching { djiValue<T> { KeyManager.getInstance().getValue(key, it) } }.getOrNull()
}

/** 비행 전 점검 값(6.7)을 키로 읽는다. 비행 화면 기체 연결부와 홈 [카메라 뷰 입력]의 비행 전 점검이 같이 쓴다. */
suspend fun djiPreflightReport(): PreflightReport {
    val connected = runCatching { KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false) }.getOrDefault(false)
    val home = readKey(FlightControllerKey.KeyHomeLocation)?.takeIf { validGeo(it.latitude, it.longitude) }
    return PreflightReport(
        connected = connected,
        gpsSatellites = readKey(FlightControllerKey.KeyGPSSatelliteCount) ?: 0,
        gpsLevel = gpsLevelOf(readKey(FlightControllerKey.KeyGPSSignalLevel)),
        homeRecorded = readKey(FlightControllerKey.KeyIsHomeLocationSet) == true,
        batteryPct = (readKey(BatteryKey.KeyChargeRemainingInPercent) ?: 0).toDouble(),
        batteryTempC = readKey(BatteryKey.KeyBatteryTemperature) ?: 0.0,
        sdFreeMb = (readKey(CameraKey.KeySDCardRemainSpace, ComponentIndexType.LEFT_OR_MAIN) ?: 0).toLong(),
        ldmEnabled = runCatching { LDMManager.getInstance().isLDMEnabled }.getOrDefault(false),
        obstacleAvoidanceOn = runCatching { djiValue<Boolean>(AppConfig.Timeouts.KEY_READ_MS) { PerceptionManager.getInstance().getOverallObstacleAvoidanceEnabled(it) } }.getOrDefault(false),
        rthAltitudeM = (readKey(FlightControllerKey.KeyGoHomeHeight) ?: 0).toDouble(),
        lostLinkActionIsRth = readKey(FlightControllerKey.KeyFailsafeAction) == FailsafeAction.GOHOME,
        home = home?.let { GeoPoint(it.latitude, it.longitude) },
    )
}

/** 상태 줄 추가 값. 못 읽으면 null. */
suspend fun djiPreflightExtras() = PreflightExtras(
    flightMode = flightModeLetter(
        readKey(FlightControllerKey.KeyRemoteControllerFlightMode).letter(),
        readKey(FlightControllerKey.KeyMultipleFlightModeEnabled), readKey(FlightControllerKey.KeyCustomFunctionMode).fLetter(),
    ),
    voltageV = readKey(BatteryKey.KeyVoltage)?.let { it / 1000.0 },
)
