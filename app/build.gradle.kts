plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fz.yqlandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fz.yqlandroid"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // ========== Material Icons Extended ==========
    implementation("androidx.compose.material:material-icons-extended")
    
    // ========== WebRTC (Stream WebRTC Android) ==========
    implementation(libs.stream.webrtc)
    
    // ========== 网络请求 & WebSocket (OkHttp自带WebSocket支持) ==========
    implementation(libs.okhttp)
    
    // ========== JSON解析 ==========
    implementation(libs.gson)
    
    // ========== 协程 ==========
    implementation(libs.kotlinx.coroutines.android)
    
    // ========== Navigation Compose ==========
    implementation(libs.androidx.navigation.compose)
    
    // ========== ViewModel Compose ==========
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // ========== 权限请求 ==========
    implementation(libs.accompanist.permissions)
    
    // ========== CameraX (相机预览) ==========
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    
    // ========== ML Kit 二维码扫描 ==========
    implementation(libs.mlkit.barcode.scanning)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}