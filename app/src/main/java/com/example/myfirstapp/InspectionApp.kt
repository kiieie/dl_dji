package com.example.myfirstapp

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.AppDatabase
import com.example.myfirstapp.data.SettingsStore
import com.example.myfirstapp.drone.djiRcButtonPresses
import dji.v5.common.error.IDJIError
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.register.DJISDKInitEvent
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

    /** RC 하드웨어 버튼 누름(10장). SDK 등록이 끝난 뒤에 감시를 시작한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rcButtons: Flow<RcButton> = djiRegistered.filter { it }.take(1).flatMapLatest { djiRcButtonPresses() }

    private fun setDjiStatus(text: String) {
        Log.i("DjiSdk", text)
        _djiStatus.value = text
    }

    override fun onCreate() {
        super.onCreate()
        initDjiSdk()
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
            SDKManager.getInstance().init(this, object : SDKManagerCallback {
                override fun onRegisterSuccess() {
                    setDjiStatus("✅ DJI SDK 등록 성공")
                    djiRegistered.value = true
                }

                override fun onRegisterFailure(error: IDJIError) {
                    setDjiStatus("❌ DJI SDK 등록 실패: ${error.description()}")
                }

                override fun onProductDisconnect(productId: Int) = Log.i("DjiSdk", "기체 연결 끊김: $productId").let {}
                override fun onProductConnect(productId: Int) = Log.i("DjiSdk", "기체 연결: $productId").let {}
                override fun onProductChanged(productId: Int) {}

                override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                    if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) enableLdm { SDKManager.getInstance().registerApp() }
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
            })
        } catch (e: Throwable) {
            // x86_64 에뮬레이터 등 DJI 네이티브 라이브러리가 없는 환경. 모의 기체로는 계속 쓸 수 있다.
            setDjiStatus("❌ DJI SDK 초기화 실패: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }
}
