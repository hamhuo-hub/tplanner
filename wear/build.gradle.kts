plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hamhuo.tplanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hamhuo.tplanner"
        // VibrationEffect.createOneShot 需要 API 26，故 minSdk 提到 26。
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    // 现代表盘 API：Wear OS 3+/三星 Galaxy Watch 仅识别这一套（及 WFF），
    // 旧的 WallpaperService 表盘不会出现在表盘选择器里。
    implementation(libs.androidx.wear.watchface)
}
