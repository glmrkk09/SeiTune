plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val youtubedlAndroid = "0.18.1"

android {
    namespace = "com.mrk.seitune"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mrk.seitune"
        minSdk = 24
        targetSdk = 34
        versionCode = 35
        versionName = "0.10.5"
        // arm64 only = one small APK (the builder signs a single APK). Add "armeabi-v7a" for old 32-bit phones.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // so assembleRelease installs without a keystore
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // yt-dlp's python/ffmpeg are shipped as .so files and must be extracted to run
    packaging { jniLibs { useLegacyPackaging = true; pickFirsts += "**/libc++_shared.so" } }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    // offline speech recognition: writes lyrics for songs that have none anywhere (model downloaded on first use)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // ffmpeg library removed to save ~25-30 MB per APK (no MP3 conversion / HD video merge)
}
