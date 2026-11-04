package com.example.myfirstapp.drone

import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.bandwidthLabel
import com.example.myfirstapp.core.model.signalWord
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIActionKeyInfo
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.airlink.FrequencyBand
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.PhotoFileFormat
import dji.sdk.keyvalue.value.camera.PhotoRatio
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.IntValueConfig
import com.example.myfirstapp.core.model.settingRange
import dji.sdk.keyvalue.value.flightcontroller.ControlChannelMapping
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 기체 설정 항목 하나(Pilot 2 ⋯ 설정 탭, 매뉴얼 57쪽). 값은 종류마다 정해진 형으로 오간다:
 * [Kind.Number]는 Int, [Kind.Decimal]은 Double, [Kind.Toggle]은 Boolean, [Kind.Choice]는 선택지 번호(Int), [Kind.Info]는 글(String),
 * [Kind.Range]는 `Pair<Double, Double>`, [Kind.HomePoint]는 누른 버튼 번호(Int), [Kind.Action]은 값 없음.
 */
class AircraftSetting(
    val label: String,
    val kind: Kind,
    val note: String? = null,
    internal val read: suspend () -> Any? = { null },
    internal val write: (suspend (Any) -> Unit)? = null,
    /** 장애물 감지 설정의 방향 칸(수평·상방·하방): 같은 칸끼리 한 화면에 보인다(Pilot 2 [수평|상방|하방]). */
    val group: String? = null,
    /** [Kind.Number] 범위를 기체에서 읽는다(`KeyGoHomeHeightRange` 등). 없거나 못 읽으면 [Kind.Number.range]. */
    internal val readRange: suspend () -> IntRange? = { null },
) {
    /** 화면 상태·모의 값의 열쇠. 방향 칸마다 이름이 같아("장애물 제동 거리") 칸을 붙인다. */
    val id get() = if (group == null) label else "$group/$label"

    sealed interface Kind {
        /**
         * [big]이 있으면 비행 전 점검 스테퍼처럼 −big −step 값 +step +big(10.5.5). ⋯ 설정 탭에서는 Pilot 2처럼 범위 글 + 값 칸(누르면 숫자 키보드),
         * [slider]면 슬라이더(짐벌 설정).
         */
        data class Number(val range: IntRange, val step: Int, val unit: String, val big: Int? = null, val slider: Boolean = false) : Kind
        /** 실수 값(장애물 제동·경고 거리): 슬라이더 + 값 칸(Pilot 2 장애물 감지 설정). */
        data class Decimal(val range: ClosedFloatingPointRange<Double>, val step: Double, val unit: String) : Kind
        data object Toggle : Kind
        /** [dropdown]이면 Pilot 2 ˅ 목록(신호 끊김 조치·스틱 모드), 아니면 칸 나누기(T/N/S | A/N/S). */
        data class Choice(val options: List<String>, val dropdown: Boolean = false) : Kind
        data object Info : Kind
        /** 두 손잡이 값(낮은 쪽, 높은 쪽): 배터리 경고(매우 낮음·낮음), 장애물 제동·경고 거리(10.5.5). 값은 `Pair<Double, Double>`. */
        data class Range(val range: ClosedFloatingPointRange<Double>, val unit: String) : Kind
        /** [confirm]이 있으면 실행 전에 확인을 묻는다(되돌릴 수 없는 동작). */
        data class Action(val confirm: String?) : Kind
        /** 홈포인트 설정 [기체 H][조종기 A]: 0 기체 위치로, 1 조종기 위치로. */
        data object HomePoint : Kind
        /** MSDK 5.18에 키가 없거나 화면 안내 단계가 필요해 Pilot 2에서 바꾸는 항목: 흐린 줄 "Pilot 2에서 설정"(값을 지어내지 않음). */
        data object Pilot2 : Kind
    }

    suspend fun load(): Any? = runCatching { withTimeoutOrNull(AppConfig.Timeouts.SETTINGS_READ_MS) { read() } }.getOrNull()

    /** 숫자 항목의 범위: 기체 값, 못 읽으면 [Kind.Number.range]. 숫자 항목이 아니면 null. */
    suspend fun loadRange(): IntRange? {
        val k = kind as? Kind.Number ?: return null
        return runCatching { withTimeoutOrNull(AppConfig.Timeouts.SETTINGS_READ_MS) { readRange() } }.getOrNull() ?: k.range
    }
    suspend fun save(value: Any) = write?.invoke(value) ?: Unit
}

