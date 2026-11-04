package com.example.myfirstapp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myfirstapp.core.model.FlightParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

/** ⑥ 설정 값(10장, 3.4 DataStore). 비행 기본값은 `MISSION.flightParams`와 같은 JSON으로 저장한다. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    /** 새 계획의 파라미터 초기값. 저장한 적이 없거나 읽을 수 없으면 설계 기본값(6.3)이다. */
    val flightDefaults: Flow<FlightParams> = store.data.map { p ->
        p[FLIGHT]?.let { runCatching { flightParamsFromJson(it, legacyTerrainFollow = true) }.getOrNull() } ?: FlightParams()
    }

    /** 비행 중(live) 모델로 고른 폴더(`models/<이름>/`). null이면 `role`로 자동으로 고른다(9.6 `ModelPick`). 옛 "고른 모델" 값을 그대로 이어 쓴다. */
    val liveModelDir: Flow<String?> = store.data.map { it[MODEL] }

    /** 정밀(full, 착륙 뒤) 모델로 고른 폴더. null이면 `role`로 자동으로 고른다. */
    val fullModelDir: Flow<String?> = store.data.map { it[FULL_MODEL] }

    /** 배경 지도(7.4): Bing 위성(기본) / VWorld 위성 / VWorld 일반지도. 다음에 지도 화면을 열 때 적용된다. */
    val mapSource: Flow<MapSource> = store.data.map { p -> MapSource.entries.firstOrNull { it.name == p[MAP_SOURCE] } ?: MapSource.BING }

    /** VWorld 위성 위에 도로·지명 라벨(하이브리드)을 겹칠지. */
    val vworldLabels: Flow<Boolean> = store.data.map { it[VWORLD_LABELS] ?: true }

    /** 앨범 보기(10.4.3): 선로별(기본, true) / 미션별. 마지막에 본 것으로 연다. */
    val albumByLine: Flow<Boolean> = store.data.map { it[ALBUM_BY_LINE] ?: true }

    /** 판독 전주 목록 폭(dp, 10.4.3). 끌어서 바꾼 폭을 다음에도 쓴다. 없으면 null(기본 폭). */
    val resultListWidth: Flow<Float?> = store.data.map { it[RESULT_LIST_WIDTH] }

    suspend fun setAlbumByLine(on: Boolean) {
        store.edit { it[ALBUM_BY_LINE] = on }
    }

    suspend fun setResultListWidth(dp: Float) {
        store.edit { it[RESULT_LIST_WIDTH] = dp }
    }

    suspend fun setMapSource(s: MapSource) {
        store.edit { it[MAP_SOURCE] = s.name }
    }

    suspend fun setVworldLabels(on: Boolean) {
        store.edit { it[VWORLD_LABELS] = on }
    }

    suspend fun setFlightDefaults(p: FlightParams) {
        store.edit { it[FLIGHT] = p.toJson() }
    }

    suspend fun setLiveModelDir(name: String?) {
        store.edit { if (name == null) it.remove(MODEL) else it[MODEL] = name }
    }

    suspend fun setFullModelDir(name: String?) {
        store.edit { if (name == null) it.remove(FULL_MODEL) else it[FULL_MODEL] = name }
    }

    private companion object {
        val FLIGHT = stringPreferencesKey("flight_defaults")
        val MODEL = stringPreferencesKey("model_dir")
        val FULL_MODEL = stringPreferencesKey("full_model_dir")
        val MAP_SOURCE = stringPreferencesKey("map_source")
        val VWORLD_LABELS = booleanPreferencesKey("vworld_labels")
        val ALBUM_BY_LINE = booleanPreferencesKey("album_by_line")
        val RESULT_LIST_WIDTH = floatPreferencesKey("result_list_width")
    }
}
