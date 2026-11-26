package com.gharaudio.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlidePhotoPreviewTest {
    @Test fun landscapeAndPortraitKeepMatchingGeometry() {
        assertEquals(768 to 384, photoPreviewDimensions(2048,1024))
        assertEquals(384 to 768, photoPreviewDimensions(1024,2048))
        assertEquals(32 to 16, photoPreviewDimensions(32,16))
    }
    @Test fun extremeAspectRatiosRemainPositiveAndBounded() {
        for ((w,h) in listOf(1 to 20000, 20000 to 1, 2048 to 2048, 4096 to 2160)) {
            val (x,y) = photoPreviewDimensions(w,h)
            assertTrue(x in 1..768 && y in 1..768)
            assertTrue(x <= w && y <= h)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidDimensions() {
        photoPreviewDimensions(0,20)
    }
}
