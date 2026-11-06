package com.example.myfirstapp.drone

import com.example.myfirstapp.data.OpLog
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.camera.CameraStreamSettingsInfo
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
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

    /**
     * 망원 사진 저장 시험(2026-11-06): "사진 저장 렌즈" 키(`KeyCaptureCameraStreamSettings`)를 키 만드는 방식마다 읽고 광각+망원으로 써 본다.
     * `createKey`로는 읽기·쓰기 모두 -473(CANNOT_START_TASK_VLOTAGE_ALARM). 카메라 설정을 바꾸므로 실내 시험에서만. `--es bench capture`.
     */
    suspend fun captureLenses() {
        val info = CameraKey.KeyCaptureCameraStreamSettings
        val both = CameraStreamSettingsInfo(false, listOf(CameraVideoStreamSourceType.WIDE_CAMERA, CameraVideoStreamSourceType.ZOOM_CAMERA))
        val variants = listOf(
            "createKey" to KeyTools.createKey(info, cam),
            "lensDefault" to KeyTools.createCameraKey(info, cam, CameraLensType.CAMERA_LENS_DEFAULT),
            "lensWide" to KeyTools.createCameraKey(info, cam, CameraLensType.CAMERA_LENS_WIDE),
            "lensZoom" to KeyTools.createCameraKey(info, cam, CameraLensType.CAMERA_LENS_ZOOM),
        )
        for ((name, key) in variants) {
            val read = probe { djiValue<CameraStreamSettingsInfo> { KeyManager.getInstance().getValue(key, it) }.toJson() }
            val set = probe { djiDone { KeyManager.getInstance().setValue(key, both, it) } }
            val after = probe { djiValue<CameraStreamSettingsInfo> { KeyManager.getInstance().getValue(key, it) }.toJson() }
            OpLog.log("keyProbe", "capture/$name", mapOf("read" to read, "set" to set, "after" to after))
        }
        OpLog.log("keyProbe", "capture/done")
    }

    /**
     * 장애물 센서 원시 값(실내 시험 1.12, `--es bench obstacle`): [seconds]초 동안 `ObstacleDataListener`를 받아 2초마다 한 줄
     * (받은 횟수, 수평 최소 mm와 그 방향, 위·아래 mm, 감지 켬, 시뮬레이터 여부). 앱 경고 로직을 거치지 않는다.
     */
    suspend fun obstacleRaw(seconds: Int = 20) {
        val pm = dji.v5.manager.aircraft.perception.PerceptionManager.getInstance()
        var count = 0
        var last: dji.v5.manager.aircraft.perception.data.ObstacleData? = null
        var info: dji.v5.manager.aircraft.perception.data.PerceptionInfo? = null
        val l = dji.v5.manager.aircraft.perception.listener.ObstacleDataListener { d -> count++; last = d }
        val p = dji.v5.manager.aircraft.perception.listener.PerceptionInformationListener { i -> info = i }
        pm.addObstacleDataListener(l)
        pm.addPerceptionInformationListener(p)
        try {
            repeat(seconds / 2) {
                kotlinx.coroutines.delay(2_000)
                val d = last
                val h = d?.horizontalObstacleDistance.orEmpty()
                val min = h.withIndex().filter { it.value > 0 }.minByOrNull { it.value }
                OpLog.log(
                    "keyProbe", "obstacleRaw",
                    mapOf(
                        "count" to count, "sectors" to h.size, "interval" to d?.horizontalAngleInterval,
                        "minMm" to min?.value, "minDeg" to min?.let { it.index * (d?.horizontalAngleInterval ?: 0) },
                        // 방향별 최솟값(기수 0°부터 시계 방향, 칸마다 ±45°): 앞·오른쪽·뒤·왼쪽
                        "front" to sector(h, d?.horizontalAngleInterval, 0), "right" to sector(h, d?.horizontalAngleInterval, 90),
                        "back" to sector(h, d?.horizontalAngleInterval, 180), "left" to sector(h, d?.horizontalAngleInterval, 270),
                        "upMm" to d?.upwardObstacleDistance, "downMm" to d?.downwardObstacleDistance,
                        "horizOn" to info?.isHorizontalObstacleAvoidanceEnabled, "sim" to djiSimulatorOn(),
                    ),
                )
            }
        } finally {
            pm.removeObstacleDataListener(l)
            pm.removePerceptionInformationListener(p)
        }
    }

    /** [center]° ±45° 칸의 0보다 큰 최솟값(mm). */
    private fun sector(h: List<Int>, interval: Int?, center: Int): Int? {
        val step = (interval ?: 0).takeIf { it > 0 } ?: return null
        return h.withIndex().filter { (i, v) -> v > 0 && ((i * step - center + 540) % 360 - 180).let { kotlin.math.abs(it) <= 45 } }.minOfOrNull { it.value }
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
        FlightControllerKey.KeyGPSSatelliteCount to null,
        FlightControllerKey.KeyGPSSignalLevel to null,
        FlightControllerKey.KeyConnection to null,
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
