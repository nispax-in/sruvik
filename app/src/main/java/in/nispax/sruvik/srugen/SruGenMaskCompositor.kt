package `in`.nispax.sruvik.srugen

/**
 * Pixel compositor shared by every local edit path.
 *
 * Alpha 0 is a hard guarantee: the corresponding output pixel is copied
 * directly from the original array, without colour conversion or rounding.
 */
object SruGenMaskCompositor {
    fun composite(
        originalPixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        generatedPixels: IntArray,
        generatedWidth: Int,
        generatedHeight: Int,
        destinationLeft: Int,
        destinationTop: Int,
        maskAlpha: ByteArray,
        maskWidth: Int,
        maskHeight: Int
    ): IntArray {
        require(imageWidth > 0 && imageHeight > 0)
        require(originalPixels.size == Math.multiplyExact(imageWidth, imageHeight))
        require(generatedWidth > 0 && generatedHeight > 0)
        require(generatedPixels.size == Math.multiplyExact(generatedWidth, generatedHeight))
        require(maskWidth > 0 && maskHeight > 0)
        require(maskAlpha.size == Math.multiplyExact(maskWidth, maskHeight))
        require(destinationLeft >= 0 && destinationTop >= 0)
        require(destinationLeft + generatedWidth <= imageWidth)
        require(destinationTop + generatedHeight <= imageHeight)

        val output = originalPixels.copyOf()
        for (localY in 0 until generatedHeight) {
            val y = destinationTop + localY
            val maskY = (y.toLong() * maskHeight / imageHeight).toInt()
                .coerceIn(0, maskHeight - 1)
            for (localX in 0 until generatedWidth) {
                val x = destinationLeft + localX
                val maskX = (x.toLong() * maskWidth / imageWidth).toInt()
                    .coerceIn(0, maskWidth - 1)
                val alpha = maskAlpha[maskY * maskWidth + maskX].toInt() and 0xff
                if (alpha == 0) continue

                val originalIndex = y * imageWidth + x
                val generatedIndex = localY * generatedWidth + localX
                val old = originalPixels[originalIndex]
                val fresh = generatedPixels[generatedIndex]
                val inverse = 255 - alpha
                val red = ((old ushr 16 and 0xff) * inverse +
                    (fresh ushr 16 and 0xff) * alpha) / 255
                val green = ((old ushr 8 and 0xff) * inverse +
                    (fresh ushr 8 and 0xff) * alpha) / 255
                val blue = ((old and 0xff) * inverse +
                    (fresh and 0xff) * alpha) / 255
                output[originalIndex] = (old and -0x1000000) or
                    (red shl 16) or (green shl 8) or blue
            }
        }
        return output
    }
}
