package `in`.nispax.sruvik.srugen

import android.content.Context

enum class SruGenGalleryAvailability {
    INSTALLABLE,
    PACK_VERIFICATION_PENDING,
    CONVERSION_PENDING,
    RUNTIME_PENDING
}

enum class SruGenGalleryEngine {
    MEDIAPIPE_SD15,
    NEZUMI_MNN_SD15
}

data class SruGenGalleryModel(
    val id: String,
    val name: String,
    val subtitle: String,
    val runtime: String,
    val availability: SruGenGalleryAvailability,
    val description: String,
    val lore: String,
    val styleTags: List<String>,
    val baseModel: String,
    val engine: SruGenGalleryEngine?,
    val downloadUrl: String?,
    val archiveFileName: String?,
    val archiveSha256: String?,
    val approximateDownloadBytes: Long,
    val archiveMaxBytes: Long,
    val minAndroidApi: Int,
    val recommendedRamGb: Int,
    val sourceUrl: String,
    val licenseName: String,
    val licenseUrl: String,
    val attribution: String,
    val licenseNote: String,
    val maxExtractedBytes: Long = 2_000_000_000L,
    val requiredAbis: Set<String> = setOf("arm64-v8a"),
    val pinnedRevision: String? = null,
    val features: List<String> = emptyList()
) {
    val canInstall: Boolean
        get() = availability == SruGenGalleryAvailability.INSTALLABLE &&
            engine != null &&
            !downloadUrl.isNullOrBlank() &&
            !archiveFileName.isNullOrBlank() &&
            (engine == SruGenGalleryEngine.MEDIAPIPE_SD15 || !archiveSha256.isNullOrBlank())
}

object SruGenGalleryCatalog {
    private const val GIB = 1024L * 1024L * 1024L

    /** These are Sruvik's conservative release gates, not the model authors' minimum specs. */
    const val MIN_TOTAL_RAM_BYTES = 8L * GIB
    const val MIN_AVAILABLE_RAM_BYTES = 4L * GIB
    const val MIN_DOWNLOAD_STORAGE_BYTES = 5L * GIB

