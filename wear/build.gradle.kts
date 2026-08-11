import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 项目专用签名：手机和手表用同一把 keystore，Wearable Data Layer 才认
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasProjectKeystore = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.hamhuo.tplanner"
    compileSdk = 35

    signingConfigs {
        if (hasProjectKeystore) {
            create("project") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.hamhuo.tplanner"
        // VibrationEffect.createOneShot 需要 API 26，故 minSdk 提到 26。
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "mobile_2.0.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("project") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("project")?.let { signingConfig = it }
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
    }

    sourceSets {
        getByName("main").kotlin.directories.add(
            rootProject.file("shared/src/main/kotlin").absolutePath,
        )
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    // 现代表盘 API：Wear OS 3+/三星 Galaxy Watch 仅识别这一套（及 WFF），
    // 旧的 WallpaperService 表盘不会出现在表盘选择器里。
    implementation(libs.androidx.wear.watchface)
    // Wearable Data Layer — 手表端通过 GMS 唤醒手机（国际版 Wear OS）。
    // 无 GMS 的 RFCOMM 降级当前只覆盖手机 → 手表日程同步。
    implementation(libs.play.services.wearable)
}
