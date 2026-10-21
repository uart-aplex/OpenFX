package com.openfx.core.sync.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

data class OpenFxDevice(
    val ip: String,
    val deviceName: String,
    val port: Int = 8989,
    /** 接收端的固定識別碼；舊版 (0.2.x) 接收端沒有 */
    val deviceId: String? = null
) {
    /** 儲存配對碼用的 key — 優先用 deviceId，IP 變動後仍有效 */
    val peerKey: String
        get() = deviceId?.takeIf { it.isNotBlank() } ?: "$ip:$port"
}

object LanDeviceScanner {

    fun getLocalIpAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()

        for (networkInterface in interfaces) {
            val addresses = networkInterface.inetAddresses

            for (address in addresses) {
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val ip = address.hostAddress ?: continue

                    if (ip.isPrivateLanAddress()) {
                        return ip
                    }
                }
            }
        }

        return null
    }

    fun scanSameSubnet(
        port: Int = 8989,
        timeoutMs: Int = 1500,
        retryCount: Int = 2,
        batchSize: Int = 32
    ): List<OpenFxDevice> {
        val localIp = getLocalIpAddress() ?: return emptyList()
        val prefix = localIp.substringBeforeLast(".")
        val found = Collections.synchronizedList(mutableListOf<OpenFxDevice>())

        val ipList = (1..254).map { index -> "$prefix.$index" }

        ipList.chunked(batchSize).forEach { batch ->
            val threads = batch.map { ip ->
                thread(start = true) {
                    repeat(retryCount) {
                        val device = probeDevice(ip, port, timeoutMs)

                        if (device != null) {
                            synchronized(found) {
                                if (found.none { it.ip == device.ip }) {
                                    found.add(device)
                                }
                            }
                            return@thread
                        }

                        Thread.sleep(80)
                    }
                }
            }

            threads.forEach { it.join() }
        }

        return found.toList().sortedBy { it.ip }
    }

    private fun probeDevice(
        ip: String,
        port: Int,
        timeoutMs: Int
    ): OpenFxDevice? {
        return try {
            val url = URL("http://$ip:$port/ping")
            val conn = url.openConnection() as HttpURLConnection

            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.useCaches = false

            val responseCode = conn.responseCode

            if (responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

                if (json?.get("app")?.jsonPrimitive?.contentOrNull == "OpenFX") {
                    val name = json["deviceName"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: "OpenFX Device"
                    val deviceId = json["deviceId"]?.jsonPrimitive?.contentOrNull
                    OpenFxDevice(ip = ip, deviceName = name, port = port, deviceId = deviceId)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.isPrivateLanAddress(): Boolean {
        if (startsWith("10.") || startsWith("192.168.")) return true

        val parts = split(".")
        if (parts.size < 2 || parts.first() != "172") return false

        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }
}
