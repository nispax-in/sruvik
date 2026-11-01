package `in`.nispax.sruvik.srugen

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Pinned, allow-listed MNN SD1.5 pack installer.
 *
 * It never imports arbitrary checkpoints. Only gallery entries with a known engine,
 * immutable source revision, archive SHA-256, and expected model file contract are accepted.
 * Existing installed model files are retained until a replacement has passed validation.
 */
object SruGenModelPackInstaller {
    private const val PREFS = "srugen_model_pack_downloads"
    private const val MAX_ARCHIVE_ENTRIES = 2_000
    private const val MIN_REMAINING_BYTES = 750L * 1024L * 1024L
    private const val MAX_MODEL_PACK_BYTES = 2_000_000_000L
    private val requiredFiles = listOf(
        "model.json",
        "tokenizer.json",
        "clip_v2.mnn",
        "clip_v2.mnn.weight",
        "unet.mnn",
        "unet.mnn.weight",
        "vae_decoder_fp16.mnn",
        "vae_decoder_fp16.mnn.weight",
        "token_emb.bin",
        "pos_emb.bin"
    )
    private val allowedFiles = requiredFiles.toSet() + setOf(
        "README.md", "NOTICE", "LICENSE", "LICENSE.md", "LICENSE.txt",
        "MODEL_LICENSE.md", "model_card.md"
    )

    fun modelDirectory(context: Context, modelId: String): File =
        File(File(context.filesDir, "srugen-models"), modelId)

    fun installed(context: Context, model: SruGenGalleryModel): Boolean {
        if (model.engine != SruGenGalleryEngine.NEZUMI_MNN_SD15) return false
        recoverInterruptedInstall(context, model.id)
        return validatePack(File(modelDirectory(context, model.id), "pack"))
    }

