package `in`.nispax.sruvik.srugen

/**
 * Routes edits that can be completed by deterministic, mask-preserving pixel
 * operations. Structural garment/hair changes remain on a generative route.
 */
object SruGenColorEditParser {
    private val editVerb = Regex("\\b(change|edit|recolor|recolour|turn|make|replace|switch)\\b")
    private val colorWords = Regex("\\b(black|white|gray|grey|red|orange|yellow|green|teal|cyan|blue|purple|violet|pink|brown)\\b")
    private val garments = Regex("\\b(clothes|clothing|outfit|shirt|t-shirt|tee|blazer|jacket|coat|hoodie|sweater|cardigan|vest|blouse|tank top|undershirt|turtleneck|dress|gown|trousers|pants|jeans|shorts|skirt)\\b")
    private val hairTerms = Regex("\\b(hair|hairstyle|hairstyles)\\b")

    fun targetColor(prompt: String): String? {
        val text = prompt.lowercase()
        if (!editVerb.containsMatchIn(text)) return null
        val region = targetRegion(text) ?: return null
        if (region == "clothes" && !Regex("\\b(color|colour|to|into)\\b").containsMatchIn(text) &&
            !colorWords.containsMatchIn(text)) return null
        if (region == "hair" && !Regex("\\b(color|colour|to|into)\\b").containsMatchIn(text) &&
            !colorWords.containsMatchIn(text)) return null
        val unsupported = Regex("\\b(striped|stripe|checked|checkered|plaid|pattern|polka|floral|leather|denim|silk|logo|print|embroider|sleeveless|long-sleeve|short-sleeve|change style|new design|longer|shorter|curly|straight|braided|fringe|bangs)\\b")
        if (unsupported.containsMatchIn(text)) return null
        return colorWords.findAll(text).map { it.value }.distinct().singleOrNull()
    }

    fun targetRegion(prompt: String): String? {
        val text = prompt.lowercase()
        val hair = hairTerms.containsMatchIn(text)
        val clothes = garments.containsMatchIn(text)
        return when {
            hair && !clothes -> "hair"
            clothes && !hair -> "clothes"
            else -> null
        }
    }

    /** True when a structural edit names clothing and can use the clothing-only composite path. */
    fun hasClothingTarget(prompt: String): Boolean =
        garments.containsMatchIn(prompt.lowercase())

    /** Returns distinct, explicitly named garment groups; not true garment instances. */
    fun garmentTargets(prompt: String): Set<String> {
        val text = prompt.lowercase()
        val patterns = linkedMapOf(
            "outerwear" to Regex("\\b(blazer|jacket|coat|hoodie|sweater|cardigan|vest)\\b"),
            "inner-top" to Regex("\\b(shirt|t-shirt|tee|blouse|tank top|undershirt|turtleneck)\\b"),
            "lower-body" to Regex("\\b(trousers|pants|jeans|shorts|skirt)\\b"),
            "dress" to Regex("\\b(dress|gown)\\b")
        )
        return patterns.filterValues { it.containsMatchIn(text) }.keys
    }

    fun requiresMultiGarmentEdit(prompt: String): Boolean = garmentTargets(prompt).size > 1

    fun requiresMultiRegionEdit(prompt: String): Boolean {
        val text = prompt.lowercase()
        return (hairTerms.containsMatchIn(text) && garments.containsMatchIn(text)) ||
            requiresMultiGarmentEdit(text)
    }

    fun isSimpleColorChange(prompt: String): Boolean = targetColor(prompt) != null
}
