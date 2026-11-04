package `in`.nispax.sruvik.srugen

import android.content.Context
import java.io.File

object SruGenEngineFactory {
    fun isInstalled(context: Context, modelId: String): Boolean {
        val model = SruGenGalleryCatalog.find(modelId) ?: return false
        return when (model.engine) {
            SruGenGalleryEngine.MEDIAPIPE_SD15 -> SruGenModelInstaller.isInstalled(context)
            SruGenGalleryEngine.NEZUMI_MNN_SD15 -> SruGenModelPackInstaller.installed(context, model)
            null -> false
        }
    }

    /** True only when the verified installed MNN pack contains a VAE encoder for img2img. */
    fun supportsImageEditing(context: Context, modelId: String): Boolean {
        val model = SruGenGalleryCatalog.find(modelId) ?: return false
        return model.engine == SruGenGalleryEngine.NEZUMI_MNN_SD15 &&
            isInstalled(context, modelId) &&
            SruGenModelPackInstaller.hasImg2ImgEncoder(context, modelId)
    }

    /**
     * Prefer the active model, then fall back to another installed gallery model
     * that has the VAE encoder required for genuine image-to-image inference.
     */
    fun installedImageEditingModel(
        context: Context,
        preferredModelId: String? = null
    ): SruGenGalleryModel? {
        val candidates = buildList {
            preferredModelId?.let { SruGenGalleryCatalog.find(it) }?.let(::add)
            addAll(SruGenGalleryCatalog.models)
        }.distinctBy { it.id }
        return candidates.firstOrNull { supportsImageEditing(context, it.id) }
    }

    fun installedBytes(context: Context, modelId: String): Long {
        val model = SruGenGalleryCatalog.find(modelId) ?: return 0L
        return when (model.engine) {
            SruGenGalleryEngine.MEDIAPIPE_SD15 -> {
                val dir = SruGenModelInstaller.modelDirectory(context)
                if (SruGenModelInstaller.isInstalled(context)) {
                    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else 0L
            }
            SruGenGalleryEngine.NEZUMI_MNN_SD15 ->
                if (SruGenModelPackInstaller.installed(context, model)) {
                    SruGenModelPackInstaller.installedBytes(context, modelId)
                } else 0L
            null -> 0L
        }
    }

    /**
     * Stops a mapped native runtime before removing its model files. Run on Dispatchers.IO.
     */
    fun deleteInstalled(context: Context, modelId: String): Long {
        val model = SruGenGalleryCatalog.find(modelId)
            ?: throw IllegalArgumentException("The selected model is not in Sruvik's trusted gallery.")
        require(model.canInstall && model.engine != null) {
            "This gallery entry has no installable model payload to remove."
        }
        require(isInstalled(context, modelId)) {
            "This model is not currently installed."
        }

        val wasActive = SruGenActiveModelStore.activeModelId(context) == modelId
        if (wasActive) {
            val engine = SruGenEngineRegistry.engine
            engine?.requestStop()
            SruGenEngineRegistry.closeAndDetach(engine)
        }

        val removedBytes = when (model.engine) {
            SruGenGalleryEngine.MEDIAPIPE_SD15 ->
                SruGenModelInstaller.deleteInstalled(context)
            SruGenGalleryEngine.NEZUMI_MNN_SD15 ->
                SruGenModelPackInstaller.deleteInstalled(context, model)
            null -> 0L
        }
        check(!isInstalled(context, modelId)) {
            "Sruvik removed the model payload but the installed-state check still sees model files."
        }
        // Uninstalling an inactive gallery entry must not change the user's selection.
        if (wasActive) {
            SruGenActiveModelStore.selectFallbackAfterRemoval(context, modelId)
        }
        return removedBytes
    }

    fun create(context: Context, modelId: String): SruGenEngine {
        val model = SruGenGalleryCatalog.find(modelId)
            ?: throw IllegalArgumentException("The selected SruGen model is not in the trusted gallery.")
        require(model.canInstall) {
            "This model is listed in the gallery but does not yet have a verified install package."
        }
        require(isInstalled(context, modelId)) {
            "Install and verify ${model.name} before selecting it for image generation."
        }
        return when (model.engine) {
            SruGenGalleryEngine.MEDIAPIPE_SD15 ->
                MediaPipeSruGenEngine(
                    context.applicationContext,
                    File(SruGenModelInstaller.modelDirectory(context), "sd15_bins").absolutePath
                )
            SruGenGalleryEngine.NEZUMI_MNN_SD15 ->
                NezumiMnnSruGenEngine(
                    context.applicationContext,
                    SruGenModelPackInstaller.modelWeightsDirectory(context, modelId),
                    model.name
                )
            null -> error("This model does not have an installable runtime.")
        }
    }
}