    fun installedBytes(context: Context, modelId: String): Long =
        modelDirectory(context, modelId).takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun startDownload(context: Context, model: SruGenGalleryModel): Long {
        require(model.engine == SruGenGalleryEngine.NEZUMI_MNN_SD15 && model.canInstall) {
            "This model does not have a verified MNN installation package."
        }
        val compatibility = SruGenModelCompatibility.assess(context, model)
        require(compatibility.canDownload) { compatibility.reasons.joinToString("\n") }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(requireNotNull(model.downloadUrl)))
            .setTitle("Sruvik Models · ${model.name}")
            .setDescription("Downloading a checksum-verified on-device model pack.")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                requireNotNull(model.archiveFileName)
            )
        val id = manager.enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key(model.id), id).apply()
        return id
    }

    fun savedDownloadId(context: Context, modelId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key(modelId), -1L)

    fun clearDownloadId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(modelId)).apply()
    }

    fun queryDownload(context: Context, id: Long): SruGenDownloadStatus? {
        if (id < 0L) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return SruGenDownloadStatus(
                id = id,
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloadedBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L),
                totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0L),
                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            )
        }
    }

    fun downloadUri(context: Context, id: Long): Uri? {
        if (id < 0L) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.getUriForDownloadedFile(id)
    }

    fun cancelDownload(context: Context, modelId: String, id: Long) {
        if (id >= 0L) {
            runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
        }
        clearDownloadId(context, modelId)
    }

    fun removeDownload(context: Context, id: Long) {
        if (id >= 0L) runCatching {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        }
    }

    fun exceedsApprovedDownloadLimit(model: SruGenGalleryModel, status: SruGenDownloadStatus): Boolean {
        val maximum = model.archiveMaxBytes
        return maximum <= 0L ||
            status.downloadedBytes > maximum ||
            (status.totalBytes > 0L && status.totalBytes > maximum)
    }

    fun installArchive(
        context: Context,
        model: SruGenGalleryModel,
        input: InputStream,
        onProgress: (String) -> Unit = {}
    ) {
        require(model.engine == SruGenGalleryEngine.NEZUMI_MNN_SD15 && model.canInstall) {
            "This model does not have an enabled MNN installation package."
        }
        require(model.archiveSha256?.matches(Regex("[0-9a-fA-F]{64}")) == true) {
            "The model does not have a valid SHA-256 integrity pin."
        }
        val compatibility = SruGenModelCompatibility.assessForInstall(context, model)
        require(compatibility.canDownload) { compatibility.reasons.joinToString("\n") }
        recoverInterruptedInstall(context, model.id)

        val staging = File(context.filesDir, "srugen-pack-${model.id}-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Unable to create a private model staging folder." }
        val archive = File(staging, "download.zip")
        try {
            onProgress("Copying model archive")
            copyBounded(context, input, archive, model.archiveMaxBytes)
            require(archive.length() > 0L) { "The downloaded model archive is empty." }

            onProgress("Verifying SHA-256")
            val actualHash = sha256(archive)
            require(actualHash.equals(model.archiveSha256, ignoreCase = true)) {
                "The model archive checksum did not match Sruvik's pinned SHA-256. The package was rejected; no installed model was changed."
            }

            onProgress("Extracting verified model files")
            val extracted = File(staging, "extracted")
            check(extracted.mkdirs()) { "Unable to create the model extraction folder." }
            extractSafely(context, archive, extracted, model.maxExtractedBytes, onProgress)

            val packRoot = findPackRoot(extracted)
                ?: throw IllegalArgumentException("This archive does not contain the complete Nezumi MNN SD1.5 file set.")
            require(validatePack(packRoot)) {
                "The archive is missing required MNN weights or tokenizer/config files."
            }

            // Validate the model identity before making it visible to the engine.
            val modelJson = File(packRoot, "model.json").readText(Charsets.UTF_8)
            require(modelJson.contains("sd1.5", ignoreCase = true) ||
                modelJson.contains("sd15", ignoreCase = true)) {
                "This model manifest does not identify a supported SD1.5 pack."
            }

            val root = modelDirectory(context, model.id)
            val parent = root.parentFile ?: error("Invalid model storage path.")
            check(parent.exists() || parent.mkdirs()) { "Unable to create model library storage." }
            val next = File(parent, "${model.id}.next")
            val backup = File(parent, "${model.id}.previous")
            if (next.exists()) next.deleteRecursively()
            check(next.mkdirs()) { "Unable to prepare a new model folder." }

            val stagedModel = File(next, "pack")
            if (!packRoot.renameTo(stagedModel)) {
                packRoot.copyRecursively(stagedModel, overwrite = false)
            }
            require(validatePack(stagedModel)) { "The staged model failed final validation." }

            // Two-phase promotion. Do not remove the old model until the new one is live.
            if (backup.exists()) backup.deleteRecursively()
            val hadOldModel = root.exists()
            if (hadOldModel) {
                check(root.renameTo(backup)) {
                    "Sruvik could not protect the currently installed model. The new model was not activated."
                }
            }
            try {
                check(next.renameTo(root)) { "Sruvik could not activate the new model pack." }
                require(validatePack(File(root, "pack"))) { "The activated model did not pass its integrity checks." }
            } catch (failure: Exception) {
                if (root.exists()) root.deleteRecursively()
                if (hadOldModel && backup.exists() && !backup.renameTo(root)) {
                    throw IllegalStateException(
                        "Installation failed. The previous model is preserved in its recovery folder and can be restored.",
                        failure
                    )
                }
                throw failure
            }
            if (backup.exists()) backup.deleteRecursively()
            onProgress("Model installed and verified")
        } finally {
            staging.deleteRecursively()
        }
    }

    fun modelWeightsDirectory(context: Context, modelId: String): File =
        File(modelDirectory(context, modelId), "pack")

    private fun copyBounded(context: Context, input: InputStream, target: File, maxBytes: Long) {
        require(maxBytes > 0L) { "The approved archive size limit is missing." }
        FileOutputStream(target).use { output ->
            input.use { source ->
                val buffer = ByteArray(1024 * 1024)
                var copied = 0L
                try {
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= maxBytes) {
                            "The downloaded archive exceeded Sruvik's approved size limit and was rejected."
                        }
                        output.write(buffer, 0, count)
                        if (copied % (32L * 1024L * 1024L) < count) {
                            require(availableBytes(context) >= MIN_REMAINING_BYTES) {
                                "Sruvik stopped copying to preserve a storage recovery reserve. Your previous model remains untouched."
                            }
                        }
                    }
                    output.fd.sync()
                } finally {
                    buffer.fill(0)
                }
            }
        }
    }

    private fun extractSafely(
        context: Context,
        archive: File,
        destination: File,
        maxExtractedBytes: Long,
        onProgress: (String) -> Unit
    ) {
        require(maxExtractedBytes in 1L..MAX_MODEL_PACK_BYTES) {
            "The model's declared extraction limit is outside Sruvik's allowed range."
        }
        val seen = mutableSetOf<String>()
        var expanded = 0L
        var files = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive), 1024 * 1024)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                files++
                require(files <= MAX_ARCHIVE_ENTRIES) { "The archive contains too many entries." }
                val name = entry.name.replace('\\', '/').trimEnd('/')
                require(name.isNotBlank() && !name.startsWith("/") &&
                    !name.contains(':') && name.split('/').none { it == ".." || it.isBlank() }) {
                    "The archive contains an unsafe file path."
                }
                val target = File(destination, name).canonicalFile
                val canonicalRoot = destination.canonicalFile
                require(target.path.startsWith(canonicalRoot.path + File.separator) || target == canonicalRoot) {
                    "The archive attempted to write outside its private staging folder."
                }
                if (entry.isDirectory) {
                    check(target.exists() || target.mkdirs()) { "Unable to create a model subfolder." }
                } else {
                    val leaf = target.name
                    require(leaf in allowedFiles) {
                        "The model archive contains an unexpected file ($leaf). It was rejected."
                    }
                    require(seen.add(name.lowercase(Locale.ROOT))) { "The archive contains duplicate file paths." }
                    val parent = target.parentFile ?: error("Invalid archive entry path.")
                    check(parent.exists() || parent.mkdirs()) { "Unable to create a model subfolder." }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        try {
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= maxExtractedBytes) {
                                    "The extracted model exceeded its approved size limit."
                                }
                                if (expanded % (32L * 1024L * 1024L) < count) {
                                    require(availableBytes(context) >= MIN_REMAINING_BYTES) {
                                        "Extraction stopped to preserve 750 MB of free storage. Any existing model remains untouched."
                                    }
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        } finally {
                            buffer.fill(0)
                        }
                    }
                    if (files % 5 == 0) onProgress("Extracted $files verified archive entries")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun findPackRoot(root: File): File? {
        val candidates = sequenceOf(root) + root.walkTopDown().filter { it.isDirectory && it != root }
        return candidates.firstOrNull(::validatePack)
    }

    private fun validatePack(directory: File): Boolean {
        if (!directory.isDirectory) return false
        return requiredFiles.all { name ->
            val file = File(directory, name)
            file.isFile && file.length() > 0L
        }
    }

    private fun recoverInterruptedInstall(context: Context, modelId: String) {
        val root = modelDirectory(context, modelId)
        val backup = File(root.parentFile, "$modelId.previous")
        if (validatePack(File(root, "pack"))) {
            if (backup.exists()) backup.deleteRecursively()
            return
        }
        if (validatePack(File(backup, "pack"))) {
            if (root.exists()) root.deleteRecursively()
            check(backup.renameTo(root)) {
                "Sruvik could not restore the previous verified model. Its recovery folder has been preserved."
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            } finally {
                buffer.fill(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun availableBytes(context: Context): Long =
        StatFs(context.filesDir.absolutePath).availableBytes

    private fun key(modelId: String) = "download_${modelId}"
}
