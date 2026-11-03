package `in`.nispax.sruvik.srugen

/**
 * Routes simple garment recolours to a mask-preserving pixel operation instead
 * of asking an img2img model to regenerate the entire garment/body crop.
 */
object SruGenColorEditParser {
    private val targetColors = linkedMapOf(
        "red" to 0f,
        "orange" to 30f,
        "yellow" to 55f,
        "green" to 120f,
        "teal" to 175f,
        "cyan" to 190f,
        "blue" to 220f,
        "purple" to 275f,
        "violet" to 275f,
        "pink" to 330f,
        "brown" to 25f
    )

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

    fun isSimpleColorChange(prompt: String): Boolean = targetColor(prompt) != null
}
