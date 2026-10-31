package com.example.myfirstapp.drone

import com.example.myfirstapp.AppConfig
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.PhotoFileFormat
import dji.sdk.keyvalue.value.camera.PhotoRatio
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 기체 설정 항목 하나(Pilot 2 설정 탭, 매뉴얼 57쪽). 값은 종류마다 정해진 형으로 오간다:
 * [Kind.Number]는 Int, [Kind.Toggle]은 Boolean, [Kind.Choice]는 선택지 번호(Int), [Kind.Info]는 글(String), [Kind.Action]은 값 없음.
 */
class AircraftSetting(
    val label: String,
    val kind: Kind,
    val note: String? = null,
    internal val read: suspend () -> Any? = { null },
    internal val write: (suspend (Any) -> Unit)? = null,
) {
    sealed interface Kind {
        data class Number(val range: IntRange, val step: Int, val unit: String) : Kind
        data object Toggle : Kind
        data class Choice(val options: List<String>) : Kind
        data object Info : Kind
        /** [confirm]이 있으면 실행 전에 확인을 묻는다(되돌릴 수 없는 동작). */
        data class Action(val confirm: String?) : Kind
    }

    suspend fun load(): Any? = runCatching { withTimeoutOrNull(AppConfig.Timeouts.SETTINGS_READ_MS) { read() } }.getOrNull()
    suspend fun save(value: Any) = write?.invoke(value) ?: Unit
}

/** 탭 이름과 그 탭의 항목. 펌웨어 업데이트·GEO 잠금 해제는 본 앱에서 하지 않는다(7.2, 출동 전 Pilot 2). */
fun djiAircraftSettingTabs(): List<Pair<String, List<AircraftSetting>>> = listOf(
    "비행 제어" to listOf(
        number("RTH 고도", FlightControllerKey.KeyGoHomeHeight, 20..500, 10, "m", "전주·전선보다 충분히 높게. 법정 상한 150m"),
        number("최고 고도", FlightControllerKey.KeyHeightLimit, 20..500, 10, "m", "법정 상한 150m"),
        toggle("거리 제한", FlightControllerKey.KeyDistanceLimitEnabled),
        number("거리 제한 값", FlightControllerKey.KeyDistanceLimit, 15..8000, 100, "m"),
        choice("신호 상실 동작", FlightControllerKey.KeyFailsafeAction, listOf(FailsafeAction.GOHOME to "RTH", FailsafeAction.HOVER to "호버", FailsafeAction.LANDING to "착륙")),
        action("홈포인트를 기체 위치로", FlightControllerKey.KeyHomeLocationUsingCurrentAircraftLocation, null),
        action("컴퍼스 캘리브레이션 시작", FlightControllerKey.KeyStartCompassCalibration, "기체를 들고 안내대로 돌려야 합니다. 시작할까요?"),
        action("IMU 캘리브레이션 시작", FlightControllerKey.KeyStartIMUCalibration, "평평한 곳에서 안내대로 기체 방향을 바꿔야 합니다. 시작할까요?"),
    ),
    "감지" to listOf(
        AircraftSetting(
            "장애물 감지", AircraftSetting.Kind.Toggle, "가는 전선·통신선은 감지하지 못할 수 있습니다(5.6)",
            read = { djiValue<Boolean> { perception.getOverallObstacleAvoidanceEnabled(it) } },
            write = { v -> djiDone { perception.setOverallObstacleAvoidanceEnabled(v as Boolean, it) } },
        ),
        AircraftSetting(
            "장애물 동작", AircraftSetting.Kind.Choice(listOf("제동", "우회", "끄기")), "웨이포인트 미션 중에는 제동",
            read = { avoidance.indexOf(djiValue<ObstacleAvoidanceType> { perception.getObstacleAvoidanceType(it) }) },
            write = { v -> djiDone { perception.setObstacleAvoidanceType(avoidance[v as Int], it) } },
        ),
        distance("수평 경고 거리", warning = true),
        distance("수평 제동 거리", warning = false),
        AircraftSetting(
            "비전 포지셔닝", AircraftSetting.Kind.Toggle,
            read = { djiValue<Boolean> { perception.getVisionPositioningEnabled(it) } },
            write = { v -> djiDone { perception.setVisionPositioningEnabled(v as Boolean, it) } },
        ),
        AircraftSetting(
            "정밀 착륙", AircraftSetting.Kind.Toggle,
            read = { djiValue<Boolean> { perception.getPrecisionLandingEnabled(it) } },
            write = { v -> djiDone { perception.setPrecisionLandingEnabled(v as Boolean, it) } },
        ),
    ),
    "조종기" to listOf(
        choice("스틱 모드", RemoteControllerKey.KeyControlMode, listOf(ControlMode.JP to "모드 1", ControlMode.USA to "모드 2", ControlMode.CH to "모드 3")),
        AircraftSetting("사용자 버튼", AircraftSetting.Kind.Info, read = { "C1 재개 · C2 짐벌 중앙/아래 · 일시정지 버튼은 기체가 처리" }),
        action("조종기 연동(페어링)", RemoteControllerKey.KeyRequestPairing, "기체의 연동 버튼을 눌러야 합니다. 지금 연결이 끊깁니다. 시작할까요?"),
    ),
    "영상 전송" to listOf(
        AircraftSetting("신호 품질", AircraftSetting.Kind.Info, "주파수·채널 설정은 SDK 5.18.0에 없어 Pilot 2에서 합니다", read = { read(AirLinkKey.KeySignalQuality)?.let { "$it%" } }),
    ),
    "배터리" to listOf(
        AircraftSetting("배터리", AircraftSetting.Kind.Info, read = {
            val pct = read(BatteryKey.KeyChargeRemainingInPercent)
            val mv = read(BatteryKey.KeyVoltage)
            val t = read(BatteryKey.KeyBatteryTemperature)
            "${pct ?: "--"}% · ${mv?.let { "%.2fV".format(it / 1000.0) } ?: "--"} · ${t ?: "--"}℃"
        }),
        toggle("스마트 RTH", FlightControllerKey.KeyLowBatteryRTHEnabled, "배터리가 복귀에 필요한 양까지 줄면 기체가 RTH를 권합니다"),
        number("배터리 부족 경고", FlightControllerKey.KeyLowBatteryWarningThreshold, 15..50, 5, "%"),
        number("심각한 부족 경고", FlightControllerKey.KeySeriousLowBatteryWarningThreshold, 10..45, 5, "%"),
    ),
    "짐벌·카메라" to listOf(
        choice("사진 형식", CameraKey.KeyPhotoFileFormat, listOf(PhotoFileFormat.JPEG to "JPEG", PhotoFileFormat.RAW_JPEG to "RAW+JPEG"), camera),
        choice("사진 비율", CameraKey.KeyPhotoRatio, listOf(PhotoRatio.RATIO_4COLON3 to "4:3", PhotoRatio.RATIO_16COLON9 to "16:9"), camera, "4:3 권장(16:9는 위아래가 잘림, 현장 문서)"),
        toggle("짐벌 피치 위로 확장", GimbalKey.KeyPitchRangeExtensionEnabled, null, camera),
        action("짐벌 자동 캘리브레이션", GimbalKey.KeyGimbalCalibrate, "기체를 평평한 곳에 두세요. 시작할까요?", camera),
        AircraftSetting("SD 카드", AircraftSetting.Kind.Info, read = {
            val state = read(CameraKey.KeyCameraSDCardState, camera)
            val free = read(CameraKey.KeySDCardRemainSpace, camera)
            "${state ?: "--"} · ${free?.let { "${it / 1024}GB 남음" } ?: "--"}"
        }),
        AircraftSetting(
            "SD 카드 포맷", AircraftSetting.Kind.Action("SD 카드의 사진이 모두 지워지고 되돌릴 수 없습니다. 비행 후 원본을 받았는지 확인하세요. 포맷할까요?"),
            write = { djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(CameraKey.KeyFormatStorage, camera), CameraStorageLocation.SDCARD, it) } },
        ),
    ),
)

