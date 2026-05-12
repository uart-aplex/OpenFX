package com.openfx.app.ui.tv.remote

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.openfx.core.file.FileOperations
import com.openfx.core.sync.RemoteCommand
import com.openfx.core.sync.RemoteResponse
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import javax.inject.Inject

/**
 * 遠端控制服務 — 在 TV 端背景執行
 * 監聽手機端 TCP 連線 (port 18920)，接收 RemoteCommand，回傳 RemoteResponse
 * 
 * 在統一 APK 架構下，此 Service 只有在 TV 上才會被啟動
 */
@AndroidEntryPoint
class RemoteControlService : Service() {

    @Inject lateinit var fileOps: FileOperations

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val PORT = 18920
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startListening()
    }

    private fun startListening() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch { handleClient(clientSocket) }
                }
            } catch (_: Exception) { }
        }
    }

    private suspend fun handleClient(socket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            while (true) {
                val line = reader.readLine() ?: break
                val response = processCommand(line)
                writer.println(json.encodeToString(RemoteResponse.serializer(), response))
            }
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
    }

    private suspend fun processCommand(commandJson: String): RemoteResponse {
        return try {
            when (val cmd = json.decodeFromString(RemoteCommand.serializer(), commandJson)) {
                is RemoteCommand.ListDir -> fileOps.listFiles(cmd.path).fold(
                    onSuccess = { RemoteResponse.FileList(it) },
                    onFailure = { RemoteResponse.Error(it.message ?: "Unknown error") }
                )
                is RemoteCommand.Rename -> fileOps.rename(cmd.path, cmd.newName).fold(
                    onSuccess = { RemoteResponse.Success("Renamed") },
                    onFailure = { RemoteResponse.Error(it.message ?: "Rename failed") }
                )
                is RemoteCommand.Delete -> fileOps.delete(cmd.paths).fold(
                    onSuccess = { RemoteResponse.Success("Deleted $it items") },
                    onFailure = { RemoteResponse.Error(it.message ?: "Delete failed") }
                )
                is RemoteCommand.Move -> {
                    fileOps.move(cmd.sources, cmd.dest).collect { }
                    RemoteResponse.Success("Moved")
                }
                is RemoteCommand.Copy -> {
                    fileOps.copy(cmd.sources, cmd.dest).collect { }
                    RemoteResponse.Success("Copied")
                }
                is RemoteCommand.CreateDir -> fileOps.createDirectory(cmd.parentPath, cmd.name).fold(
                    onSuccess = { RemoteResponse.Success("Created") },
                    onFailure = { RemoteResponse.Error(it.message ?: "Failed") }
                )
                is RemoteCommand.PlayMedia -> RemoteResponse.Success("Playing ${cmd.path}")
                is RemoteCommand.GetStorageInfo -> RemoteResponse.Success("Storage info")
            }
        } catch (e: Exception) {
            RemoteResponse.Error("Parse error: ${e.message}")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        serverSocket?.close()
        super.onDestroy()
    }
}
