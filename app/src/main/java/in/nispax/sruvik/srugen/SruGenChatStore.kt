package `in`.nispax.sruvik.srugen

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SruGenMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attachmentId: String? = null,
    val attachmentMime: String? = null,
    val modelName: String? = null
)

data class SruGenChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<SruGenMessage> = emptyList()
)

/**
 * Encrypted, app-private SruGen chat store.
 *
 * Conversation records and every user attachment/result are AES-256-GCM encrypted.
 * The storage directory is under noBackupFilesDir, file names are random UUIDs,
 * and the Keystore wrapping key is non-exportable. This is local encryption at rest;
 * the Keystore key is not biometric-gated because chat autosave must work in background.
 */
object SruGenChatStore {
    private const val DIRECTORY = "srugen"
    private const val ATTACHMENTS = "attachments"
    private const val CHAT_FILE = "conversations.enc"
    private const val KEY_ALIAS = "in.nispax.sruvik.srugen.chats.v1"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_CHAT_BYTES = 8 * 1024 * 1024
    private const val MAX_ATTACHMENT_BYTES = 24 * 1024 * 1024
    private const val MAGIC_CHAT = 0x53524743 // SRGC
    private const val MAGIC_ATTACHMENT = 0x53524741 // SRGA

    private fun root(context: Context): File =
        File(context.noBackupFilesDir, DIRECTORY).apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("Unable to create private SruGen storage.")
        }

    private fun attachmentDir(context: Context): File =
        File(root(context), ATTACHMENTS).apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("Unable to create encrypted attachment storage.")
        }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = KEY_ALIAS
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as? SecretKey
                ?: throw SecurityException("SruGen history encryption key is unavailable.")
        }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun loadChats(context: Context): List<SruGenChat> {
        val file = File(root(context), CHAT_FILE)
        if (!file.isFile) return emptyList()
        val plain = decryptFile(file, MAGIC_CHAT, "SruGen:conversations:v1")
        return try {
            val array = JSONArray(String(plain, Charsets.UTF_8))
            val loaded = buildList {
                for (i in 0 until array.length()) add(chatFromJson(array.getJSONObject(i)))
            }
            // Older builds wrote a blank "New chat" as soon as SruGen opened.
            // Drop those drafts during migration; only user-authored content is history.
            val meaningful = loaded.filter(::hasUserContent)
            if (meaningful.size != loaded.size) saveChats(context, meaningful)
            meaningful.sortedByDescending { it.updatedAt }
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Upsert one conversation without writing a stale snapshot of the entire list.
     * This lets a background generation append its result while the UI navigates.
     */
    @Synchronized
    fun upsertChat(context: Context, chat: SruGenChat) {
        val latest = loadChats(context).filterNot { it.id == chat.id }
        saveChats(context, (listOf(chat) + latest).sortedByDescending { it.updatedAt })
    }

    @Synchronized
    fun appendMessage(context: Context, chatId: String, message: SruGenMessage) {
        val latest = loadChats(context)
        val chat = latest.firstOrNull { it.id == chatId }
            ?: throw IllegalStateException("The encrypted conversation for this generation no longer exists.")
        upsertChat(
            context,
            chat.copy(
                updatedAt = System.currentTimeMillis(),
                messages = chat.messages + message
            )
        )
    }

    @Synchronized
    fun saveChats(context: Context, chats: List<SruGenChat>) {
        val json = JSONArray()
        chats.asSequence().filter(::hasUserContent).take(100).forEach { chat -> json.put(chatToJson(chat)) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CHAT_BYTES) { "SruGen chat history reached its local storage limit." }
        try {
            encryptFile(
                File(root(context), CHAT_FILE),
                MAGIC_CHAT,
                "SruGen:conversations:v1",
                bytes
            )
        } finally {
            bytes.fill(0)
        }
    }

    fun saveAttachment(context: Context, bytes: ByteArray, mimeType: String): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ATTACHMENT_BYTES) {
            "Choose an image smaller than 24 MB."
        }
        require(mimeType.startsWith("image/")) { "SruGen chat attachments must be images." }
        val id = UUID.randomUUID().toString()
        encryptFile(
            File(attachmentDir(context), "$id.srga"),
            MAGIC_ATTACHMENT,
            "SruGen:attachment:v1:$id",
            bytes
        )
        return id
    }

    fun readAttachment(context: Context, id: String): ByteArray {
        require(isId(id)) { "Invalid SruGen attachment reference." }
        val file = File(attachmentDir(context), "$id.srga")
        if (!file.isFile) throw java.io.FileNotFoundException("The encrypted SruGen attachment is missing.")
        return decryptFile(file, MAGIC_ATTACHMENT, "SruGen:attachment:v1:$id")
    }

    fun deleteAttachment(context: Context, id: String) {
        if (isId(id)) File(attachmentDir(context), "$id.srga").delete()
    }

    fun deleteChat(context: Context, chat: SruGenChat) {
        chat.messages.mapNotNull { it.attachmentId }.distinct().forEach { deleteAttachment(context, it) }
        saveChats(context, loadChats(context).filterNot { it.id == chat.id })
    }

    fun memoryContext(chats: List<SruGenChat>, currentChatId: String, maxItems: Int = 5): String {
        val messages = chats.asSequence()
            .filter { it.id != currentChatId }
            .flatMap { it.messages.asSequence() }
            .filter { it.role == "user" && it.text.isNotBlank() }
            .sortedByDescending { it.createdAt }
            .take(maxItems)
            .toList()
            .sortedBy { it.createdAt }
        if (messages.isEmpty()) return ""
        return messages.joinToString("\n") { "- " + it.text.take(180) }
    }

    private fun encryptFile(file: File, magic: Int, aadText: String, plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aadText.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv ?: throw SecurityException("SruGen storage nonce unavailable.")
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            val stream = FileOutputStream(temp)
            val output = java.io.DataOutputStream(java.io.BufferedOutputStream(stream))
            output.writeInt(magic)
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
            stream.fd.sync()
            output.close()
            if (!temp.renameTo(file)) throw java.io.IOException("Unable to commit encrypted SruGen data.")
        } finally {
            temp.delete()
            encrypted.fill(0)
        }
    }

    private fun decryptFile(file: File, magic: Int, aadText: String): ByteArray {
        java.io.DataInputStream(java.io.BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != magic) throw SecurityException("Unsupported encrypted SruGen record.")
            val ivSize = input.readInt()
            if (ivSize != NONCE_BYTES) throw SecurityException("Invalid SruGen nonce.")
            val iv = ByteArray(ivSize)
            input.readFully(iv)
            val encryptedSize = input.readInt()
            val limit = if (magic == MAGIC_CHAT) MAX_CHAT_BYTES + 16 else MAX_ATTACHMENT_BYTES + 16
            if (encryptedSize < 16 || encryptedSize > limit || encryptedSize > input.available()) {
                throw SecurityException("Invalid encrypted SruGen payload.")
            }
            val encrypted = ByteArray(encryptedSize)
            input.readFully(encrypted)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(aadText.toByteArray(Charsets.UTF_8))
                return cipher.doFinal(encrypted)
            } finally {
                encrypted.fill(0)
                iv.fill(0)
            }
        }
    }

    private fun chatToJson(chat: SruGenChat) = JSONObject().apply {
        put("id", chat.id)
        put("title", chat.title)
        put("updatedAt", chat.updatedAt)
        val messages = JSONArray()
        chat.messages.forEach { message ->
            messages.put(JSONObject().apply {
                put("id", message.id)
                put("role", message.role)
                put("text", message.text)
                put("createdAt", message.createdAt)
                put("attachmentId", message.attachmentId ?: JSONObject.NULL)
                put("attachmentMime", message.attachmentMime ?: JSONObject.NULL)
                put("modelName", message.modelName ?: JSONObject.NULL)
            })
        }
        put("messages", messages)
    }

    private fun chatFromJson(json: JSONObject): SruGenChat {
        val messages = json.optJSONArray("messages") ?: JSONArray()
        val list = buildList {
            for (i in 0 until messages.length()) {
                val item = messages.getJSONObject(i)
                add(
                    SruGenMessage(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        role = item.optString("role", "user"),
                        text = item.optString("text", ""),
                        createdAt = item.optLong("createdAt", 0L),
                        attachmentId = item.optString("attachmentId").takeUnless { it.isBlank() || it == "null" },
                        attachmentMime = item.optString("attachmentMime").takeUnless { it.isBlank() || it == "null" },
                        modelName = item.optString("modelName").takeUnless { it.isBlank() || it == "null" }
                    )
                )
            }
        }
        return SruGenChat(
            id = json.optString("id", UUID.randomUUID().toString()),
            title = json.optString("title", "New chat").take(64),
            updatedAt = json.optLong("updatedAt", 0L),
            messages = list
        )
    }


    private fun hasUserContent(chat: SruGenChat): Boolean =
        chat.messages.any { message ->
            message.role == "user" && (message.text.isNotBlank() || message.attachmentId != null)
        }

    private fun isId(value: String) = value.matches(Regex("[0-9a-fA-F-]{36}"))
}
