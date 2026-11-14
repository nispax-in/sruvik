package `in`.nispax.sruvik.srugen

/** Conservative routing for local, region-aware edits. */
object SruGenEditIntentParser {
    enum class Finger(val label: String, val landmarkIndices: IntArray) {
        THUMB("thumb", intArrayOf(1, 2, 3, 4)),
        INDEX("index", intArrayOf(5, 6, 7, 8)),
        MIDDLE("middle", intArrayOf(9, 10, 11, 12)),
        RING("ring", intArrayOf(13, 14, 15, 16)),
        PINKY("little", intArrayOf(17, 18, 19, 20))
    }

    enum class HandSide { LEFT, RIGHT }

    private val editVerb = Regex("\\b(change|edit|make|modify|reshape|lengthen|shorten|replace|style|cut|grow|give|add|recolor|recolour|turn|switch|dye|color|colour)\\b")

    fun isHairEdit(prompt: String): Boolean {
        val text = prompt.lowercase()
        val target = Regex("\\b(hair|hairstyle|haircut|bangs|fringe|curls|curl|ponytail|braids|braid|layers)\\b")
        return editVerb.containsMatchIn(text) && target.containsMatchIn(text)
    }

    fun isFingerEdit(prompt: String): Boolean {
        val text = prompt.lowercase()
        return Regex("\\b(finger|fingers|thumb|index finger|pointer finger|middle finger|ring finger|pinky|little finger)\\b")
            .containsMatchIn(text)
    }

    fun isClothingEdit(prompt: String): Boolean {
        val text = prompt.lowercase()
        val target = Regex("\\b(clothes|clothing|outfit|shirt|t-shirt|tee|jacket|coat|hoodie|sweater|dress|trousers|pants|jeans|skirt|shorts|blouse|uniform|suit)\\b")
        return editVerb.containsMatchIn(text) && target.containsMatchIn(text)
    }

    fun isSupportedRegionEdit(prompt: String): Boolean =
        isHairEdit(prompt) || isFingerEdit(prompt) || isClothingEdit(prompt)

    fun finger(prompt: String): Finger? {
        val text = prompt.lowercase()
        val found = buildList {
            if (Regex("\\b(thumb)\\b").containsMatchIn(text)) add(Finger.THUMB)
            if (Regex("\\b(index|pointer)\\s+finger\\b").containsMatchIn(text)) add(Finger.INDEX)
            if (Regex("\\bmiddle\\s+finger\\b").containsMatchIn(text)) add(Finger.MIDDLE)
            if (Regex("\\bring\\s+finger\\b").containsMatchIn(text)) add(Finger.RING)
            if (Regex("\\b(pinky|little)\\s+finger\\b").containsMatchIn(text)) add(Finger.PINKY)
        }
        return found.distinct().singleOrNull()
    }

    fun handSide(prompt: String): HandSide? {
        val text = prompt.lowercase()
        val left = Regex("\\bleft\\s+hand\\b").containsMatchIn(text)
        val right = Regex("\\bright\\s+hand\\b").containsMatchIn(text)
        return when {
            left && !right -> HandSide.LEFT
            right && !left -> HandSide.RIGHT
            else -> null
        }
    }
}
