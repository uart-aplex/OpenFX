package com.openfx.core.sync.http

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OpenFxHttpSender {

    const val DEFAULT_PORT = 8989

    class UploadCancelledException : IOException("Upload cancelled")

    /**
     * 上傳控制柄 — 用來從外部取消進行中的上傳
     * cancel() 會中斷連線，讓阻塞中的寫入立即失敗
     */
    class UploadHandle {
        @Volatile
        var isCancelled: Boolean = false
            private set

        @Volatile
        internal var connection: HttpURLConnection? = null

        fun cancel() {
            isCancelled = true
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun uploadFile(
        targetIp: String,
        port: Int = DEFAULT_PORT,
        file: File,
        senderDeviceName: String = "OpenFX Device",
        handle: UploadHandle? = null,
        onProgress: (bytesSent: Long) -> Unit = {}
    ): Boolean {
        return file.inputStream().use { input ->
            uploadStream(
                targetIp = targetIp,
                port = port,
                fileName = file.name,
                contentLength = file.length(),
                input = input,
                senderDeviceName = senderDeviceName,
                handle = handle,
                onProgress = onProgress
            )
        }
    }

    fun uploadStream(
        targetIp: String,
        port: Int = DEFAULT_PORT,
        fileName: String,
        contentLength: Long,
        input: InputStream,
        senderDeviceName: String = "OpenFX Device",
        handle: UploadHandle? = null,
        onProgress: (bytesSent: Long) -> Unit = {}
    ): Boolean {
        require(contentLength >= 0) { "Content length is required for HTTP upload" }
        if (handle?.isCancelled == true) throw UploadCancelledException()

        val encodedName = URLEncoder.encode(fileName, "UTF-8")
        val url = URL("http://$targetIp:$port/upload?filename=$encodedName")
        val conn = url.openConnection() as HttpURLConnection
        handle?.connection = conn

        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        // 回應要等整個檔案傳完對方才會送出,大檔案需要較長容忍時間
        conn.readTimeout = 300_000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Content-Length", contentLength.toString())
        conn.setRequestProperty(
            "X-OpenFX-Device",
            URLEncoder.encode(senderDeviceName, "UTF-8")
        )
        conn.setFixedLengthStreamingMode(contentLength)

        return try {
            conn.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    if (handle?.isCancelled == true) throw UploadCancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    onProgress(sent)
                }
                output.flush()
            }
            conn.responseCode in 200..299
        } finally {
            handle?.connection = null
            conn.disconnect()
        }
    }
}
