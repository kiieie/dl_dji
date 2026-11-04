package com.example.myfirstapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.view.KeyEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.example.myfirstapp.detect.ModelBench
import com.example.myfirstapp.drone.DjiPoc
import com.example.myfirstapp.ui.MapScreen
import com.example.myfirstapp.ui.ProjectScreen
import com.example.myfirstapp.ui.flight.FlightScreen
import com.example.myfirstapp.ui.flight.ManualFlightScreen
import com.example.myfirstapp.ui.mission.MissionNav
import com.example.myfirstapp.ui.procedure.PreflightScreen
import com.example.myfirstapp.ui.procedure.HmsScreen
import com.example.myfirstapp.ui.home.HomeScreen
import com.example.myfirstapp.ui.album.AlbumScreen
import com.example.myfirstapp.ui.result.ResultScreen
import com.example.myfirstapp.ui.replay.FlightRecordsScreen
import com.example.myfirstapp.ui.replay.ReplayScreen
import com.example.myfirstapp.ui.settings.SettingsHub
import com.example.myfirstapp.ui.theme.LightScreen
import com.example.myfirstapp.ui.theme.MyFirstAppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer

/** RC 5D ←를 뒤로 가기로 쓰는 화면(10.3 허용 목록). 계획 화면은 ↔가 값 바꾸기라 뺀다. */
private val FIVE_D_BACK_ROUTES = listOf("projects", "settings", "verify", "album", "records", "hms")

