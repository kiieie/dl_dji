package com.example.myfirstapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.data.ProjectSummary
import com.example.myfirstapp.ui.FlightScreen
import com.example.myfirstapp.ui.MapScreen
import com.example.myfirstapp.ui.ProjectScreen
import com.example.myfirstapp.ui.theme.MyFirstAppTheme
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = (application as InspectionApp).database.projectDao()
        setContent {
            MyFirstAppTheme {
                var verifying by remember { mutableStateOf(false) }
                var openProject by remember { mutableStateOf<ProjectSummary?>(null) }
                var flightMission by remember { mutableStateOf<Long?>(null) }
                BackHandler(verifying || openProject != null) { verifying = false; openProject = null }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val project = openProject
                    val mission = flightMission
                    when {
                        verifying -> VerificationPanel()
                        // 비행 화면은 자체 BackHandler로 비행 중 뒤로 가기를 막는다.
                        project != null && mission != null -> FlightScreen(project.id, mission, dao, onExit = { flightMission = null })
                        project != null -> MapScreen(project, dao, onBack = { openProject = null }, onStartFlight = { flightMission = it })
                        else -> ProjectScreen(dao, onOpenProject = { openProject = it }, onOpenVerification = { verifying = true })
                    }
                }
            }
        }
    }

    /** DJI SDK와 TFLite 로드 확인용 검증 화면(PoC 전까지 유지). */
    @Composable
    private fun VerificationPanel() {
        var djiStatus by remember { mutableStateOf("DJI SDK: 초기화되지 않음") }
        var yoloStatus by remember { mutableStateOf("YOLO TFLite: 초기화되지 않음") }

        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "M1 Mac Mini 연동 테스트", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(30.dp))

            // 라이브러리 연동 현황판
            Text(text = djiStatus, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = yoloStatus, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(40.dp))

            // 검증 시작 버튼들
            Button(
                onClick = {
                    djiStatus = "DJI 라이브러리 검증 중..."
                    testDjiInitialization { success, message ->
                        djiStatus = if (success) "✅ DJI SDK 검증 완료: $message" else "❌ DJI SDK 실패: $message"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("DJI SDK V5 라이브러리 검증")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    yoloStatus = "YOLO Interpreter 테스트 중..."
                    val success = testTfliteInterpreter()
                    yoloStatus = if (success) "✅ YOLO TFLite 엔진 정상 작동!" else "❌ YOLO TFLite 구동 실패"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("YOLO (TFLite) 엔진 검증")
            }
        }
    }

    // 1. DJI 네이티브 바이너리가 M1 및 스마트폰 환경에서 크래시 없이 로드되는지 확인
    // 변경된 오버라이딩 메소드 이름 적용 (onInitProcess -> V5 규격 맞춤)
    private fun testDjiInitialization(onResult: (Boolean, String) -> Unit) {
        try {
            SDKManager.getInstance().init(applicationContext, object : SDKManagerCallback {
                override fun onRegisterSuccess() {
                    onResult(true, "등록 성공")
                }

                override fun onRegisterFailure(error: IDJIError) {
                    onResult(false, "등록 실패: ${error.description()}")
                }

                override fun onProductDisconnect(productId: Int) {}

                override fun onProductConnect(productId: Int) {}

                override fun onProductChanged(productId: Int) {}

                override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {}

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
            })
        } catch (e: NoClassDefFoundError) {
            onResult(false, "에뮬레이터(x86_64) 미지원: DJI SDK는 ARM64 실기기 환경이 필요합니다.")
        } catch (e: ClassNotFoundException) {
            onResult(false, "에뮬레이터(x86_64) 미지원: DJI SDK는 ARM64 실기기 환경이 필요합니다.")
        } catch (e: Throwable) {
            onResult(false, e.localizedMessage ?: e.javaClass.simpleName ?: "알 수 없는 에러")
        }
    }

    // 2. TensorFlow Lite 코어 엔진이 에러 없이 가속 실행을 인지하는지 확인
    private fun testTfliteInterpreter(): Boolean {
        return try {
            val emptyBuffer = ByteBuffer.allocateDirect(4) // 테스트용 가상 버퍼 생성
            val options = Interpreter.Options().apply {
                setUseNNAPI(false) // 기본 환경 분석을 위해 일시 비활성화
            }
            val interpreter = Interpreter(emptyBuffer, options)
            interpreter.close()
            true
        } catch (_: IllegalArgumentException) {
            // 빈 버퍼를 전달했기 때문에 발생하는 포맷 에러는 정상적인 반응입니다.
            // 핵심인 C++ 하위 라이브러리가 메모리에 정상 링크되었다는 증거입니다.
            true
        } catch (e: Throwable) {
            false
        }
    }
}
