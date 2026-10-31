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

    fun modelDirectory(context: Context): File =
        File(File(context.filesDir, "srugen-models"), MODEL_DIR_NAME)

    fun isInstalled(context: Context): Boolean {
        val dir = modelDirectory(context)
        val bins = File(dir, "sd15_bins")
        return bins.isDirectory &&
            bins.listFiles()?.any { it.isFile && it.extension.equals("bin", true) } == true &&
            File(bins, "bpe_simple_vocab_16e6.txt").isFile
    }

    fun availableBytes(context: Context): Long {
        val stats = StatFs(context.filesDir.absolutePath)
        return stats.availableBytes
    }

    fun startDownload(context: Context): Long {
        require(availableBytes(context) >= 5_000_000_000L) {
            "SruGen needs at least 5 GB of free internal storage for the model download and extraction."
        }
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
        require(availableBytes(context) >= 5_000_000_000L) {
            "SruGen needs at least 5 GB of free internal storage to install this model."
        }
        val staging = File(context.filesDir, "srugen-model-staging-${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IllegalStateException("Unable to create model staging directory.")
        val archive = File(staging, "sd15-mediapipe.zip")
        try {
            onProgress("Copying model archive")
            FileOutputStream(archive).use { output ->
                input.use { source -> source.copyTo(output, 1024 * 1024) }
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
            unzipSafely(archive, extractedRoot, onProgress)
            val bins = findBinsDirectory(extractedRoot)
                ?: throw IllegalArgumentException("This ZIP does not contain a valid sd15_bins model folder.")
            val modelRoot = modelDirectory(context)
            val parent = modelRoot.parentFile ?: throw IllegalStateException("Model directory path is invalid.")
            if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("Unable to create model storage.")
            val next = File(parent, "sd15-mediapipe-next")
            if (next.exists()) next.deleteRecursively()
            if (!next.mkdirs()) throw IllegalStateException("Unable to prepare the model install.")
            bins.copyRecursively(File(next, "sd15_bins"), overwrite = true)
            if (!File(File(next, "sd15_bins"), "bpe_simple_vocab_16e6.txt").isFile) {
                throw IllegalArgumentException("The extracted model is missing its tokenizer vocabulary.")
            }
            if (modelRoot.exists()) modelRoot.deleteRecursively()
            if (!next.renameTo(modelRoot)) {
                throw IllegalStateException("Unable to finish installing the local model.")
            }
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

    private fun unzipSafely(archive: File, destination: File, onProgress: (String) -> Unit) {
        var expanded = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(archive), 1024 * 1024)).use { zip ->
            var entry = zip.nextEntry
            var fileCount = 0
            while (entry != null) {
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
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            require(expanded <= MAX_EXTRACTED_BYTES) { "The extracted model exceeds the supported size limit." }
                            output.write(buffer, 0, count)
                        }
                        buffer.fill(0)
                        output.fd.sync()
                    }
                    fileCount++
                    if (fileCount % 20 == 0) onProgress("Extracting model files · $fileCount")
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
