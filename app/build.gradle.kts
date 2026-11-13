plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.replayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.replayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 65
        versionName = "1.1.5"

        val defaultTgToken = (System.getenv("TG_BOT_TOKEN") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val defaultTgChat = (System.getenv("TG_CHAT_ID") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_TG_BOT_TOKEN", "\"$defaultTgToken\"")
        buildConfigField("String", "DEFAULT_TG_CHAT_ID", "\"$defaultTgChat\"")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("replayerSigning") {
            storeFile = file("keystore/replayer.keystore")
            storePassword = "replayer123"
            keyAlias = "replayer"
            keyPassword = "replayer123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("replayerSigning")
        }
        release {
            signingConfig = signingConfigs.getByName("replayerSigning")
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
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "**/*.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Android TV Compose (Material3 TV)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // LibVLC for robust multi-format playback (all AVI, DivX, Xvid, MKV, MP4, etc.)
    implementation("org.videolan.android:libvlc-all:3.5.1")

    // Networking, JSON, Image loading
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation("com.google.zxing:core:3.5.3")
}

