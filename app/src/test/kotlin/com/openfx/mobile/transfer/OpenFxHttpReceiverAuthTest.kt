package com.openfx.mobile.transfer

import com.openfx.core.sync.http.OpenFxHttpReceiver
import com.openfx.core.sync.http.OpenFxHttpSender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files

class OpenFxHttpReceiverAuthTest {

    private val pin = "135790"
    private val deviceName = "Living \"Room\" TV\\1"
    private lateinit var saveDir: File
    private lateinit var receiver: OpenFxHttpReceiver
    private var port = 0
    private val received = mutableListOf<File>()

    @Before
    fun setUp() {
        saveDir = Files.createTempDirectory("openfx-recv").toFile()
        port = ServerSocket(0).use { it.localPort }
        receiver = OpenFxHttpReceiver(
            deviceName = deviceName,
            saveDir = saveDir,
            pin = pin,
            deviceId = "device-123",
            port = port,
            onFileReceived = { file, _ -> synchronized(received) { received += file } }
        )
        receiver.start()
        // 等 ServerSocket 綁定完成
        repeat(50) {
            if (OpenFxHttpSender.checkPin("127.0.0.1", port, null) != OpenFxHttpSender.PinCheck.UNREACHABLE) return
            Thread.sleep(20)
        }
    }

    @After
    fun tearDown() {
        receiver.stop()
        saveDir.deleteRecursively()
    }

    @Test
    fun pingIsValidJsonEvenWithQuotesInDeviceName() {
        val conn = URL("http://127.0.0.1:$port/ping").openConnection() as HttpURLConnection
        val body = conn.inputStream.bufferedReader().readText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(deviceName, json["deviceName"]!!.jsonPrimitive.content)
        assertEquals("device-123", json["deviceId"]!!.jsonPrimitive.content)
        assertEquals("pin", json["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun checkPinDistinguishesRightAndWrong() {
        assertEquals(OpenFxHttpSender.PinCheck.WRONG, OpenFxHttpSender.checkPin("127.0.0.1", port, null))
        assertEquals(OpenFxHttpSender.PinCheck.WRONG, OpenFxHttpSender.checkPin("127.0.0.1", port, "000000"))
        assertEquals(OpenFxHttpSender.PinCheck.OK, OpenFxHttpSender.checkPin("127.0.0.1", port, pin))
    }

    @Test
    fun uploadWithoutPinIsRejectedAndNothingIsSaved() {
        val bytes = ByteArray(32 * 1024) { it.toByte() }
        try {
            OpenFxHttpSender.uploadStream(
                targetIp = "127.0.0.1",
                port = port,
                fileName = "evil.apk",
                contentLength = bytes.size.toLong(),
                input = bytes.inputStream(),
                pin = null
            )
            fail("Upload without pin should not succeed")
        } catch (_: java.io.IOException) {
            // 401 (PinRejectedException) 或對方關閉連線導致的寫入錯誤都算拒絕
        }
        assertTrue(saveDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun uploadWithPinSucceeds() {
        val bytes = "hello openfx".toByteArray()
        val ok = OpenFxHttpSender.uploadStream(
            targetIp = "127.0.0.1",
            port = port,
            fileName = "hello.txt",
            contentLength = bytes.size.toLong(),
            input = bytes.inputStream(),
            pin = pin
        )
        assertTrue(ok)
        assertEquals("hello openfx", File(saveDir, "hello.txt").readText())
    }

    @Test
    fun missingPinDoesNotCountTowardLockout() {
        repeat(10) {
            assertEquals(OpenFxHttpSender.PinCheck.WRONG, OpenFxHttpSender.checkPin("127.0.0.1", port, null))
        }
        assertEquals(OpenFxHttpSender.PinCheck.OK, OpenFxHttpSender.checkPin("127.0.0.1", port, pin))
    }

    @Test
    fun repeatedWrongPinsLockOutEvenTheRightPin() {
        repeat(5) {
            assertEquals(OpenFxHttpSender.PinCheck.WRONG, OpenFxHttpSender.checkPin("127.0.0.1", port, "111111"))
        }
        assertEquals(OpenFxHttpSender.PinCheck.LOCKED, OpenFxHttpSender.checkPin("127.0.0.1", port, pin))
    }

    @Test
    fun changedPinTakesEffectImmediately() {
        receiver.pin = "864200"
        assertEquals(OpenFxHttpSender.PinCheck.WRONG, OpenFxHttpSender.checkPin("127.0.0.1", port, pin))
        assertEquals(OpenFxHttpSender.PinCheck.OK, OpenFxHttpSender.checkPin("127.0.0.1", port, "864200"))
    }

    @Test
    fun dotDotFilenameStaysInsideSaveDir() {
        val bytes = "x".toByteArray()
        OpenFxHttpSender.uploadStream(
            targetIp = "127.0.0.1",
            port = port,
            fileName = "..",
            contentLength = bytes.size.toLong(),
            input = bytes.inputStream(),
            pin = pin
        )
        assertTrue(File(saveDir, "received_file").isFile)
    }
}
