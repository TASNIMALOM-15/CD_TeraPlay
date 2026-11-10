plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teraplay.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.teraplay.app"
        minSdk = 24
        targetSdk = 34
        // GitHub Actions numbers every build (1, 2, 3 ...): a higher versionCode lets the new APK install over the old one
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.7.$run"
    }

    // ONE fixed signing key for every build. (A fresh GitHub runner creates a new random debug key each time,
    // and Android refuses to update an app that is signed with a different key - that forced the uninstall.)
    signingConfigs {
        create("teraplay") {
            storeFile = rootProject.file("keystore/teraplay.jks")
            storeType = "pkcs12"
            storePassword = "teraplay123"
            keyAlias = "teraplay"
            keyPassword = "teraplay123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("teraplay")
            isDebuggable = false   // runs like a release build (the debuggable flag makes apps slower)
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("teraplay")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
}
