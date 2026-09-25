package com.openfx.core.sync.http

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class OpenFxHttpReceiver(
    private val deviceName: String,
    private val saveDir: File,
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
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

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
                    Thread {
                        handleClient(socket)
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

                handleRequest(requestLine, headers, input, output)
            } catch (_: Exception) {
                // 對方中途斷線或協議錯誤 — 直接結束這條連線
            }
        }
    }

    private fun handleRequest(
        requestLine: String,
        headers: Map<String, String>,
        input: InputStream,
        output: java.io.OutputStream
    ) {
        when {
                requestLine.startsWith("GET /ping") -> {
                    val body = """
                        {
                          "app": "OpenFX",
                          "deviceName": "$deviceName",
                          "port": $port,
                          "version": 1
                        }
                    """.trimIndent()

                    writeResponse(
                        output = output,
                        status = "200 OK",
                        contentType = "application/json; charset=utf-8",
                        body = body
                    )
                }

                requestLine.startsWith("POST /upload") -> {
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

                        val body = """{"ok":true,"path":"${target.absolutePath}"}"""

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
            .ifBlank { "received_file" }

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
