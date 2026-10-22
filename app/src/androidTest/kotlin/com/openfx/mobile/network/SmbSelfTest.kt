package com.openfx.mobile.network

import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.openfx.core.file.LocalFileOperations
import com.openfx.core.file.RoutingFileOperations
import com.openfx.core.media.MediaUris
import com.openfx.core.media.SmbDataSource
import com.openfx.core.network.smb.SmbConnectionPool
import com.openfx.core.network.smb.SmbFileOperations
import com.openfx.core.network.smb.SmbPath
import com.openfx.core.network.smb.SmbServer
import com.openfx.core.network.smb.SmbServerRepository
import com.openfx.core.security.SecretCipher
import kotlinx.coroutines.flow.collect
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 對真實 SMB 伺服器的端對端測試 (不經過 UI)
 *
 * am instrument -e mode smb -e smbHost <ip> -e smbPort <port> -e smbUser <user>
 *   -e smbPassword <pw> -e smbShare <share> -e smbSample <共享內的檔名，用來測 DataSource>
 *
 * 會在共享內建立並刪除 OpenFX_selftest 資料夾，結束時移除測試用的伺服器設定。
 */
@UnstableApi
class SmbSelfTest(private val context: Context, args: Bundle) {
    private val host = requireNotNull(args.getString("smbHost")) { "smbHost required" }
    private val port = args.getString("smbPort")?.toInt() ?: SmbPath.DEFAULT_PORT
    private val user = args.getString("smbUser").orEmpty()
    private val password = args.getString("smbPassword").orEmpty()
    private val share = requireNotNull(args.getString("smbShare")) { "smbShare required" }
    private val sample = args.getString("smbSample")

    private val log = StringBuilder()

    suspend fun run(): String {
        val repository = SmbServerRepository(context, SecretCipher())
        val pool = SmbConnectionPool(repository)
        val smb = SmbFileOperations(pool)
        val ops = RoutingFileOperations(LocalFileOperations(context), smb)

        val server = repository.save(
            SmbServer(id = "", name = "selftest", host = host, port = port, username = user, share = share),
            password
        )
        val root = server.rootPath
        val remoteDir = "$root/OpenFX_selftest"
        val work = File(context.cacheDir, "smb_selftest").apply { deleteRecursively(); mkdirs() }

        try {
            // 1. 列出共享
            val listing = ops.listFiles(root).getOrThrow()
            step("list share: ${listing.size} entries (${listing.take(5).joinToString { it.name }})")
            check(listing.isNotEmpty()) { "share listing is empty" }

            // 2. 本機 → SMB (含子資料夾)
            val src = File(work, "upload").apply { mkdirs() }
            File(src, "a.bin").writeBytes(Random.nextBytes(1_500_000))
            File(src, "sub").mkdirs()
            File(src, "sub/b 中文 #2.txt").writeText("hello smb 測試\n")
            ops.createDirectory(root, "OpenFX_selftest").getOrThrow()
            ops.copy(listOf(src.absolutePath), remoteDir).collect()
            val uploaded = ops.listFiles("$remoteDir/upload").getOrThrow().map { it.name }.toSet()
            check(uploaded == setOf("a.bin", "sub")) { "unexpected upload listing: $uploaded" }
            val remoteA = smb.stat("$remoteDir/upload/a.bin") ?: error("a.bin missing")
            check(remoteA.size == 1_500_000L) { "a.bin size ${remoteA.size}" }
            step("local -> smb copy ok (a.bin ${remoteA.size} bytes, sub/)")

            // 3. SMB → 本機，內容要一模一樣
            val down = File(work, "download").apply { mkdirs() }
            ops.copy(listOf("$remoteDir/upload"), down.absolutePath).collect()
            check(sha(File(down, "upload/a.bin")) == sha(File(src, "a.bin"))) { "a.bin hash mismatch" }
            check(File(down, "upload/sub/b 中文 #2.txt").readText() == "hello smb 測試\n") { "b.txt mismatch" }
            step("smb -> local copy ok (sha256 match)")

            // 4. 同名複製要自動改名 _copy
            ops.copy(listOf(src.absolutePath + "/a.bin"), "$remoteDir/upload").collect()
            check(smb.stat("$remoteDir/upload/a_copy.bin") != null) { "a_copy.bin not created" }
            step("conflict rename ok (a_copy.bin)")

            // 5. 同共享內移動 (伺服器端改名)
            ops.move(listOf("$remoteDir/upload/a_copy.bin"), "$remoteDir/upload/sub").collect()
            check(smb.stat("$remoteDir/upload/a_copy.bin") == null) { "move left source behind" }
            check(smb.stat("$remoteDir/upload/sub/a_copy.bin")?.size == 1_500_000L) { "moved file missing" }
            step("same-share move ok")

            // 6. 改名
            ops.rename("$remoteDir/upload/sub/a_copy.bin", "renamed.bin").getOrThrow()
            check(smb.stat("$remoteDir/upload/sub/renamed.bin") != null) { "rename failed" }
            step("rename ok")

            // 7. 播放器 DataSource：從中間 offset 讀，和串流讀到的內容比對 (檔名含 # 與空白)
            val samplePath = sample?.let { "$root/$it" } ?: "$remoteDir/upload/a.bin"
            val expected = smb.openRead(samplePath).use { it.readBytes() }
            val offset = expected.size / 3L
            val length = 4096
            val source = SmbDataSource(pool)
            val opened = source.open(DataSpec.Builder().setUri(MediaUris.forPath(samplePath)).setPosition(offset).build())
            check(opened == expected.size - offset) { "DataSource length $opened" }
            val got = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val n = source.read(got, filled, length - filled)
                check(n > 0) { "DataSource ended early" }
                filled += n
            }
            source.close()
            check(got.contentEquals(expected.copyOfRange(offset.toInt(), offset.toInt() + length))) { "DataSource bytes mismatch" }
            step("DataSource range read ok (${samplePath.substringAfterLast('/')} @ $offset)")

            // 8. 搜尋
            val found = mutableListOf<String>()
            ops.search(remoteDir, "中文", true).collect { found += it.name }
            check(found.any { it.startsWith("b 中文") }) { "search did not find file: $found" }
            step("search ok")
        } finally {
            runCatching { ops.delete(listOf(remoteDir)).getOrThrow() }
                .onSuccess { step("cleanup: removed OpenFX_selftest") }
                .onFailure { step("cleanup failed: ${it.message}") }
            repository.delete(server.id)
            work.deleteRecursively()
        }
        return log.toString()
    }

    private fun step(message: String) {
        log.append("  ✓ ").append(message).append('\n')
    }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
