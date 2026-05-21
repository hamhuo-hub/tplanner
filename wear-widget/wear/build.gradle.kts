plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace         = "com.tplanner.wear"
    compileSdk        = 34
    defaultConfig {
        applicationId = "com.tplanner.wear"
        minSdk        = 30   // Wear OS 3 (Galaxy Watch 4+)
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.wear.core)
    implementation(libs.wear.tiles)
    implementation(libs.wear.tiles.material)
    implementation(libs.wear.watchface)
    implementation(libs.wear.watchface.data)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)
    implementation(libs.gson)
}
