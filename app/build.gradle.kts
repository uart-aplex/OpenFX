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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
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
    }
}

dependencies {
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
