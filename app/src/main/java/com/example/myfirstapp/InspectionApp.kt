package com.example.myfirstapp

import com.example.myfirstapp.drone.startDjiInputLog
import com.example.myfirstapp.detect.ModelBench
import com.example.myfirstapp.detect.PreciseDetection
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import com.example.myfirstapp.data.OpLog
import android.app.Application
import android.content.Context
import android.util.Log
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.AppDatabase
import com.example.myfirstapp.data.RcBattery
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.data.savedSatelliteTilesFile
import java.io.File
import com.example.myfirstapp.drone.djiRcButtonPresses
import dji.v5.common.error.IDJIError
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.register.DJISDKInitEvent
import com.example.myfirstapp.drone.initWpmz
import dji.v5.manager.SDKManager
import dji.v5.manager.ldm.LDMManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.take

/** 수동 DI 조립 지점(3.3). DJI SDK 초기화·등록도 여기서 한다(공식 샘플 순서: init → INITIALIZE_COMPLETE → registerApp). */
class InspectionApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this) // DJI SDK 클래스 로드(공식 샘플 DJIAircraftApplication과 같음)
    }

    val database by lazy { AppDatabase.create(this) }
    val settings by lazy { SettingsStore(this) }

    private val _djiStatus = MutableStateFlow("DJI SDK: 초기화 중")
    /** 검증 화면에 보이는 SDK 상태 문구. SDK 콜백 스레드에서 갱신하므로 StateFlow로 넘긴다. */
    val djiStatus: StateFlow<String> = _djiStatus.asStateFlow()
    private val djiRegistered = MutableStateFlow(false)
    /** SDK 등록이 끝났는지. 키 구독은 등록 뒤에 해야 한다. */
    val djiReady: StateFlow<Boolean> = djiRegistered.asStateFlow()

    private val _djiInitialized = MutableStateFlow(false)
    /** SDK 초기화 완료(INITIALIZE_COMPLETE): DJI 기기(RC)에서 돌고 있음. 등록 성공과 따로 본다(앱 재시작 복구가 모의 기체로 가지 않게). */
    val djiInitialized: StateFlow<Boolean> = _djiInitialized.asStateFlow()

    /** RC 하드웨어 버튼 누름(10장). SDK 등록이 끝난 뒤에 감시를 시작한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rcButtons: Flow<RcButton> = djiRegistered.filter { it }.take(1).flatMapLatest { djiRcButtonPresses() }

    private fun setDjiStatus(text: String) {
        Log.i("DjiSdk", text)
        OpLog.log("sdk", text)
        _djiStatus.value = text
    }

    /** 조종기 자체 배터리(10.4.3 상단 바). onCreate에서 한 번 등록한다. */
    lateinit var rcBattery: StateFlow<RcBattery?>
        private set

    /** 앱 전체 수명의 작업(운용 로그용 RC 버튼 감시, 정밀 탐지, 모델 속도 측정). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 착륙 뒤 정밀 탐지 일꾼(9.6). 앱이 켜져 있는 동안 남은 사진을 처리한다. */
    lateinit var precise: PreciseDetection
        private set

    /** 모델 속도 측정(9.6): 검증 화면 버튼, 또는 `adb shell am start -n kr.co.dl_dji/com.example.myfirstapp.MainActivity --es bench all`. */
    fun startBench() = ModelBench.start(this, appScope, settings, precise)

    override fun onCreate() {
        super.onCreate()
        OpLog.init(this)
        savedSatelliteTilesFile = File(filesDir, "satellite-tiles.txt")
        OpLog.log("app", "start")
        rcBattery = RcBattery.watch(this)
        precise = PreciseDetection(this, database.projectDao(), settings, appScope, rcBattery)
        initDjiSdk()
        appScope.launch { rcButtons.collect { OpLog.log("rc", it.name) } }
    }

    private val _ldmStatus = MutableStateFlow("LDM: 확인 전")
    /** LDM(7.2) 상태 문구. 검증 화면에 보인다. */
    val ldmStatus: StateFlow<String> = _ldmStatus.asStateFlow()

    /**
     * LDM(7.2): init 뒤 라이선스 내용을 먼저 로드하고 enableLDM을 부른다(로드 전에 켜면 SDK가 DJI 서버에 라이선스를 요청한다).
     * 끝나면 성공·실패와 관계없이 [then]으로 등록을 이어 간다. 실패하면 7.2 선택지 ②(최초 1회 온라인 등록)와 같다.
     */
    private fun enableLdm(then: () -> Unit) {
        val ldm = LDMManager.getInstance()
        val license = BuildConfig.DJI_LDM_LICENSE
        if (license.isEmpty()) {
            _ldmStatus.value = "LDM: 라이선스 없음(secrets/license_kr.co.dl_dji.txt)"
            return then()
        }
        val loadError = ldm.loadLocalLDMLicenseContent(license)
        if (loadError != null) {
            _ldmStatus.value = "❌ LDM 라이선스 로드 실패: ${loadError.description()}"
            Log.w("DjiSdk", _ldmStatus.value)
            return then()
        }
        ldm.enableLDM(this, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                _ldmStatus.value = "✅ LDM 켜짐(라이선스 로드 ${ldm.isLDMLicenseLoaded()}, 사용 ${ldm.isLDMEnabled()})"
                Log.i("DjiSdk", _ldmStatus.value)
                then()
            }

            override fun onFailure(error: IDJIError) {
                _ldmStatus.value = "❌ LDM 켜기 실패: ${error.description()}"
                Log.w("DjiSdk", _ldmStatus.value)
                then()
            }
        })
    }

    private fun initDjiSdk() {
        try {
            // 검사기 준비는 지오이드 파일 복사가 있어 메인 스레드 밖에서(KMZ 검사는 비행 시작 때라 그 전에 끝난다)
            Thread { initWpmz(this) }.start()
            SDKManager.getInstance().init(this, object : SDKManagerCallback {
                override fun onRegisterSuccess() {
                    setDjiStatus("✅ DJI SDK 등록 성공")
                    runCatching { startDjiInputLog() }.onFailure { OpLog.log("error", "inputLog", mapOf("message" to it.message)) }
                    djiRegistered.value = true
                }

                override fun onRegisterFailure(error: IDJIError) {
                    setDjiStatus("❌ DJI SDK 등록 실패: ${error.description()}")
                }

                override fun onProductDisconnect(productId: Int) = Log.i("DjiSdk", "기체 연결 끊김: $productId").let {}
                override fun onProductConnect(productId: Int) = Log.i("DjiSdk", "기체 연결: $productId").let {}
                override fun onProductChanged(productId: Int) {}

                override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                    if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                        _djiInitialized.value = true
                        enableLdm { SDKManager.getInstance().registerApp() }
                    }
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
            })
        } catch (e: Throwable) {
            // x86_64 에뮬레이터 등 DJI 네이티브 라이브러리가 없는 환경. 모의 기체로는 계속 쓸 수 있다.
            setDjiStatus("❌ DJI SDK 초기화 실패: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }
}
