plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.openfx.mobile"
    compileSdk = Versions.compileSdk

    defaultConfig {
        applicationId = "com.openfx.mobile"
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk
        versionCode = 5
        versionName = "0.3.0"
        testInstrumentationRunner = "com.openfx.mobile.apps.AppTransferInstrumentation"
    }

    // Release 簽章：CI 從 GitHub Secrets 還原 keystore 並設定這四個環境變數；
    // 本機沒設時 release 不簽章 (F-Droid 會用自己的金鑰簽)
    val releaseKeystore = System.getenv("OPENFX_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("OPENFX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENFX_KEY_ALIAS")
                keyPassword = System.getenv("OPENFX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
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
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // 依 values-xx 資料夾自動產生 locales_config，Android 13+ 可在系統設定單獨切換 App 語言
    androidResources {
        generateLocaleConfig = true
    }
    // App 內可切換語言，AAB 不能依裝置語言拆掉其他語系的資源
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    testImplementation(Deps.junit)
    implementation(project(":core"))

    // Compose (Mobile)
    implementation(platform(Deps.composeBom))
    implementation(Deps.composeUi)
    implementation(Deps.composeMaterial3)
    implementation(Deps.composeIcons)
    implementation(Deps.composeTooling)
    debugImplementation(Deps.composeToolingDebug)

    // Compose for TV (統一 APK — TV UI 用這組)
    implementation(Deps.tvFoundation)
    implementation(Deps.tvMaterial)
    implementation(Deps.leanback)

    // AndroidX
    implementation(Deps.coreKtx)
    implementation(Deps.activityCompose)
    implementation(Deps.biometric)
    implementation(Deps.zxingCore)
    implementation(Deps.zxingEmbedded)
    implementation(Deps.lifecycleRuntime)
    implementation(Deps.lifecycleViewModel)
    implementation(Deps.navigationCompose)

    // Media
    implementation(Deps.media3Player)
    implementation(Deps.media3Ui)
    implementation(Deps.media3Session)

    // Image
    implementation(Deps.coilCompose)
    implementation(Deps.coilVideo)

    // Serialization
    implementation(Deps.kotlinSerialization)

    // Hilt
    implementation(Deps.hiltAndroid)
    ksp(Deps.hiltCompiler)
    implementation(Deps.hiltNavCompose)
}
