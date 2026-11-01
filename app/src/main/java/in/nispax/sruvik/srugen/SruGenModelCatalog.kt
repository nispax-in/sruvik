package `in`.nispax.sruvik.srugen

enum class SruGenModelAvailability {
    READY_IN_APP,
    NATIVE_RUNTIME_PENDING,
    MODEL_CONVERSION_PENDING,
    DEVICE_VALIDATION_PENDING
}

data class SruGenModelInfo(
    val id: String,
    val displayName: String,
    val runtime: String,
    val availability: SruGenModelAvailability,
    val detail: String
)

/**
 * Allow-list of model/runtime combinations that SruGen may expose.
 * A checkpoint is not installable merely because its file extension looks familiar.
 */
object SruGenModelCatalog {
    val models: List<SruGenModelInfo> = listOf(
        SruGenModelInfo(
            id = "sd15-mediapipe",
            displayName = "Stable Diffusion 1.5",
            runtime = "MediaPipe",
            availability = SruGenModelAvailability.READY_IN_APP,
            detail = "Verified archive format and pinned SHA-256; device preflight required before inference."
        ),
        SruGenModelInfo(
            id = "dreamshaper-mnn",
            displayName = "DreamShaper",
            runtime = "MNN-Diffusion",
            availability = SruGenModelAvailability.MODEL_CONVERSION_PENDING,
            detail = "Not downloadable yet. It needs a reproducible MNN conversion, tokenizer/config validation, signed manifest and device benchmarks."
        ),
        SruGenModelInfo(
            id = "chilloutmix-mnn",
            displayName = "ChilloutMix",
            runtime = "MNN-Diffusion",
            availability = SruGenModelAvailability.NATIVE_RUNTIME_PENDING,
            detail = "MNN documents this model family, but Sruvik does not yet ship the native Android runtime or a verified model pack."
        ),
        SruGenModelInfo(
            id = "taiyi-mnn",
            displayName = "Taiyi Stable Diffusion",
            runtime = "MNN-Diffusion",
            availability = SruGenModelAvailability.NATIVE_RUNTIME_PENDING,
            detail = "MNN documents this model family, but Sruvik does not yet ship the native Android runtime or a verified model pack."
        )
    )

    val activeModel: SruGenModelInfo
        get() = models.first { it.availability == SruGenModelAvailability.READY_IN_APP }

    fun isInstallable(modelId: String): Boolean =
        models.any { it.id == modelId && it.availability == SruGenModelAvailability.READY_IN_APP }
}