/** 모의 기체(기체 연결 안 됨): [store]에 값이 있으면 그것으로 읽고 쓰고, 없으면 원래대로 읽는다(앱이 정한 글). 동작은 하지 않는다. */
internal fun AircraftSetting.mem(store: MutableMap<String, Any>) = AircraftSetting(
    label, kind, note, group = group,
    read = { if (id in store) store[id] else this@mem.read() },
    write = { v -> if (kind !is AircraftSetting.Kind.Action && kind != AircraftSetting.Kind.HomePoint) store[id] = v },
)

/** 카메라 뷰 카메라 설정 아이콘이 여는 탭(우리 것, Pilot 2는 카메라 뷰 ☰ 화면). */
const val CAMERA_SETTINGS_TAB = "카메라 설정"

/**
 * ⋯ 설정 탭(10.5.6): Pilot 2 이름·순서(11-04 녹화 [F2], 매뉴얼 57쪽). RTK는 M3E 기본 구성에 없어 뺀다. 펌웨어 업데이트·GEO 잠금 해제는
 * 본 앱에서 하지 않는다(7.2, 출동 전 Pilot 2). [fake]면(기체 연결 안 됨) 같은 항목을 [AppConfig.AircraftSettings.FAKE_VALUES]로 그린다.
 * [general]은 일반 탭 앞의 앱 항목(지도 전환 등, `appGeneralSettings`). [CAMERA_SETTINGS_TAB]은 우리 탭(추가).
 */
