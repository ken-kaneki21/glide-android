package com.gharaudio.launcher

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires owner-approved instrumentation on a suitable device; not a host JVM check. */
@RunWith(AndroidJUnit4::class)
class GlidePhotoComparisonDeviceTest {
    @Test fun smallOriginalPreviewIsAnIndependentBitmap() {
        val input = Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.rgb(50,80,100))
        val preview = createOriginalPhotoPreview(input)
        try {
            assertNotSame(input,preview)
            assertEquals(32,preview.width); assertEquals(16,preview.height)
            assertEquals(input.getPixel(5,5),preview.getPixel(5,5))
            input.recycle()
            assertFalse(preview.isRecycled)
            assertEquals(Color.rgb(50,80,100),preview.getPixel(5,5))
        } finally { if (!input.isRecycled) input.recycle(); preview.recycle() }
    }
    @Test fun portraitPreviewIsBoundedAndSourceIsUnchanged() {
        val input = Bitmap.createBitmap(800,1600,Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.WHITE)
        val preview = createOriginalPhotoPreview(input)
        try {
            assertEquals(384,preview.width); assertEquals(768,preview.height)
            assertEquals(800,input.width); assertEquals(1600,input.height)
            assertEquals(Color.WHITE,input.getPixel(1,1))
        } finally { input.recycle(); preview.recycle() }
    }
}
