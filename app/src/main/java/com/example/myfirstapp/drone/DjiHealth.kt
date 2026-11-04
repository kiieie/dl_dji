package com.example.myfirstapp.drone

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.example.myfirstapp.AppConfig
import com.example.myfirstapp.core.model.GeoArea
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.GeoSummary
import com.example.myfirstapp.core.model.GeoZone
import com.example.myfirstapp.core.model.HmsIssue
import com.example.myfirstapp.core.model.HmsLevel
import com.example.myfirstapp.core.model.geoSummary
import dji.sdk.keyvalue.key.HmsKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.hms.HMSMaintainRecordState
import dji.v5.manager.aircraft.flysafe.FlySafeNotificationListener
import dji.v5.manager.aircraft.flysafe.FlyZoneManager
import dji.v5.manager.aircraft.flysafe.info.FlySafeReturnToHomeInformation
import dji.v5.manager.aircraft.flysafe.info.FlySafeSeriousWarningInformation
import dji.v5.manager.aircraft.flysafe.info.FlySafeTipInformation
import dji.v5.manager.aircraft.flysafe.info.FlySafeWarningInformation
import dji.v5.manager.aircraft.flysafe.info.FlyZoneInformation
import dji.v5.manager.aircraft.flysafe.info.FlyZoneShape
import dji.v5.manager.aircraft.flysafe.info.MultiPolygonFlyZoneShape
import dji.v5.manager.diagnostic.DJIDeviceHealthInfo
import dji.v5.manager.diagnostic.DeviceHealthManager
import dji.v5.manager.diagnostic.WarningLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// HMS·GEO 구역(10.5.5). 모두 조종기·기체에 이미 있는 값을 읽기만 한다(7.2): 네트워크를 쓰는 SDK 기능(GEO 데이터베이스 받기·잠금 해제·
// 라이선스, 펌웨어 확인, 로그 올리기)은 부르지 않는다.

/** 모의 HMS·GEO(기체 연결 안 됨일 때만): 비행 화면 모의 RC 줄 [HMS]로 켜고 끈다. 현장에서 가짜 경고가 보이지 않게 처음은 꺼짐. */
object MockHealth {
    val on = MutableStateFlow(false)
    fun toggle() { on.value = !on.value }
    private val active get() = on.value && !djiAircraftConnected()
    internal fun issues() = if (active) listOf(AppConfig.Health.FAKE_ISSUE) else emptyList()
    internal fun geo() = if (active) AppConfig.Health.FAKE_GEO else null
}

/**
 * HMS 항목: SDK `DeviceHealthManager`(앱 등록 때 SDK가 `BaseManager`에서 연다)가 기체·조종기 HMS 푸시(`DJIDiagnosticsManager`)로 모은 목록.
 * 글은 SDK 리소스 `hms_title_<코드>`·`hms_content_<코드>`라 조종기 언어를 따른다(values-ko에 제목 1371/1667개). 조종기가 한국어가 아니면
 * 한국어 리소스로 다시 찾고, 없으면 SDK 글 그대로 둔다. 코드는 늘 같이 보인다.
 */
fun djiHmsIssues(context: Context): List<HmsIssue> = runCatching {
    DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos.orEmpty().map { h ->
        val code = h.informationCode().orEmpty()
        HmsIssue(code, h.korean(context, "hms_title_", h.title()), h.korean(context, "hms_content_", h.description()), h.warningLevel().level())
    }
}.getOrDefault(emptyList()) + MockHealth.issues()

private fun WarningLevel?.level() = when (this) {
    WarningLevel.SERIOUS_WARNING -> HmsLevel.SERIOUS
    WarningLevel.WARNING, WarningLevel.CAUTION -> HmsLevel.CAUTION
    else -> HmsLevel.NORMAL
}

private var koResources: Resources? = null

/** SDK와 같은 이름·자리표시자(%alarmid, %index, %component_index)로 한국어 리소스를 찾는다. 이미 한국어면 SDK 글. */
// lint: APK로 설치해 번들 언어 나누기가 없다(values-ko가 늘 들어 있음)
@SuppressLint("AppBundleLocaleChanges")
private fun DJIDeviceHealthInfo.korean(context: Context, prefix: String, sdk: String?): String {
    if (context.resources.configuration.locales[0].language == Locale.KOREAN.language) return sdk.orEmpty()
    val res = koResources ?: context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.KOREAN) }).resources.also { koResources = it }
    val code = informationCode().orEmpty()
    val hex = code.drop(2)
    // lint: SDK 리소스는 이름으로만 찾을 수 있다(코드마다 하나)
    @Suppress("DiscouragedApi")
    val id = listOf("0x${hex.uppercase()}", code, "0x${hex.lowercase()}").map { res.getIdentifier(prefix + it, "string", context.packageName) }.firstOrNull { it != 0 }
        ?: return sdk.orEmpty()
    return res.getString(id).replace("%alarmid", code).replace("%index", "${sensorIndex() + 1}")
        .replace("%component_index", "${componentId().coerceIn(0, 2) + 1}")
}

