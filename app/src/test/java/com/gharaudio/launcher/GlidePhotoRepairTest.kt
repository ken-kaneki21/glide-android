package com.gharaudio.launcher

import org.junit.Assert.*
import org.junit.Test

class GlidePhotoRepairTest {
    @Test fun removesSmallMarkOnUniformBackground() {
        val original=IntArray(21*21){0xff8090a0.toInt()}
        for(y in 9..11)for(x in 9..11)original[y*21+x]=0xff000000.toInt()
        val result=repairSmallPhotoMark(original,21,21,10,10,2)
        assertTrue(result.all { it==0xff8090a0.toInt() })
        assertEquals(0xff000000.toInt(),original[10*21+10])
    }
    @Test fun preservesEveryUnmarkedPixelAndDoesNotAliasInput() {
        val original=IntArray(31*31){ i -> 0xff000000.toInt() or (i*137 and 0xffffff) }
        val saved=original.copyOf()
        val result=repairSmallPhotoMark(original,31,31,15,15,2)
        assertNotSame(original,result);assertArrayEquals(saved,original)
        for(y in 0..30)for(x in 0..30)if((x-15)*(x-15)+(y-15)*(y-15)>4)
            assertEquals(original[y*31+x],result[y*31+x])
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsExcessiveMask() {
        repairSmallPhotoMark(IntArray(21*21),21,21,10,10,12)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsOutOfBoundsSelection() {
        repairSmallPhotoMark(IntArray(21*21),21,21,21,10,2)
    }
    @Test fun cancellationLeavesOriginalUntouched() {
        val original=IntArray(21*21){0xff304050.toInt()};val saved=original.copyOf()
        var calls=0;var cancelled=false
        try {repairSmallPhotoMark(original,21,21,10,10,2){if(++calls==8)throw IllegalStateException("cancelled")}}
        catch(expected:IllegalStateException){cancelled=true}
        assertTrue(cancelled);assertArrayEquals(saved,original)
    }
}
