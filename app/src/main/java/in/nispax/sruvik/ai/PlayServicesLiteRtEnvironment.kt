package `in`.nispax.sruvik.ai

import android.content.Context
import com.google.ai.edge.litert.Environment
import com.google.android.gms.litert.LiteRt
import com.google.android.gms.litert.client.LiteRtInitializationOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** One process-wide LiteRT environment with the Play Services GPU delegate enabled. */
internal object PlayServicesLiteRtEnvironment {
    private val mutex = Mutex()
    @Volatile private var environment: Environment? = null

    suspend fun get(context: Context): Environment = mutex.withLock {
        environment ?: LiteRt.initialize(
            context.applicationContext,
            LiteRtInitializationOptions.builder().setEnableGpu(true).build()
        ).await().also { environment = it }
    }
}