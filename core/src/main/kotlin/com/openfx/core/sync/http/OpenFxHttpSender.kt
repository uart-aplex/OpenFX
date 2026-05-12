package com.openfx.core.sync.http

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OpenFxHttpSender {
    fun uploadFile(
        targetIp: String,
        port: Int = 8989,
        file: File
    ): Boolean {
        val encodedName = URLEncoder.encode(file.name, "UTF-8")
        val url = URL("http://$targetIp:$port/upload?filename=$encodedName")
        val conn = url.openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Content-Length", file.length().toString())

        file.inputStream().use { input ->
            conn.outputStream.use { output ->
                input.copyTo(output)
            }
        }

        return conn.responseCode in 200..299
    }
}