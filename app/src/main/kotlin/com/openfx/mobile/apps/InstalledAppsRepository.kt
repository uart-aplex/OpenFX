package com.openfx.mobile.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.UUID
import javax.inject.Inject

data class InstalledApp(
    val packageName: String,
    val label: String,
    val version: String,
    val size: Long,
    val isSystem: Boolean,
    val isSplit: Boolean
)

class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun listApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        val collator = Collator.getInstance()
        packages.mapNotNull { info ->
            currentCoroutineContext().ensureActive()
            val app = info.applicationInfo ?: return@mapNotNull null
            val files = apkFiles(app)
            if (!app.enabled || files.any { !it.isFile || !it.canRead() }) return@mapNotNull null
            InstalledApp(
                packageName = info.packageName,
                label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                version = info.versionName.orEmpty(),
                size = files.sumOf { it.length() },
                isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isSplit = files.size > 1
            )
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    suspend fun export(packageName: String): File = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        val app = requireNotNull(info.applicationInfo)
        val apks = apkFiles(app)
        require(apks.all { it.isFile && it.canRead() }) { "APK files are no longer available" }
        val directory = File(context.cacheDir, "app_exports/${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Unable to create export directory" }
        val version = info.versionName.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val file = File(directory, "${info.packageName}_${version.ifBlank { "app" }}.${if (apks.size > 1) "apks" else "apk"}")
        try {
            val coroutineContext = currentCoroutineContext()
            if (apks.size > 1) {
                AppPackageArchive.write(apks, file) { coroutineContext.ensureActive() }
            } else {
                apks.single().inputStream().buffered().use { input ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            check(file.length() > 0)
            file
        } catch (error: Throwable) {
            file.delete()
            directory.delete()
            throw error
        }
    }

    private fun apkFiles(info: ApplicationInfo): List<File> =
        listOf(File(info.sourceDir)) + info.splitSourceDirs.orEmpty().map(::File)
}
