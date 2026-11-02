package `in`.nispax.sruvik.srugen

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Exports an explicitly selected SruGen attachment to Android's shared Pictures
 * collection. The encrypted SruGen chat copy is kept; this is a second, ordinary
 * gallery-visible copy that other apps can open.
 */
object SruGenImageGallery {
    @Throws(IOException::class, SecurityException::class)
    fun saveToPictures(context: Context, imageBytes: ByteArray, requestedMimeType: String): Uri {
        require(imageBytes.isNotEmpty()) { "The saved image is empty." }
        require(imageBytes.size <= 24 * 1024 * 1024) { "This image is too large to export." }

        val mimeType = requestedMimeType
            .takeIf { it.startsWith("image/") && !it.endsWith("/*") }
            ?: "image/png"
        val extension = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/png" -> "png"
            else -> throw IOException("Unsupported image type for Gallery export: $mimeType")
        }
        val fileName = "Sruvik-${System.currentTimeMillis()}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Sruvik"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Sruvik"
                )
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Unable to create Pictures/Sruvik.")
                }
                put(MediaStore.Images.Media.DATA, File(directory, fileName).absolutePath)
            }
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Android could not create a Gallery image.")
        try {
            resolver.openOutputStream(uri, "w")?.use { stream ->
                stream.write(imageBytes)
                stream.flush()
            } ?: throw IOException("Android could not open the new Gallery image for writing.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                check(resolver.update(uri, ready, null, null) == 1) {
                    "The image was written, but Android could not publish it to Gallery."
                }
            }
            return uri
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }
}
