package com.openfx.core.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** 保險箱設定檔 (不含機密：salt、迭代次數、被包裝的資料金鑰) */
@Serializable
data class VaultConfig(
    val version: Int = 1,
    val salt: String,
    val iterations: Int,
    /** 以密碼衍生金鑰包裝的資料金鑰 (hex) */
    val wrappedKey: String,
    /** 以生物辨識保護的 Keystore 金鑰包裝的資料金鑰 (hex)；未啟用時為 null */
    val biometricWrappedKey: String? = null
)

/** 保險箱內的檔案或資料夾 (名稱與結構都存在加密索引中) */
@Serializable
data class VaultEntry(
    val id: String,
    /** null = 最上層 */
    val parentId: String? = null,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    /** 原始檔案的修改時間 */
    val modified: Long = 0,
    /** 加入保險箱的時間 */
    val added: Long = 0
)

/** 保險箱未解鎖 */
class VaultLockedException : IOException("Vault is locked")

/**
 * 加密保險箱的檔案儲存 — 純 JVM (不依賴 Android)，以目錄為單位
 *
 * root/
 *   vault.json      設定 (salt、迭代次數、包裝後的資料金鑰)
 *   index.bin       加密的項目索引 (檔名、資料夾結構、大小…)
 *   blobs/<id>      各檔案的加密內容，檔名是隨機 id，不洩漏原始檔名
 *
 * 所有方法都是阻塞 I/O，請在背景執行緒呼叫；以 @Synchronized 保護索引一致性。
 */
class VaultStore(val root: File, private val iterations: () -> Int = { VaultCrypto.DEFAULT_ITERATIONS }) {

    /** 測試用：固定迭代次數 */
    constructor(root: File, iterations: Int) : this(root, { iterations })

    private val json = Json { ignoreUnknownKeys = true }
    private val entriesSerializer = ListSerializer(VaultEntry.serializer())

    private val configFile = File(root, "vault.json")
    private val indexFile = File(root, "index.bin")
    private val blobDir = File(root, "blobs")

    private var dek: ByteArray? = null
    private var entries: MutableList<VaultEntry> = mutableListOf()

    val isCreated: Boolean get() = configFile.isFile
    val isUnlocked: Boolean @Synchronized get() = dek != null

    // ===== 建立 / 解鎖 / 上鎖 =====

