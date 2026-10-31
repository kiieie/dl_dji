import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// DJI SDK 앱 키는 secrets/dji.properties(git 제외)에서 읽어 매니페스트에 넣는다. 없으면 빈 값으로 빌드되고 SDK 등록만 실패한다.
val djiApiKey: String = rootProject.file("secrets/dji.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) }.getProperty("DJI_API_KEY") }
    ?: "".also { logger.warn("secrets/dji.properties 없음: DJI_API_KEY 없이 빌드합니다") }

// Cesium ion 토큰(7.4 임시 결정, 온라인 위성 배경). secrets/cesium.properties(git 제외)가 없으면 온라인 배경 없이 빌드한다.
val cesiumIonToken: String = rootProject.file("secrets/cesium.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) }.getProperty("CESIUM_ION_TOKEN") }
    .orEmpty()

// VWorld 오픈API 키(7.4 배경 지도 선택). secrets/vworld.properties(git 제외)가 없으면 VWorld 지도 없이 빌드한다.
val vworldApiKey: String = rootProject.file("secrets/vworld.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) }.getProperty("VWORLD_API_KEY") }
    .orEmpty()

// DJI LDM 오프라인 라이선스(7.2). secrets/license_kr.co.dl_dji.txt(git 제외)의 내용을 BuildConfig로 넣는다. 없으면 LDM 없이 빌드한다.
val ldmLicense: String = rootProject.file("secrets/license_kr.co.dl_dji.txt").takeIf { it.exists() }
    ?.readText()?.trim().orEmpty()
    .also { if (it.isEmpty()) logger.warn("secrets/license_kr.co.dl_dji.txt 없음: LDM 라이선스 없이 빌드합니다") }

android {
    namespace = "com.example.myfirstapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.dl_dji"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["DJI_API_KEY"] = djiApiKey
        buildConfigField("String", "CESIUM_ION_TOKEN", "\"$cesiumIonToken\"")
        buildConfigField("String", "VWORLD_API_KEY", "\"$vworldApiKey\"")
        buildConfigField("String", "DJI_LDM_LICENSE", "\"$ldmLicense\"")

        // M1 Mac 및 DJI SDK 기기 구동을 위한 아키텍처 고정
        ndk {
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            // 원본 설정을 유지하되, 필요에 따라 minifyEnabled 등 추가 조절 가능
            optimization {
                enable = false
            }
        }
    }

    // 최신 Compose 및 DJI SDK 연동을 위해 Java 17로 업그레이드
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // DJI SDK 내부 네이티브 라이브러리(.so) 파일 충돌 방지
    packaging {
        resources {
            pickFirsts.add("lib/**/libc++_shared.so")
            pickFirsts.add("lib/**/libcrypto.so")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maplibre.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 1. TensorFlow Lite (YOLO TFLite 모델 실행용)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu) {
        exclude(group = "org.tensorflow")
    }
    implementation(libs.tensorflow.lite.support) {
        exclude(group = "org.tensorflow")
    }

    // 2. DJI Mobile SDK V5 의존성
    implementation(libs.dji.sdk.aircraft) {
        exclude(group = "org.tensorflow")
    }
    compileOnly(libs.dji.sdk.aircraft.provided)
}
