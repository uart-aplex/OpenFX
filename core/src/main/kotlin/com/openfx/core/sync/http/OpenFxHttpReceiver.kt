package com.openfx.core.sync.http

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class OpenFxHttpReceiver(
    private val deviceName: String,
    private val saveDir: File,
    private val port: Int = 8989
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
            } catch (_: Exception) {
                isRunning = false
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
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val reader = input.bufferedReader()
            val requestLine = reader.readLine() ?: return

            val headers = mutableMapOf<String, String>()

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break

                val index = line.indexOf(":")
                if (index > 0) {
                    val key = line.substring(0, index).trim().lowercase()
                    val value = line.substring(index + 1).trim()
                    headers[key] = value
                }
            }

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
                        }
                    }

                    val body = """{"ok":true,"path":"${target.absolutePath}"}"""

                    writeResponse(
                        output = output,
                        status = "200 OK",
                        contentType = "application/json; charset=utf-8",
                        body = body
                    )
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
            .substringBefore(" ")

        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            "received_file"
        }
    }

    private fun createNonConflictingFile(dir: File, filename: String): File {
        var safeName = filename
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
}