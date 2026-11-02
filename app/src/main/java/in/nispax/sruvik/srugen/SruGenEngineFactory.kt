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
