package com.openfx.mobile.apps

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import com.openfx.core.sync.TransferManager
import com.openfx.core.sync.TransferStatus
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.core.sync.http.OpenFxHttpReceiver
import com.openfx.mobile.ui.MainActivity
import com.openfx.mobile.ui.theme.OpenFXTheme
import com.openfx.mobile.ui.transfer.TransferViewModel
import com.openfx.core.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** No test-library downloads: exercises real package exports, HTTP transfer and session staging. */
class AppTransferInstrumentation : Instrumentation() {
    private var mode: String? = null
    private var targetIp: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode")
        targetIp = arguments?.getString("targetIp")
        start()
    }

    override fun onStart() {
        thread {
            val result = Bundle()
            try {
                if (mode == "ui") showPicker()
                else if (mode == "send") runBlocking { sendToPeer() }
                else runBlocking { verifyTransfer() }
                result.putString("stream", "PASS: ${mode ?: "export / transfer / stage"}\n")
                finish(-1, result)
            } catch (error: Throwable) {
                result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
                finish(0, result)
            }
        }
    }

    private fun showPicker() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val viewModel = InstalledAppsViewModel(InstalledAppsRepository(targetContext))
        runOnMainSync {
            activity.setContent {
                OpenFXTheme {
                    InstalledAppsDialog("Test receiver", false, null, {}, { activity.finish() }, viewModel)
                }
            }
        }
        val end = System.currentTimeMillis() + 10000
        while (viewModel.state.value.loading && System.currentTimeMillis() < end) Thread.sleep(100)
        check(!viewModel.state.value.loading && viewModel.state.value.error == null)
        Thread.sleep(1000)
        val field = nodes(uiAutomation.rootInActiveWindow).first { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                viewModel.state.value.apps.first { it.packageName == targetContext.packageName }.label)
        }))
        Thread.sleep(1000)
        val row = nodes(uiAutomation.rootInActiveWindow).first { node ->
            node.isCheckable && node.isClickable && nodes(node).any {
                it.text?.toString()?.contains(targetContext.packageName) == true
            }
        }
        check(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(1000)
        val expected = targetContext.getString(com.openfx.mobile.R.string.apps_selected, 1)
        var selected = false
        repeat(30) {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            if (nodes(uiAutomation.rootInActiveWindow).any { it.text?.toString() == expected }) selected = true
            if (!selected) Thread.sleep(100)
        }
        check(selected) { "Selection count did not update" }
        val screenshot = requireNotNull(uiAutomation.takeScreenshot())
        File(targetContext.getExternalFilesDir(null), "installed-app-picker.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }

    private suspend fun verifyTransfer() {
        val repository = InstalledAppsRepository(targetContext)
        val apps = repository.listApps()
        check(apps.any { it.packageName == targetContext.packageName })
        val split = apps.filter { it.isSplit && !it.isSystem }.minByOrNull { it.size }
        check(split != null) { "Need one installed split app to exercise complete export" }
        val exports = mutableListOf<File>()
        val receiveDir = File(targetContext.cacheDir, "app_transfer_test").apply { mkdirs() }
        val received = AtomicReference<File?>()
        val receiver = OpenFxHttpReceiver(
            deviceName = "App transfer test",
            saveDir = receiveDir,
            port = 18989,
            onFileReceived = { file, _ -> received.set(file) }
        )
        try {
            receiver.start()
            delay(300)
            val manager = TransferManager()
            listOf(targetContext.packageName, split.packageName).forEach { packageName ->
                val file = repository.export(packageName)
                exports += file
                if (file.extension == "apk") {
                    val source = File(targetContext.packageManager.getApplicationInfo(packageName, 0).sourceDir)
                    check(digest(file) == digest(source))
                } else {
                    val app = targetContext.packageManager.getApplicationInfo(packageName, 0)
                    val sources = listOf(File(app.sourceDir)) + app.splitSourceDirs.orEmpty().map(::File)
                    ZipFile(file).use { zip ->
                        val entries = AppPackageArchive.entries(zip)
                        check(entries.size == sources.size)
                        entries.forEachIndexed { index, entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            check(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(MessageDigest.getInstance("SHA-256").digest(sources[index].readBytes())))
                        }
                    }
                }
                received.set(null)
                val id = manager.sendStream(file.name, file.length(), OpenFxDevice(ip = "127.0.0.1", deviceName = "App transfer test", port = 18989), "Sender") { file.inputStream() }
                withTimeout(60000) {
                    while (manager.history.value.none { it.id == id }) delay(50)
                    check(manager.history.value.first { it.id == id }.status == TransferStatus.COMPLETED)
                    while (received.get() == null) delay(50)
                }
                check(digest(file) == digest(received.get()!!))
                if (file.extension == "apks") {
                    val sessionId = AppPackageInstaller.stage(targetContext, received.get()!!)
                    try {
                        targetContext.packageManager.packageInstaller.openSession(sessionId).use { session ->
                            ZipFile(file).use { zip ->
                                check(session.names.toSet() == AppPackageArchive.entries(zip).map { it.name }.toSet())
                            }
                        }
                    } finally { targetContext.packageManager.packageInstaller.abandonSession(sessionId) }
                }
            }
        } finally {
            receiver.stop()
            exports.forEach { it.delete(); it.parentFile?.delete() }
            receiveDir.listFiles()?.forEach { it.delete() }
            receiveDir.delete()
        }
    }

    private suspend fun sendToPeer() {
        val repository = InstalledAppsRepository(targetContext)
        val split = repository.listApps().filter { it.isSplit && !it.isSystem }
            .sortedBy { it.size }.firstOrNull { !hasNativeLibraries(it.packageName) }
        check(split != null)
        val manager = TransferManager()
        val viewModel = TransferViewModel(manager, SettingsRepository(targetContext), repository, targetContext)
        val queued = AtomicBoolean(false)
        viewModel.sendInstalledApps(
            listOf(targetContext.packageName, split.packageName),
            OpenFxDevice(ip = requireNotNull(targetIp), deviceName = "Peer"),
            onQueued = { queued.set(true) }
        )
        withTimeout(90000) {
            while (!queued.get()) delay(100)
            while (manager.history.value.size < 2) delay(100)
        }
        check(manager.history.value.all { it.status == TransferStatus.COMPLETED })
        delay(200)
        val exportDir = File(targetContext.cacheDir, "app_exports")
        check(exportDir.listFiles().orEmpty().all { it.listFiles().isNullOrEmpty() }) { "Export snapshots were not cleaned up" }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasNativeLibraries(packageName: String): Boolean {
        val app = targetContext.packageManager.getApplicationInfo(packageName, 0)
        return (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).any { path ->
            ZipFile(path).use { zip -> zip.entries().asSequence().any { it.name.startsWith("lib/") } }
        }
    }
}
