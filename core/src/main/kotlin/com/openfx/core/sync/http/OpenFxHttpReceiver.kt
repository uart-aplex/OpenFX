package com.openfx.core.sync.http

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * OpenFX HTTP 接收端
 *
 * 安全性：/upload 與 /auth 都必須帶 X-OpenFX-Pin header，值等於接收端畫面上的 6 位數配對碼。
 * 同一個 IP 連續輸錯 [MAX_PIN_FAILURES] 次會被鎖 [LOCKOUT_MS] 毫秒 (回 429)，避免區網內暴力猜碼。
 * /ping 不需驗證，只回傳公開資訊 (名稱、deviceId、port)。
 */
class OpenFxHttpReceiver(
    private val deviceName: String,
    private val saveDir: File,
    pin: String,
    private val deviceId: String = "",
    private val port: Int = 8989,
    private val onFileReceived: (File, senderDeviceName: String) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {},
    private val onProgress: (
        fileName: String,
        bytesReceived: Long,
        totalBytes: Long,
        senderDeviceName: String
    ) -> Unit = { _, _, _, _ -> },
    private val onFailed: (fileName: String, reason: String) -> Unit = { _, _ -> }
) {
    companion object {
        const val PIN_HEADER = "X-OpenFX-Pin"
        private const val MAX_PIN_FAILURES = 5
        private const val LOCKOUT_MS = 60_000L
        private const val MAX_CONNECTIONS = 8
    }

    /** 配對碼 — 接收中也可更換，下一個請求立即生效 */
    @Volatile
    var pin: String = pin

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    private class PinAttempts(var failures: Int = 0, var lockedUntil: Long = 0L)

    private val pinAttempts = ConcurrentHashMap<String, PinAttempts>()
    private val connectionSlots = Semaphore(MAX_CONNECTIONS)

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return

        isRunning = true
        saveDir.mkdirs()

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(port)

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    // 同時連線數上限 — 避免惡意或異常的大量連線耗盡執行緒
                    if (!connectionSlots.tryAcquire()) {
                        runCatching { socket.close() }
                        continue
                    }
                    Thread {
                        try {
                            handleClient(socket)
                        } finally {
                            connectionSlots.release()
                        }
                    }.start()
                }
            } catch (e: Exception) {
                val wasRunning = isRunning
                isRunning = false
                if (wasRunning) {
                    onError(e.message ?: "HTTP receiver stopped")
                }
            }
        }

        serverThread?.start()
    }

    fun stop() {
        isRunning = false

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverThread?.interrupt()
        serverSocket = null
        serverThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                // 30 秒沒收到資料就判定對方斷線 — 避免半開連線讓接收任務永遠卡住
                client.soTimeout = 30_000
                // header 是逐 byte 讀的，務必包 buffer（body 也從同一條 stream 繼續讀）
                val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
                val output = client.getOutputStream()

                val requestLine = input.readHttpLine() ?: return

                val headers = mutableMapOf<String, String>()

                while (true) {
                    val line = input.readHttpLine() ?: break
                    if (line.isEmpty()) break

                    val index = line.indexOf(":")
                    if (index > 0) {
                        val key = line.substring(0, index).trim().lowercase()
                        val value = line.substring(index + 1).trim()
                        headers[key] = value
                    }
                }

                val remoteIp = client.inetAddress?.hostAddress ?: "unknown"
                handleRequest(requestLine, headers, remoteIp, input, output)
            } catch (_: Exception) {
                // 對方中途斷線或協議錯誤 — 直接結束這條連線
            }
        }
    }

    private fun handleRequest(
        requestLine: String,
        headers: Map<String, String>,
        remoteIp: String,
        input: InputStream,
        output: java.io.OutputStream
    ) {
        when {
                requestLine.startsWith("GET /ping") -> {
                    val body = buildJsonObject {
                        put("app", "OpenFX")
                        put("deviceName", deviceName)
                        put("deviceId", deviceId)
                        put("port", port)
                        put("version", 2)
                        put("auth", "pin")
                    }.toString()

                    writeResponse(
                        output = output,
                        status = "200 OK",
                        contentType = "application/json; charset=utf-8",
                        body = body
                    )
                }

                requestLine.startsWith("GET /auth") -> {
                    if (!authorize(headers, remoteIp, output)) return
                    writeResponse(
                        output = output,
                        status = "200 OK",
                        contentType = "application/json; charset=utf-8",
                        body = buildJsonObject { put("ok", true) }.toString()
                    )
                }

                requestLine.startsWith("POST /upload") -> {
                    if (!authorize(headers, remoteIp, output)) return
                    val filename = extractFilename(requestLine)
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                    val senderDeviceName = decodeDeviceName(headers["x-openfx-device"])

                    if (contentLength <= 0L) {
                        writeResponse(
                            output = output,
                            status = "400 Bad Request",
                            contentType = "text/plain; charset=utf-8",
                            body = "Missing Content-Length"
                        )
                        return
                    }

                    // usableSpace 取不到時回傳 0 — 只有確定空間不足才拒收
                    val freeSpace = saveDir.usableSpace
                    if (freeSpace in 1 until contentLength) {
                        writeResponse(
                            output = output,
                            status = "507 Insufficient Storage",
                            contentType = "text/plain; charset=utf-8",
                            body = "Not enough free space"
                        )
                        onError("儲存空間不足，無法接收 $filename")
                        return
                    }

                    val target = createNonConflictingFile(saveDir, filename)

                    try {
                        onProgress(filename, 0L, contentLength, senderDeviceName)
                        target.outputStream().use { fileOut ->
                            var remaining = contentLength
                            val buffer = ByteArray(64 * 1024)

                            while (remaining > 0) {
                                val read = input.read(
                                    buffer,
                                    0,
                                    minOf(buffer.size.toLong(), remaining).toInt()
                                )

                                if (read <= 0) break

                                fileOut.write(buffer, 0, read)
                                remaining -= read
                                onProgress(
                                    filename,
                                    contentLength - remaining,
                                    contentLength,
                                    senderDeviceName
                                )
                            }

                            if (remaining > 0) {
                                throw IllegalStateException("Upload ended early: $remaining bytes missing")
                            }
                        }

                        onFileReceived(target, senderDeviceName)

                        val body = buildJsonObject {
                            put("ok", true)
                            put("name", target.name)
                        }.toString()

                        writeResponse(
                            output = output,
                            status = "200 OK",
                            contentType = "application/json; charset=utf-8",
                            body = body
                        )
                    } catch (e: Exception) {
                        target.delete()
                        val message = e.message ?: "Upload failed"
                        onFailed(filename, message)
                        onError(message)
                        writeResponse(
                            output = output,
                            status = "500 Internal Server Error",
                            contentType = "text/plain; charset=utf-8",
                            body = message
                        )
                    }
                }

                else -> {
                    writeResponse(
                        output = output,
                        status = "404 Not Found",
                        contentType = "text/plain; charset=utf-8",
                        body = "Not Found"
                    )
                }
        }
    }

    /**
     * 驗證 X-OpenFX-Pin。失敗時已寫出 401/429 回應，呼叫端直接 return。
     */
    private fun authorize(
        headers: Map<String, String>,
        remoteIp: String,
        output: java.io.OutputStream
    ): Boolean {
        val now = System.currentTimeMillis()
        val attempts = pinAttempts.getOrPut(remoteIp) { PinAttempts() }
        val candidate = headers[PIN_HEADER.lowercase()]

        val result = synchronized(attempts) {
            when {
                attempts.lockedUntil > now -> AuthResult.LOCKED
                pinMatches(candidate) -> {
                    attempts.failures = 0
                    AuthResult.OK
                }
                // 沒帶配對碼 (傳送端首次預檢) 不算猜錯
                candidate.isNullOrEmpty() -> AuthResult.WRONG
                else -> {
                    attempts.failures++
                    if (attempts.failures >= MAX_PIN_FAILURES) {
                        attempts.failures = 0
                        attempts.lockedUntil = now + LOCKOUT_MS
                    }
                    AuthResult.WRONG
                }
            }
        }

        when (result) {
            AuthResult.OK -> return true
            AuthResult.LOCKED -> writeResponse(
                output = output,
                status = "429 Too Many Requests",
                contentType = "text/plain; charset=utf-8",
                body = "Too many wrong pairing codes"
            )
            AuthResult.WRONG -> writeResponse(
                output = output,
                status = "401 Unauthorized",
                contentType = "text/plain; charset=utf-8",
                body = "Pairing code required"
            )
        }
        return false
    }

    private enum class AuthResult { OK, WRONG, LOCKED }

    /** 固定時間比較，避免從回應時間推測配對碼 */
    private fun pinMatches(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            pin.toByteArray(Charsets.UTF_8)
        )
    }

    private fun writeResponse(
        output: java.io.OutputStream,
        status: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)

        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun extractFilename(requestLine: String): String {
        val raw = requestLine
            .substringAfter("filename=", "received_file")
            .substringBefore("&")
            .substringBefore(" ")

        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            "received_file"
        }
    }

    private fun decodeDeviceName(encodedName: String?): String {
        if (encodedName.isNullOrBlank()) return "OpenFX Device"
        return runCatching { URLDecoder.decode(encodedName, "UTF-8") }
            .getOrDefault("OpenFX Device")
            .takeIf { it.isNotBlank() }
            ?: "OpenFX Device"
    }

    private fun createNonConflictingFile(dir: File, filename: String): File {
        val safeName = filename
            .replace("/", "_")
            .replace("\\", "_")
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "received_file"

        var target = File(dir, safeName)

        if (!target.exists()) return target

        val nameWithoutExt = target.nameWithoutExtension
        val ext = if (target.extension.isNotEmpty()) ".${target.extension}" else ""

        var counter = 1
        while (target.exists()) {
            val suffix = if (counter == 1) "_copy" else "_copy$counter"
            target = File(dir, "$nameWithoutExt$suffix$ext")
            counter++
        }

        return target
    }

    private fun InputStream.readHttpLine(): String? {
        val bytes = mutableListOf<Byte>()

        while (true) {
            val value = read()
            if (value == -1) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.ISO_8859_1)
            }

            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.lastIndex)
                }
                return bytes.toByteArray().toString(Charsets.ISO_8859_1)
            }

            bytes.add(value.toByte())
        }
    }
}