fun djiAircraftSettingTabs(fake: Boolean, general: List<AircraftSetting> = emptyList()): List<Pair<String, List<AircraftSetting>>> {
    val store = AppConfig.AircraftSettings.FAKE_VALUES.toMutableMap()
    fun List<AircraftSetting>.orFake() = if (fake) map { it.mem(store) } else this
    val cfg = AppConfig.AircraftSettings
    return listOf(
        "비행 컨트롤러 설정" to listOf(
            AircraftSetting("홈포인트 설정", AircraftSetting.Kind.HomePoint, write = { v -> if (v == 0) homeToAircraft() else homeToRc() }),
            toggle("다중 비행 모드 활성화", FlightControllerKey.KeyMultipleFlightModeEnabled, "끄면 스위치가 S·F에 있어도 N 모드로 납니다(매뉴얼 28쪽)"),
            choice(
                "비행 모드", FlightControllerKey.KeyCustomFunctionMode, listOf(ControlChannelMapping.TRIPOD to "T/N/S", ControlChannelMapping.ATTITUDE to "A/N/S"),
                note = "스위치 F 자리의 모드. T(삼각대): 부드러운 촬영을 위해 최대 비행 속도 제한 · A(자세): 위성·비전 포지셔닝 없이 자세만 유지 · " +
                    "S(스포츠): 기동성과 속도 향상 · N(일반): 위성과 하향 비전 포지셔닝",
            ),
            number(
                "RTH 고도", FlightControllerKey.KeyGoHomeHeight, AppConfig.Preflight.ALTITUDE_RANGE_M, 10, "m",
                "홈포인트에서 50m 이상이면 이 고도까지 올라 홈으로, 5~50m면 지금 고도로 홈으로, 5m 미만이면 바로 착륙합니다. 전주·전선보다 충분히 높게(법정 상한 150m)",
                rangeKey = FlightControllerKey.KeyGoHomeHeightRange,
            ),
            number("최고 고도", FlightControllerKey.KeyHeightLimit, AppConfig.Preflight.ALTITUDE_RANGE_M, 10, "m", "법정 상한 150m", rangeKey = FlightControllerKey.KeyHeightLimitRange),
            toggle("최대 거리", FlightControllerKey.KeyDistanceLimitEnabled),
            number("최대 거리 값", FlightControllerKey.KeyDistanceLimit, AppConfig.Preflight.DISTANCE_RANGE_M, 100, "m", rangeKey = FlightControllerKey.KeyDistanceLimitRange),
            choice("신호 끊김 조치", FlightControllerKey.KeyFailsafeAction, LOST_ACTIONS, dropdown = true),
            action("컴퍼스 캘리브레이션", FlightControllerKey.KeyStartCompassCalibration, "기체를 들고 안내대로 돌려야 합니다. 시작할까요?"),
            action("IMU 캘리브레이션", FlightControllerKey.KeyStartIMUCalibration, "평평한 곳에서 안내대로 기체 방향을 바꿔야 합니다. 시작할까요?"),
            toggle("균형 선회", FlightControllerKey.KeyCoordinatedTurnEnabled, "수동 비행 전용"),
            pilot2("GNSS", "GPS+Galileo+BDS. MSDK 5.18에 고르는 키가 없음"),
            toggle("내 드론 찾기", FlightControllerKey.KeyESCBeepEnabled, "모터 신호음으로 땅에 내린 기체를 찾습니다"),
        ).orFake(),
        "장애물 감지 설정" to (
            listOf(
                AircraftSetting(
                    "장애물 회피", AircraftSetting.Kind.Choice(listOf("정지", "회피", "꺼짐")),
                    "정지: 장애물이 감지되면 기체가 자동으로 정지합니다. 해당 방향 장애물 회피를 켜야 합니다. 가는 전선·통신선은 감지하지 못할 수 있습니다(5.6)",
                    read = { avoidance.indexOf(djiValue<ObstacleAvoidanceType> { perception.getObstacleAvoidanceType(it) }) },
                    write = { v -> djiDone { perception.setObstacleAvoidanceType(avoidance[v as Int], it) } },
                ),
            ) + DIRECTIONS.flatMap { (d, name) ->
                listOf(
                    AircraftSetting(
                        "$name 장애물 회피", AircraftSetting.Kind.Toggle,
                        "경고 거리 안의 장애물은 노란 영역, 제동 거리 안이면 빨간 영역이 표시되며 기체가 자동으로 제동됩니다".takeIf { d == PerceptionDirection.HORIZONTAL },
                        read = { djiValue<Boolean> { perception.getObstacleAvoidanceEnabled(d, it) } },
                        write = { v -> djiDone { perception.setObstacleAvoidanceEnabled(v as Boolean, d, it) } }, group = name,
                    ),
                    obstacleDistance("장애물 제동 거리", d, name, warning = false),
                    obstacleDistance("장애물 경고 거리", d, name, warning = true),
                )
            } + listOf(
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
            )
            ).orFake(),
        "조종기 설정" to listOf(
            choice("스틱 모드", RemoteControllerKey.KeyControlMode, STICK_MODES, dropdown = true),
            pilot2("조종기 캘리브레이션", "스틱·다이얼을 화면 안내대로 움직이는 단계라 Pilot 2에서(키 `KeyRcCalibrateChannels`는 있음)"),
            AircraftSetting("RC 버튼 맞춤 설정", AircraftSetting.Kind.Info, "우리 앱은 Pilot 2 기본값(10.5.10). 일시정지 버튼은 기체가 처리, 재개는 화면 ▶", read = { "C1 광각/줌 · C2 지도/카메라" }),
            action("기체에 연동", RemoteControllerKey.KeyRequestPairing, "기체의 연동 버튼을 눌러야 합니다. 지금 연결이 끊깁니다. 시작할까요?"),
        ).orFake(),
        "이미지 전송 설정" to listOf(
            choice("작동 주파수", AirLinkKey.KeyFrequencyBand, listOf(FrequencyBand.BAND_2_DOT_4G to "2.4 GHz", FrequencyBand.BAND_5_DOT_8G to "5G", FrequencyBand.BAND_MULTI to "듀얼 밴드")),
            info("신호 강도") { read(AirLinkKey.KeySignalQuality)?.let { "${signalWord(it)} · $it%" } },
            // ponytail: 조종기 안테나 두 개 중 센 쪽을 dBm으로 본다(MSDK 문서에 단위 없음). Pilot 2 "신호 강도: 0.0dBm"과 실기 대조
            info("신호 강도(dBm)") { read(AirLinkKey.KeyRcAntennaRssi)?.let { r -> listOfNotNull(r.antenna1, r.antenna2).maxOrNull()?.let { "$it dBm" } } },
            info("다운링크 대역폭") { read(AirLinkKey.KeyBandwidth)?.name?.let(::bandwidthLabel) },
            // ponytail: Mbps로 본다(MSDK 문서에 단위 없음). Pilot 2 "49.21Mbps"와 실기 대조
            info("이미지 전송 비트 전송률") { read(AirLinkKey.KeyDynamicDataRate)?.let { "%.2fMbps".format(it) } },
            pilot2("Enhanced Transmission Settings", "4G 보조 전송·SIM: 쓰지 않음(데이터 주권, 4장)"),
            pilot2("동영상 출력 유형", "조종기 HDMI 출력. MSDK 5.18에 키가 없음"),
            pilot2("출력 디스플레이", "맞추기 / 채우기 / 늘리기(HDMI)"),
            pilot2("비행 매개변수 표시", "HDMI 화면에 비행 값 겹치기"),
        ).orFake(),
        "인텔리전트 배터리 정보" to listOf(
            info("배터리 용량") { read(BatteryKey.KeyChargeRemainingInPercent)?.let { "$it%" } },
            // ponytail: 초로 본다(키 이름). 실기 Pilot 2 "비행시간 00:00"과 대조
            info("비행시간") { read(FlightControllerKey.KeyFlightTimeInSeconds)?.let { "%02d:%02d".format(it / 60, it % 60) } },
            info("배터리") {
                val cells = listOf(read(BatteryKey.KeyIsLowCellVoltageDetected), read(BatteryKey.KeyIsVoltageDifferenceDetected))
                if (cells.all { it == null }) null else if (cells.any { it == true }) "확인 필요(셀 전압)" else "정상"
            },
            info("배터리 잔량") { read(BatteryKey.KeyChargeRemainingInPercent)?.let { "$it%" } },
            info("온도") { read(BatteryKey.KeyBatteryTemperature)?.let { "%.1f℃".format(it) } },
            info("전압") { read(BatteryKey.KeyVoltage)?.let { "%.2fV".format(it / 1000.0) } },
            info("충전 사이클 수") { read(BatteryKey.KeyNumberOfDischarges)?.toString() },
            info("상세 정보") {
                listOfNotNull(
                    read(BatteryKey.KeyCellVoltages)?.takeIf { it.isNotEmpty() }?.joinToString(" · ", "셀 ", " V") { "%.2f".format(it / 1000.0) },
                    read(BatteryKey.KeyFullChargeCapacity)?.let { "완충 용량 ${it}mAh" },
                ).joinToString(" · ").ifEmpty { null }
            },
            toggle("스마트 RTH", FlightControllerKey.KeyLowBatteryRTHEnabled, "RTH 실행만 가능한 정도의 배터리 잔량에 도달하면 기체가 복귀합니다"),
            number("배터리 부족 경고", FlightControllerKey.KeyLowBatteryWarningThreshold, cfg.LOW_BATTERY_RANGE, 5, "%"),
            number("심각한 배터리 부족 경고", FlightControllerKey.KeySeriousLowBatteryWarningThreshold, cfg.SERIOUS_BATTERY_RANGE, 5, "%"),
            number("자체 방전 일수", BatteryKey.KeyBatterySelfDischargeInDays, cfg.SELF_DISCHARGE_DAYS, 1, "일", "보관 중 이 날짜가 지나면 배터리가 보관 잔량까지 스스로 방전합니다"),
        ).orFake(),
        "짐벌 설정" to listOf(
            number("짐벌 피치 평활도 시작/정지", GimbalKey.KeyPitchSmoothingFactor, cfg.GIMBAL_SMOOTH_RANGE, 1, "", slider = true, index = camera),
            number("짐벌 피치 최대 속도", GimbalKey.KeyPitchControlMaxSpeed, cfg.GIMBAL_SPEED_RANGE, 1, "%", slider = true, index = camera),
            action("짐벌 매개변수 초기화", GimbalKey.KeyResetControlParameters, "짐벌 평활도·최대 속도를 기본값으로 돌립니다. 할까요?", camera),
            action("짐벌 자동 캘리브레이션", GimbalKey.KeyGimbalCalibrate, "기체를 평평한 곳에 두세요. 시작할까요?", camera),
            pilot2("짐벌 조정", "영상을 보며 수평을 손으로 맞추는 단계라 Pilot 2에서"),
        ).orFake(),
        CAMERA_SETTINGS_TAB to listOf(
            choice("사진 형식", CameraKey.KeyPhotoFileFormat, listOf(PhotoFileFormat.JPEG to "JPEG", PhotoFileFormat.RAW_JPEG to "RAW+JPEG"), camera),
            choice("사진 비율", CameraKey.KeyPhotoRatio, listOf(PhotoRatio.RATIO_4COLON3 to "4:3", PhotoRatio.RATIO_16COLON9 to "16:9"), camera, "4:3 권장(16:9는 위아래가 잘림, 현장 문서)"),
            toggle("짐벌 피치 위로 확장", GimbalKey.KeyPitchRangeExtensionEnabled, null, camera),
            info("SD 카드") {
                val state = read(CameraKey.KeyCameraSDCardState, camera)
                val free = read(CameraKey.KeySDCardRemainSpace, camera)
                "${state ?: "--"} · ${free?.let { "${it / 1024}GB 남음" } ?: "--"}"
            },
            AircraftSetting(
                "SD 카드 포맷", AircraftSetting.Kind.Action("SD 카드의 사진이 모두 지워지고 되돌릴 수 없습니다. 비행 후 원본을 받았는지 확인하세요. 포맷할까요?"),
                write = { djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(CameraKey.KeyFormatStorage, camera), CameraStorageLocation.SDCARD, it) } },
            ),
        ).orFake(),
        "일반" to general + listOf(
            AircraftSetting(
                "LED 설정", AircraftSetting.Kind.Toggle, "전방 암 LED. 끄면 촬영 영상에 비치지 않습니다",
                read = { read(FlightControllerKey.KeyLEDsSettings)?.frontLEDsOn },
                write = { v -> write(FlightControllerKey.KeyLEDsSettings, (read(FlightControllerKey.KeyLEDsSettings) ?: LEDsSettings()).apply { frontLEDsOn = v as Boolean }) },
            ),
            info("기기 이름") { read(FlightControllerKey.KeyAircraftName) },
            info("정보") {
                listOfNotNull(
                    read(ProductKey.KeyProductType)?.name?.let(::modelName),
                    read(FlightControllerKey.KeySerialNumber)?.let { "SN $it" },
                    read(ProductKey.KeyFirmwareVersion)?.let { "FW $it" },
                ).joinToString(" · ").ifEmpty { null }
            },
        ).orFake(),
    )
}

