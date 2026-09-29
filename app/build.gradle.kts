plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.myfirstapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myfirstapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
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
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0") {
        exclude(group = "org.tensorflow")
    }
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") {
        exclude(group = "org.tensorflow")
    }

    // 2. DJI Mobile SDK V5 의존성
    implementation("com.dji:dji-sdk-v5-aircraft:5.18.0") {
        exclude(group = "org.tensorflow")
    }
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.18.0")
}