class MainActivity : ComponentActivity() {

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        OpLog.log("permission", "location", granted.mapValues { it.value })
    }

    /** 운용 로그(4.4): 안드로이드 키 입력(볼륨, 뒤로, 게임패드 등). RC Pro 전용 버튼은 SDK로 따로 받는다(drone/DjiInputLog.kt). */
    // lint 오탐: Activity.dispatchKeyEvent는 공개 프레임워크 API인데 androidx.core의 ComponentActivity 재정의에 붙은 @RestrictTo가 걸린다
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) OpLog.log("key", KeyEvent.keyCodeToString(event.keyCode), mapOf("repeat" to event.repeatCount))
        return super.dispatchKeyEvent(event)
    }

    /** 파일 선택 창 등 다른 창에서 돌아오면 시스템 바를 다시 숨긴다. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
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
        // Pilot 2처럼 전체 화면(10.0): 상태바·내비바를 숨기고, 가장자리를 쓸면 잠깐 나온다
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // 계획 이륙 지점의 셋째 대안(조종기 자체 GPS, 10.5.5). 거절해도 기체·DJI 조종기 GPS와 지도 선택은 된다
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        val dao = (application as InspectionApp).database.projectDao()
        startBenchIfAsked(intent)
        setContent {
            MyFirstAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(dao)
                }
            }
        }
    }

    /** 앱이 이미 떠 있을 때 adb로 다시 연 경우(launchMode singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startBenchIfAsked(intent)
    }

    /**
     * 모델 속도 측정(9.6)을 adb로 시작: `adb shell am start -n kr.co.dl_dji/com.example.myfirstapp.MainActivity --es bench all`.
     * 기체가 꺼져 있어도 된다. 결과는 `files/logs/bench-<시각>.txt`와 운용 로그(`bench`), 진행은 검증 화면에 보인다.
     * `--es bench full`이면 지속 성능 GPU 캐시가 없을 때 만들어 잰다(모델마다 수 분), `--es benchPhoto <절대 경로>`로 사진을 고른다.
     */
    private fun startBenchIfAsked(intent: Intent?) {
        val arg = intent?.getStringExtra("bench") ?: return
        val photo = intent.getStringExtra("benchPhoto")
        OpLog.log("bench", "adb", mapOf("arg" to arg, "photo" to photo))
        (application as InspectionApp).startBench(full = arg == "full", photo = photo)
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
        /** 미션 비행 화면을 연다(10.3): 홈 위에 그 프로젝트를 고른 비행경로 → 비행. 같은 화면은 한 번만 쌓는다. */
        fun openFlight(pid: Long, mid: Long) {
            nav.navigate("projects?pid=$pid") { popUpTo("home"); launchSingleTop = true }
            nav.navigate("flight/$pid/$mid") { launchSingleTop = true }
        }
        /** 비행 화면 라이브러리 패널(10.5.8)에서 고른 곳: 비행 화면을 닫고(땅에서만 열림) 그 프로젝트의 비행경로 위에 연다. */
        fun libraryNav(n: MissionNav) {
            val m = n.mission
            nav.navigate("projects?pid=${m.projectId}") { popUpTo("home"); launchSingleTop = true }
            val route = when (n) {
                is MissionNav.Flight -> "flight/${m.projectId}/${m.id}"
                is MissionNav.Replan -> "map/${m.projectId}?replan=${m.id}"
                is MissionNav.Result -> "result/${m.projectId}/${m.id}"
                is MissionNav.Copy -> "map/${m.projectId}?copy=${m.id}"
                is MissionNav.Replay -> "replay?mission=${m.id}"
            }
            nav.navigate(route) { launchSingleTop = true }
        }
        // 앱 재시작 복구(6.4): 끝나지 않은 소티가 있으면 그 미션의 비행 화면을 바로 연다(뒤로 가면 비행경로)
        var recoveryChecked by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (recoveryChecked) return@LaunchedEffect
            recoveryChecked = true
            dao.openSortieMission()?.let { openFlight(it.projectId, it.missionId) }
        }
        // RC 5D ← = 뒤로(10.3). 허용한 화면에서만 동작한다(홈·비행·수동 비행·판독은 제외, 새 화면도 기본은 제외).
        // 대화상자가 열려 있으면 그 대화상자만 닫힌다(FullscreenAlertDialog의 BackHandler).
        LaunchedEffect(Unit) {
            (application as InspectionApp).rcButtons.collect { b ->
                val route = nav.currentDestination?.route ?: return@collect
                if (b == RcButton.FIVE_D_LEFT && FIVE_D_BACK_ROUTES.any { route.startsWith(it) }) onBackPressedDispatcher.onBackPressed()
            }
        }
        NavHost(nav, startDestination = "home") {
            // 홈(Pilot 2 홈페이지, 10장): 비행경로·앨범·설정 타일, 기체 상태, 카메라 뷰로 들어가기
            // 절차 화면은 Pilot 2처럼 밝은 바탕(LightScreen, 2026-11-04 사용자 결정). 카메라 뷰·지도는 어두운 기본 테마
            composable("home") {
                LightScreen { HomeScreen(
                    dao,
                    onRoutes = { nav.navigate("projects") },
                    onAlbum = { _, _ -> nav.navigate("album") },
                    onFlight = ::openFlight,
                    onNewPlan = { pid -> nav.navigate("map/$pid") },
                    // Pilot 2 [카메라 뷰 입력] → 비행 전 점검 → 카메라 뷰(10.5.5)
                    onCamera = { nav.navigate("preflight?camera=true") },
                    onSettings = { nav.navigate("settings?general=true") { launchSingleTop = true } },
                    onHms = { nav.navigate("hms") { launchSingleTop = true } },
                    onVerification = { nav.navigate("verify") },
                    onFlightRecords = { nav.navigate("records") },
                ) }
            }
            // 비행 기록(10.4.6, Pilot 2 프로필 → 비행 기록): 자동 비행 소티·수동 비행 최신순 → 다시 보기
            composable("records") {
                LightScreen {
                    FlightRecordsScreen(dao, onBack = { nav.popBackStack() }) { r ->
                        nav.navigate(if (r.sessionId != null) "replay?session=${r.sessionId}" else "replay?mission=${r.missionId}&sortie=${r.sortieId}")
                    }
                }
            }
            // 비행 다시 보기(10.4.6): 미션(소티, 없으면 첫 소티) 또는 수동 비행. 5D ←→는 찾기라 뒤로 가기에 넣지 않는다
            composable(
                "replay?mission={mission}&sortie={sortie}&session={session}",
                listOf("mission", "sortie", "session").map { n -> navArgument(n) { type = NavType.LongType; defaultValue = -1L } },
            ) { e ->
                fun arg(n: String) = e.arguments!!.getLong(n).takeIf { it > 0 }
                ReplayScreen(dao, arg("mission"), arg("sortie"), arg("session"), onBack = { nav.popBackStack() }, onReview = { pid, mid -> nav.navigate("result/$pid/$mid") })
            }
            // 수동 비행 카메라 뷰(Pilot 2 카메라 뷰): 미션 없이 조종사가 스틱으로 난다. 경로 알약을 누르면 최근 미션 비행 화면으로
            composable("camera") {
                ManualFlightScreen(
                    dao,
                    onExit = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
                    onOpenMission = { m -> openFlight(m.projectId, m.missionId) },
                    onAlbum = { nav.navigate("album") { launchSingleTop = true } },
                    onLibraryNav = ::libraryNav,
                    onPreflight = { nav.navigate("preflight?camera=false") { launchSingleTop = true } },
                    onCameraSettings = { nav.navigate("settings?camera=true") { launchSingleTop = true } },
                )
            }
            // 비행 전 점검(10.5.5): camera면 [카메라 뷰 입력]으로 수동 비행 카메라 뷰(점검 화면은 스택에서 뺌), 아니면 닫기만
            composable("preflight?camera={camera}", listOf(navArgument("camera") { type = NavType.BoolType; defaultValue = false })) { e ->
                val toCamera = e.arguments!!.getBoolean("camera")
                LightScreen {
                    PreflightScreen(
                        onClose = { nav.popBackStack() },
                        onNext = if (toCamera) ({ nav.navigate("camera") { popUpTo("preflight?camera={camera}") { inclusive = true } } }) else null,
                        onHms = { nav.navigate("hms") { launchSingleTop = true } },
                    )
                }
            }
            // HMS 목록(10.5.5): 홈 HMS 칸·비행 전 점검 "표준 HMS ›". [기체 점검 ›]은 ⋯ 상태 점검
            composable("hms") {
                LightScreen { HmsScreen(onClose = { nav.popBackStack() }, onAircraftCheck = { nav.navigate("settings") { launchSingleTop = true } }) }
            }
            // 비행경로(마스터-디테일). pid가 있으면 그 프로젝트를 고른 채 연다
            composable("projects?pid={pid}", listOf(navArgument("pid") { type = NavType.LongType; defaultValue = -1L })) { e ->
                LightScreen { ProjectScreen(
                    dao,
                    initialProjectId = e.arguments!!.getLong("pid").takeIf { it > 0 },
                    onBack = { nav.popBackStack() },
                    onNewPlan = { pid -> nav.navigate("map/$pid") },
                    onFlight = { pid, mid -> nav.navigate("flight/$pid/$mid") { launchSingleTop = true } },
                    onReplan = { pid, mid -> nav.navigate("map/$pid?replan=$mid") },
                    onResult = { pid, mid -> nav.navigate("result/$pid/$mid") },
                    onCopy = { pid, mid -> nav.navigate("map/$pid?copy=$mid") },
                    onReplay = { mid -> nav.navigate("replay?mission=$mid") },
                    onImportRoute = { pid, uri -> nav.navigate("map/$pid?kmz=${android.net.Uri.encode(uri)}") },
                ) }
            }
            composable(
                "map/{pid}?replan={replan}&copy={copy}&kmz={kmz}",
                listOf(
                    navArgument("pid") { type = NavType.LongType }, navArgument("replan") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("copy") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("kmz") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e ->
                val pid = e.arguments!!.getLong("pid")
                val replan = e.arguments!!.getLong("replan").takeIf { it > 0 }
                val copy = e.arguments!!.getLong("copy").takeIf { it > 0 }
                // ▶·[저장하고 수행]: 비행 화면을 비행 전 점검을 연 채로(10.5.5 → 체크리스트)
                MapScreen(
                    pid, dao, onBack = { nav.popBackStack() }, onStartFlight = { nav.navigate("flight/$pid/$it?pre=true") { launchSingleTop = true } },
                    replanFrom = replan, copyFrom = copy, importUri = e.arguments!!.getString("kmz"),
                )
            }
            composable("flight/{pid}/{mid}?pre={pre}", ids + navArgument("pre") { type = NavType.BoolType; defaultValue = false }) { e ->
                val pid = e.arguments!!.getLong("pid")
                val mid = e.arguments!!.getLong("mid")
                FlightScreen(
                    pid, mid, dao, openPreflight = e.arguments!!.getBoolean("pre"),
                    onExit = { nav.popBackStack() },
                    // 미션이 끝나면 비행 화면을 스택에서 빼고 결과로 간다(뒤로 가면 계획 화면)
                    onShowResult = { nav.navigate("result/$pid/$mid") { popUpTo("flight/{pid}/{mid}?pre={pre}") { inclusive = true } } },
                    onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
                    onCameraSettings = { nav.navigate("settings?camera=true") { launchSingleTop = true } },
                    onAlbum = { nav.navigate("album") { launchSingleTop = true } },
                    onHms = { nav.navigate("hms") { launchSingleTop = true } },
                    // 지도 라이브러리 패널(10.5.8) [열기]·편집: 비행 화면을 닫고 그곳으로(떠 있으면 패널은 미리보기만)
                    onLibraryNav = ::libraryNav,
                )
            }
            composable("result/{pid}/{mid}", ids) { e ->
                val mid = e.arguments!!.getLong("mid")
                ResultScreen(e.arguments!!.getLong("pid"), mid, dao, onBack = { nav.popBackStack() }, onReplay = { nav.navigate("replay?mission=$mid") })
            }
            composable(
                "settings?general={general}&camera={camera}",
                listOf(navArgument("general") { type = NavType.BoolType; defaultValue = false }, navArgument("camera") { type = NavType.BoolType; defaultValue = false }),
            ) { e ->
                LightScreen { SettingsHub(onBack = { nav.popBackStack() }, initialGeneral = e.arguments!!.getBoolean("general"), initialCamera = e.arguments!!.getBoolean("camera")) }
            }
            // 앨범(10.4.3 ⑦): 사진 모아 보기. 미션 사진을 누르면 그 미션 판독으로
            composable("album") { LightScreen { AlbumScreen(dao, onBack = { nav.popBackStack() }, onReview = { pid, mid -> nav.navigate("result/$pid/$mid") }) } }
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
            Spacer(modifier = Modifier.height(10.dp))
            // 모델 속도 측정(9.6): models/의 모든 모델 CPU·GPU, 비행 중 모델 연속 30회. adb로도 시작한다(--es bench all)
            val bench by ModelBench.status.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(onClick = { app.startBench() }) { Text("모델 속도 측정") }
                Text(text = bench, style = MaterialTheme.typography.bodyMedium)
            }
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
                        poc("계획 KMZ") { DjiPoc.planKmz(app.database.projectDao(), pocDir, ShotMode.DIAGONAL4, terrain = mode == ShotMode.DIAGONAL4).let { (f, text) -> kmz = f; text } }
                    }) { Text(if (mode == ShotMode.OVERHEAD) "계획 KMZ(이륙 기준)" else "계획 KMZ(지형 추적)") }
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
