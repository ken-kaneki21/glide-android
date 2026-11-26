package com.gharaudio.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class GlideFrameOffsetTest {
    @Test
    fun matchesTranslatedFrameWithExposureShift() {
        val width = 42
        val height = 36
        val random = Random(40)
        val reference = IntArray(width * height) { random.nextInt(20, 210) }
        val shifted = IntArray(reference.size)
        for (y in 4 until height) {
            for (x in 0 until width - 3) {
                shifted[(y - 4) * width + x + 3] = reference[y * width + x] + 25
            }
        }
        assertEquals(3 to -4, findFrameOffset(reference, shifted, width, height))
    }

    @Test
    fun alignsVerticalMotionInHorizontalTexture() {
        val width = 48
        val height = 36
        val random = Random(77)
        val bands = IntArray(height) { random.nextInt(10, 240) }
        val reference = IntArray(width * height) { bands[it / width] }
        val shifted = IntArray(reference.size) { bands[(it / width - 5).coerceAtLeast(0)] }
        assertEquals(0 to 5, findFrameOffset(reference, shifted, width, height))
    }

    @Test
    fun flatFramesKeepTheirOriginalCrop() {
        val reference = IntArray(36 * 36) { 80 }
        val brighter = IntArray(reference.size) { 100 }
        assertEquals(0 to 0, findFrameOffset(reference, brighter, 36, 36))
    }
}
