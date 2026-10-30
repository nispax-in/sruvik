package `in`.nispax.sruvik.ai

import android.content.Context
import com.google.ai.edge.litert.Environment
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.litert.client.LiteRtInitializationOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** One process-wide LiteRT environment; CPU-only is deliberate for this first safe pass. */
internal object PlayServicesLiteRtEnvironment {
    private val mutex = Mutex()
    @Volatile private var environment: Environment? = null

    suspend fun get(context: Context): Environment = mutex.withLock {
        environment ?: LiteRt.initialize(
            context.applicationContext,
            LiteRtInitializationOptions.builder().setEnableGpu(false).build()
        ).await().also { environment = it }
    }
}