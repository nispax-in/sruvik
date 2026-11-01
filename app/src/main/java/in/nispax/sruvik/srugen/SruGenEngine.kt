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

    /** Ask a running implementation to stop at its next safe checkpoint. */
    fun requestStop() {}

    suspend fun generate(
        request: SruGenRequest,
        onProgress: (Int, String) -> Unit
    ): SruGenResult
}

/** Null until a real engine is installed/configured: no fake image results. */
object SruGenEngineRegistry {
    private val lock = Any()
    // Serializes engine creation with native teardown. Without this gate, returning
    // to Studio immediately after a memory trim could allocate a second model while
    // the old native generator is still being closed.
    private val lifecycleLock = java.util.concurrent.locks.ReentrantLock()

    @Volatile
    private var current: SruGenEngine? = null

    var engine: SruGenEngine?
        get() = current
        set(value) {
            lifecycleLock.lock()
            try {
                synchronized(lock) { current = value }
            } finally {
                lifecycleLock.unlock()
            }
        }

    fun getOrCreate(factory: () -> SruGenEngine): SruGenEngine {
        lifecycleLock.lock()
        try {
            return synchronized(lock) {
                current?.takeIf { !it.isClosed } ?: factory().also { current = it }
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    /**
     * Stop new allocations, detach this exact instance, and wait for native close.
     * Call from an IO/worker thread, never Android's main thread.
     */
    fun closeAndDetach(expected: SruGenEngine?) {
        lifecycleLock.lock()
        try {
            val detached = synchronized(lock) {
                if (expected != null && current === expected) {
                    current = null
                    expected
                } else {
                    null
                }
            }
            if (detached is AutoCloseable) detached.close()
        } finally {
            lifecycleLock.unlock()
        }
    }
}
