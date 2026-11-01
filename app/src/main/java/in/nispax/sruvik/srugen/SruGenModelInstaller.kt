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

data class SruGenDownloadStatus(
    val id: Long,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int = 0
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

object SruGenModelInstaller {
    const val MODEL_NAME = "Stable Diffusion 1.5 · MediaPipe"
    const val ARCHIVE_BYTES = 2_050_000_000L
    const val DOWNLOAD_URL = "https://huggingface.co/na5h13/stable-diffusion-v1-5-mediapipe/resolve/main/sd15-mediapipe.zip"
    private const val EXPECTED_SHA256 = "0e17f95821b6a247d01807b20920206052fc32483af2af42f03ff61954397758"
    private const val PREFS = "srugen_model_installer"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val MODEL_DIR_NAME = "sd15-mediapipe"
    private const val MAX_EXTRACTED_BYTES = 5_000_000_000L
    // The archive (about 2.05 GB) and extracted model (up to 5 GB) coexist
    // during staging. Keep extra headroom rather than filling the user's phone.
    private const val MIN_INSTALL_FREE_BYTES = 8_000_000_000L
    private const val MIN_REMAINING_FREE_BYTES = 750_000_000L
    private const val MAX_ARCHIVE_BYTES = 2_200_000_000L
    private const val MAX_ARCHIVE_ENTRIES = 50_000
    private const val BACKUP_DIR_NAME = "sd15-mediapipe-previous"

    fun modelDirectory(context: Context): File =
        File(File(context.filesDir, "srugen-models"), MODEL_DIR_NAME)

    fun isInstalled(context: Context): Boolean {
        recoverInterruptedInstall(context)
        return isValidModelDirectory(modelDirectory(context))
    }

    private fun isValidModelDirectory(dir: File): Boolean {
        val bins = File(dir, "sd15_bins")
        return bins.isDirectory &&
            bins.listFiles()?.any { it.isFile && it.extension.equals("bin", true) && it.length() > 0L } == true &&
            File(bins, "bpe_simple_vocab_16e6.txt").isFile &&
            File(bins, "bpe_simple_vocab_16e6.txt").length() > 0L
    }

    /**
     * A previous app/process interruption must never leave a valid model deleted.
     * If promotion was interrupted, restore the previous verified install.
     */
    @Synchronized
    private fun recoverInterruptedInstall(context: Context) {
        val root = modelDirectory(context)
        val backup = File(root.parentFile, BACKUP_DIR_NAME)
        if (isValidModelDirectory(root)) {
            if (backup.exists()) backup.deleteRecursively()
            return
        }
        if (isValidModelDirectory(backup)) {
            if (root.exists()) root.deleteRecursively()
            if (!backup.renameTo(root)) {
                throw IllegalStateException("SruGen could not restore the previous model. It has not deleted the recovery copy.")
            }
        }
    }

    private fun requireInstallSpace(context: Context) {
        val free = availableBytes(context)
        require(free >= MIN_INSTALL_FREE_BYTES) {
            val gb = String.format(java.util.Locale.US, "%.1f", free / 1_000_000_000.0)
            "SruGen needs at least 8 GB free internal storage to stage this model safely. Only $gb GB is available. No existing model was changed."
        }
    }

    fun availableBytes(context: Context): Long {
        val stats = StatFs(context.filesDir.absolutePath)
        return stats.availableBytes
    }

    fun startDownload(context: Context): Long {
        requireInstallSpace(context)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(DOWNLOAD_URL))
            .setTitle("SruGen · Stable Diffusion 1.5")
            .setDescription("Downloading the optional 1.91 GB on-device image model.")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "srugen-sd15-mediapipe.zip"
            )
        val id = manager.enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        return id
    }

    fun savedDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L)

    fun clearDownloadId(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DOWNLOAD_ID).apply()
    }

    fun queryDownload(context: Context, id: Long): SruGenDownloadStatus? {
        if (id < 0) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            fun col(name: String) = it.getColumnIndex(name)
            return SruGenDownloadStatus(
                id,
                it.getInt(col(DownloadManager.COLUMN_STATUS)),
                it.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                it.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                it.getInt(col(DownloadManager.COLUMN_REASON))
            )
        }
    }

    fun cancelDownload(context: Context, id: Long) {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        clearDownloadId(context)
    }

    fun importArchive(context: Context, input: InputStream, onProgress: (String) -> Unit = {}) {
        // The ZIP has already consumed storage when this is invoked from DownloadManager.
        // Check an initial reserve here, then enforce the 750 MB floor while copying/extracting.
        recoverInterruptedInstall(context)
        require(availableBytes(context) >= 1_500_000_000L) {
            "SruGen needs at least 1.5 GB remaining internal storage to stage this model. No existing model was changed."
        }
        val staging = File(context.filesDir, "srugen-model-staging-${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IllegalStateException("Unable to create model staging directory.")
        val archive = File(staging, "sd15-mediapipe.zip")
        try {
            onProgress("Copying model archive")
            FileOutputStream(archive).use { output ->
                input.use { source ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    try {
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_ARCHIVE_BYTES) {
                                "The model archive is larger than the approved download. It was rejected before extraction."
                            }
                            output.write(buffer, 0, count)
                            if (copied % (32L * 1024L * 1024L) < count) {
                                require(availableBytes(context) >= MIN_REMAINING_FREE_BYTES) {
                                    "SruGen stopped copying because the storage safety reserve was reached. Your existing model was not changed."
                                }
                            }
                        }
                        require(copied > 0L) { "The selected model archive is empty." }
                    } finally {
                        buffer.fill(0)
                    }
                }
                output.fd.sync()
            }
            onProgress("Verifying model archive")
            val digest = sha256(archive)
            if (!digest.equals(EXPECTED_SHA256, ignoreCase = true)) {
                throw SecurityException("The model archive checksum does not match the trusted model file.")
            }
            onProgress("Extracting model weights")
            val extractedRoot = File(staging, "extracted").apply {
                if (!mkdirs()) throw IllegalStateException("Unable to create extraction directory.")
            }
            require(availableBytes(context) >= MIN_REMAINING_FREE_BYTES) {
                "SruGen does not have enough remaining free storage to extract safely."
            }
            unzipSafely(archive, extractedRoot, context, onProgress)
            val bins = findBinsDirectory(extractedRoot)
                ?: throw IllegalArgumentException("This ZIP does not contain a valid sd15_bins model folder.")
            val modelRoot = modelDirectory(context)
            val parent = modelRoot.parentFile ?: throw IllegalStateException("Model directory path is invalid.")
            if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Unable to create model storage.")
            val next = File(parent, "sd15-mediapipe-next")
            val backup = File(parent, BACKUP_DIR_NAME)
            if (next.exists()) next.deleteRecursively()
            if (!next.mkdirs()) throw IllegalStateException("Unable to prepare the model install.")
            val installedBins = File(next, "sd15_bins")
            if (!bins.renameTo(installedBins)) {
                bins.copyRecursively(installedBins, overwrite = true)
                bins.deleteRecursively()
            }
            if (!isValidModelDirectory(next)) {
                throw IllegalArgumentException("The extracted model is missing required weights or tokenizer files.")
            }

            // Two-phase directory promotion: keep the previous model until the new
            // directory is in place and validated. Restore the old one on failure.
            if (backup.exists()) backup.deleteRecursively()
            val hadPrevious = modelRoot.exists()
            if (hadPrevious && !modelRoot.renameTo(backup)) {
                throw IllegalStateException("Unable to protect the previous model before installing the new one.")
            }
            try {
                if (!next.renameTo(modelRoot)) {
                    throw IllegalStateException("Unable to activate the new model directory.")
                }
                if (!isValidModelDirectory(modelRoot)) {
                    throw IllegalStateException("The new model failed its final integrity check.")
                }
            } catch (failure: Exception) {
                if (modelRoot.exists()) modelRoot.deleteRecursively()
                if (hadPrevious && backup.exists() && !backup.renameTo(modelRoot)) {
                    throw IllegalStateException("New model activation failed and the old model needs recovery from its backup.", failure)
                }
                throw failure
            }
            if (backup.exists()) backup.deleteRecursively()
            onProgress("Model ready")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            buffer.fill(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzipSafely(archive: File, destination: File, context: Context, onProgress: (String) -> Unit) {
        var expanded = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(archive), 1024 * 1024)).use { zip ->
            var entry = zip.nextEntry
            var fileCount = 0
            while (entry != null) {
                fileCount++
                require(fileCount <= MAX_ARCHIVE_ENTRIES) { "The model archive contains too many entries." }
                val safeName = entry.name.replace('\\', '/')
                require(!safeName.startsWith("/") && safeName.split('/').none { it == ".." }) {
                    "The model archive contains an unsafe path."
                }
                val target = File(destination, safeName).canonicalFile
                val root = destination.canonicalFile
                require(target.path.startsWith(root.path + File.separator) || target == root) {
                    "The model archive escapes its install directory."
                }
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) throw IllegalStateException("Unable to create model folder.")
                } else {
                    target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Unable to create model folder.") }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        try {
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= MAX_EXTRACTED_BYTES) { "The extracted model exceeds the supported size limit." }
                                if (expanded % (32L * 1024L * 1024L) < count) {
                                    require(availableBytes(context) >= MIN_REMAINING_FREE_BYTES) {
                                        "SruGen stopped extraction to preserve 750 MB of free storage. The previous model remains untouched."
                                    }
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        } finally {
                            buffer.fill(0)
                        }
                    }
                    if (fileCount % 20 == 0) onProgress("Extracting model entries · $fileCount")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun findBinsDirectory(root: File): File? {
        if (File(root, "sd15_bins").isDirectory) return File(root, "sd15_bins")
        return root.walkTopDown().firstOrNull { file ->
            file.isDirectory && File(file, "bpe_simple_vocab_16e6.txt").isFile &&
                file.listFiles()?.any { it.extension.equals("bin", true) } == true
        }
    }
}
