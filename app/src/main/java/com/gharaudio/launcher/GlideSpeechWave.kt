package com.gharaudio.launcher

import java.io.InputStream
import java.io.ByteArrayOutputStream

internal const val SPEECH_SECONDS_LIMIT = 30
internal data class SpeechPcm(val samples: ShortArray, val sourceRate: Int, val seconds: Double)

/** Strict RIFF PCM16 reader. Compressed WAV/float/RF64 and non-WAV containers are rejected. */
internal fun readSpeechWave(input: InputStream, checkpoint: () -> Unit = {}): SpeechPcm {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkpoint()
        val n = input.read(buffer)
        if (n < 0) break
        require(bytes.size() + n <= 8 * 1024 * 1024) { "Audio file exceeds 8 MiB" }
        bytes.write(buffer, 0, n)
    }
    val data = bytes.toByteArray()
    fun u16(p: Int): Int {
        require(p >= 0 && p + 2 <= data.size) { "Truncated WAV" }
        return (data[p].toInt() and 255) or ((data[p+1].toInt() and 255) shl 8)
    }
    fun u32(p: Int): Long = u16(p).toLong() or (u16(p+2).toLong() shl 16)
    fun tag(p: Int) = String(data, p, 4, Charsets.US_ASCII)
    require(data.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "Choose a PCM16 WAV file" }
    require(u32(4) + 8 == data.size.toLong()) { "Truncated or inconsistent WAV length" }
    var p = 12; var channels = 0; var rate = 0; var start = -1; var count = 0
    while (p + 8 <= data.size) {
        checkpoint()
        val name = tag(p); val length = u32(p+4)
        require(length <= data.size - p - 8) { "Truncated WAV chunk" }
        val n = length.toInt(); val body = p + 8
        when (name) {
            "fmt " -> {
                require(channels == 0 && n >= 16 && u16(body) == 1 && u16(body+14) == 16) { "Only uncompressed PCM16 WAV is supported" }
                channels = u16(body+2)
                require(channels in 1..2) { "Use mono or stereo audio" }
                val declaredRate = u32(body+4)
                require(declaredRate in 8000L..48000L) { "Supported rates: 8–48 kHz" }
                rate = declaredRate.toInt()
                require(u16(body+12) == channels*2 && u32(body+8) == rate.toLong()*channels*2) { "Invalid PCM layout" }
            }
            "data" -> { require(start == -1) { "Multiple audio chunks are unsupported" }; start=body; count=n }
        }
        p = body + n + (n and 1)
        require(p <= data.size) { "Missing WAV padding" }
    }
    require(p == data.size && channels != 0 && start >= 0 && count > 0 && count % (channels*2) == 0) { "Missing or incomplete PCM audio" }
    val frames = count / (channels*2)
    require(frames <= rate*SPEECH_SECONDS_LIMIT) { "Choose a clip of 30 seconds or less" }
    val mono = ShortArray(frames)
    for (i in mono.indices) {
        if (i % 4096 == 0) checkpoint()
        val a = u16(start+i*channels*2).toShort().toInt()
        mono[i] = if (channels == 1) a.toShort() else ((a+u16(start+i*4+2).toShort().toInt())/2).toShort()
    }
    return SpeechPcm(resampleSpeech(mono, rate, checkpoint), rate, frames.toDouble()/rate)
}

/** Bounded linear conversion to the recognizer's 16 kHz mono PCM16 input. */
internal fun resampleSpeech(input: ShortArray, rate: Int, checkpoint: () -> Unit = {}): ShortArray {
    require(rate in 8000..48000 && input.isNotEmpty() && input.size <= rate*SPEECH_SECONDS_LIMIT)
    if (rate == 16000) return input.copyOf()
    val size = (input.size.toLong()*16000/rate).toInt().coerceAtLeast(1)
    return ShortArray(size) { i ->
        if(i % 4096 == 0) checkpoint()
        val position = i.toDouble()*rate/16000
        val left = position.toInt().coerceAtMost(input.lastIndex)
        val right = (left+1).coerceAtMost(input.lastIndex)
        (input[left] + (input[right]-input[left])*(position-left)).toInt().coerceIn(-32768,32767).toShort()
    }
}