private val keys get() = KeyManager.getInstance()
private val perception get() = PerceptionManager.getInstance()
private val camera = ComponentIndexType.LEFT_OR_MAIN
private val avoidance = listOf(ObstacleAvoidanceType.BRAKE, ObstacleAvoidanceType.BYPASS, ObstacleAvoidanceType.CLOSE)
private val DIRECTIONS = listOf(PerceptionDirection.HORIZONTAL to "수평", PerceptionDirection.UPWARD to "상방", PerceptionDirection.DOWNWARD to "하방")
private val LOST_ACTIONS = listOf(FailsafeAction.GOHOME to "RTH", FailsafeAction.HOVER to "호버링", FailsafeAction.LANDING to "착륙")
private val STICK_MODES = listOf(ControlMode.JP to "모드 1", ControlMode.USA to "모드 2", ControlMode.CH to "모드 3")

private fun <T> key(info: DJIKeyInfo<T>, index: ComponentIndexType?) = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)

// 형을 밝혀 써야 콜백 버전 getValue가 골라진다(안 쓰면 기본값을 받는 동기 버전이 골라져 콜백이 오지 않음)
private suspend fun <T> read(info: DJIKeyInfo<T>, index: ComponentIndexType? = null): T? = djiValue<T> { keys.getValue(key(info, index), it) }

