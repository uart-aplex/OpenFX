object Versions {
    const val compileSdk = 35
    const val minSdk = 24
    const val targetSdk = 35
    const val tvMinSdk = 24

    const val kotlin = "2.2.0"
    const val agp = "8.13.2"
    const val ksp = "2.2.0-2.0.2"

    // Compose
    const val composeBom = "2024.12.01"
    const val composeCompiler = "1.5.15"
    const val composeTv = "1.0.0"

    // AndroidX
    const val coreKtx = "1.15.0"
    const val lifecycle = "2.8.7"
    const val navigation = "2.8.5"
    const val datastore = "1.1.1"
    const val room = "2.6.1"
    const val media3 = "1.5.1"
    const val leanback = "1.0.0"
    const val activity = "1.9.3"

    // DI
    const val hilt = "2.57"
    const val hiltNav = "1.2.0"

    // Image
    const val coil = "3.0.4"

    // Network / Files
    const val smbj = "0.13.0"
    const val commonsNet = "3.11.1"
    const val jsch = "0.2.21"
    const val commonsCompress = "1.27.1"
    const val junrar = "7.5.5"

    // Serialization
    const val serialization = "1.7.3"

    // Coroutines
    const val coroutines = "1.9.0"
    const val junit = "4.12"
}

object Deps {
    const val junit = "junit:junit:${Versions.junit}"
    // Kotlin
    const val kotlinSerialization = "org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.serialization}"
    const val coroutinesCore = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutines}"
    const val coroutinesAndroid = "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}"

    // AndroidX Core
    const val coreKtx = "androidx.core:core-ktx:${Versions.coreKtx}"
    const val activityCompose = "androidx.activity:activity-compose:${Versions.activity}"

    // Compose
    const val composeBom = "androidx.compose:compose-bom:${Versions.composeBom}"
    const val composeUi = "androidx.compose.ui:ui"
    const val composeMaterial3 = "androidx.compose.material3:material3"
    const val composeIcons = "androidx.compose.material:material-icons-extended"
    const val composeTooling = "androidx.compose.ui:ui-tooling-preview"
    const val composeToolingDebug = "androidx.compose.ui:ui-tooling"

    // Compose TV
    const val tvFoundation = "androidx.tv:tv-foundation:${Versions.composeTv}"
    const val tvMaterial = "androidx.tv:tv-material:${Versions.composeTv}"
    const val leanback = "androidx.leanback:leanback:${Versions.leanback}"

    // Lifecycle
    const val lifecycleRuntime = "androidx.lifecycle:lifecycle-runtime-compose:${Versions.lifecycle}"
    const val lifecycleViewModel = "androidx.lifecycle:lifecycle-viewmodel-compose:${Versions.lifecycle}"

    // Navigation
    const val navigationCompose = "androidx.navigation:navigation-compose:${Versions.navigation}"

    // DataStore
    const val datastore = "androidx.datastore:datastore-preferences:${Versions.datastore}"

    // Room
    const val roomRuntime = "androidx.room:room-runtime:${Versions.room}"
    const val roomKtx = "androidx.room:room-ktx:${Versions.room}"
    const val roomCompiler = "androidx.room:room-compiler:${Versions.room}"

    // Media3 (ExoPlayer)
    const val media3Player = "androidx.media3:media3-exoplayer:${Versions.media3}"
    const val media3Ui = "androidx.media3:media3-ui:${Versions.media3}"
    const val media3Session = "androidx.media3:media3-session:${Versions.media3}"

    // Image Loading
    const val coilCompose = "io.coil-kt.coil3:coil-compose:${Versions.coil}"
    const val coilVideo = "io.coil-kt.coil3:coil-video:${Versions.coil}"

    // Hilt DI
    const val hiltAndroid = "com.google.dagger:hilt-android:${Versions.hilt}"
    const val hiltCompiler = "com.google.dagger:hilt-compiler:${Versions.hilt}"
    const val hiltNavCompose = "androidx.hilt:hilt-navigation-compose:${Versions.hiltNav}"

    // Network File Systems
    const val smbj = "com.hierynomus:smbj:${Versions.smbj}"
    const val commonsNet = "commons-net:commons-net:${Versions.commonsNet}"
    const val jsch = "com.github.mwiede:jsch:${Versions.jsch}"

    // Archive
    const val commonsCompress = "org.apache.commons:commons-compress:${Versions.commonsCompress}"
    const val junrar = "com.github.junrar:junrar:${Versions.junrar}"
}
