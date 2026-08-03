import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
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
            signingConfig = signingConfigs.findByName("project") ?: signingConfigs.getByName("debug")
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
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    // 现代表盘 API：Wear OS 3+/三星 Galaxy Watch 仅识别这一套（及 WFF），
    // 旧的 WallpaperService 表盘不会出现在表盘选择器里。
    implementation(libs.androidx.wear.watchface)
    // Wearable Data Layer — 手表端通过 GMS 发消息给手机（国际版 Wear OS）。
    // 国行三星无 GMS 时 fallback 到 PhoneWaker 的经典蓝牙 RFCOMM。
    implementation(libs.play.services.wearable)
}