    val models = listOf(
        SruGenGalleryModel(
            id = "sd15-mediapipe",
            name = "Stable Diffusion 1.5",
            subtitle = "The versatile starting point",
            runtime = "MediaPipe",
            availability = SruGenGalleryAvailability.INSTALLABLE,
            description = "SruGen's original general-purpose image model. A flexible starting point for concept art, illustration and visual experiments.",
            lore = "A well-travelled sketchbook from the first age of diffusion. Open any page and a different world is waiting.",
            styleTags = listOf("General purpose", "Illustration", "Concept art"),
            baseModel = "Stable Diffusion 1.5",
            engine = SruGenGalleryEngine.MEDIAPIPE_SD15,
            downloadUrl = "https://huggingface.co/na5h13/stable-diffusion-v1-5-mediapipe/resolve/main/sd15-mediapipe.zip",
            archiveFileName = "srugen-sd15-mediapipe.zip",
            archiveSha256 = null, // Verified internally by the legacy SruGenModelInstaller.
            approximateDownloadBytes = 2_050_000_000L,
            archiveMaxBytes = 2_200_000_000L,
            minAndroidApi = 26,
            maxExtractedBytes = 5_000_000_000L,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/na5h13/stable-diffusion-v1-5-mediapipe",
            licenseName = "CreativeML Open RAIL-M",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "Stable Diffusion contributors",
            licenseNote = "Review the source model card and CreativeML Open RAIL-M use restrictions before downloading.",
            requiredAbis = emptySet()
        ),
        SruGenGalleryModel(
            id = "cyberrealistic-mnn",
            name = "CyberRealistic",
            subtitle = "Natural portraits and lifelike scenes",
            runtime = "Nezumi Kiln · MNN",
            availability = SruGenGalleryAvailability.INSTALLABLE,
            description = "A photorealistic SD1.5 conversion tuned for portraits and lifelike scenes without requiring an overly complicated prompt.",
            lore = "A quiet observatory above a sleeping city. Its lens catches small human details, soft light and the stories between them.",
            styleTags = listOf("Photorealism", "Portraits", "Natural light"),
            baseModel = "cyberdelia/CyberRealistic",
            engine = SruGenGalleryEngine.NEZUMI_MNN_SD15,
            downloadUrl = "https://huggingface.co/Mouserat/CyberRealistic-mnn/resolve/ce5c474/CyberRealistic-mnn-int8-block32.zip",
            archiveFileName = "CyberRealistic-mnn-int8-block32.zip",
            archiveSha256 = "3fcd7c1bbc2a452a3f52fcae8edd54f3c212836cb8f1189ce422f8e9899bfa5a",
            approximateDownloadBytes = 1_180_000_000L,
            archiveMaxBytes = 1_350_000_000L,
            minAndroidApi = 31,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/Mouserat/CyberRealistic-mnn",
            licenseName = "CreativeML Open RAIL-M (upstream wording caveat)",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "Cyberdelia · cyberdelia/CyberRealistic",
            licenseNote = "The upstream card notes inconsistent RAIL-M / RAIL++-M wording. Read the original card and full licence; Sruvik is not providing legal advice.",
            requiredAbis = setOf("arm64-v8a")
        ),
        SruGenGalleryModel(
            id = "rev-animated-mnn",
            name = "ReV Animated",
            subtitle = "Anime, fantasy and semi-realistic art",
            runtime = "Nezumi Kiln · MNN",
            availability = SruGenGalleryAvailability.INSTALLABLE,
            description = "A versatile SD1.5 checkpoint merge, converted to MNN int8 block-32 for offline mobile generation.",
            lore = "An atlas drawn by a wandering dream-cartographer: one page for moonlit cities, one for impossible forests, one for heroes not yet born.",
            styleTags = listOf("Anime", "Fantasy", "Semi-realistic"),
            baseModel = "s6yx/ReV_Animated v1.2.2",
            engine = SruGenGalleryEngine.NEZUMI_MNN_SD15,
            downloadUrl = "https://huggingface.co/Mouserat/ReV_Animated-mnn/resolve/3efaa2a/ReV_Animated-mnn-int8-block32.zip",
            archiveFileName = "ReV_Animated-mnn-int8-block32.zip",
            archiveSha256 = "02f191b1f5378e29ecf1352a855e7f43122113c1613f6b4b6cb4ccd4cc28de7c",
            approximateDownloadBytes = 1_180_000_000L,
            archiveMaxBytes = 1_350_000_000L,
            minAndroidApi = 31,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/Mouserat/ReV_Animated-mnn",
            licenseName = "CreativeML Open RAIL-M",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "s6yx · ReV_Animated",
            licenseNote = "The converted checkpoint inherits the original merge's CreativeML Open RAIL-M use restrictions. Read the source card before use.",
            requiredAbis = setOf("arm64-v8a")
        ),
        SruGenGalleryModel(
            id = "anything-v5-mnn",
            name = "Anything V5",
            subtitle = "Anime-style illustration and character art",
            runtime = "Nezumi Kiln · MNN",
            availability = SruGenGalleryAvailability.INSTALLABLE,
            description = "An SD1.5 anime-oriented model converted to the Nezumi MNN pack layout. It is suited to stylised character art and detailed illustration.",
            lore = "A travelling manga studio with an endless stack of storyboards: every page begins with a character, a mood and a world waiting to be drawn.",
            styleTags = listOf("Anime", "Character art", "Illustration"),
            baseModel = "stablediffusionapi/anything-v5",
            engine = SruGenGalleryEngine.NEZUMI_MNN_SD15,
            downloadUrl = "https://huggingface.co/Mouserat/Anything-V5-mnn/resolve/91c6996/Anything-V5-mnn-int8-block32.zip",
            archiveFileName = "Anything-V5-mnn-int8-block32.zip",
            archiveSha256 = "99e70f3fb7a33276adec354bf466bfa8b4aa8e6f4131c86a9c17cc1c1e0a1042",
            approximateDownloadBytes = 1_180_000_000L,
            archiveMaxBytes = 1_350_000_000L,
            pinnedRevision = "91c6996",
            minAndroidApi = 31,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/Mouserat/Anything-V5-mnn",
            licenseName = "CreativeML Open RAIL-M (declared upstream)",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "Anything V5 contributors · ModelsLab / stablediffusionapi.com conversion",
            licenseNote = "The MNN pack publisher says the source weights were taken from a third-party re-packaging, not a confirmed first-party upload from the original Anything V5 creator. Verify provenance and licence independently before commercial use. This is an unofficial conversion, not endorsed by the original creator.",
            features = listOf("Text to image", "Anime illustration", "MNN int8 block-32", "Offline CPU inference")
        ),
        SruGenGalleryModel(
            id = "majicmix-realistic-v6-mnn",
            name = "majicMIX Realistic v6",
            subtitle = "Photorealistic scenes with detailed light",
            runtime = "Nezumi Kiln · MNN",
            availability = SruGenGalleryAvailability.INSTALLABLE,
            description = "A photorealistic SD1.5 checkpoint conversion with strong lighting and shadow detail. The archive includes an optional VAE encoder, but Sruvik currently uses its text-to-image path only.",
            lore = "A midnight film laboratory where every scene is developed slowly: soft highlights, deep shadows and details that feel almost tangible.",
            styleTags = listOf("Photorealism", "Cinematic light", "Portraits"),
            baseModel = "emilianJR/majicMIX_realistic_v6",
            engine = SruGenGalleryEngine.NEZUMI_MNN_SD15,
            downloadUrl = "https://huggingface.co/Mouserat/majicMIX_realistic_v6-mnn/resolve/265cbbf/majicMIX_realistic_v6-int8-block32.zip",
            archiveFileName = "majicMIX_realistic_v6-int8-block32.zip",
            archiveSha256 = "caeceb902b6c01f09c8685d87f712b2107c4e5e6241011efe77c8e11e80990ff",
            approximateDownloadBytes = 1_240_000_000L,
            archiveMaxBytes = 1_420_000_000L,
            pinnedRevision = "265cbbf",
            minAndroidApi = 31,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/Mouserat/majicMIX_realistic_v6-mnn",
            licenseName = "CreativeML Open RAIL-M",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "majicMIX Realistic author · emilianJR diffusers packaging",
            licenseNote = "The source card says this v6 conversion is under CreativeML Open RAIL-M and asks users to verify the upstream licence if commercial use matters. Earlier v3/v4 merges had additional restrictions; this listing is v6 only. Sruvik does not provide legal advice.",
            features = listOf("Text to image", "Photorealistic style", "MNN int8 block-32", "Offline CPU inference")
        ),
        SruGenGalleryModel(
            id = "cuteyukimix-mnn",
            name = "CuteYukiMix",
            subtitle = "Anime-inspired illustration",
            runtime = "Nezumi Kiln · MNN",
            availability = SruGenGalleryAvailability.PACK_VERIFICATION_PENDING,
            description = "An experimental SD1.5 anime-style merge converted to MNN. The public pack's internal layout still needs matching to the exact Sruvik loader contract.",
            lore = "A pocket-sized winter kingdom where every streetlight glows like a tiny star and every cat knows the way home.",
            styleTags = listOf("Anime", "Illustration", "Pastel"),
            baseModel = "eienmojiki/CuteYukiMix-kemiaomiao",
            engine = SruGenGalleryEngine.NEZUMI_MNN_SD15,
            downloadUrl = null,
            archiveFileName = "CuteYukiMix-int8-block32.zip",
            archiveSha256 = "84c16e63f78dc8e52ea969d34b757f52d22d4869b1bb6ff6f4686c1388e010c2",
            approximateDownloadBytes = 1_180_000_000L,
            archiveMaxBytes = 1_350_000_000L,
            minAndroidApi = 31,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/Mouserat/CuteYukiMix-mnn",
            licenseName = "CreativeML Open RAIL-M",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "kemiaomiao · eienmojiki",
            licenseNote = "Sruvik has not enabled this pack until its manifest and internal file layout are verified."
        ),
        SruGenGalleryModel(
            id = "realistic-vision-v6",
            name = "Realistic Vision V6",
            subtitle = "Natural portraits and cinematic realism",
            runtime = "MNN · legacy pack format",
            availability = SruGenGalleryAvailability.PACK_VERIFICATION_PENDING,
            description = "A widely used photorealistic SD1.5 checkpoint. The available conversion uses the older clip.mnn / unet.mnn / vae_decoder.mnn layout, so it cannot yet be passed to Sruvik's current Nezumi loader.",
            lore = "A location scout who travels with a camera and a notebook, collecting believable streets, faces and quiet moments from places that do not exist.",
            styleTags = listOf("Photorealism", "Portraits", "Cinematic"),
            baseModel = "SG161222/Realistic_Vision_V6.0_B1_noVAE",
            engine = SruGenGalleryEngine.NEZUMI_MNN_SD15,
            downloadUrl = null,
            archiveFileName = "Realistic_Vision_V6.0_B1-mnn-int8-block32.zip",
            archiveSha256 = null,
            approximateDownloadBytes = 1_400_000_000L,
            archiveMaxBytes = 0L,
            minAndroidApi = 31,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/Mouserat/Realistic_Vision_V6.0_B1-mnn",
            licenseName = "CreativeML Open RAIL-M",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "SG161222 · Realistic Vision",
            licenseNote = "The source conversion's model files use an older MNN naming/layout than the current Nezumi runtime expects. Sruvik will keep the download locked until a verified compatible pack is prepared."
        ),
        SruGenGalleryModel(
            id = "illustrious-xl-v2",
            name = "Illustrious XL v2",
            subtitle = "High-detail illustration and anime XL",
            runtime = "MNN-Diffusion · SDXL class",
            availability = SruGenGalleryAvailability.RUNTIME_PENDING,
            description = "A larger XL-family illustration model. Its model package and memory footprint need a separate SDXL-capable Android pipeline; the current SD1.5 engine cannot load it.",
            lore = "A grand archive of painted worlds, where every page is dense with intricate architecture, expressive characters and impossible colour.",
            styleTags = listOf("Anime XL", "High detail", "Illustration"),
            baseModel = "Illustrious XL v2.0",
            engine = null,
            downloadUrl = null,
            archiveFileName = null,
            archiveSha256 = null,
            approximateDownloadBytes = 3_010_000_000L,
            archiveMaxBytes = 0L,
            minAndroidApi = 34,
            recommendedRamGb = 16,
            sourceUrl = "https://huggingface.co/Mouserat/Illustrious-XL-v2.0-diffusers-mnn",
            licenseName = "CreativeML Open RAIL-M",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "Illustrious XL contributors · Mouserat MNN conversion",
            licenseNote = "The available MNN archives are multi-gigabyte SDXL/Illustrious packs. Sruvik has no validated SDXL Android runtime or device-specific memory profile yet."
        ),
        SruGenGalleryModel(
            id = "dreamshaper-8",
            name = "DreamShaper 8",
            subtitle = "Cinematic and painterly styles",
            runtime = "Nezumi Kiln · MNN",
            availability = SruGenGalleryAvailability.CONVERSION_PENDING,
            description = "A popular SD1.5 fine-tune with a broad style range. The original checkpoint is not an MNN model pack and needs a reproducible conversion.",
            lore = "A travelling theatre that can stage a photograph, a painted fable or a half-remembered dream with the same cast of light.",
            styleTags = listOf("Cinematic", "Painterly", "Versatile"),
            baseModel = "DreamShaper 8 · SD1.5",
            engine = SruGenGalleryEngine.NEZUMI_MNN_SD15,
            downloadUrl = null,
            archiveFileName = null,
            archiveSha256 = null,
            approximateDownloadBytes = 0L,
            archiveMaxBytes = 0L,
            minAndroidApi = 31,
            recommendedRamGb = 8,
            sourceUrl = "https://huggingface.co/GENMT/dreamshaper-8",
            licenseName = "CreativeML Open RAIL-M",
            licenseUrl = "https://huggingface.co/spaces/CompVis/stable-diffusion-license",
            attribution = "Lykon · DreamShaper",
            licenseNote = "Sruvik will not download the original safetensors file into an MNN engine. A converted, pinned and tested MNN pack is required."
        ),
        SruGenGalleryModel(
            id = "sd35-mnn",
            name = "Stable Diffusion 3.5",
            subtitle = "Newer multi-style diffusion family",
            runtime = "MNN-Diffusion",
            availability = SruGenGalleryAvailability.RUNTIME_PENDING,
            description = "A newer diffusion family with a different runtime and memory profile. It needs a dedicated Android integration and device testing.",
            lore = "A vast observatory of living constellations, where every prompt is a new sky being assembled.",
            styleTags = listOf("New generation", "Multi-style", "Research"),
            baseModel = "Stable Diffusion 3.5",
            engine = null,
            downloadUrl = null,
            archiveFileName = null,
            archiveSha256 = null,
            approximateDownloadBytes = 0L,
            archiveMaxBytes = 0L,
            minAndroidApi = 34,
            recommendedRamGb = 16,
            sourceUrl = "https://huggingface.co/stabilityai/stable-diffusion-3.5-large",
            licenseName = "Model-dependent",
            licenseUrl = "https://huggingface.co/stabilityai/stable-diffusion-3.5-large",
            attribution = "Stability AI",
            licenseNote = "No Sruvik-compatible, checksum-pinned Android package is available yet."
        ),
        SruGenGalleryModel(
            id = "sana",
            name = "Sana",
            subtitle = "High-detail image research model",
            runtime = "MNN-Diffusion",
            availability = SruGenGalleryAvailability.RUNTIME_PENDING,
            description = "Sana uses a separate text-feature and diffusion pipeline; it cannot be loaded by the current SD1.5 engine.",
            lore = "A quiet garden of geometric light, where tiny details unfold into enormous imagined worlds.",
            styleTags = listOf("High detail", "Research", "New generation"),
            baseModel = "Sana",
            engine = null,
            downloadUrl = null,
            archiveFileName = null,
            archiveSha256 = null,
            approximateDownloadBytes = 0L,
            archiveMaxBytes = 0L,
            minAndroidApi = 34,
            recommendedRamGb = 16,
            sourceUrl = "https://github.com/NVlabs/Sana",
            licenseName = "Model-dependent",
            licenseUrl = "https://github.com/NVlabs/Sana",
            attribution = "NVIDIA Research",
            licenseNote = "No Sruvik-compatible Android runtime and verified model pack are available yet."
        ),
        SruGenGalleryModel(
            id = "wan-2-1",
            name = "Wan 2.1",
            subtitle = "Video diffusion research model",
            runtime = "MNN-Diffusion",
            availability = SruGenGalleryAvailability.RUNTIME_PENDING,
            description = "A video-oriented diffusion family with a substantially different resource footprint and output pipeline.",
            lore = "A moving canvas that turns a single idea into a sequence of places, moments and motion.",
            styleTags = listOf("Video", "Motion", "Research"),
            baseModel = "Wan 2.1",
            engine = null,
            downloadUrl = null,
            archiveFileName = null,
            archiveSha256 = null,
            approximateDownloadBytes = 0L,
            archiveMaxBytes = 0L,
            minAndroidApi = 34,
            recommendedRamGb = 24,
            sourceUrl = "https://github.com/Wan-Video/Wan2.1",
            licenseName = "Model-dependent",
            licenseUrl = "https://github.com/Wan-Video/Wan2.1",
            attribution = "Wan-AI",
            licenseNote = "No Sruvik-compatible Android video engine or checksum-pinned pack is available yet."
        )
    )

    fun find(modelId: String): SruGenGalleryModel? = models.firstOrNull { it.id == modelId }
    fun installableModels(): List<SruGenGalleryModel> = models.filter { it.canInstall }
}

object SruGenActiveModelStore {
    private const val PREFS = "srugen_active_model"
    private const val KEY_ACTIVE_MODEL = "active_model_id"

    fun activeModelId(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_MODEL, null)
        return stored?.takeIf { SruGenGalleryCatalog.find(it)?.canInstall == true }
            ?: "sd15-mediapipe"
    }

    fun setActiveModel(context: Context, modelId: String) {
        require(SruGenGalleryCatalog.find(modelId)?.canInstall == true) {
            "This model is not enabled for installation or use in this Sruvik build."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_MODEL, modelId).apply()
    }
}