/** HMS 목록 아래 기체 정보: 기체·조종기 펌웨어(로컬 값만), 기본 정비 기록. 못 읽으면 null. */
data class HmsDevice(val aircraftFw: String? = null, val rcFw: String? = null, val maintenance: String? = null)

/**
 * 펌웨어 버전은 기체·조종기 키(로컬). 업데이트 확인은 DJI 서버에 물어야 해 하지 않는다(7.2). 정비 기록은 `HmsKey.KeyHMSGetAircraftBasicMaintainData`
 * (기체에 저장된 마지막 기본 정비)로, "점검 필요/만료됨" 판정 기준(정비 주기)은 SDK에 없어 날짜·이륙 횟수만 보인다.
 */
suspend fun djiHmsDevice(): HmsDevice {
    val m = readKey(HmsKey.KeyHMSGetAircraftBasicMaintainData)
    return HmsDevice(
        aircraftFw = readKey(ProductKey.KeyFirmwareVersion)?.takeIf { djiAircraftConnected() },
        rcFw = readKey(RemoteControllerKey.KeyFirmwareVersion),
        maintenance = when (m?.state) {
            // ponytail: 시각 단위(초·ms)를 문서에서 못 찾아 크기로 가른다. 실기 Pilot 2 정비 기록과 대조
            HMSMaintainRecordState.VALID -> listOfNotNull(
                m.maintainUtcTime?.takeIf { it > 0 }?.let { t -> SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(if (t < 10_000_000_000L) t * 1000 else t)) },
                m.takeoffTimes?.let { "이후 이륙 ${it}회" },
            ).joinToString(" · ").ifEmpty { "기록 있음" }
            HMSMaintainRecordState.INVALID -> "기록 없음"
            else -> null
        },
    )
}

/**
 * GEO 구역: SDK `FlyZoneManager`가 기체 둘레 구역이 바뀔 때(비행 안전 코어 `onLimitDataChanged`) 스스로 기체 위치로 조회해 알리는 목록
 * (`onSurroundingFlyZonesUpdate`)을 받아 둔다. 우리는 리스너를 목록에 더하기만 한다. `getFlyZonesInSurroundingArea`를 직접 부르지 않는 이유:
 * 비행 안전 코어에 가짜 GPS를 넣고(`JNIFSDataProvider.setFakeGps`) 조회 구현이 암호화된 네이티브라 네트워크를 쓰는지 확인할 수 없다(7.2).
 */
object DjiFlySafe {
    private val _zones = MutableStateFlow<List<GeoZone>?>(null)
    /** 받은 적이 없으면 null(기체 연결 전, 또는 SDK가 아직 알리지 않음). */
    val zones: StateFlow<List<GeoZone>?> = _zones.asStateFlow()

    private val listener = object : FlySafeNotificationListener {
        override fun onSurroundingFlyZonesUpdate(infos: MutableList<FlyZoneInformation>) { _zones.value = infos.map { it.zone() } }
        override fun onTipNotificationUpdate(info: FlySafeTipInformation) = Unit
        override fun onWarningNotificationUpdate(info: FlySafeWarningInformation) = Unit
        override fun onSeriousWarningNotificationUpdate(info: FlySafeSeriousWarningInformation) = Unit
        override fun onReturnToHomeNotificationUpdate(info: FlySafeReturnToHomeInformation) = Unit
    }

    /** SDK 등록 뒤 한 번(InspectionApp). */
    fun watch() = runCatching { FlyZoneManager.getInstance().addFlySafeNotificationListener(listener) }
}

private fun LocationCoordinate2D.geo() = GeoPoint(latitude, longitude)

private fun FlyZoneInformation.zone() = GeoZone(
    name.orEmpty(),
    if (shape == FlyZoneShape.CIRCLE) {
        listOfNotNull(circleCenter?.let { GeoArea(it.geo(), circleRadius, limitM = upperLimit.toInt()) })
    } else {
        multiPolygonFlyZoneInformation.orEmpty().map { p ->
            if (p.shape == MultiPolygonFlyZoneShape.CYLINDER) GeoArea(p.cylinderCenter?.geo(), p.cylinderRadius, limitM = p.limitedHeight)
            else GeoArea(points = p.polygonPoints.orEmpty().map { it.geo() }, limitM = p.limitedHeight)
        }
    },
)

/** 홈 GEO 구역 카드 값: 받은 구역을 기체(없으면 조종기) 위치에서 요약. 구역이나 위치가 없으면 null("로컬 데이터 없음"). */
suspend fun djiGeoSummary(): GeoSummary? = MockHealth.geo() ?: runCatching {
    val zones = DjiFlySafe.zones.value ?: return null
    val p = djiPositions()
    geoSummary(zones, p.aircraft ?: p.rc ?: return null, AppConfig.Preflight.ALTITUDE_RANGE_M.last)
}.getOrNull()
