package com.example.myfirstapp.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.data.ModelInfo
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
    /** ⑥ 설정에서 고른 모델 폴더. null이면 이름이 가장 뒤인 모델(9.1). */
    val selectedModel: String? = null,
    val maps: List<MapFile> = emptyList(),
    val message: String? = null,
    val deleting: PendingDelete? = null,
    val busy: Boolean = false,
) {
    val paramErrors get() = params.validate()
    val canSave get() = params != savedParams && paramErrors.isEmpty()

    /** 비행할 때 실제로 쓸 모델: 고른 모델이 규격을 통과하면 그것, 아니면 통과한 모델 중 이름이 가장 뒤인 것. */
    val activeModel get() = models.firstOrNull { it.dir == selectedModel && it.error == null }?.dir
        ?: models.firstOrNull { it.error == null }?.dir
}

/** ⑥ 설정(10장): 비행 기본값(F-09), 탐지 모델 관리(F-18), 오프라인 지도 관리. */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val modelsDir: File,
    private val mapsDir: File,
    private val resolver: ContentResolver,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val p = settings.flightDefaults.first()
            _state.update { it.copy(params = p, savedParams = p, selectedModel = settings.modelDir.first()) }
            refreshFiles()
        }
    }

    fun updateParams(p: FlightParams) = _state.update { it.copy(params = p) }
    fun resetParams() = _state.update { it.copy(params = FlightParams(), message = "설계 기본값으로 되돌렸습니다. 저장해야 적용됩니다.") }

    fun saveParams() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            settings.setFlightDefaults(s.params)
            _state.update { it.copy(savedParams = s.params, message = "비행 기본값을 저장했습니다. 새 계획부터 적용됩니다.") }
        }
    }

    fun selectModel(dir: String) = viewModelScope.launch {
        settings.setModelDir(dir)
        _state.update { it.copy(selectedModel = dir, message = "다음 비행부터 모델 $dir 을(를) 씁니다.") }
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
        val target = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }
        runFileJob(null) {
            when (target) {
                is PendingDelete.Model -> {
                    File(modelsDir, target.name).deleteRecursively()
                    if (_state.value.selectedModel == target.name) {
                        settings.setModelDir(null)
                        _state.update { it.copy(selectedModel = null) }
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
    }

    companion object {
        fun factory(settings: SettingsStore, appFilesDir: File?, resolver: ContentResolver) = viewModelFactory {
            initializer { SettingsViewModel(settings, File(appFilesDir, "models"), File(appFilesDir, "maps"), resolver) }
        }
    }
}
