package com.industria.calidad

import android.graphics.Bitmap

/**
 * Translates a black-and-white bitmap into G-code for laser engraving.
 *
 * Uses a row-scanning strategy: for each row, groups consecutive black pixels
 * into horizontal line segments, emitting G0 (rapid move) to the start and G1
 * (engrave) to the end of each segment. This produces far fewer commands than
 * pixel-by-pixel output.
 */
object GCodeTranslator {

    fun translate(bitmap: Bitmap, scale: Float = 1f, feedRate: Int = 1000): List<String> {
        val commands = mutableListOf<String>()
        val width = bitmap.width
        val height = bitmap.height

        commands.add("G90")                  // absolute positioning
        commands.add("G21")                  // millimeters
        commands.add("M5")                   // laser off initially
        commands.add("G0 X0.00 Y0.00")
        commands.add("G1 F$feedRate")

        for (y in 0 until height) {
            val yPos = y * scale
            var x = 0
            while (x < width) {
                // Skip non-black pixels
                if (bitmap.getPixel(x, y) != android.graphics.Color.BLACK) {
                    x++
                    continue
                }
                // Found start of a black segment
                val startX = x
                while (x < width && bitmap.getPixel(x, y) == android.graphics.Color.BLACK) {
                    x++
                }
                val endX = x - 1

                val xStart = startX * scale
                val xEnd = (endX + 1) * scale

                commands.add("G0 X${"%.2f".format(xStart)} Y${"%.2f".format(yPos)}")
                commands.add("M3")           // laser on
                commands.add("G1 X${"%.2f".format(xEnd)} Y${"%.2f".format(yPos)}")
                commands.add("M5")           // laser off
            }
        }

        commands.add("G0 X0.00 Y0.00")      // return home
        commands.add("M5")                   // ensure laser off
        return commands
    }
}
