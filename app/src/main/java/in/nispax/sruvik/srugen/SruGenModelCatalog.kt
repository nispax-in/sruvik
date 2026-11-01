package `in`.nispax.sruvik.srugen

enum class SruGenModelAvailability {
    READY_IN_APP,
    NATIVE_RUNTIME_PENDING,
    MODEL_CONVERSION_PENDING,
    MODEL_PACK_VERIFICATION_PENDING,
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
            availability = SruGenModelAvailability.MODEL_PACK_VERIFICATION_PENDING,
            detail = "An MNN-converted DreamShaper 8 pack exists for Nezumi Kiln. Sruvik has integrated the arm64 JNI runtime, but the exact archive SHA-256, install manifest and real-device tests are still pending; download remains locked."
        ),
        SruGenModelInfo(
            id = "sd35-mnn",
            displayName = "Stable Diffusion 3.5",
            runtime = "MNN-Diffusion",
            availability = SruGenModelAvailability.NATIVE_RUNTIME_PENDING,
            detail = "MNN upstream documents SD 3.5. Sruvik still needs an Android native build, a verified model pack and device-specific memory/thermal benchmarks."
        ),
        SruGenModelInfo(
            id = "sana-mnn",
            displayName = "Sana",
            runtime = "MNN-Diffusion",
            availability = SruGenModelAvailability.NATIVE_RUNTIME_PENDING,
            detail = "MNN upstream documents Sana. It also needs a separate text-feature pipeline and a verified model pack; neither is shipped in Sruvik."
        ),
        SruGenModelInfo(
            id = "wan21-mnn",
            displayName = "Wan 2.1",
            runtime = "MNN-Diffusion",
            availability = SruGenModelAvailability.NATIVE_RUNTIME_PENDING,
            detail = "MNN upstream documents Wan video diffusion. It is heavier than the current image-only engine and remains disabled pending a separate memory-safe implementation."
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
