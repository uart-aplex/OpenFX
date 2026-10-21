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

    /** 接收端拒絕配對碼 (401) 或暫時鎖定 (429) */
    class PinRejectedException(val locked: Boolean) : IOException(
        if (locked) "Too many wrong pairing codes" else "Pairing code rejected"
    )

    enum class PinCheck {
        /** 配對碼正確 */
        OK,
        /** 配對碼錯誤或未提供 */
        WRONG,
        /** 輸錯太多次，接收端暫時鎖定 */
        LOCKED,
        /** 0.2.x 舊版接收端，沒有 /auth，也不檢查配對碼 */
        LEGACY,
        /** 連不到對方 */
        UNREACHABLE
    }

    /** 用 GET /auth 預先確認配對碼，避免上傳整個檔案才被拒絕 */
    fun checkPin(targetIp: String, port: Int = DEFAULT_PORT, pin: String?): PinCheck {
        return try {
            val conn = URL("http://$targetIp:$port/auth").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.useCaches = false
                pin?.let { conn.setRequestProperty(OpenFxHttpReceiver.PIN_HEADER, it) }
                when (conn.responseCode) {
                    in 200..299 -> PinCheck.OK
                    401 -> PinCheck.WRONG
                    429 -> PinCheck.LOCKED
                    404 -> PinCheck.LEGACY
                    else -> PinCheck.UNREACHABLE
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            PinCheck.UNREACHABLE
        }
    }

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
        pin: String? = null,
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
                pin = pin,
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
        pin: String? = null,
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
        pin?.let { conn.setRequestProperty(OpenFxHttpReceiver.PIN_HEADER, it) }
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
            when (conn.responseCode) {
                401 -> throw PinRejectedException(locked = false)
                429 -> throw PinRejectedException(locked = true)
                else -> conn.responseCode in 200..299
            }
        } finally {
            handle?.connection = null
            conn.disconnect()
        }
    }
}
