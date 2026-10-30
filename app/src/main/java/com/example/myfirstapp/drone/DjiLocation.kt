package com.example.myfirstapp.drone

import com.example.myfirstapp.core.model.GeoPoint
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 지금 위치: 조종기 GPS → 기체 GPS(위성 6개 이상일 때만) → 없음. 계획 화면의 이륙 지점·지도 초기 위치에 쓴다.
 * 조종사가 이륙 지점 옆에 서므로 조종기를 먼저 본다(2026-10-30 사용자 요청). 기체는 GPS가 없어도 마지막 위치(예: 꺼진 시뮬레이터 위치)를
 * 주므로 위성 수로 거른다. SDK가 없거나(에뮬레이터) 답이 없으면 null.
 */
suspend fun djiCurrentLocation(): GeoPoint? = runCatching {
    val keys = KeyManager.getInstance()
    suspend fun <T> read(key: dji.sdk.keyvalue.key.DJIKey<T>): T? = withTimeoutOrNull(2000) {
        suspendCancellableCoroutine { c ->
            keys.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(t: T) = c.resume(t)
                override fun onFailure(error: IDJIError) = c.resume(null)
            })
        }
    }
    val rc = read(KeyTools.createKey(RemoteControllerKey.KeyRcGPSInfo))
        ?.takeIf { it.isValid == true && validGeo(it.location?.latitude, it.location?.longitude) }
        ?.let { GeoPoint(it.location.latitude, it.location.longitude) }
    rc ?: if (keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false) &&
        (read(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)) ?: 0) >= 6
    ) {
        read(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D))?.takeIf { validGeo(it.latitude, it.longitude) }?.let { GeoPoint(it.latitude, it.longitude) }
    } else {
        null
    }
}.getOrNull()

/** 기체가 SDK에 연결되어 있는지. SDK가 없거나 등록 전이면 false. */
fun djiAircraftConnected(): Boolean =
    runCatching { KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false) }.getOrDefault(false)

/** SDK 좌표가 쓸 만한지: 범위 안이고 (0, 0)이 아님. 홈 포인트가 기록되기 전에는 엉뚱한 값이 온다(2026-10-30). */
fun validGeo(lat: Double?, lon: Double?): Boolean =
    lat != null && lon != null && !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
