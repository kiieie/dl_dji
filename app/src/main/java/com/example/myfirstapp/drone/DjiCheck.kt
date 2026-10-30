package com.example.myfirstapp.drone

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.flightcontroller.RemoteControllerFlightMode
import dji.v5.manager.KeyManager
import dji.v5.manager.ldm.LDMManager
import kotlinx.coroutines.withTimeoutOrNull

/** 기체 점검 한 줄(10.4.3 ⑧): 이름, 값, 판정, 확인 필요할 때 [guide](보정 안내 등). */
data class CheckItem(val name: String, val value: String, val level: AircraftStatus.Level, val guide: String? = null)

/**
 * 기체 점검 결과: 기체·조종기 정보 한 줄과 항목. [flightMode]는 RC 모드 스위치(F/N/S, M3E의 N은 SDK에서 P).
 * 기체가 없으면 [connected] 거짓이고 항목은 조종기·LDM만.
 */
data class AircraftCheck(
    val connected: Boolean = false,
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val rcSerial: String? = null,
    val rcFirmware: String? = null,
    val flightMode: Char? = null,
    val items: List<CheckItem> = emptyList(),
) {
    /** 확인 필요 항목이 맨 위(10.4.3). */
    val sorted get() = items.sortedBy { if (it.level == AircraftStatus.Level.OK) 1 else 0 }
    val issues get() = items.count { it.level != AircraftStatus.Level.OK }
}

private val OK = AircraftStatus.Level.OK
private val WARN = AircraftStatus.Level.WARN
private val ERROR = AircraftStatus.Level.ERROR

/** 나침반 보정 안내(Pilot 2 나침반 보정 순서). 앱은 보정을 시작하지 않는다(조종사가 확인하고 Pilot 2식 절차로). */
const val COMPASS_GUIDE = "철골·자동차·전선에서 떨어진 곳에서 기체를 수평으로 들고 한 바퀴, 기수를 아래로 세워 한 바퀴 돌립니다. 끝나면 [다시 점검]으로 확인하세요."

/** SDK에서 기체 점검 값을 읽는다(각 2초 안, 못 읽으면 "--"). */
suspend fun djiAircraftCheck(): AircraftCheck = runCatching {
    val keys = KeyManager.getInstance()
    suspend fun <T> read(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = withTimeoutOrNull(2_000) {
        val key = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        runCatching { djiValue<T> { keys.getValue(key, it) } }.getOrNull()
    }
    val ldm = runCatching { LDMManager.getInstance().isLDMEnabled }.getOrDefault(false)
    val ldmItem = CheckItem("LDM", if (ldm) "켜짐 · 외부 전송 막음" else "꺼짐", if (ldm) OK else ERROR)
    val rcSerial = read(RemoteControllerKey.KeySerialNumber)
    val rcFirmware = read(RemoteControllerKey.KeyFirmwareVersion)
    if (!keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false)) {
        return AircraftCheck(rcSerial = rcSerial, rcFirmware = rcFirmware, items = listOf(CheckItem("기체 연결", "연결 안 됨", ERROR), ldmItem))
    }
    val mode = when (read(FlightControllerKey.KeyRemoteControllerFlightMode)) {
        RemoteControllerFlightMode.P -> 'N'
        RemoteControllerFlightMode.S -> 'S'
        RemoteControllerFlightMode.F -> 'F'
        RemoteControllerFlightMode.T -> 'T'
        else -> null
    }
    val compassError = read(FlightControllerKey.KeyCompassHasError)
    val imuWarming = read(FlightControllerKey.KeyIsIMUWarmingUp)
    val pct = read(BatteryKey.KeyChargeRemainingInPercent)
    val mv = read(BatteryKey.KeyVoltage)
    val temp = read(BatteryKey.KeyBatteryTemperature)
    val sats = read(FlightControllerKey.KeyGPSSatelliteCount)
    val home = read(FlightControllerKey.KeyIsHomeLocationSet)
    val rth = read(FlightControllerKey.KeyGoHomeHeight)
    val limit = read(FlightControllerKey.KeyHeightLimit)
    val camera = ComponentIndexType.LEFT_OR_MAIN
    val sdState = read(CameraKey.KeyCameraSDCardState, camera)?.name
    val sdFree = read(CameraKey.KeySDCardRemainSpace, camera)
    val items = listOf(
        CheckItem(
            "나침반", when (compassError) { true -> "간섭 감지 · 보정 필요"; false -> "정상"; null -> "--" },
            if (compassError == true) ERROR else OK, COMPASS_GUIDE.takeIf { compassError == true },
        ),
        CheckItem("비행 모드", mode?.let { "$it · 자동 비행은 N에서만" } ?: "--", if (mode == 'N') OK else WARN),
        CheckItem(
            "기체 배터리", listOfNotNull(pct?.let { "$it%" }, mv?.let { "%.1fV".format(it / 1000.0) }, temp?.let { "%.0f°C".format(it) }).joinToString(" · ").ifEmpty { "--" },
            if ((pct ?: 100) < 30) WARN else OK,
        ),
        CheckItem("GNSS", "위성 ${sats ?: "--"} · ${if (home == true) "홈 기록됨" else "홈 기록 전"}", if ((sats ?: 0) >= 8 && home == true) OK else WARN),
        CheckItem("IMU", if (imuWarming == true) "예열 중" else "예열 완료", if (imuWarming == true) WARN else OK),
        CheckItem("RTH 고도", "${rth ?: "--"} m · 최고 고도 ${limit ?: "--"} m", OK),
        CheckItem(
            "저장 공간", if (sdState == null || sdState == "NORMAL") sdFree?.let { "%.1f GB 남음".format(it / 1024.0) } ?: "--" else "SD $sdState",
            if (sdState != null && sdState != "NORMAL") ERROR else if ((sdFree ?: Int.MAX_VALUE) < 2048) WARN else OK,
        ),
        ldmItem,
    )
    AircraftCheck(
        connected = true,
        model = read(ProductKey.KeyProductType)?.name?.let(::modelName),
        serial = read(FlightControllerKey.KeySerialNumber),
        firmware = read(ProductKey.KeyFirmwareVersion),
        rcSerial = rcSerial, rcFirmware = rcFirmware, flightMode = mode, items = items,
    )
}.getOrDefault(AircraftCheck(items = listOf(CheckItem("SDK", "등록 전", ERROR))))
