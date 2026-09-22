plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.yunsi.tiptransferdemo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.yunsi.tiptransferdemo"
        minSdk = 29
        targetSdk = 37
        // 실기기에서 설치된 BLE 구현 버전을 구분하기 위한 테스트 빌드 번호.
        versionCode = 8
        versionName = "1.0.8-ble-v2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.zxing.core)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.coroutines.play.services)
    // Apache-2.0. 오픈소스 런타임. 재화 이동/도착에 쓰는 Lottie 모션을 Compose에 표시한다.
    implementation("com.airbnb.android:lottie-compose:6.6.2")
    // ISC 라이선스의 Compose 파티클 라이브러리. 완료 순간에만 짧고 절제된 축하 효과로 사용한다.
    implementation("nl.dionsegijn:konfetti-compose:2.0.5")
    // 이전 3D 구현 파일의 소스 호환을 위해 임시 유지한다. 화면에서는 2D 에셋 렌더러만 사용한다.
    implementation("io.github.sceneview:sceneview:4.17.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
