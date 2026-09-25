package com.openfx.core.sync.http

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

data class OpenFxDevice(
    val ip: String,
    val deviceName: String,
    val port: Int = 8989
)

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

                if (body.contains("OpenFX", ignoreCase = true)) {
                    val name = extractJsonString(body, "deviceName") ?: "OpenFX Device"
                    OpenFxDevice(ip = ip, deviceName = name, port = port)
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

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun String.isPrivateLanAddress(): Boolean {
        if (startsWith("10.") || startsWith("192.168.")) return true

        val parts = split(".")
        if (parts.size < 2 || parts.first() != "172") return false

        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }
}
