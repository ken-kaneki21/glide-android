package com.gharaudio.launcher

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class GlidePhotoRepairBoundaryTest {
    @Test fun repairsClippedCornerWithoutChangingOtherPixels() {
        val pixels = IntArray(81 * 81) { 0xff537ca9.toInt() }
        pixels[0] = 0xff000000.toInt()
        val before = pixels.copyOf()
        val output = repairSmallPhotoMark(pixels, 81, 81, 0, 0, 3)
        assertTrue(output.all { it == 0xff537ca9.toInt() })
        assertArrayEquals(before, pixels)
    }

    @Test fun onePixelWideInputDoesNotWrapAcrossRows() {
        val pixels = IntArray(128) { 0xff8090a0.toInt() }
        pixels[63] = 0xff000000.toInt()
        val output = repairSmallPhotoMark(pixels, 1, 128, 0, 63, 1)
        assertTrue(output.all { it == 0xff8090a0.toInt() })
        assertEquals(0xff000000.toInt(), pixels[63])
    }

    @Test fun onePixelHighInputDoesNotWrapAcrossColumns() {
        val pixels = IntArray(128) { 0xff123456.toInt() }
        pixels[64] = 0xff000000.toInt()
        assertTrue(repairSmallPhotoMark(pixels, 128, 1, 64, 0, 1).all { it == 0xff123456.toInt() })
    }

    @Test fun uniformSemitransparentBackgroundRetainsAlpha() {
        val pixels = IntArray(51 * 51) { 0x408090a0 }
        pixels[25 * 51 + 25] = 0
        assertTrue(repairSmallPhotoMark(pixels, 51, 51, 25, 25, 1).all { it == 0x408090a0 })
    }

    @Test fun transparentBackgroundDoesNotCreateOpaquePixels() {
        val pixels = IntArray(51 * 51)
        pixels[25 * 51 + 25] = 0xffffffff.toInt()
        assertTrue(repairSmallPhotoMark(pixels, 51, 51, 25, 25, 1).all { it == 0 })
    }

    @Test fun cancellationDuringFinalSmoothingLeavesInputExact() {
        val pixels = IntArray(51 * 51) { 0xff405060.toInt() }
        val before = pixels.copyOf()
        var total = 0
        repairSmallPhotoMark(pixels, 51, 51, 25, 25, 2) { total++ }
        var calls = 0
        try {
            repairSmallPhotoMark(pixels, 51, 51, 25, 25, 2) {
                if (++calls == total) throw CancellationException("test cancellation")
            }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals(total, calls)
        }
        assertArrayEquals(before, pixels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedBufferBeforeProcessing() {
        repairSmallPhotoMark(IntArray(20), 21, 21, 10, 10, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDimensionBeyondMemoryBound() {
        repairSmallPhotoMark(IntArray(2049), 2049, 1, 10, 0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroRadius() {
        repairSmallPhotoMark(IntArray(51 * 51), 51, 51, 25, 25, 0)
    }

    @Test fun repairsAreDeterministicAndReturnIndependentCopies() {
        val pixels = IntArray(51 * 51) { i -> 0xff000000.toInt() or (i * 183 and 0xffffff) }
        val first = repairSmallPhotoMark(pixels, 51, 51, 25, 25, 3)
        val second = repairSmallPhotoMark(pixels, 51, 51, 25, 25, 3)
        assertArrayEquals(first, second)
        assertNotSame(first, second)
        first[0] = 0
        assertEquals(pixels[0], second[0])
    }
}
