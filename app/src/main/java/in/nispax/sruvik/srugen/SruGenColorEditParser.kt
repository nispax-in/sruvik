package `in`.nispax.sruvik.srugen

/**
 * Routes simple garment recolours to a mask-preserving pixel operation instead
 * of asking an img2img model to regenerate the entire garment/body crop.
 */
object SruGenColorEditParser {
    fun targetColor(prompt: String): String? {
        val normalized = prompt.lowercase()
        val hasEditVerb = Regex("\\b(change|edit|recolor|recolour|turn|make|replace|switch)\\b")
            .containsMatchIn(normalized)
        val hasGarment = Regex("\\b(clothes|clothing|outfit|shirt|t-shirt|tee|jacket|coat|hoodie|sweater|dress|trousers|pants|jeans)\\b")
            .containsMatchIn(normalized)
        val colorWordPattern = Regex("\\b(black|white|gray|grey|red|orange|yellow|green|teal|cyan|blue|purple|violet|pink|brown)\\b")
        val hasColorIntent = Regex("\\b(color|colour|to|into)\\b").containsMatchIn(normalized) ||
            colorWordPattern.containsMatchIn(normalized)
        if (!hasEditVerb || !hasGarment || !hasColorIntent) return null

        // Only handle a plain colour request here. Patterns, fabric/shape changes,
        // logos or multiple requested changes remain on the generative img2img path.
        val unsupportedDetails = Regex(
            "\\b(striped|stripe|checked|checkered|plaid|pattern|polka|floral|leather|denim|silk|logo|print|embroider|sleeveless|long-sleeve|short-sleeve|change style|new design)\\b"
        )
        if (unsupportedDetails.containsMatchIn(normalized)) return null

        val words = Regex("\\b(black|white|gray|grey|red|orange|yellow|green|teal|cyan|blue|purple|violet|pink|brown)\\b")
        val matches = words.findAll(normalized).map { it.value }.distinct().toList()
        return matches.singleOrNull()
    }

    /**
     * Returns distinct, explicitly named garment groups. The current on-device
     * selfie segmenter produces one broad "clothes" class, not separate masks
     * for an outer jacket and an inner shirt. Those requests must not be sent
     * through the generic img2img path as if separate garment control existed.
     */
    fun garmentTargets(prompt: String): Set<String> {
        val text = prompt.lowercase()
        val patterns = linkedMapOf(
            "outerwear" to Regex("\\b(blazer|jacket|coat|hoodie|sweater|cardigan|vest)\\b"),
            "inner-top" to Regex("\\b(shirt|t-shirt|tee|blouse|tank top|undershirt)\\b"),
            "lower-body" to Regex("\\b(trousers|pants|jeans|shorts|skirt)\\b"),
            "dress" to Regex("\\b(dress|gown)\\b")
        )
        return patterns.filterValues { it.containsMatchIn(text) }.keys
    }

    fun requiresMultiGarmentEdit(prompt: String): Boolean =
        garmentTargets(prompt).size > 1

    fun isSimpleColorChange(prompt: String): Boolean = targetColor(prompt) != null
}
