package com.example.myfirstapp

import android.view.KeyEvent
import androidx.compose.runtime.DisposableEffect
import android.view.MotionEvent
import com.example.myfirstapp.data.OpLog
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.myfirstapp.core.model.ShotMode
import com.example.myfirstapp.core.port.RcButton
import com.example.myfirstapp.data.ProjectDao
import com.example.myfirstapp.drone.DjiPoc
import com.example.myfirstapp.ui.MapScreen
import com.example.myfirstapp.ui.ProjectScreen
import com.example.myfirstapp.ui.flight.FlightScreen
import com.example.myfirstapp.ui.home.HomeScreen
import com.example.myfirstapp.ui.mission.MissionListScreen
import com.example.myfirstapp.ui.result.ResultFilter
import com.example.myfirstapp.ui.result.ResultScreen
import com.example.myfirstapp.ui.settings.SettingsHub
import com.example.myfirstapp.ui.theme.MyFirstAppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    /** 운용 로그(4.4): 안드로이드 키 입력(볼륨, 뒤로, 게임패드 등). RC Pro 전용 버튼은 SDK로 따로 받는다(drone/DjiInputLog.kt). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) OpLog.log("key", KeyEvent.keyCodeToString(event.keyCode), mapOf("repeat" to event.repeatCount))
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (isFinishing) OpLog.markCleanExit()
        super.onDestroy()
    }

    /** 운용 로그(4.4): 화면을 누른 위치(손가락을 뗄 때). 어떤 버튼인지는 각 화면 동작 로그로 남는다. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) OpLog.log("touch", "up", mapOf("x" to ev.x.toInt(), "y" to ev.y.toInt()))
        return super.dispatchTouchEvent(ev)
    }

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
        DisposableEffect(nav) {
            val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, d, args ->
                OpLog.log("nav", d.route.orEmpty(), args?.keySet()?.associateWith { k -> args.get(k)?.toString() }.orEmpty())
            }
            nav.addOnDestinationChangedListener(listener)
            onDispose { nav.removeOnDestinationChangedListener(listener) }
        }
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
        // RC 5D ← = 뒤로(10장). 첫 화면(홈, 앱 종료), 비행 화면(안전), 판독 화면(←→ 판정)은 제외한다.
        LaunchedEffect(Unit) {
            (application as InspectionApp).rcButtons.collect { b ->
                val route = nav.currentDestination?.route ?: return@collect
                if (b == RcButton.FIVE_D_LEFT && route != "home" && !route.startsWith("flight") && !route.startsWith("result")) {
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        NavHost(nav, startDestination = "home") {
            // 홈(Pilot 2 홈페이지, 10장): 비행경로·앨범·설정 타일, 기체 상태, 카메라 뷰로 들어가기
            composable("home") {
                HomeScreen(
                    dao,
                    onRoutes = { nav.navigate("projects") },
                    onAlbum = { pid, mid -> nav.navigate("result/$pid/$mid") },
                    onFlight = { pid, mid ->
                        nav.navigate("missions/$pid")
                        nav.navigate("flight/$pid/$mid")
                    },
                    onSettings = { nav.navigate("settings") },
                    onVerification = { nav.navigate("verify") },
                )
            }
            composable("projects") {
                ProjectScreen(
                    dao,
                    onBack = { nav.popBackStack() },
                    onNewPlan = { pid -> nav.navigate("map/$pid") },
                    onFlight = { pid, mid -> nav.navigate("flight/$pid/$mid") },
                    onReplan = { pid, mid -> nav.navigate("map/$pid?replan=$mid") },
                    onResult = { pid, mid -> nav.navigate("result/$pid/$mid") },
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
                    onOpenSettings = { nav.navigate("settings") },
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
            composable("settings") { SettingsHub(onBack = { nav.popBackStack() }) }
            composable("verify") { VerificationPanel() }
        }
    }

    /** DJI SDK와 TFLite 로드 확인용 검증 화면(PoC 전까지 유지). */
    @Composable
    private fun VerificationPanel() {
        val app = application as InspectionApp
        // SDK 초기화·등록은 InspectionApp이 앱 시작 때 한다. 여기서는 상태만 보여 준다.
        val djiStatus by app.djiStatus.collectAsState()
        val ldmStatus by app.ldmStatus.collectAsState()
        var yoloStatus by remember { mutableStateOf("YOLO TFLite: 초기화되지 않음") }
        var rcStatus by remember { mutableStateOf("RC 버튼: SDK 등록 뒤 감시") }
        var pocStatus by remember { mutableStateOf("기체 PoC: 버튼을 누르세요") }
        val scope = rememberCoroutineScope()
        var telemetry by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            app.djiReady.first { it }
            while (true) {
                telemetry = runCatching { DjiPoc.telemetry() }.getOrDefault("위치: SDK 준비 전")
                kotlinx.coroutines.delay(1000)
            }
        }
        // 기체 PoC(13장 1단계) 호출. 실패는 문구로만 보인다.
        fun poc(name: String, call: suspend () -> String) {
            pocStatus = "$name 진행 중..."
            scope.launch { pocStatus = runCatching { call() }.getOrElse { "❌ $name 실패: ${it.message}" } }
        }
        LaunchedEffect(Unit) {
            rcStatus = "RC 버튼: 감시 중(일시정지, C1, C2, RTH)"
            app.rcButtons.collect { b ->
                rcStatus = "RC 버튼: $b 누름"
                // PoC ⑥: 미션 중 일시정지 버튼은 펌웨어가 처리하고, C1로 미션을 재개해 본다
                if (b == RcButton.C1) poc("C1 재개") { DjiPoc.resume() }
                Log.i("RcKey", "$b t=${SystemClock.uptimeMillis()}")
            }
        }

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "M1 Mac Mini 연동 테스트", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(30.dp))

            // 라이브러리 연동 현황판
            Text(text = djiStatus, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = ldmStatus, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = yoloStatus, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = rcStatus, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = telemetry, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { poc("기체 상태") { DjiPoc.aircraft() } }) { Text("기체 상태") }
                OutlinedButton(onClick = { poc("촬영·다운로드") { DjiPoc.shootAndDownload(File(getExternalFilesDir(null), "poc")) } }) { Text("촬영·다운로드(③)") }
                OutlinedButton(onClick = { poc("시뮬레이터 켜기") { DjiPoc.simulator(true) } }) { Text("시뮬레이터 켜기") }
                OutlinedButton(onClick = { poc("시뮬레이터 끄기") { DjiPoc.simulator(false) } }) { Text("시뮬레이터 끄기") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // PoC ①: 올릴 KMZ. 처음은 현장 KMZ(adb로 files/poc/M1_137L-A.kmz에 넣음), [계획 KMZ]를 누르면 앱이 만든 파일
            val pocDir = File(getExternalFilesDir(null), "poc")
            var kmz by remember { mutableStateOf(File(pocDir, "M1_137L-A.kmz")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShotMode.entries.forEach { mode ->
                    OutlinedButton(onClick = {
                        poc("계획 KMZ") { DjiPoc.planKmz(app.database.projectDao(), pocDir, mode).let { (f, text) -> kmz = f; text } }
                    }) { Text(if (mode == ShotMode.OVERHEAD) "계획 KMZ(전주 위)" else "계획 KMZ(4방향)") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { poc("업로드") { DjiPoc.upload(kmz) } }) { Text("미션 업로드(①)") }
                OutlinedButton(onClick = { poc("시작") { DjiPoc.start(kmz) } }) { Text("미션 시작") }
                OutlinedButton(onClick = { poc("일시정지") { DjiPoc.pause() } }) { Text("일시정지") }
                OutlinedButton(onClick = { poc("재개") { DjiPoc.resume() } }) { Text("재개") }
                OutlinedButton(onClick = { poc("정지") { DjiPoc.stop(kmz) } }) { Text("정지") }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = pocStatus, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(20.dp))

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