private suspend fun <T> write(info: DJIKeyInfo<T>, value: T, index: ComponentIndexType? = null) = djiDone { keys.setValue(key(info, index), value, it) }

private fun number(
    label: String, info: DJIKeyInfo<Int>, range: IntRange, step: Int, unit: String, note: String? = null, big: Int? = null,
    slider: Boolean = false, index: ComponentIndexType? = null, rangeKey: DJIKeyInfo<IntValueConfig>? = null,
) = AircraftSetting(
    label, AircraftSetting.Kind.Number(range, step, unit, big, slider), note, read = { read(info, index) }, write = { v -> write(info, v as Int, index) },
    readRange = { rangeKey?.let { read(it) }?.let { settingRange(it.min, it.max, range) } },
)

/** 화면에 보일 숫자 항목의 범위(항목 `id` → 범위): 기체 범위 키, 모의 기체·실패는 앱 범위([AppConfig.Preflight]). */
suspend fun List<AircraftSetting>.loadRanges(): Map<String, IntRange> = mapNotNull { a -> a.loadRange()?.let { a.id to it } }.toMap()

private fun toggle(label: String, info: DJIKeyInfo<Boolean>, note: String? = null, index: ComponentIndexType? = null) =
    AircraftSetting(label, AircraftSetting.Kind.Toggle, note, read = { read(info, index) }, write = { v -> write(info, v as Boolean, index) })