    @Synchronized
    fun create(password: CharArray) {
        check(!isCreated) { "Vault already exists" }
        root.mkdirs()
        blobDir.mkdirs()
        val key = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)
        writeConfig(password, key, biometricWrappedKey = null)
        dek = key
        entries = mutableListOf()
        saveIndex()
    }

    /** 密碼錯誤丟 [VaultWrongPasswordException] */
    @Synchronized
    fun unlock(password: CharArray) {
        val config = readConfig()
        val kek = VaultCrypto.pbkdf2(password, config.salt.hexToBytes(), config.iterations)
        try {
            unlockWithKey(VaultCrypto.unwrapKey(kek, config.wrappedKey.hexToBytes()))
        } finally {
            kek.fill(0)
        }
    }

    /** 用已解開的資料金鑰解鎖 (生物辨識流程用) */
    @Synchronized
    fun unlockWithKey(key: ByteArray) {
        val loaded = loadIndex(key)
        dek = key
        entries = loaded
    }

    @Synchronized
    fun lock() {
        dek?.fill(0)
        dek = null
        entries = mutableListOf()
    }

    @Synchronized
    fun changePassword(oldPassword: CharArray, newPassword: CharArray) {
        val config = readConfig()
        val oldKek = VaultCrypto.pbkdf2(oldPassword, config.salt.hexToBytes(), config.iterations)
        val key = try {
            VaultCrypto.unwrapKey(oldKek, config.wrappedKey.hexToBytes())
        } finally {
            oldKek.fill(0)
        }
        try {
            writeConfig(newPassword, key, config.biometricWrappedKey)
        } finally {
            key.fill(0) // 解出來的副本，與目前使用中的金鑰是不同物件
        }
    }

    /** 目前的資料金鑰副本 (啟用生物辨識時拿去以 Keystore 金鑰包裝) */
    @Synchronized
    fun exportKeyForBiometric(): ByteArray = requireKey().copyOf()

    @get:Synchronized
    val config: VaultConfig? get() = if (isCreated) readConfig() else null

    @Synchronized
    fun setBiometricWrappedKey(wrapped: ByteArray?) {
        val config = readConfig()
        writeAtomically(configFile, json.encodeToString(VaultConfig.serializer(),
            config.copy(biometricWrappedKey = wrapped?.toHex())).toByteArray())
    }

    // ===== 瀏覽 =====

    @Synchronized
    fun list(parentId: String?): List<VaultEntry> {
        requireKey()
        return entries.filter { it.parentId == parentId }
            .sortedWith(compareBy<VaultEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    @Synchronized
    fun get(id: String): VaultEntry? {
        requireKey()
        return entries.firstOrNull { it.id == id }
    }

    /** 由上到下的資料夾路徑 (麵包屑用)，不含最上層 */
    @Synchronized
    fun pathOf(folderId: String?): List<VaultEntry> {
        requireKey()
        val path = mutableListOf<VaultEntry>()
        var current = folderId?.let { id -> entries.firstOrNull { it.id == id } }
        while (current != null) {
            path += current
            current = current.parentId?.let { id -> entries.firstOrNull { it.id == id } }
        }
        return path.reversed()
    }

    // ===== 新增 =====

    @Synchronized
    fun createFolder(parentId: String?, name: String): VaultEntry {
        requireKey()
        requireFolder(parentId)
        val entry = VaultEntry(
            id = newId(),
            parentId = parentId,
            name = uniqueName(parentId, cleanName(name)),
            isDirectory = true,
            modified = System.currentTimeMillis(),
            added = System.currentTimeMillis()
        )
        entries += entry
        saveIndex()
        return entry
    }

    /** 加密 [input] 存入保險箱；同名時自動改名 */
    @Synchronized
    fun addFile(parentId: String?, name: String, modified: Long, input: InputStream): VaultEntry {
        val key = requireKey()
        requireFolder(parentId)
        val id = newId()
        blobDir.mkdirs()
        val temp = File(blobDir, "$id.tmp")
        val size = try {
            temp.outputStream().buffered().use { out -> VaultCrypto.encrypt(key, id.toByteArray(), input, out) }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        if (!temp.renameTo(File(blobDir, id))) {
            temp.delete()
            throw IOException("Cannot store encrypted file")
        }
        val entry = VaultEntry(
            id = id,
            parentId = parentId,
            name = uniqueName(parentId, cleanName(name)),
            isDirectory = false,
            size = size,
            modified = modified,
            added = System.currentTimeMillis()
        )
        entries += entry
        try {
            saveIndex()
        } catch (e: Exception) {
            entries.remove(entry)
            File(blobDir, id).delete()
            throw e
        }
        return entry
    }

    /** 把本機檔案或資料夾 (含子目錄) 加密存入；回傳最上層新增的項目 */
    fun addLocal(parentId: String?, source: File): VaultEntry {
        if (source.isDirectory) {
            val folder = createFolder(parentId, source.name)
            source.listFiles()?.sortedBy { it.name }?.forEach { child -> addLocal(folder.id, child) }
            return folder
        }
        return source.inputStream().use { addFile(parentId, source.name, source.lastModified(), it) }
    }

    // ===== 讀取 / 匯出 =====

    /** 解密中的串流；呼叫端負責關閉 */
    @Synchronized
    fun open(id: String): InputStream {
        val key = requireKey()
        val entry = entries.firstOrNull { it.id == id && !it.isDirectory } ?: throw IOException("No such file")
        return VaultCrypto.decryptingStream(key, entry.id.toByteArray(), File(blobDir, entry.id).inputStream().buffered())
    }

    /**
     * 解密匯出到 [destDir] (資料夾會連同內容一起匯出)；同名時加 _1、_2…
     * 回傳匯出的最上層檔案。
     */
    fun export(id: String, destDir: File): File {
        val entry = synchronized(this) { get(id) } ?: throw IOException("No such entry")
        destDir.mkdirs()
        val target = uniqueFile(destDir, entry.name)
        if (entry.isDirectory) {
            target.mkdirs()
            list(entry.id).forEach { child -> export(child.id, target) }
        } else {
            val partial = File(target.parentFile, target.name + ".part")
            try {
                open(entry.id).use { input -> partial.outputStream().use { input.copyTo(it, 256 * 1024) } }
                if (!partial.renameTo(target)) throw IOException("Cannot write ${target.name}")
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
            if (entry.modified > 0) target.setLastModified(entry.modified)
        }
        return target
    }

    // ===== 修改 =====

    @Synchronized
    fun rename(id: String, newName: String): VaultEntry {
        requireKey()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) throw IOException("No such entry")
        val current = entries[index]
        val renamed = current.copy(name = uniqueName(current.parentId, cleanName(newName), exceptId = id))
        entries[index] = renamed
        saveIndex()
        return renamed
    }

    @Synchronized
    fun move(ids: List<String>, newParentId: String?) {
        requireKey()
        requireFolder(newParentId)
        ids.forEach { id ->
            // 不能把資料夾搬進自己或自己的子資料夾
            if (newParentId != null && pathOf(newParentId).any { it.id == id }) {
                throw IOException("Cannot move a folder into itself")
            }
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0) return@forEach
            val entry = entries[index]
            entries[index] = entry.copy(
                parentId = newParentId,
                name = uniqueName(newParentId, entry.name, exceptId = id)
            )
        }
        saveIndex()
    }

    /** 刪除項目 (資料夾連同內容) 與其加密檔案 */
    @Synchronized
    fun delete(ids: List<String>) {
        requireKey()
        val doomed = mutableSetOf<String>()
        fun collect(id: String) {
            if (!doomed.add(id)) return
            entries.filter { it.parentId == id }.forEach { collect(it.id) }
        }
        ids.forEach(::collect)
        entries.removeAll { it.id in doomed }
        saveIndex()
        // 索引更新成功後才刪除密文；萬一中途失敗也只會留下孤兒檔，不會遺失仍在索引中的資料
        doomed.forEach { File(blobDir, it).delete() }
    }

    // ===== 備份 / 還原 =====

    /**
     * 把整個保險箱 (仍是加密狀態) 打包成 zip 寫到 [output]
     * 備份檔沒有密碼就打不開，可以放在隨身碟、NAS 或雲端。
     */
    @Synchronized
    fun backupTo(output: java.io.OutputStream) {
        check(isCreated) { "Vault not created" }
        java.util.zip.ZipOutputStream(output.buffered()).use { zip ->
            fun put(name: String, file: File) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            put(CONFIG_NAME, configFile)
            if (indexFile.isFile) put(INDEX_NAME, indexFile)
            blobDir.listFiles()?.filter { it.isFile && BLOB_NAME.matches(it.name) }?.forEach { put("blobs/${it.name}", it) }
        }
    }

    /** 保險箱佔用的空間 (加密後) */
    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ===== 內部 =====

    private fun requireKey(): ByteArray = dek ?: throw VaultLockedException()

    private fun requireFolder(id: String?) {
        if (id == null) return
        val folder = entries.firstOrNull { it.id == id } ?: throw IOException("No such folder")
        if (!folder.isDirectory) throw IOException("Not a folder")
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun cleanName(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim().ifEmpty { "untitled" }

    private fun uniqueName(parentId: String?, name: String, exceptId: String? = null): String {
        val siblings = entries.filter { it.parentId == parentId && it.id != exceptId }.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in siblings) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var counter = 1
        while (true) {
            val candidate = "${base}_$counter$ext"
            if (candidate.lowercase() !in siblings) return candidate
            counter++
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${base}_$counter$ext")
            counter++
        }
        return target
    }

    private fun writeConfig(password: CharArray, key: ByteArray, biometricWrappedKey: String?) {
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
        val rounds = iterations()
        val kek = VaultCrypto.pbkdf2(password, salt, rounds)
        val wrapped = try {
            VaultCrypto.wrapKey(kek, key)
        } finally {
            kek.fill(0)
        }
        val config = VaultConfig(
            salt = salt.toHex(),
            iterations = rounds,
            wrappedKey = wrapped.toHex(),
            biometricWrappedKey = biometricWrappedKey
        )
        writeAtomically(configFile, json.encodeToString(VaultConfig.serializer(), config).toByteArray())
    }

    private fun readConfig(): VaultConfig {
        if (!configFile.isFile) throw IOException("Vault not created")
        return json.decodeFromString(VaultConfig.serializer(), configFile.readText())
    }

    private fun loadIndex(key: ByteArray): MutableList<VaultEntry> {
        if (!indexFile.isFile) return mutableListOf()
        val bytes = VaultCrypto.decryptingStream(key, INDEX_ID, indexFile.inputStream().buffered()).use { it.readBytes() }
        return json.decodeFromString(entriesSerializer, String(bytes, Charsets.UTF_8)).toMutableList()
    }

    private fun saveIndex() {
        val key = requireKey()
        val plain = json.encodeToString(entriesSerializer, entries).toByteArray(Charsets.UTF_8)
        val sealed = ByteArrayOutputStream()
        VaultCrypto.encrypt(key, INDEX_ID, ByteArrayInputStream(plain), sealed)
        plain.fill(0)
        writeAtomically(indexFile, sealed.toByteArray())
    }

    /** 先寫暫存檔再改名，斷電也不會留下寫一半的索引/設定 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        root.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            // Windows / 部分檔案系統不允許覆寫式 rename
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Cannot write ${target.name}")
        }
    }

    companion object {
        private val INDEX_ID = "index".toByteArray()
        private const val CONFIG_NAME = "vault.json"
        private const val INDEX_NAME = "index.bin"
        private val BLOB_NAME = Regex("[0-9a-f]{32}")

        /**
         * 從 [backupTo] 產生的備份還原到 [root] (取代現有保險箱)
         *
         * 先解到暫存資料夾並檢查內容，確認是有效備份才替換；只接受預期的檔名，防止 zip-slip。
         * 還原後保險箱是上鎖狀態，需要用「備份當時的密碼」解鎖。
         */
        fun restore(input: InputStream, root: File) {
            val staging = File(root.parentFile, root.name + ".restore")
            staging.deleteRecursively()
            File(staging, "blobs").mkdirs()
            try {
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        val allowed = name == CONFIG_NAME || name == INDEX_NAME ||
                            (name.startsWith("blobs/") && BLOB_NAME.matches(name.removePrefix("blobs/")))
                        if (!allowed || entry.isDirectory) continue
                        File(staging, name).outputStream().use { zip.copyTo(it) }
                    }
                }
                val config = File(staging, CONFIG_NAME)
                if (!config.isFile) throw VaultCorruptedException("Not a vault backup")
                // 能解析設定檔才算有效
                Json { ignoreUnknownKeys = true }.decodeFromString(VaultConfig.serializer(), config.readText())

                val old = File(root.parentFile, root.name + ".old")
                old.deleteRecursively()
                if (root.exists() && !root.renameTo(old)) throw IOException("Cannot replace vault")
                if (!staging.renameTo(root)) {
                    old.renameTo(root)
                    throw IOException("Cannot restore vault")
                }
                old.deleteRecursively()
            } catch (e: Exception) {
                staging.deleteRecursively()
                throw if (e is IOException) e else VaultCorruptedException("Invalid vault backup", e)
            }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Invalid hex" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
