package `in`.nispax.sruvik.security

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Storage boundary for the future Srukrypt encrypted vault.
 *
 * Keep vault ciphertext, encrypted metadata and any encrypted previews under
 * [Context.noBackupFilesDir]. Never place vault content in MediaStore, shared
 * external storage, regular cache directories or Android backup domains.
 *
 * Android removes app-private files and the app's Keystore namespace when the
 * package is uninstalled. Srukrypt is intentionally not recoverable after
 * uninstall; users must explicitly export anything they want to keep first.
 *
 * This class establishes the storage location only. It does not claim to
 * implement vault encryption or key management yet.
 */
object SrukryptStorage {
    private const val DIRECTORY_NAME = "srukrypt"

    @Throws(IOException::class)
    fun vaultDirectory(context: Context): File {
        val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create the private Srukrypt vault directory.")
        }
        if (!directory.isDirectory) {
            throw IOException("The Srukrypt vault path is not a directory.")
        }
        return directory
    }

    fun keyAlias(vaultId: String): String {
        require(vaultId.isNotBlank()) { "Vault ID must not be blank." }
        require(vaultId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "Vault ID contains unsupported characters."
        }
        return "in.nispax.sruvik.srukrypt.$vaultId"
    }
}
