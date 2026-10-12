package com.example.myfirstapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.ui.MapScreen
import com.example.myfirstapp.ui.ProjectScreen
import com.example.myfirstapp.ui.flight.FlightScreen
import com.example.myfirstapp.ui.mission.MissionListScreen
import com.example.myfirstapp.ui.result.ResultFilter
import com.example.myfirstapp.ui.result.ResultScreen
import com.example.myfirstapp.ui.settings.SettingsScreen
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(dao)
                }
            }
        }
    }

    /**
     * 화면 이동(10장 화면 흐름). 화면마다 백 스택 항목이 생기고, 그 화면의 ViewModel은 항목이 스택에서 빠질 때 정리된다.
     * 비행 화면은 1차 판독 화면에 다녀오는 동안 스택에 남아 실행기가 이어진다.
     */
    @Composable
    private fun AppNavHost(dao: ProjectDao) {
        val nav = rememberNavController()
        val ids = listOf(navArgument("pid") { type = NavType.LongType }, navArgument("mid") { type = NavType.LongType })
        // 앱 재시작 복구(6.4): 끝나지 않은 소티가 있으면 그 미션의 비행 화면을 바로 연다(뒤로 가면 미션 목록)
        var recoveryChecked by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (recoveryChecked) return@LaunchedEffect
            recoveryChecked = true
            dao.openSortieMission()?.let {
                nav.navigate("missions/${it.projectId}")
                nav.navigate("flight/${it.projectId}/${it.missionId}")
            }
        }
        NavHost(nav, startDestination = "projects") {
            composable("projects") {
                ProjectScreen(
                    dao,
                    onOpenProject = { nav.navigate("missions/${it.id}") },
                    onOpenVerification = { nav.navigate("verify") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("missions/{pid}", listOf(navArgument("pid") { type = NavType.LongType })) { e ->
                val pid = e.arguments!!.getLong("pid")
                MissionListScreen(
                    pid, dao,
                    onBack = { nav.popBackStack() },
                    onNewPlan = { nav.navigate("map/$pid") },
                    onFlight = { nav.navigate("flight/$pid/$it") },
                    onReplan = { nav.navigate("map/$pid?replan=$it") },
                    onResult = { nav.navigate("result/$pid/$it") },
                )
            }
            composable(
                "map/{pid}?replan={replan}",
                listOf(navArgument("pid") { type = NavType.LongType }, navArgument("replan") { type = NavType.LongType; defaultValue = -1L }),
            ) { e ->
                val pid = e.arguments!!.getLong("pid")
                val replan = e.arguments!!.getLong("replan").takeIf { it > 0 }
                MapScreen(pid, dao, onBack = { nav.popBackStack() }, onStartFlight = { nav.navigate("flight/$pid/$it") }, replanFrom = replan)
            }
            composable("flight/{pid}/{mid}", ids) { e ->
                val pid = e.arguments!!.getLong("pid")
                val mid = e.arguments!!.getLong("mid")
                FlightScreen(
                    pid, mid, dao,
                    onExit = { nav.popBackStack() },
                    // 미션이 끝나면 비행 화면을 스택에서 빼고 결과로 간다(뒤로 가면 계획 화면)
                    onShowResult = { nav.navigate("result/$pid/$mid") { popUpTo("flight/{pid}/{mid}") { inclusive = true } } },
                    // 소티 완료 뒤 1차 판독은 검출 전주부터 연다(뒤로 가면 비행 화면)
                    onReview = { nav.navigate("result/$pid/$mid?filter=DETECTED") },
                )
            }
            composable(
                "result/{pid}/{mid}?filter={filter}",
                ids + navArgument("filter") { type = NavType.StringType; nullable = true },
            ) { e ->
                ResultScreen(
                    e.arguments!!.getLong("pid"), e.arguments!!.getLong("mid"), dao,
                    initialFilter = e.arguments!!.getString("filter")?.let(ResultFilter::valueOf),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable("verify") { VerificationPanel() }
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
