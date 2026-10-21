package com.openfx.core.sync.http

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * UDP broadcast 裝置發現
 *
 * 接收端開啟「我要接收」時會同時啟動 advertiser：
 * 監聽 UDP 18930 port，收到 OPENFX_DISCOVER 就回覆自己的名稱和 HTTP port。
 *
 * 掃描端廣播 DISCOVER 訊息，收集 2 秒內的回覆。
 * 比對整個 /24 網段做 HTTP ping 快得多 (2 秒 vs 數十秒)。
 */
object UdpDiscovery {

    const val DISCOVERY_PORT = 18930
    private const val REQUEST = "OPENFX_DISCOVER_V1"
    private const val RESPONSE_PREFIX = "OPENFX_HERE_V1|"

    /**
     * 啟動回應器，讓其他裝置找得到本機。
     * 回傳的 [Closeable] close() 後停止回應。
     */
    fun startAdvertiser(
        deviceName: String,
        httpPort: Int = OpenFxHttpSender.DEFAULT_PORT,
        deviceId: String = ""
    ): Closeable {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(DISCOVERY_PORT))
            broadcast = true
            soTimeout = 500
        }
        val running = AtomicBoolean(true)

        val worker = thread(start = true, isDaemon = true, name = "openfx-udp-advertiser") {
            val buffer = ByteArray(1024)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (message == REQUEST) {
                        val safeName = deviceName.replace("|", " ")
                        // 第 3 欄 deviceId 是 0.3 新增；舊版掃描端只讀前兩欄，相容
                        val payload = "$RESPONSE_PREFIX$safeName|$httpPort|$deviceId"
                            .toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(payload, payload.size, packet.address, packet.port))
                    }
                } catch (_: SocketTimeoutException) {
                    // 定期醒來檢查 running 旗標
                } catch (_: Exception) {
                    // socket 被關閉或網路錯誤 — 結束執行緒
                    break
                }
            }
        }

        return Closeable {
            running.set(false)
            try {
                socket.close()
            } catch (_: Exception) {
            }
            worker.interrupt()
        }
    }

    /**
     * 廣播搜尋附近的 OpenFX 接收端，最多等待 [timeoutMs] 毫秒。
     * 這是阻塞呼叫，請在 IO thread 執行。
     */
    fun scan(timeoutMs: Int = 2000): List<OpenFxDevice> {
        val found = LinkedHashMap<String, OpenFxDevice>()
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 200
        }

        try {
            val payload = REQUEST.toByteArray(Charsets.UTF_8)
            val targets = mutableListOf(InetAddress.getByName("255.255.255.255"))

            // 加上子網路定向廣播位址 (部分裝置不轉發 255.255.255.255)
            LanDeviceScanner.getLocalIpAddress()?.let { ip ->
                targets.add(InetAddress.getByName(ip.substringBeforeLast(".") + ".255"))
            }

            targets.forEach { target ->
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)

            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (message.startsWith(RESPONSE_PREFIX)) {
                        val parts = message.removePrefix(RESPONSE_PREFIX).split("|")
                        val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "OpenFX Device"
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: OpenFxHttpSender.DEFAULT_PORT
                        val deviceId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                        val ip = packet.address.hostAddress ?: continue
                        found[ip] = OpenFxDevice(ip = ip, deviceName = name, port = port, deviceId = deviceId)
                    }
                } catch (_: SocketTimeoutException) {
                    // 繼續等到 deadline
                }
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        return found.values.toList()
    }
}
