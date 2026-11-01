package `in`.nispax.sruvik

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import `in`.nispax.sruvik.srugen.SruGenEngineRegistry

/**
 * Release heavyweight native inference resources when Android hides the app
 * or signals low memory. Encrypted conversations and model files are untouched.
 */
class SruvikApplication : Application(), ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            releaseInferenceEngine("UI hidden / memory trim")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseInferenceEngine("system low-memory callback")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun releaseInferenceEngine(reason: String) {
        val current = SruGenEngineRegistry.engine ?: return
        val detached = SruGenEngineRegistry.detach(current) ?: return
        if (detached is AutoCloseable) {
            // Native close may wait for the current diffusion step's lock. Never
            // block Android's main-thread memory callback on that native teardown.
            Thread({
                runCatching { detached.close() }
                    .onFailure { android.util.Log.w("SruvikApplication", "Could not release native SruGen engine ($reason).", it) }
            }, "SruGen-engine-release").apply {
                isDaemon = true
                start()
            }
        }
    }
}
