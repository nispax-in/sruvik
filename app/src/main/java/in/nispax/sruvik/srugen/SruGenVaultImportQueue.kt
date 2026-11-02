package `in`.nispax.sruvik.srugen

/**
 * One-shot, in-process handoff from a SruGen result card to Srukrypt.
 * The vault still requires its own biometric/passphrase unlock before import.
 */
data class SruGenVaultImportRequest(
    val attachmentId: String,
    val mimeType: String,
    val title: String = "SruGen image"
)

object SruGenVaultImportQueue {
    @Volatile private var pending: SruGenVaultImportRequest? = null

    @Synchronized
    fun enqueue(request: SruGenVaultImportRequest) {
        pending = request
    }

    @Synchronized
    fun consume(): SruGenVaultImportRequest? {
        val request = pending
        pending = null
        return request
    }
}
