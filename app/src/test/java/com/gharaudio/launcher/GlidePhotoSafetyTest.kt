package com.gharaudio.launcher

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GlidePhotoSafetyTest {
    @Test fun brightScenesAreNotLifted() {
        assertEquals(0.0, photoLiftStrength(IntArray(30) { 180 }), 0.0)
        val pixel = 0xffbad0ef.toInt()
        assertEquals(pixel, liftPhotoPixel(pixel, photoLiftGains(0.0)))
    }

    @Test fun shadowsKeepNeutralityAndProtectSaturatedHighlights() {
        val out = liftPhotoPixel(0xff303030.toInt(), photoLiftGains(1.0))
        assertEquals(out and 255, out ushr 8 and 255)
        assertEquals(out and 255, out ushr 16 and 255)
        assertTrue((out and 255) > 48)
        assertEquals(0xffff0000.toInt(), liftPhotoPixel(0xffff0000.toInt(), photoLiftGains(1.0)))
    }

    @Test fun uncertainAndMovingFramesFallBack() {
        val w = 48; val h = 48
        val random = Random(18)
        val a = IntArray(w*h) { random.nextInt(30,190) }
        val b = IntArray(w*h) { a[it]+15 }
        assertTrue(safeToStack(a,b,w,h,0 to 0))
        for(y in 12..34) for(x in 12..34) b[y*w+x] += 90
        assertFalse(safeToStack(a,b,w,h,0 to 0))
        assertFalse(safeToStack(IntArray(w*h){80},IntArray(w*h){95},w,h,0 to 0))
        assertFalse(safeToStack(a,a,w,h,8 to 0))
    }

    @Test fun alignmentCooperatesWithCancellation() {
        var called = false
        try {
            findFrameOffset(IntArray(144),IntArray(144),12,12) { called=true; throw InterruptedException() }
            fail("Cancellation callback must interrupt the search")
        } catch (_: InterruptedException) { assertTrue(called) }
    }
}