private val keys get() = KeyManager.getInstance()
private val perception get() = PerceptionManager.getInstance()
private val camera = ComponentIndexType.LEFT_OR_MAIN
private val avoidance = listOf(ObstacleAvoidanceType.BRAKE, ObstacleAvoidanceType.BYPASS, ObstacleAvoidanceType.CLOSE)

private fun <T> key(info: DJIKeyInfo<T>, index: ComponentIndexType?) = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)

// 형을 밝혀 써야 콜백 버전 getValue가 골라진다(안 쓰면 기본값을 받는 동기 버전이 골라져 콜백이 오지 않음)
private suspend fun <T> read(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = djiValue<T> { keys.getValue(key(info, index), it) }

private suspend fun <T> write(info: DJIKeyInfo<T>, value: T, index: ComponentIndexType? = null) = djiDone { keys.setValue(key(info, index), value, it) }

private fun number(label: String, info: DJIKeyInfo<Int>, range: IntRange, step: Int, unit: String, note: String? = null) =
    AircraftSetting(label, AircraftSetting.Kind.Number(range, step, unit), note, read = { read(info) }, write = { v -> write(info, v as Int) })

private fun toggle(label: String, info: DJIKeyInfo<Boolean>, note: String? = null, index: ComponentIndexType? = null) =
    AircraftSetting(label, AircraftSetting.Kind.Toggle, note, read = { read(info, index) }, write = { v -> write(info, v as Boolean, index) })

private fun <T> choice(label: String, info: DJIKeyInfo<T>, options: List<Pair<T, String>>, index: ComponentIndexType? = null, note: String? = null) =
    AircraftSetting(
        label, AircraftSetting.Kind.Choice(options.map { it.second }), note,
        read = { read(info, index).let { v -> options.indexOfFirst { it.first == v } } },
        write = { v -> write(info, options[v as Int].first, index) },
    )

private fun action(label: String, info: DJIActionKeyInfo<EmptyMsg, EmptyMsg>, confirm: String?, index: ComponentIndexType? = null) =
    AircraftSetting(label, AircraftSetting.Kind.Action(confirm), write = {
        val k = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        djiValue<EmptyMsg> { keys.performAction(k, it) }
    })

/** 장애물 수평 경고·제동 거리(m, 0.5 단위를 정수로 보여 줌). */
private fun distance(label: String, warning: Boolean) = AircraftSetting(
    label, AircraftSetting.Kind.Number(1..20, 1, "m"),
    read = {
        djiValue<Double> {
            if (warning) perception.getObstacleAvoidanceWarningDistance(PerceptionDirection.HORIZONTAL, it)
            else perception.getObstacleAvoidanceBrakingDistance(PerceptionDirection.HORIZONTAL, it)
        }.toInt()
    },
    write = { v ->
        djiDone {
            if (warning) perception.setObstacleAvoidanceWarningDistance((v as Int).toDouble(), PerceptionDirection.HORIZONTAL, it)
            else perception.setObstacleAvoidanceBrakingDistance((v as Int).toDouble(), PerceptionDirection.HORIZONTAL, it)
        }
    },
)
