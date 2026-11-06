package com.example.myfirstapp.drone

import com.example.myfirstapp.AppConfig
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.LivePositions
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.SimulatorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 지금 위치들(10.5.5): 기체 GPS(연결되고 위성 6개 이상일 때만)와 기수, DJI 조종기 GPS. 계획 화면의 이륙 지점·[기체 위치로]·[조종기 위치로]·기체 화살표에 쓴다.
 * 기체는 GPS가 없어도 마지막 위치(예: 꺼진 시뮬레이터 위치)를 주므로 위성 수로 거른다. SDK가 없거나(에뮬레이터) 답이 없으면 null.
 */
suspend fun djiPositions(): LivePositions = runCatching {
    val keys = KeyManager.getInstance()
    suspend fun <T> read(key: dji.sdk.keyvalue.key.DJIKey<T>): T? = withTimeoutOrNull(AppConfig.Timeouts.KEY_READ_MS) {
        suspendCancellableCoroutine { c ->
            keys.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(t: T) = c.resume(t)
                override fun onFailure(error: IDJIError) = c.resume(null)
            })
        }
    }
    val connected = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false)
    // 시뮬레이터는 위성 수를 0으로 알린다(위치·홈은 정상, 2026-11-06 실내 시험): 시뮬레이터면 위성 수로 거르지 않는다
    val sim = runCatching { SimulatorManager.getInstance().isSimulatorEnabled }.getOrDefault(false)
    val aircraft = if (connected && (sim || (read(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)) ?: 0) >= AppConfig.Checks.LOCATION_MIN_SATELLITES)) {
        read(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D))?.takeIf { validGeo(it.latitude, it.longitude) }?.let { GeoPoint(it.latitude, it.longitude) }
    } else {
        null
    }
    LivePositions(
        aircraft = aircraft,
        headingDeg = if (aircraft == null) 0.0 else ((read(KeyTools.createKey(FlightControllerKey.KeyCompassHeading)) ?: 0.0) + 360) % 360,
        rc = read(KeyTools.createKey(RemoteControllerKey.KeyRcGPSInfo))
            ?.takeIf { it.isValid == true && validGeo(it.location?.latitude, it.location?.longitude) }
            ?.let { GeoPoint(it.location.latitude, it.location.longitude) },
    )
}.getOrDefault(LivePositions())

/**
 * 조종기(RC Pro) 안드로이드 GPS: 마지막 위치(GPS·네트워크 중 새것), 없고 [fresh]면 GPS로 새 위치를 한 번 기다린다(10.5.5 셋째 대안).
 * 마지막 위치가 [AppConfig.Checks.LOCATION_MAX_AGE_MS]보다 오래되었으면 버린다(어제 다른 곳 위치가 이륙 지점이 되지 않게).
 * 새 위치는 GPS 공급자만 켠다(네트워크 위치는 외부 서버에 묻기 때문, 데이터 주권). 권한이 없거나 못 찾으면 null.
 */
@SuppressLint("MissingPermission") // 권한은 바로 위에서 확인한다
suspend fun deviceLocation(context: Context, fresh: Boolean): GeoPoint? = runCatching {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= AppConfig.Checks.LOCATION_MAX_AGE_MS * 1_000_000 }
        .maxByOrNull { it.elapsedRealtimeNanos }
    val loc = last ?: if (fresh && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        withTimeoutOrNull(AppConfig.Timeouts.DEVICE_LOCATION_MS) {
            suspendCancellableCoroutine { c ->
                val cancel = CancellationSignal()
                c.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, LocationManager.GPS_PROVIDER, cancel, ContextCompat.getMainExecutor(context)) { c.resume(it) }
            }
        }
    } else {
        null
    }
    loc?.takeIf { validGeo(it.latitude, it.longitude) }?.let { GeoPoint(it.latitude, it.longitude) }
}.getOrNull()

/** 기체가 SDK에 연결되어 있는지. SDK가 없거나 등록 전이면 false. */
fun djiAircraftConnected(): Boolean =
    runCatching { KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false) }.getOrDefault(false)

/** SDK 좌표가 쓸 만한지: 범위 안이고 (0, 0)이 아님. 홈 포인트가 기록되기 전에는 엉뚱한 값이 온다(2026-10-30). */
fun validGeo(lat: Double?, lon: Double?): Boolean =
    lat != null && lon != null && !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
