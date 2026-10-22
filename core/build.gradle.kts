plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.openfx.core"
    compileSdk = Versions.compileSdk

    defaultConfig {
        minSdk = Versions.minSdk
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
    // Kotlin
    implementation(Deps.coroutinesCore)
    implementation(Deps.coroutinesAndroid)
    implementation(Deps.kotlinSerialization)

    // AndroidX
    implementation(Deps.coreKtx)
    implementation(Deps.datastore)

    // Room
    implementation(Deps.roomRuntime)
    implementation(Deps.roomKtx)
    ksp(Deps.roomCompiler)

    // Media3 (ExoPlayer)
    implementation(Deps.media3Player)
    implementation(Deps.media3Session)

    // Hilt
    implementation(Deps.hiltAndroid)
    ksp(Deps.hiltCompiler)

    // Network File Systems
    implementation(Deps.smbj)
    implementation(Deps.dcerpc)
    implementation(Deps.commonsNet)
    implementation(Deps.jsch)

    // Archive
    implementation(Deps.commonsCompress)
    implementation(Deps.junrar)
}