private fun <T> choice(label: String, info: DJIKeyInfo<T>, options: List<Pair<T, String>>, index: ComponentIndexType? = null, note: String? = null, dropdown: Boolean = false) =
    AircraftSetting(
        label, AircraftSetting.Kind.Choice(options.map { it.second }, dropdown), note,
        read = { read(info, index).let { v -> options.indexOfFirst { it.first == v } } },
        write = { v -> write(info, options[v as Int].first, index) },
    )

private fun info(label: String, value: suspend () -> String?) = AircraftSetting(label, AircraftSetting.Kind.Info, read = value)

private fun pilot2(label: String, note: String?) = AircraftSetting(label, AircraftSetting.Kind.Pilot2, note)

private fun action(label: String, info: DJIActionKeyInfo<EmptyMsg, EmptyMsg>, confirm: String?, index: ComponentIndexType? = null) =
    AircraftSetting(label, AircraftSetting.Kind.Action(confirm), write = {
        val k = if (index == null) KeyTools.createKey(info) else KeyTools.createKey(info, index)
        djiValue<EmptyMsg> { keys.performAction(k, it) }
    })

/** 홈포인트를 기체 위치로(비행 전 점검·기체 설정·지도 기체 카드 [H 지정]이 같이 쓴다). */
internal suspend fun homeToAircraft() {
    djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(FlightControllerKey.KeyHomeLocationUsingCurrentAircraftLocation), it) }
}

