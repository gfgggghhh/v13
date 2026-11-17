plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.veinfinder.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.veinfinder.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Phone / built-in / external (Camera2) cameras
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // OpenCV (replaces Emgu.CV + opencv_*231.dll of the Windows app)
    implementation("org.opencv:opencv:4.9.0")

    // USB (UVC) webcam support - verify the latest version on GitHub: shiyinghan/UVCAndroid
    implementation("com.herohan:UVCAndroid:1.0.5")

    // Unit tests (pure logic: camera capability maths, settings validation, perf counters)
    testImplementation("junit:junit:4.13.2")
}
