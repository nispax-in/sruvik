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
        current.requestStop()
        // Native close can wait for the current inference checkpoint. Run it off
        // the main thread; the registry lifecycle gate prevents a second model
        // from being allocated until this one has actually released its memory.
        Thread({
            runCatching { SruGenEngineRegistry.closeAndDetach(current) }
                .onFailure { android.util.Log.w("SruvikApplication", "Could not release native SruGen engine ($reason).", it) }
        }, "SruGen-engine-release").apply {
            isDaemon = true
            start()
        }
    }
}