/** 홈포인트를 조종기 GPS 위치로(지도 조종기 카드 [H 지정]도 같음). */
internal suspend fun homeToRc() {
    val g = read(RemoteControllerKey.KeyRcGPSInfo)?.takeIf { it.isValid == true } ?: error("조종기 GPS 없음")
    djiValue<EmptyMsg> { keys.performAction(KeyTools.createKey(FlightControllerKey.KeyHomeLocationUsingCurrentRemoteControllerLocation), LocationCoordinate2D(g.location.latitude, g.location.longitude), it) }
}

/** 장애물 제동·경고 거리(m). 수평은 Pilot 2 범위(11-04 녹화), 상방·하방은 녹화가 없어 비행 전 점검 손잡이 범위. */
private fun obstacleDistance(label: String, d: PerceptionDirection, group: String, warning: Boolean): AircraftSetting {
    val cfg = AppConfig.AircraftSettings
    val range = if (d != PerceptionDirection.HORIZONTAL) AppConfig.Preflight.SENSING_RANGE_M else if (warning) cfg.WARN_RANGE_M else cfg.BRAKE_RANGE_M
    return AircraftSetting(
        label, AircraftSetting.Kind.Decimal(range, cfg.DISTANCE_STEP_M, "m"), group = group,
        read = {
            djiValue<Double> {
                if (warning) perception.getObstacleAvoidanceWarningDistance(d, it) else perception.getObstacleAvoidanceBrakingDistance(d, it)
            }
        },
        write = { v ->
            djiDone {
                if (warning) perception.setObstacleAvoidanceWarningDistance(v as Double, d, it) else perception.setObstacleAvoidanceBrakingDistance(v as Double, d, it)
            }
        },
    )
}

/**
 * 비행 전 점검 화면의 설정(10.5.5, Pilot 2 "비행 전 점검" 순서). 값은 기체 설정 탭과 같은 키로 읽고 쓴다.
 * [sensing]은 방향마다 (감지 켜기, 제동·경고 거리).
 */
class PreflightSettings(
    val rth: AircraftSetting,
    val maxAltitude: AircraftSetting,
    val lostAction: AircraftSetting,
    val distanceLimitOn: AircraftSetting,
    val distanceLimit: AircraftSetting,
    val stickMode: AircraftSetting,
    val homeAircraft: AircraftSetting,
    val homeRc: AircraftSetting,
    val battery: AircraftSetting,
    val avoidance: AircraftSetting,
    val sensing: List<Pair<AircraftSetting, AircraftSetting>>,
) {
    val all get() = listOf(rth, maxAltitude, lostAction, distanceLimitOn, distanceLimit, stickMode, homeAircraft, homeRc, battery, avoidance) + sensing.flatMap { it.toList() }

    /** 기체가 없을 때(모의 기체): 같은 항목을 메모리 값으로 읽고 쓴다. 처음 값은 [AppConfig.Preflight.FAKE_VALUES]. */
    fun inMemory(): PreflightSettings {
        val store: MutableMap<String, Any> = AppConfig.Preflight.FAKE_VALUES.toMutableMap()
        fun AircraftSetting.mem() = mem(store)
        return PreflightSettings(
            rth.mem(), maxAltitude.mem(), lostAction.mem(), distanceLimitOn.mem(), distanceLimit.mem(), stickMode.mem(),
            homeAircraft.mem(), homeRc.mem(), battery.mem(), avoidance.mem(), sensing.map { (a, b) -> a.mem() to b.mem() },
        )
    }
}

/**
 * 비행 전 점검 설정(실제 기체). RTH·최고 고도·최대 비행 거리 범위는 기체 범위 키로 읽고, 못 읽으면 [AppConfig.Preflight.ALTITUDE_RANGE_M](Pilot 2 20~1500m)·
 * [AppConfig.Preflight.DISTANCE_RANGE_M]. 장애물 거리는 [AppConfig.Preflight.SENSING_RANGE_M].
 */
