package com.example.myfirstapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
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
        p[FLIGHT]?.let { runCatching { flightParamsFromJson(it) }.getOrNull() } ?: FlightParams()
    }

    /** 고른 모델 폴더 이름(`models/<이름>/`). null이면 이름이 가장 뒤인 모델을 쓴다(9.1). */
    val modelDir: Flow<String?> = store.data.map { it[MODEL] }

    suspend fun setFlightDefaults(p: FlightParams) {
        store.edit { it[FLIGHT] = p.toJson() }
    }

    suspend fun setModelDir(name: String?) {
        store.edit { if (name == null) it.remove(MODEL) else it[MODEL] = name }
    }

    private companion object {
        val FLIGHT = stringPreferencesKey("flight_defaults")
        val MODEL = stringPreferencesKey("model_dir")
    }
}
