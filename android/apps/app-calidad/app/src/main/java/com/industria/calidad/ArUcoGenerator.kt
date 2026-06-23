package com.industria.calidad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap

/**
 * Generates ArUco markers compatible with DICT_4X4_50.
 *
 * Each marker is a 6x6 grid: 1-cell black border surrounding a 4x4 data region.
 * The 4x4 inner bits are looked up from a pre-computed dictionary (IDs 0-49).
 */
object ArUcoGenerator {

    /**
     * DICT_4X4_50 inner-bit patterns (row-major, MSB first per row).
     * Each entry is 4 bytes representing 4 rows of 4 bits.
     * Source: OpenCV aruco module dictionary data.
     */
    private val DICT_4X4_50: Array<IntArray> = arrayOf(
        intArrayOf(0b0101, 0b0010, 0b1001, 0b0100), // 0
        intArrayOf(0b1001, 0b1110, 0b0100, 0b0111), // 1
        intArrayOf(0b1100, 0b0001, 0b1110, 0b0101), // 2
        intArrayOf(0b0110, 0b0101, 0b1000, 0b1110), // 3
        intArrayOf(0b1010, 0b0110, 0b0010, 0b1010), // 4
        intArrayOf(0b0100, 0b1010, 0b1110, 0b0001), // 5
        intArrayOf(0b1110, 0b1101, 0b0011, 0b0100), // 6
        intArrayOf(0b0000, 0b1001, 0b0101, 0b1011), // 7
        intArrayOf(0b1011, 0b0100, 0b1100, 0b1001), // 8
        intArrayOf(0b0001, 0b1000, 0b0110, 0b0010), // 9
        intArrayOf(0b1111, 0b0011, 0b1011, 0b0110), // 10
        intArrayOf(0b0011, 0b0111, 0b0001, 0b1101), // 11
        intArrayOf(0b0010, 0b1100, 0b1111, 0b1000), // 12
        intArrayOf(0b1000, 0b1011, 0b0111, 0b1111), // 13
        intArrayOf(0b1101, 0b0000, 0b1010, 0b0011), // 14
        intArrayOf(0b0111, 0b1111, 0b1101, 0b1100), // 15
        intArrayOf(0b0101, 0b0111, 0b1001, 0b0001), // 16
        intArrayOf(0b1001, 0b1011, 0b0100, 0b1010), // 17
        intArrayOf(0b1100, 0b0100, 0b1110, 0b1000), // 18
        intArrayOf(0b0110, 0b0000, 0b1000, 0b0011), // 19
        intArrayOf(0b1010, 0b0011, 0b0010, 0b0101), // 20
        intArrayOf(0b0100, 0b1111, 0b1110, 0b1100), // 21
        intArrayOf(0b1110, 0b1000, 0b0011, 0b1001), // 22
        intArrayOf(0b0000, 0b0100, 0b0101, 0b0110), // 23
        intArrayOf(0b1011, 0b1001, 0b1100, 0b0100), // 24
        intArrayOf(0b0001, 0b1101, 0b0110, 0b1111), // 25
        intArrayOf(0b1111, 0b0110, 0b1011, 0b1011), // 26
        intArrayOf(0b0011, 0b0010, 0b0001, 0b0000), // 27
        intArrayOf(0b0010, 0b1001, 0b1111, 0b0101), // 28
        intArrayOf(0b1000, 0b0110, 0b0111, 0b0010), // 29
        intArrayOf(0b1101, 0b0101, 0b1010, 0b1110), // 30
        intArrayOf(0b0111, 0b1010, 0b1101, 0b0001), // 31
        intArrayOf(0b0101, 0b1100, 0b1001, 0b1010), // 32
        intArrayOf(0b1001, 0b0000, 0b0100, 0b0001), // 33
        intArrayOf(0b1100, 0b1011, 0b1110, 0b1111), // 34
        intArrayOf(0b0110, 0b1111, 0b1000, 0b0100), // 35
        intArrayOf(0b1010, 0b1100, 0b0010, 0b1111), // 36
        intArrayOf(0b0100, 0b0000, 0b1110, 0b0110), // 37
        intArrayOf(0b1110, 0b0011, 0b0011, 0b0011), // 38
        intArrayOf(0b0000, 0b1111, 0b0101, 0b1000), // 39
        intArrayOf(0b1011, 0b0010, 0b1100, 0b1110), // 40
        intArrayOf(0b0001, 0b0110, 0b0110, 0b0101), // 41
        intArrayOf(0b1111, 0b1101, 0b1011, 0b1100), // 42
        intArrayOf(0b0011, 0b1001, 0b0001, 0b0111), // 43
        intArrayOf(0b0010, 0b0100, 0b1111, 0b1110), // 44
        intArrayOf(0b1000, 0b0000, 0b0111, 0b1101), // 45
        intArrayOf(0b1101, 0b1010, 0b1010, 0b0000), // 46
        intArrayOf(0b0111, 0b0101, 0b1101, 0b1011), // 47
        intArrayOf(0b1100, 0b0110, 0b0000, 0b0010), // 48
        intArrayOf(0b0001, 0b1011, 0b1100, 0b1000)  // 49
    )

    /**
     * Builds an ArUco marker bitmap.
     * @param size pixel size of the output (will be rounded to multiple of 6)
     * @param markerId ID 0-49 from DICT_4X4_50
     */
    fun buildBitmap(size: Int = 300, markerId: Int = 7): Bitmap {
        val id = markerId.coerceIn(0, DICT_4X4_50.size - 1)
        val gridSize = 6 // 4x4 data + 1-cell border on each side
        val cellPx = maxOf(1, size / gridSize)
        val totalPx = cellPx * gridSize

        val bitmap = createBitmap(totalPx, totalPx)
        val canvas = Canvas(bitmap)

        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }

        // Fill all white first
        canvas.drawRect(0f, 0f, totalPx.toFloat(), totalPx.toFloat(), whitePaint)

        val pattern = DICT_4X4_50[id]

        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val isBorder = row == 0 || row == gridSize - 1 || col == 0 || col == gridSize - 1
                val isBlack = if (isBorder) {
                    true // border is always black
                } else {
                    val dataRow = row - 1
                    val dataCol = col - 1
                    val bits = pattern[dataRow]
                    (bits shr (3 - dataCol)) and 1 == 1
                }

                if (isBlack) {
                    val x = col * cellPx
                    val y = row * cellPx
                    canvas.drawRect(
                        x.toFloat(), y.toFloat(),
                        (x + cellPx).toFloat(), (y + cellPx).toFloat(),
                        blackPaint
                    )
                }
            }
        }
        return bitmap
    }
}