fun djiPreflightSettings() = PreflightSettings(
    rth = number("RTH 고도", FlightControllerKey.KeyGoHomeHeight, AppConfig.Preflight.ALTITUDE_RANGE_M, 10, "m", big = 100, rangeKey = FlightControllerKey.KeyGoHomeHeightRange),
    maxAltitude = number("최고 고도", FlightControllerKey.KeyHeightLimit, AppConfig.Preflight.ALTITUDE_RANGE_M, 10, "m", big = 100, rangeKey = FlightControllerKey.KeyHeightLimitRange),
    lostAction = choice("신호 끊김 조치", FlightControllerKey.KeyFailsafeAction, LOST_ACTIONS),
    distanceLimitOn = toggle("최대 비행 거리", FlightControllerKey.KeyDistanceLimitEnabled),
    distanceLimit = number("최대 비행 거리 값", FlightControllerKey.KeyDistanceLimit, AppConfig.Preflight.DISTANCE_RANGE_M, 100, "m", rangeKey = FlightControllerKey.KeyDistanceLimitRange),
    stickMode = choice("조종 스틱 모드", RemoteControllerKey.KeyControlMode, STICK_MODES),
    homeAircraft = AircraftSetting("홈포인트를 기체 위치로", AircraftSetting.Kind.Action(null), write = { homeToAircraft() }),
    homeRc = AircraftSetting("홈포인트를 조종기 위치로", AircraftSetting.Kind.Action(null), write = { homeToRc() }),
    battery = AircraftSetting(
        "배터리 경고", AircraftSetting.Kind.Range(AppConfig.Preflight.BATTERY_WARN_RANGE, "%"),
        read = { (read(FlightControllerKey.KeySeriousLowBatteryWarningThreshold) ?: error("값 없음")).toDouble() to (read(FlightControllerKey.KeyLowBatteryWarningThreshold) ?: error("값 없음")).toDouble() },
        write = { v ->
            val (serious, low) = v as Pair<*, *>
            write(FlightControllerKey.KeySeriousLowBatteryWarningThreshold, (serious as Double).toInt())
            write(FlightControllerKey.KeyLowBatteryWarningThreshold, (low as Double).toInt())
        },
    ),
    avoidance = AircraftSetting(
        "장애물 회피", AircraftSetting.Kind.Choice(listOf("정지", "회피", "꺼짐")),
        read = { avoidance.indexOf(djiValue<ObstacleAvoidanceType> { perception.getObstacleAvoidanceType(it) }) },
        write = { v -> djiDone { perception.setObstacleAvoidanceType(avoidance[v as Int], it) } },
    ),
    sensing = listOf(PerceptionDirection.HORIZONTAL to "수평", PerceptionDirection.UPWARD to "상향", PerceptionDirection.DOWNWARD to "하향").map { (d, name) ->
        AircraftSetting(
            "$name 감지", AircraftSetting.Kind.Toggle,
            read = { djiValue<Boolean> { perception.getObstacleAvoidanceEnabled(d, it) } },
            write = { v -> djiDone { perception.setObstacleAvoidanceEnabled(v as Boolean, d, it) } },
        ) to AircraftSetting(
            "$name 감지 거리", AircraftSetting.Kind.Range(AppConfig.Preflight.SENSING_RANGE_M, "m"),
            read = { djiValue<Double> { perception.getObstacleAvoidanceBrakingDistance(d, it) } to djiValue<Double> { perception.getObstacleAvoidanceWarningDistance(d, it) } },
            write = { v ->
                val (brake, warn) = v as Pair<*, *>
                djiDone { perception.setObstacleAvoidanceBrakingDistance(brake as Double, d, it) }
                djiDone { perception.setObstacleAvoidanceWarningDistance(warn as Double, d, it) }
            },
        )
    },
)
