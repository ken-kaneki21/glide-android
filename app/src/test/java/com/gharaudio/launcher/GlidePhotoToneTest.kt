package com.gharaudio.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlidePhotoToneTest {
    @Test fun burstMedianRejectsBrightAndDarkOutliers() {
        assertEquals(101, medianOfThree(255, 100, 101))
        assertEquals(100, medianOfThree(101, 0, 100))
        assertEquals(200, medianOfThree(200, 200, 5))
    }

    @Test fun preservesBlackAndWhiteAndLiftsShadows() {
        assertEquals(0, glidePhotoTone(0))
        assertEquals(255, glidePhotoTone(255))
        assertTrue(glidePhotoTone(48) > 48)
    }

    @Test fun toneCurveIsMonotoneAndBounded() {
        val values = (0..255).map(::glidePhotoTone)
        assertTrue(values.all { it in 0..255 })
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
    }
}
