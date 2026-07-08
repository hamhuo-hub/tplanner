import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Load API keys from local.properties (never committed to VCS)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.hamhuo.tplanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hamhuo.tplanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.0"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${localProperties.getProperty("deepseek.api.key", "")}\"")
        buildConfigField("String", "AMAP_API_KEY", "\"${localProperties.getProperty("amap.api.key", "")}\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // PackageManagerCompat.getUnusedAppRestrictionsStatus 返回 ListenableFuture，
    // 需要 guava 的 listenablefuture 存根在编译期可见
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    // Wearable Data Layer — 手表 ↔ 手机通过 GMS 通信（国际版 Wear OS 设备）。
    // 国行三星无 GMS 时走经典蓝牙 RFCOMM fallback。
    implementation(libs.play.services.wearable)
}
