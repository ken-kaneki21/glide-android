package com.gharaudio.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideChaptersTest {
    @Test fun readsTitlesAndConvertsCueFrames() {
        val chapters = parseCueChapters(listOf(
            "FILE \"recording.flac\" WAVE",
            "  TRACK 01 AUDIO",
            "    TITLE \"Introduction\"",
            "    INDEX 01 00:00:00",
            "  TRACK 02 AUDIO",
            "    INDEX 01 01:02:37"
        ), 90_000)
        assertEquals(listOf(GlideChapter("Introduction", 0), GlideChapter("Chapter 2", 62_493)), chapters)
    }

    @Test fun rejectsInvalidAndOutOfRangeIndexes() {
        val chapters = parseCueChapters(listOf(
            "TRACK 01 AUDIO", "INDEX 01 00:00:75",
            "TRACK 02 AUDIO", "INDEX 01 00:60:00",
            "TRACK 03 AUDIO", "INDEX 01 02:00:00"
        ), 120_000)
        assertTrue(chapters.isEmpty())
    }

    @Test fun rejectsMultiFileCue() {
        assertTrue(parseCueChapters(listOf(
            "FILE \"one.flac\" WAVE", "TRACK 01 AUDIO", "INDEX 01 00:00:00",
            "FILE \"two.flac\" WAVE", "TRACK 02 AUDIO", "INDEX 01 00:00:00"
        ), 0).isEmpty())
    }
}
