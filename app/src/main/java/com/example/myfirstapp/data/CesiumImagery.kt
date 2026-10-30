package com.example.myfirstapp.data

import kotlinx.coroutines.delay
import android.util.Log
import com.example.myfirstapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cesium ion의 Bing Maps Aerial(자산 2)을 MapLibre 래스터 타일 주소로 바꾼다(7.4 임시 결정, 온라인 전용).
 * ion 엔드포인트가 Bing 키를 주고, Bing 메타데이터가 `{quadkey}` 타일 주소를 준다. 타일 요청에는 인증 헤더가 필요 없다.
 * 타일 주소는 Bing 세션마다 다르므로 저장하지 않고 화면을 열 때마다 받는다.
 */
suspend fun cesiumBingTileUrls(token: String): List<String> = withContext(Dispatchers.IO) {
    val endpoint = getJson("https://api.cesium.com/v1/assets/2/endpoint?access_token=${enc(token)}")
    val type = endpoint.optString("externalType")
    check(type == "BING") { "ion 자산 2가 Bing이 아닙니다: $type" }
    val opt = endpoint.getJSONObject("options")
    val style = opt.optString("mapStyle", "Aerial")
    val meta = getJson("${opt.getString("url")}/REST/v1/Imagery/Metadata/$style?key=${enc(opt.getString("key"))}&uriScheme=https")
    val res = meta.getJSONArray("resourceSets").getJSONObject(0).getJSONArray("resources").getJSONObject(0)
    val template = res.getString("imageUrl")
    val subs = res.getJSONArray("imageUrlSubdomains")
    (0 until subs.length()).map { template.replace("{subdomain}", subs.getString(it)) }
}

/** 마지막으로 받은 타일 주소(앱이 시작할 때 정한다). 인터넷이 없으면 이 주소로 MapLibre 캐시의 타일을 보여 준다. */
var savedSatelliteTilesFile: File? = null

/**
 * 토큰이 있으면 위성 타일 주소를 준다. 받지 못하면(인터넷 없는 현장 등) 마지막으로 받은 주소를 주어,
 * 전에 본 곳은 MapLibre 캐시로 보이게 한다. 둘 다 없으면 빈 목록. 계획·비행 화면 공용.
 */
suspend fun onlineSatelliteTiles(): List<String> {
    if (BuildConfig.CESIUM_ION_TOKEN.isBlank()) return emptyList()
    return runCatching { cesiumBingTileUrls(BuildConfig.CESIUM_ION_TOKEN) }
        .onSuccess { urls -> runCatching { savedSatelliteTilesFile?.writeText(urls.joinToString("\n")) } }
        .onFailure { Log.w("CesiumImagery", "온라인 위성 배경 실패: $it") } // UnknownHostException은 Log가 스택을 지우므로 메시지로 남긴다
        .getOrElse { runCatching { savedSatelliteTilesFile?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } }.getOrNull().orEmpty() }
}

/** 받을 때까지 10초마다 다시 시도한다(화면을 연 뒤에 인터넷이 붙는 경우). 토큰이 없으면 바로 빈 목록. 화면을 닫으면 취소된다. */
suspend fun onlineSatelliteTilesRetrying(): List<String> {
    if (BuildConfig.CESIUM_ION_TOKEN.isBlank()) return emptyList()
    while (true) {
        onlineSatelliteTiles().takeIf { it.isNotEmpty() }?.let { return it }
        delay(10_000)
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun getJson(url: String): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 10_000
    try {
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } finally {
        c.disconnect()
    }
}
