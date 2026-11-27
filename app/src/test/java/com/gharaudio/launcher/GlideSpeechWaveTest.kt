package com.gharaudio.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class GlideSpeechWaveTest {
    private fun wave(rate:Int=16000,channels:Int=1,samples:ShortArray=ShortArray(1600){1000}):ByteArray {
        val data=ByteBuffer.allocate(44+samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray());data.putInt(36+samples.size*2);data.put("WAVEfmt ".toByteArray())
        data.putInt(16);data.putShort(1);data.putShort(channels.toShort());data.putInt(rate)
        data.putInt(rate*channels*2);data.putShort((channels*2).toShort());data.putShort(16)
        data.put("data".toByteArray());data.putInt(samples.size*2);samples.forEach(data::putShort)
        return data.array()
    }
    @Test fun mono16kIsDecodedExactlyWithoutAliasing() {
        val samples=shortArrayOf(-32768,-1,0,1,32767)
        val result=readSpeechWave(ByteArrayInputStream(wave(samples=samples)))
        assertArrayEquals(samples,result.samples);assertNotSame(samples,result.samples)
        assertEquals(5.0/16000,result.seconds,0.0000001)
    }
    @Test fun stereoDownmixDoesNotOverflow() {
        val samples=shortArrayOf(32767,32767,-32768,-32768,20000,-20000)
        assertArrayEquals(shortArrayOf(32767,-32768,0),readSpeechWave(ByteArrayInputStream(wave(channels=2,samples=samples))).samples)
    }
    @Test fun rate48kDurationAndConstantLevelAreRetained() {
        val result=readSpeechWave(ByteArrayInputStream(wave(rate=48000,samples=ShortArray(4800){1234})))
        assertEquals(1600,result.samples.size);assertTrue(result.samples.all{it==1234.toShort()})
        assertEquals(0.1,result.seconds,0.00001)
    }
    @Test fun rate8kUpsamplingHasExpectedLengthAndFiniteAmplitude() {
        val result=readSpeechWave(ByteArrayInputStream(wave(rate=8000,samples=shortArrayOf(-100,100))))
        assertArrayEquals(shortArrayOf(-100,0,100,100),result.samples)
    }
    @Test fun exact30SecondLimitIsAccepted() {
        assertEquals(480000,readSpeechWave(ByteArrayInputStream(wave(samples=ShortArray(480000)))).samples.size)
    }
    @Test(expected=IllegalArgumentException::class) fun over30SecondsIsRejected() {
        readSpeechWave(ByteArrayInputStream(wave(samples=ShortArray(480001))))
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedInputIsRejected() {
        readSpeechWave(ByteArrayInputStream(wave().dropLast(1).toByteArray()))
    }
    @Test(expected=IllegalArgumentException::class) fun compressedWavIsRejected() {
        val bytes=wave();bytes[20]=3;readSpeechWave(ByteArrayInputStream(bytes))
    }
    @Test(expected=IllegalArgumentException::class) fun nonWavIsRejected() {
        readSpeechWave(ByteArrayInputStream("not audio".toByteArray()))
    }
    @Test fun silenceRemainsSilence() {
        assertTrue(readSpeechWave(ByteArrayInputStream(wave(samples=ShortArray(1600)))).samples.all{it==0.toShort()})
    }
    @Test fun cancellationPropagatesWithoutChangingInput() {
        val samples=ShortArray(48000){300};val before=samples.copyOf();var checks=0
        try {resampleSpeech(samples,48000){if(++checks==2)throw CancellationException()};fail("Cancellation required")}
        catch(expected:CancellationException){}
        assertArrayEquals(before,samples)
    }
    @Test(expected=IllegalArgumentException::class) fun excessiveEncodedBytesAreRejected() {
        readSpeechWave(ByteArrayInputStream(ByteArray(8*1024*1024+1)))
    }
}
