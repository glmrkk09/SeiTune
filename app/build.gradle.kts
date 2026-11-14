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
        versionCode = 36
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

// youtubedl-android pulls in an old commons-io whose Java7Support class breaks on Android
// (NoClassDefFoundError: org.apache.commons.io.Java7Support while YoutubeDL.updateYoutubeDL unzips the new yt-dlp).
// 2.11.0 no longer has that class, so force it for every configuration.
configurations.all {
    resolutionStrategy {
        force("commons-io:commons-io:2.11.0")
        eachDependency {
            if (requested.group == "commons-io" && requested.name == "commons-io") {
                useVersion("2.11.0")
                because("avoid Java7Support NoClassDefFoundError on Android")
            }
        }
    }
}

dependencies {
    implementation("commons-io:commons-io:2.11.0")
    // GuestAuth: WebView work on Dispatchers.Main, waiting/cookie read/file write on Dispatchers.IO
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    // offline speech recognition: writes lyrics for songs that have none anywhere (model downloaded on first use)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // ffmpeg: needed for MP3 conversion and for merging HD video+audio (adds ~25-30 MB to the APK)
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
}
