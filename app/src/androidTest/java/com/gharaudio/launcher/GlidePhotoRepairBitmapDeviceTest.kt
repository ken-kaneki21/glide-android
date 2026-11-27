package com.gharaudio.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Android bitmap checks; these do not exercise the live Glide UI or document picker. */
@RunWith(AndroidJUnit4::class)
class GlidePhotoRepairBitmapDeviceTest {
    @Test fun landscapeOriginalPreviewRetainsFramingAndSource() {
        val input = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.BLUE)
        val preview = createOriginalPhotoPreview(input)
        try {
            assertEquals(768, preview.width); assertEquals(384, preview.height)
            assertEquals(Color.BLUE, preview.getPixel(767, 383))
            assertEquals(1600, input.width); assertFalse(input.isRecycled)
        } finally { preview.recycle(); input.recycle() }
    }

    @Test fun squareOriginalPreviewIsBoundedWithoutChangingAspectRatio() {
        val input = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.GREEN)
        val preview = createOriginalPhotoPreview(input)
        try {
            assertEquals(768, preview.width); assertEquals(768, preview.height)
            assertEquals(Color.GREEN, preview.getPixel(0, 0))
        } finally { preview.recycle(); input.recycle() }
    }

    @Test fun repairedPixelsCanEncodeAsCopyWithoutChangingSourceBitmap() {
        val input = Bitmap.createBitmap(81, 81, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.WHITE); input.setPixel(40, 40, Color.BLACK)
        val pixels = IntArray(81 * 81)
        input.getPixels(pixels, 0, 81, 0, 0, 81, 81)
        val repaired = repairSmallPhotoMark(pixels, 81, 81, 40, 40, 2)
        val output = Bitmap.createBitmap(repaired, 81, 81, Bitmap.Config.ARGB_8888)
        var decoded: Bitmap? = null
        try {
            val bytes = ByteArrayOutputStream().use {
                assertTrue(output.compress(Bitmap.CompressFormat.JPEG, 94, it)); it.toByteArray()
            }
            decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(81, decoded.width); assertEquals(81, decoded.height)
            assertEquals(Color.BLACK, input.getPixel(40, 40))
            assertEquals(Color.WHITE, output.getPixel(40, 40))
            assertTrue(Color.red(decoded.getPixel(40, 40)) > 245)
        } finally { decoded?.recycle(); output.recycle(); input.recycle() }
    }

    @Test fun unreadableBytesDoNotProduceABitmap() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}
