package com.example.myfirstapp.ui.settings

import com.example.myfirstapp.data.toJson
import com.example.myfirstapp.data.OpLog
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.detect.ModelPick
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.data.ModelInfo
import com.example.myfirstapp.data.MapSource
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.copyMbtiles
import com.example.myfirstapp.data.importModelFolder
import com.example.myfirstapp.data.inspectModels
import com.example.myfirstapp.data.listMbtiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MapFile(val name: String, val sizeBytes: Long)

/** 삭제 확인 대화상자에 띄울 대상. */
sealed interface PendingDelete {
    val name: String

    data class Model(override val name: String) : PendingDelete
    data class Map(override val name: String) : PendingDelete
}

data class SettingsUiState(
    /** 화면에서 고치는 중인 비행 기본값과 마지막으로 저장한 값. */
    val params: FlightParams = FlightParams(),
    val savedParams: FlightParams = FlightParams(),
    val models: List<ModelInfo> = emptyList(),
    /** ⑥ 설정에서 고른 비행 중(live)·정밀(full) 모델 폴더. null이면 `role`로 자동으로 고른다(9.6). */
    val selectedLive: String? = null,
    val selectedFull: String? = null,
    val maps: List<MapFile> = emptyList(),
    val message: String? = null,
    val deleting: PendingDelete? = null,
    val busy: Boolean = false,
    /** 배경 지도(7.4)와 VWorld 위성 라벨. */
    val mapSource: MapSource = MapSource.VWORLD_SATELLITE,
    val vworldLabels: Boolean = true,
) {
    val paramErrors get() = params.validate()
    val canSave get() = params != savedParams && paramErrors.isEmpty()

    private val valid get() = models.filter { it.error == null }.map { it.choice() }

    /** 비행 중 최근 1장에 실제로 쓸 모델(9.6 `ModelPick`): 고른 것, 없으면 `live` 중 입력이 가장 작은 것. */
    val activeLive get() = ModelPick.liveOrder(valid, selectedLive).firstOrNull()

    /** 착륙 뒤 정밀 탐지에 실제로 쓸 모델: 고른 것, 없으면 `full`, 그것도 없으면 비행 중 모델. */
    val activeFull get() = ModelPick.fullOrder(valid, selectedFull, selectedLive).firstOrNull()
}

/** ⑥ 설정(10장): 비행 기본값(F-09), 탐지 모델 관리(F-18), 오프라인 지도 관리. */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val modelsDir: File,
    private val mapsDir: File,
    private val resolver: ContentResolver,
    /** 모델 파일이 바뀌었다(가져오기·삭제): GPU 캐시 만들기가 할 일을 다시 본다(9.7). */
    private val onModelsChanged: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val p = settings.flightDefaults.first()
            _state.update {
                it.copy(
                    params = p, savedParams = p, selectedLive = settings.liveModelDir.first(), selectedFull = settings.fullModelDir.first(),
                    mapSource = settings.mapSource.first(), vworldLabels = settings.vworldLabels.first(),
                )
            }
            refreshFiles()
        }
    }

    fun updateParams(p: FlightParams) = _state.update { it.copy(params = p) }
    fun resetParams() = _state.update { it.copy(params = FlightParams(), message = "설계 기본값으로 되돌렸습니다. 저장해야 적용됩니다.") }

    fun saveParams() {
        OpLog.log("setting", "flightDefaults", mapOf("params" to _state.value.params.toJson()))
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            settings.setFlightDefaults(s.params)
            _state.update { it.copy(savedParams = s.params, message = "비행 기본값을 저장했습니다. 새 계획부터 적용됩니다.") }
        }
    }

    /** 배경 지도 고르기(7.4). 다음에 지도 화면을 열 때 적용된다. */
    fun setMapSource(src: MapSource) = viewModelScope.launch {
        OpLog.log("setting", "mapSource", mapOf("source" to src.name))
        settings.setMapSource(src)
        _state.update { it.copy(mapSource = src, message = "배경 지도를 ${src.title}(으)로 바꿨습니다. 다음에 지도를 열 때 적용됩니다.") }
    }

    fun setVworldLabels(on: Boolean) = viewModelScope.launch {
        OpLog.log("setting", "vworldLabels", mapOf("on" to on))
        settings.setVworldLabels(on)
        _state.update { it.copy(vworldLabels = on) }
    }

    fun selectLive(dir: String) = viewModelScope.launch {
        OpLog.log("setting", "liveModel", mapOf("dir" to dir))
        settings.setLiveModelDir(dir)
        _state.update { it.copy(selectedLive = dir, message = "다음 비행부터 비행 중 탐지에 $dir 을(를) 씁니다.") }
    }

    fun selectFull(dir: String) = viewModelScope.launch {
        OpLog.log("setting", "fullModel", mapOf("dir" to dir))
        settings.setFullModelDir(dir)
        _state.update { it.copy(selectedFull = dir, message = "다음 정밀 탐지부터 $dir 을(를) 씁니다.") }
    }

    fun onModelFolderPicked(tree: Uri) = runFileJob("모델을 검사하는 중입니다") {
        val dir = importModelFolder(resolver, tree, modelsDir.apply { mkdirs() })
        "모델을 가져왔습니다: $dir"
    }

    fun onMapFilePicked(uri: Uri) = runFileJob("지도 파일을 복사하는 중입니다") {
        "지도를 추가했습니다: ${copyMbtiles(resolver, uri, mapsDir.apply { mkdirs() })}"
    }

    fun askDelete(target: PendingDelete) = _state.update { it.copy(deleting = target) }
    fun dismissDelete() = _state.update { it.copy(deleting = null) }

    fun confirmDelete() {
        OpLog.log("setting", "delete", mapOf("target" to _state.value.deleting?.toString()))
        val target = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }
        runFileJob(null) {
            when (target) {
                is PendingDelete.Model -> {
                    File(modelsDir, target.name).deleteRecursively()
                    if (_state.value.selectedLive == target.name) {
                        settings.setLiveModelDir(null)
                        _state.update { it.copy(selectedLive = null) }
                    }
                    if (_state.value.selectedFull == target.name) {
                        settings.setFullModelDir(null)
                        _state.update { it.copy(selectedFull = null) }
                    }
                }
                is PendingDelete.Map -> File(mapsDir, target.name).delete()
            }
            "삭제했습니다: ${target.name}"
        }
    }

    /** 파일 작업을 IO 스레드에서 하고 목록을 다시 읽는다. 실패하면 이유를 보여 준다. */
    private fun runFileJob(progress: String?, job: suspend () -> String) {
        _state.update { it.copy(busy = true, message = progress ?: it.message) }
        viewModelScope.launch {
            val msg = runCatching { withContext(Dispatchers.IO) { job() } }.getOrElse { e -> "실패: ${e.message}" }
            refreshFiles()
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    private suspend fun refreshFiles() {
        val (models, maps) = withContext(Dispatchers.IO) {
            inspectModels(modelsDir) to listMbtiles(mapsDir).map { MapFile(it.name, it.length()) }
        }
        _state.update { it.copy(models = models, maps = maps) }
        onModelsChanged()
    }

    companion object {
        fun factory(settings: SettingsStore, appFilesDir: File?, resolver: ContentResolver, onModelsChanged: () -> Unit) = viewModelFactory {
            initializer { SettingsViewModel(settings, File(appFilesDir, "models"), File(appFilesDir, "maps"), resolver, onModelsChanged) }
        }
    }
}
