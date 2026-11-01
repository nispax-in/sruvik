package `in`.nispax.sruvik.srugen

enum class SruGenMode { TEXT_TO_IMAGE, PROMPT_EDIT, REMOVE_BACKGROUND, REMOVE_OBJECT }

data class SruGenRequest(
    val mode: SruGenMode,
    val prompt: String,
    val style: String,
    val aspectRatio: String,
    val sourceImage: ByteArray?
)

data class SruGenResult(
    val imageBytes: ByteArray,
    val mimeType: String = "image/png",
    val modelName: String,
    val seed: Long? = null
)

/**
 * SruGen's inference boundary. Implementations may be fully local or a
 * user-configured private inference host. API keys must never be shipped in the APK.
 */
interface SruGenEngine {
    val displayName: String
    val isOffline: Boolean
    val isClosed: Boolean get() = false
    suspend fun generate(
        request: SruGenRequest,
        onProgress: (Int, String) -> Unit
    ): SruGenResult
}

/** Null until a real engine is installed/configured: no fake image results. */
object SruGenEngineRegistry {
    private val lock = Any()

    @Volatile
    private var current: SruGenEngine? = null

    var engine: SruGenEngine?
        get() = current
        set(value) {
            synchronized(lock) { current = value }
        }

    fun getOrCreate(factory: () -> SruGenEngine): SruGenEngine =
        synchronized(lock) {
            current?.takeIf { !it.isClosed } ?: factory().also { current = it }
        }

    /** Remove only the instance being disposed, so a stale closed engine is never reused. */
    fun detach(expected: SruGenEngine?): SruGenEngine? =
        synchronized(lock) {
            if (expected != null && current === expected) {
                current = null
                expected
            } else {
                null
            }
        }
}
