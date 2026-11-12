package de.jobisoft.amster.reader

import android.graphics.Bitmap

/**
 * Makes a second picture of the same frame for a second reading: black text on white, whatever the colour of the card and the light on it.
 * A card in orange or red turns into a mid grey in the luminance picture, with a shade from one side to the other. A global threshold
 * cannot separate that, a local one can: a pixel is black when it is clearly darker than the mean of its surroundings.
 */
object OcrImage {
    /** Side of the window of the local mean, in pixels (about a letter height of the title at the size the frames have). */
    const val WINDOW = 51

    /** A pixel is text when it is darker than this share of the local mean. */
    const val RATIO = 0.88

    /** [gray] is one grey value 0 to 255 per pixel, row by row. Returns 0 (black) or 255 (white) per pixel. */
    fun binarize(gray: IntArray, width: Int, height: Int, window: Int = WINDOW, ratio: Double = RATIO): IntArray {
        require(gray.size == width * height) { "size" }
        // integral image: the sum of any rectangle with four reads
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var row = 0L
            for (x in 0 until width) {
                row += gray[y * width + x]
                integral[(y + 1) * (width + 1) + (x + 1)] = integral[y * (width + 1) + (x + 1)] + row
            }
        }
        val half = window / 2
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val y0 = maxOf(0, y - half)
            val y1 = minOf(height, y + half + 1)
            for (x in 0 until width) {
                val x0 = maxOf(0, x - half)
                val x1 = minOf(width, x + half + 1)
                val sum = integral[y1 * (width + 1) + x1] - integral[y0 * (width + 1) + x1] -
                    integral[y1 * (width + 1) + x0] + integral[y0 * (width + 1) + x0]
                val mean = sum.toDouble() / ((x1 - x0) * (y1 - y0))
                out[y * width + x] = if (gray[y * width + x] < mean * ratio) 0 else 255
            }
        }
        return out
    }

    /** The grey values of a frame (the frame is grey already, the green channel stands for it), row by row. */
    fun grayOf(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return IntArray(w * h) { (pixels[it] shr 8) and 0xFF }
    }

    /**
     * The area of the card on the picture: the box around all its lines, a little wider (the card has a border around the text). Returns
     * left, top, right, bottom, kept inside the picture. This cuts away the poster, the table and the other cards around the card.
     */
    fun cardArea(lines: List<OcrLine>, width: Int, height: Int, margin: Double = 0.18): IntArray {
        val left = lines.minOf { it.left }
        val top = lines.minOf { it.top }
        val right = lines.maxOf { it.right }
        val bottom = lines.maxOf { it.bottom }
        val padX = ((right - left) * margin).toInt()
        val padY = ((bottom - top) * margin).toInt()
        return intArrayOf(maxOf(0, left - padX), maxOf(0, top - padY), minOf(width, right + padX), minOf(height, bottom + padY))
    }

    /** How much the area is enlarged for the reading: about [TARGET_WIDTH] pixels wide, at most [MAX_SCALE] times. */
    fun scaleFor(areaWidth: Int): Double = (TARGET_WIDTH.toDouble() / maxOf(areaWidth, 1)).coerceIn(1.0, MAX_SCALE)

    const val TARGET_WIDTH = 1000
    const val MAX_SCALE = 2.5

    /** The area of [bitmap], enlarged by [scaleFor] with filtering. */
    fun cropped(bitmap: Bitmap, area: IntArray): Bitmap {
        val w = area[2] - area[0]
        val h = area[3] - area[1]
        val part = Bitmap.createBitmap(bitmap, area[0], area[1], w, h)
        val scale = scaleFor(w)
        return if (scale > 1.0) Bitmap.createScaledBitmap(part, (w * scale).toInt(), (h * scale).toInt(), true) else part
    }

    /** The picture turned clockwise by [degrees] (0, 90, 180, 270). */
    fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** The binarized picture of a frame, see [binarize]. */
    fun binarized(bitmap: Bitmap, window: Int = WINDOW, ratio: Double = RATIO): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val gray = grayOf(bitmap)
        val bw = binarize(gray, w, h, window, ratio)
        val result = IntArray(w * h) { 0xFF000000.toInt() or (bw[it] shl 16) or (bw[it] shl 8) or bw[it] }
        return Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
    }
}
