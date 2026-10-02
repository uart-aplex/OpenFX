package com.openfx.mobile.apps

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object AppPackageInstaller {
    /** Stage all splits in one atomic installation; committing still requires system confirmation. */
    suspend fun stage(context: Context, file: File): Int = withContext(Dispatchers.IO) {
        require(file.isFile)
        val installer = context.packageManager.packageInstaller
        ZipFile(file).use { zip ->
            val entries = AppPackageArchive.entries(zip)
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(entries.sumOf { it.size })
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            try {
                installer.openSession(sessionId).use { session ->
                    entries.forEach { entry ->
                        currentCoroutineContext().ensureActive()
                        zip.getInputStream(entry).use { input ->
                            session.openWrite(entry.name, 0, entry.size).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    require(copied <= entry.size) { "APK size mismatch" }
                                    output.write(buffer, 0, count)
                                }
                                require(copied == entry.size) { "Incomplete APK" }
                                session.fsync(output)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                sessionId
            } catch (error: Throwable) {
                installer.abandonSession(sessionId)
                throw error
            }
        }
    }
}
