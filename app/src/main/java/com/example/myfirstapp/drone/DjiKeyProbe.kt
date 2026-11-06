package com.example.myfirstapp.drone

import com.example.myfirstapp.data.OpLog
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager
import kotlinx.coroutines.withTimeout

/**
 * 실내 기체 시험(`docs/plan/2026-11-04-indoor-bench.md` 4장): 키 이름만 보고 짠 값을 한 번에 읽어 운용 로그(`keyProbe`)에 남긴다.
 * 읽기만 한다. `adb shell am start -n kr.co.dl_dji/com.example.myfirstapp.MainActivity --es bench keys`.
 */
object DjiKeyProbe {
    suspend fun run() {
        val tabs = djiAircraftSettingTabs(fake = false) + ("비행 전 점검" to djiPreflightSettings().all)
        for ((tab, items) in tabs) for (a in items) {
            if (a.kind is AircraftSetting.Kind.Action || a.kind == AircraftSetting.Kind.Pilot2 || a.kind == AircraftSetting.Kind.HomePoint) continue
            OpLog.log("keyProbe", a.id, mapOf("tab" to tab, "value" to probe { a.read() }, "range" to probe { a.readRange() }))
        }
        for ((info, index) in RAW) OpLog.log("keyProbe", info.identifier, mapOf("raw" to probe { raw(info, index) }))
        for (info in LISTEN) OpLog.log("keyProbe", info.identifier, mapOf("listen" to probe { djiFirst(KeyTools.createKey(info)) }))
        OpLog.log("keyProbe", "done")
    }

    private suspend fun probe(block: suspend () -> Any?): String =
        runCatching { withTimeout(PROBE_MS) { block() } }.fold({ it.toString() }, { "ERR " + it.djiDetail() })

    // 형 매개변수로 받아야 콜백 버전 getValue가 골라진다(DjiAircraftSettings의 read와 같음)
    private suspend fun <T> raw(info: DJIKeyInfo<T>, index: ComponentIndexType?): T {
        val k = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        return djiValue<T> { KeyManager.getInstance().getValue(k, it) }
    }

    private const val PROBE_MS = 3_000L
    private val cam = ComponentIndexType.LEFT_OR_MAIN

    /** `getValue`가 응답하지 않아 첫 알림을 기다려 보는 키. */
    private val LISTEN: List<DJIKeyInfo<*>> = listOf(AirLinkKey.KeyRcAntennaRssi, FlightControllerKey.KeyMultipleFlightModeEnabled)

    /** 화면 항목이 가공해 보이는 키의 원래 값(단위·형 확인용). */
    private val RAW: List<Pair<DJIKeyInfo<*>, ComponentIndexType?>> = listOf(
        FlightControllerKey.KeyGoHomeHeightRange to null,
        FlightControllerKey.KeyHeightLimitRange to null,
        FlightControllerKey.KeyDistanceLimitRange to null,
        FlightControllerKey.KeyCustomFunctionMode to null,
        FlightControllerKey.KeyMultipleFlightModeEnabled to null,
        FlightControllerKey.KeyRemoteControllerFlightMode to null,
        FlightControllerKey.KeyFlightMode to null,
        FlightControllerKey.KeyFailsafeAction to null,
        FlightControllerKey.KeyFlightTimeInSeconds to null,
        FlightControllerKey.KeyLEDsSettings to null,
        FlightControllerKey.KeyESCBeepEnabled to null,
        FlightControllerKey.KeyWindSpeed to null,
        FlightControllerKey.KeyWindDirection to null,
        FlightControllerKey.KeyHeightAboveSeaLevel to null,
        FlightControllerKey.KeyIsHomeLocationSet to null,
        FlightControllerKey.KeyHomeLocation to null,
        RemoteControllerKey.KeyControlMode to null,
        RemoteControllerKey.KeyFlightModeSwitchState to null,
        RemoteControllerKey.KeyRecordShutterButtonEnable to null,
        RemoteControllerKey.KeyLockShutterAndRecordButton to null,
        AirLinkKey.KeyFrequencyBand to null,
        AirLinkKey.KeyRcAntennaRssi to null,
        AirLinkKey.KeyBandwidth to null,
        AirLinkKey.KeyDynamicDataRate to null,
        AirLinkKey.KeySignalQuality to null,
        GimbalKey.KeyPitchSmoothingFactor to cam,
        GimbalKey.KeyPitchControlMaxSpeed to cam,
        GimbalKey.KeyYawRelativeToAircraftHeading to cam,
        GimbalKey.KeyGimbalAttitude to cam,
        CameraKey.KeyCameraMode to cam,
        CameraKey.KeyCameraModeRange to cam,
        CameraKey.KeyShootPhotoMode to cam,
        CameraKey.KeyPhotoIntervalShootSettings to cam,
        CameraKey.KeyIntervalModeParamRange to cam,
        CameraKey.KeyCameraZoomRatiosRange to cam,
        CameraKey.KeyCameraZoomRatios to cam,
        CameraKey.KeyCameraVideoStreamSource to cam,
        CameraKey.KeyExposureCompensationRange to cam,
        CameraKey.KeyExposureCompensation to cam,
        CameraKey.KeyExposureSettings to cam,
        CameraKey.KeyAELockEnabled to cam,
        CameraKey.KeyCameraFocusMode to cam,
        CameraKey.KeyCameraSDCardState to cam,
        CameraKey.KeySDCardAvailablePhotoCount to cam,
        CameraKey.KeySDCardRemainSpace to cam,
        CameraKey.KeyPhotoFileFormat to cam,
        CameraKey.KeyPhotoRatio to cam,
    )
}
