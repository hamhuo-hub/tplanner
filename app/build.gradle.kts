import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Load API keys from local.properties (never committed to VCS)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val deepseekApiKey = providers.environmentVariable("DEEPSEEK_API_KEY").orNull
    ?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("deepseek.api.key", "")

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
        minSdk = 26
        targetSdk = 35
        // 版本号由根 build.gradle.kts 从 git tag（v*）推导，见 scripts/release.ps1
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekApiKey\"")
        buildConfigField(
            "String",
            "AMAP_API_KEY",
            "\"${localProperties.getProperty("amap.api.key", "")}\"",
        )
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("project") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        buildConfig = true
    }

    sourceSets {
        getByName("main").kotlin.directories.add(
            rootProject.file("shared/src/main/kotlin").absolutePath,
        )
        // MigrationTestHelper reads exported Room schemas from the debug APK assets.
        getByName("debug").assets.directories.add(file("schemas").absolutePath)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
    arg("room.generateKotlin", "true")
}
