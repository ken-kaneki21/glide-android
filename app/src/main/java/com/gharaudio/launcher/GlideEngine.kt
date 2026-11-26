package com.gharaudio.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.nio.ByteBuffer
import kotlin.math.pow

/*
 * Glide Engine — Glide's own player (Media3 ExoPlayer).
 *  P1 queue / play next / gapless / lock screen controls
 *  P2 output check: what the phone is playing through + the song's real format; "hi-res (pure)" = float output,
 *     no effects (on the G6 the LG Quad DAC itself is switched on in Android settings › sound with wired headphones)
 *  P3 equalizer (the phone's presets), volume levelling (ReplayGain tags), fade between songs, speed, sleep timer
 *  P4 Sing: lowers the centre-panned voice (works best on studio stereo mixes; never touches the files)
 *  P5 switchover: Glide is offered as the player for audio files anywhere on the phone
 * Audio files are only read, never changed.
 */

internal object GlideEngine {
    const val PKG = "com.gharaudio.launcher"
    private var future: ListenableFuture<MediaController>? = null

    fun enabled(context: Context) =
        context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getBoolean("glide_engine", true)

    private fun withController(context: Context, block: (MediaController) -> Unit) {
        val app = context.applicationContext
        future?.let { f ->
            if (f.isCancelled || (f.isDone && runCatching { !f.get().isConnected }.getOrDefault(true))) future = null
        }
        val f = future ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, GlideEngineService::class.java)))
            .buildAsync().also { future = it }
        f.addListener({
            val mc = runCatching { f.get() }.getOrNull()
            if (mc == null || !mc.isConnected) { future = null; GlideToast.show("glide player could not start"); return@addListener }
            runCatching { block(mc) }.onFailure { GlideToast.show("glide player: ${it.javaClass.simpleName}") }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun item(context: Context, s: Song): MediaItem = MediaItem.Builder()
        .setMediaId(s.id.toString())
        .setUri(GlideStems.sourceUri(context, s))
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
            .setArtworkUri(Uri.parse("content://media/external/audio/albumart/${s.albumId}"))
            .build())
        .build()

    fun playList(context: Context, songs: List<Song>, index: Int, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val start = index.coerceIn(0, songs.lastIndex)
        withController(context) { mc ->
            mc.setMediaItems(songs.map { item(context, it) }, start, longPosition(context, songs[start].id))
            mc.shuffleModeEnabled = shuffle
            mc.prepare()
            mc.play()
        }
        GlideLocalLists.recordLaunch(context, songs[start].id)
    }

    private fun longPrefs(c: Context) = c.getSharedPreferences("glide_long_listening", Context.MODE_PRIVATE)

    fun longSpeed(c: Context, id: Long): Float? =
        longPrefs(c).getFloat("speed_$id", 0f).takeIf { it > 0f }

    fun setLongSpeed(c: Context, id: Long, speed: Float?) {
        val edit = longPrefs(c).edit()
        if (speed == null) edit.remove("speed_$id").remove("position_$id")
        else edit.putFloat("speed_$id", speed)
        edit.apply()
        GlideFx.apply(c)
    }

    private fun longPosition(c: Context, id: Long): Long = longPrefs(c).getLong("position_$id", 0L)

    private fun resumePrefs(context: Context) = context.getSharedPreferences("glide_resume", Context.MODE_PRIVATE)

    fun savedTitle(context: Context): String? = resumePrefs(context).getString("title", null)

    fun savePlayback(context: Context, player: Player) {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val index = player.currentMediaItemIndex
        if (index !in ids.indices || ids.any { it.toLongOrNull() == null }) return
        val songId = ids[index].toLong()
        if (longSpeed(context, songId) != null) {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            longPrefs(context).edit().putLong("position_$songId", position).apply()
        }
        resumePrefs(context).edit()
            .putString("queue", ids.joinToString(","))
            .putString("title", player.mediaMetadata.title?.toString() ?: return)
            .putInt("index", index)
            .putLong("position", player.currentPosition)
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    suspend fun resumeSaved(context: Context) {
        val state = resumePrefs(context).all
        val ids = (state["queue"] as? String)?.split(',') ?: return
        val index = (state["index"] as? Int) ?: return
        val library = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getOrLoadMusicIndex(context).songs.associateBy { it.id.toString() }
        }
        val savedSong = ids.getOrNull(index)?.let(library::get)
        if (savedSong == null) {
            GlideToast.show("Saved track is missing from the library")
            return
        }
        val queue = ids.mapNotNull(library::get)
        val start = ids.take(index).count(library::containsKey)
        withController(context) { controller ->
            controller.setMediaItems(queue.map { item(context, it) }, start, (state["position"] as? Long) ?: 0L)
            controller.shuffleModeEnabled = state["shuffle"] == true
            controller.repeatMode = (state["repeat"] as? Int) ?: Player.REPEAT_MODE_OFF
            controller.prepare()
            controller.play()
        }
    }

    /** P5: a file opened from another app (file manager, browser download, WhatsApp audio…). */
    fun playUri(context: Context, uri: Uri) = withController(context) { mc ->
        mc.setMediaItem(MediaItem.fromUri(uri)); mc.prepare(); mc.play()
    }

    fun playNext(context: Context, song: Song) = withController(context) { mc ->
        if (mc.mediaItemCount == 0) { mc.setMediaItem(item(context, song)); mc.prepare(); mc.play() }
        else mc.addMediaItem((mc.currentMediaItemIndex + 1).coerceAtMost(mc.mediaItemCount), item(context, song))
        GlideToast.show("plays next")
    }

    fun addToQueue(context: Context, song: Song) = withController(context) { mc ->
        if (mc.mediaItemCount == 0) { mc.setMediaItem(item(context, song)); mc.prepare(); mc.play() }
        else mc.addMediaItem(item(context, song))
        GlideToast.show("added to now playing")
    }
}

/**
 * Sing v2. Four-channel interleaved WAV created on the laptop:
 * accompaniment L/R then vocal L/R. Each PCM frame is mixed exactly once,
 * keeping the accompaniment at unity gain while changing ONLY the vocal stem.
 * This processor is used only when a 4-channel stem pack is decoded.
 * Normal music stays on the existing stereo pipeline, unchanged.
 */
@OptIn(UnstableApi::class)
internal class GlideStemMixer : BaseAudioProcessor() {
    @Volatile var amount = 0f  // 0 = full singer, 1 = no singer
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 4)
            AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / 8
        val out = replaceOutputBuffer(frames * 4)
        val voiceGain = 1f - amount.coerceIn(0f, 1f)
        repeat(frames) {
            val instL = inputBuffer.short.toInt()
            val instR = inputBuffer.short.toInt()
            val vocalL = inputBuffer.short.toInt()
            val vocalR = inputBuffer.short.toInt()
            val l = (instL + vocalL * voiceGain).toInt().coerceIn(-32768,32767)
            val r = (instR + vocalR * voiceGain).toInt().coerceIn(-32768,32767)
            out.putShort(l.toShort()); out.putShort(r.toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}

/** Separate stem files are opt-in and private to Glide's app directory. */
internal object GlideStems {
    // Only app-private, disposable laptop-generated caches are considered.
    // Never rewrite the source track or edit its metadata.
    fun stemFile(context: Context, id: Long): java.io.File? =
        context.getExternalFilesDir("stems")?.resolve("$id.wav")

    /** Verify a full four-channel stereo-pair PCM16 WAV before Media3 sees it.
     *  WAV chunks may include LIST/JUNK and need not have a 44-byte header.
     *  The verification is intentionally strict: invalid packs fall back to the original song.
     */
    fun validStem(file: java.io.File?): Boolean {
        if (file == null || !file.isFile || file.length() < 64L) return false
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { f ->
                fun word(): Int { val a=f.readUnsignedByte(); val b=f.readUnsignedByte(); return a or (b shl 8) }
                fun dword(): Long { val a=word(); val b=word(); return ((b.toLong() shl 16) or a.toLong()) and 0xffffffffL }
                val riff = ByteArray(4)
                f.readFully(riff)
                if (!riff.contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) return@use false
                dword() // RIFF length
                f.readFully(riff)
                if (!riff.contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) return@use false
                var fmtOk = false
                var hasData = false
                while (f.filePointer + 8L <= f.length() && f.filePointer < 1_048_576L) {
                    f.readFully(riff)
                    val size = dword()
                    val chunkEnd = f.filePointer + size
                    if (chunkEnd < f.filePointer || chunkEnd > f.length()) return@use false
                    when (String(riff, Charsets.US_ASCII)) {
                        "fmt " -> {
                            if (size < 16) return@use false
                            val format=word(); val channels=word(); val rate=dword()
                            dword() // bytes per second
                            val alignment=word(); val bits=word()
                            // FFmpeg writes WAVE_FORMAT_EXTENSIBLE for four-channel PCM.
                            // Accept only the PCM subtype, not float/compressed encodings.
                            var pcm = format == 1
                            if (format == 0xfffe && size >= 40L) {
                                val cbSize = word()
                                val validBits = word()
                                dword() // channel mask; FFmpeg packs L/R/rear-L/rear-R in order
                                val guid = ByteArray(16)
                                f.readFully(guid)
                                val pcmGuid = byteArrayOf(1,0,0,0,0,0,16,0,-128,0,0,-86,0,56,-101,113)
                                pcm = cbSize >= 22 && validBits == 16 && guid.contentEquals(pcmGuid)
                            }
                            fmtOk = pcm && channels == 4 && rate in 8000L..192000L && alignment == 8 && bits == 16
                        }
                        "data" -> hasData = size >= 8L && size % 8L == 0L
                    }
                    f.seek(chunkEnd + size % 2L)
                    if (fmtOk && hasData) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }

    fun sourceUri(context: Context, song: Song): Uri {
        val prefs = GlideFx.prefs(context)
        if (prefs.getBoolean("engine_pure", false) || !prefs.getBoolean("engine_stems", false)) return song.uri
        val file = stemFile(context, song.id)
        return if (validStem(file)) Uri.fromFile(file) else song.uri
    }

    fun availableCount(context: Context): Int =
        context.getExternalFilesDir("stems")?.listFiles()?.count { it.extension.equals("wav", true) && validStem(it) } ?: 0

    fun isStemPlaying(player: Player?): Boolean =
        player?.currentMediaItem?.localConfiguration?.uri?.path?.endsWith(".wav") == true &&
        player.currentMediaItem?.localConfiguration?.uri?.path?.contains("/stems/") == true
}

/**
 * Instant Sing: blend untouched stereo with a centre-suppressed estimate.
 * 0 = original; 1 = maximum centre reduction. No global output gain changes.
 * This is NOT isolated vocals: centred bass/drums also change. Accurate vocals-only
 * control requires the independently separated vocal + accompaniment stems (Sing v2).
 */
@OptIn(UnstableApi::class)
internal class GlideVocalCut : BaseAudioProcessor() {
    @Volatile var amount = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        // IMPORTANT: the slider controls only the estimated centre contribution,
        // never the AudioTrack or ExoPlayer volume. Preserve the side signal.
        // At 0 the original is bit-identical at the PCM16 stage.
        // Do not zero a near-mono mix (L ~= R): centre cancellation would mute
        // nearly every instrument. The 0.85 ceiling keeps output audible, not
        // instrument-isolated. True isolation is handled by Sing v2 stem mode.
        val attenuation = amount.coerceIn(0f, 1f) * 0.85f
        while (inputBuffer.remaining() >= 4) {
            val l = inputBuffer.short.toFloat()
            val r = inputBuffer.short.toFloat()
            val centre = (l + r) * 0.5f
            val subtraction = centre * attenuation
            out.putShort((l - subtraction).coerceIn(-32768f, 32767f).toInt().toShort())
            out.putShort((r - subtraction).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}

/** FX: mono copy of what the Glide player is about to play, for the visualizer. Lock-free ring. */
internal object GlidePcmTap {
    private const val MASK = 16383
    private val ring = FloatArray(MASK + 1)
    private var written = 0L
    @Volatile private var published = 0L
    @Volatile var captureEnabled = false
    @Volatile var sampleRate = 44100
    @Volatile var lastWrite = 0L
    fun put(v: Float) { ring[(written and MASK.toLong()).toInt()] = v; written++ }
    fun publish() { published = written; lastWrite = android.os.SystemClock.elapsedRealtime() }
    fun active() = captureEnabled && android.os.SystemClock.elapsedRealtime() - lastWrite < 400L
    /** Window ending ~150 ms behind the newest sample (roughly what is coming out of the earphones). */
    fun copyLatest(dest: FloatArray): Boolean {
        val end = published - sampleRate * 15L / 100L
        if (!captureEnabled || end < dest.size) return false
        for (i in dest.indices) dest[i] = ring[((end - dest.size + i) and MASK.toLong()).toInt()]
        return true
    }
}

@OptIn(UnstableApi::class)
internal class GlidePcmTapProcessor : BaseAudioProcessor() {
    private var channels = 2
    private var visualizerStride = 1
    private var visualizerFrame = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount in 1..2) {
            channels = inputAudioFormat.channelCount
            // Only the spectrum's sample rate changes. The playback stream does not.
            visualizerStride = (inputAudioFormat.sampleRate / 48000).coerceAtLeast(1)
            visualizerFrame = 0
            GlidePcmTap.sampleRate = inputAudioFormat.sampleRate / visualizerStride
            inputAudioFormat
        } else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Q1's proven audio forwarding is deliberately unchanged: read/write every
        // PCM16 sample using the same ByteBuffer short operations and byte order.
        // Do not use Q2's bulk ByteBuffer.put(inputBuffer) path here.
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val captureEnabled = GlidePcmTap.captureEnabled
        while (inputBuffer.remaining() >= 2 * channels) {
            val capture = captureEnabled && visualizerFrame == 0
            var sum = 0f
            for (c in 0 until channels) {
                val v = inputBuffer.short
                out.putShort(v)
                if (capture) sum += v
            }
            if (capture) GlidePcmTap.put(sum / channels / 32768f)
            visualizerFrame++
            if (visualizerFrame >= visualizerStride) visualizerFrame = 0
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        if (captureEnabled) GlidePcmTap.publish()
    }
}

/** Live settings for the running player (same process as Glide's UI). Settings call [apply] after saving. */
@OptIn(UnstableApi::class)
internal object GlideFx {
    @Volatile var player: ExoPlayer? = null
    @Volatile var vocal: GlideVocalCut? = null
    @Volatile var stemMixer: GlideStemMixer? = null
    private var eq: Equalizer? = null
    private var rgVolume = 1f
    private val main = Handler(Looper.getMainLooper())
    private var sleepAt = 0L

    fun prefs(c: Context) = c.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE)
    val speeds = listOf(.75f, 1f, 1.25f, 1.5f)
    val sleepChoices = listOf(0, 15, 30, 60, -1, -2)
    private var sleepAfterAlbum = false
    fun hasActiveSleepTimer() = sleepAt > 0L || sleepAfterAlbum

    fun eqState(): Pair<Boolean, Int> = runCatching { val e = eq; if (e == null) false to -1 else e.enabled to e.currentPreset.toInt() }.getOrDefault(false to -1)

    fun presetNames(): List<String> = runCatching {
        val e = eq ?: return emptyList(); (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
    }.getOrDefault(emptyList())

    /** bumps whenever the player starts, stops or changes song, so settings re-read the report and presets */
    val revision = androidx.compose.runtime.mutableIntStateOf(0)

    fun attach(c: Context, p: ExoPlayer, sessionId: Int) {
        player = p; main.post { revision.intValue++ }
        runCatching { eq?.release() }
        apply(c)
    }

    fun detach() {
        main.removeCallbacksAndMessages(null)
        runCatching { eq?.release() }; eq = null; player = null; vocal = null; stemMixer = null
    }

    fun apply(c: Context) {
        val p = player ?: return
        val pr = prefs(c)
        val pure = pr.getBoolean("engine_pure", false)
        val sing = if (pure) 0f else pr.getInt("engine_sing", 0).coerceIn(0,100) / 100f
        val usingStems = GlideStems.isStemPlaying(p)
        stemMixer?.amount = sing
        vocal?.amount = if (usingStems) 0f else sing
        val songId = p.currentMediaItem?.mediaId?.toLongOrNull()
        val longSpeed = songId?.let { GlideEngine.longSpeed(c, it) }
        p.setPlaybackSpeed(longSpeed ?: pr.getFloat("engine_speed", 1f))
        updateVolume(c)
    }

    /** P3 volume levelling: REPLAYGAIN_TRACK_GAIN from the file's own tags (ID3 TXXX / Vorbis comment). */
    fun onTracks(c: Context, tracks: Tracks) {
        var gain: Float? = null
        for (g in tracks.groups) for (i in 0 until g.length) {
            val md = g.getTrackFormat(i).metadata ?: continue
            for (k in 0 until md.length()) {
                val m = Regex("replaygain_track_gain\\D*?(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(md.get(k).toString())
                if (m != null) gain = m.groupValues[1].toFloatOrNull()
            }
        }
        rgVolume = gain?.let { 10f.pow(it / 20f).coerceIn(.05f, 1f) } ?: 1f
        main.post { revision.intValue++ }
        updateVolume(c)
    }

    private fun updateVolume(c: Context) {
        val p = player ?: return
        val level = prefs(c).getBoolean("engine_rg", false) && !prefs(c).getBoolean("engine_pure", false)
        p.volume = if (level) rgVolume else 1f
    }

    private fun albumFadeKey(album: String) = "album_fade:" + album.trim().lowercase(java.util.Locale.ROOT)
    fun albumFade(c: Context, album: String): Boolean =
        prefs(c).getBoolean(albumFadeKey(album), prefs(c).getBoolean("engine_fade", false))

    fun setAlbumFade(c: Context, album: String, enabled: Boolean) {
        if (album.isBlank()) return
        prefs(c).edit().putBoolean(albumFadeKey(album), enabled).apply()
        onPlaying(c, player?.isPlaying == true)
        if (!enabled) updateVolume(c)
    }

    private fun fadeEnabled(c: Context): Boolean =
        !prefs(c).getBoolean("engine_pure", false) &&
            albumFade(c, player?.currentMediaItem?.mediaMetadata?.albumTitle?.toString().orEmpty())

    /** P3 fade between songs: 3 s down at the end, up at the start. Runs only while playing with the option on. */
    private val fadeTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val c = appContext ?: return
            if (!fadeEnabled(c) || !p.isPlaying) { updateVolume(c); return }
            val base = if (prefs(c).getBoolean("engine_rg", false)) rgVolume else 1f
            val dur = p.duration; val pos = p.currentPosition
            val f = when {
                dur > 8000 && dur - pos < 3000 -> ((dur - pos) / 3000f).coerceIn(0f, 1f)
                pos < 1500 -> (pos / 1500f).coerceIn(0f, 1f)
                else -> 1f
            }
            p.volume = base * f
            main.postDelayed(this, 120L)
        }
    }
    @Volatile var appContext: Context? = null
    fun onPlaying(c: Context, playing: Boolean) {
        appContext = c.applicationContext
        main.removeCallbacks(fadeTick)
        if (playing && fadeEnabled(c)) main.post(fadeTick) else updateVolume(c)
    }

    /** P3 sleep timer: minutes, or -1 = pause at the end of this song, 0 = off. */
    fun setSleep(c: Context, minutes: Int) {
        main.removeCallbacks(sleepRun)
        sleepAfterAlbum = minutes == -2
        player?.pauseAtEndOfMediaItems = minutes == -1
        updateAlbumSleep()
        sleepAt = 0L
        if (minutes > 0) { sleepAt = System.currentTimeMillis() + minutes * 60_000L; main.postDelayed(sleepRun, minutes * 60_000L) }
        GlideToast.show(when (minutes) { 0 -> "sleep timer off"; -1 -> "stops after this song"; -2 -> "stops at the end of this album in the queue"; else -> "stops in $minutes min" })
    }
    private val sleepRun = Runnable { player?.pause(); sleepAt = 0L }
    fun onSongChanged() {
        if (sleepAfterAlbum) updateAlbumSleep()
        else player?.pauseAtEndOfMediaItems = false
    }

    fun updateAlbumSleep() {
        if (!sleepAfterAlbum) return
        val p = player ?: return
        val album = p.currentMediaItem?.mediaMetadata?.albumTitle?.toString()
        val next = p.nextMediaItemIndex
        p.pauseAtEndOfMediaItems = album.isNullOrBlank() || next !in 0 until p.mediaItemCount ||
            p.getMediaItemAt(next).mediaMetadata.albumTitle?.toString() != album
    }

    /** P2: what the phone is playing through and what the song really is. */
    fun outputReport(c: Context): String {
        val am = c.getSystemService(AudioManager::class.java)
        val outs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val wired = outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        val bt = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val usb = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
        val route = when {
            usb != null -> "usb: ${usb.productName}"
            wired -> "wired headphones (quad dac works here when it's on in android settings › sound › quad dac)"
            bt != null -> "bluetooth: ${bt.productName} (quad dac is not used over bluetooth)"
            else -> "phone speaker (quad dac is not used)"
        }
        val p = player
        val f = p?.audioFormat ?: p?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)
        val song = if (p == null) "glide player not started yet (play a song in glide)" else if (f == null) "nothing playing in glide player" else buildString {
            append(f.sampleMimeType?.substringAfter('/') ?: "audio")
            if (f.sampleRate > 0) append(" · ${f.sampleRate / 1000.0} khz")
            when (f.pcmEncoding) {
                C.ENCODING_PCM_24BIT -> append(" · 24-bit source PCM")
                C.ENCODING_PCM_32BIT -> append(" · 32-bit source PCM")
                C.ENCODING_PCM_FLOAT -> append(" · floating-point source PCM")
            }
            if (f.bitrate > 0) append(" · ${f.bitrate / 1000} kbps")
            if (f.channelCount > 0) append(" · ${f.channelCount} ch")
        }
        val mode = if (prefs(c).getBoolean("engine_pure", false)) "hi-res (pure): float output requested, no effects; actual HAL rate must be checked separately" else "normal: 16-bit with effects"
        val stems = if (GlideStems.isStemPlaying(p)) "sing: four-channel stem mix active" else "sing: standard audio path (centre reduction is not vocal isolation)"
        return "output: $route\nsong: $song\nmode: $mode\n$stems"
    }
}

@OptIn(UnstableApi::class)
class GlideEngineService : MediaSessionService() {
    private val resumeHandler = Handler(Looper.getMainLooper())
    private var overlap: GlideOverlap? = null
    private val saveResume = object : Runnable {
        override fun run() {
            val player = session?.player?.takeIf { it.isPlaying } ?: return
            GlideEngine.savePlayback(this@GlideEngineService, player)
            resumeHandler.postDelayed(this, 5000L)
        }
    }
    private var session: MediaSession? = null
    // The player also enables setHandleAudioBecomingNoisy(true). Keep a service-
    // owned receiver as a defensive Android 9 / LG fallback when activity is gone.
    private val displayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> GlidePcmTap.captureEnabled = false
                Intent.ACTION_SCREEN_ON -> GlidePcmTap.captureEnabled = true
            }
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                session?.player?.let { if (it.isPlaying) it.pause() }
            }
        }
    }
    private var noisyRegistered = false

    override fun onCreate() {
        super.onCreate()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        noisyRegistered = true
        GlidePcmTap.captureEnabled = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        ContextCompat.registerReceiver(this, displayReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val prefs = GlideFx.prefs(this)
        val pure = prefs.getBoolean("engine_pure", false)
        val vocal = GlideVocalCut().also { GlideFx.vocal = it }
        val stemMixer = GlideStemMixer().also { GlideFx.stemMixer = it }
        val sink = DefaultAudioSink.Builder(this)
            .setEnableFloatOutput(pure)
            .setAudioProcessors(if (pure) emptyArray<AudioProcessor>() else arrayOf<AudioProcessor>(stemMixer, vocal, GlidePcmTapProcessor()))
            .build()
        val ctx = this
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val sessionId = (getSystemService(AudioManager::class.java)?.generateAudioSessionId() ?: 0)
        if (sessionId > 0) player.setAudioSessionId(sessionId)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED)) GlideFx.updateAlbumSleep()
                if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                    GlideEngine.savePlayback(ctx, player)
                }
            }

            override fun onTracksChanged(tracks: Tracks) = GlideFx.onTracks(ctx, tracks)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                GlideFx.onPlaying(ctx, isPlaying)
                resumeHandler.removeCallbacks(saveResume)
                if (isPlaying) resumeHandler.postDelayed(saveResume, 5000L)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                GlideFx.onSongChanged()
                GlideFx.apply(ctx)
                GlideFx.onPlaying(ctx, player.isPlaying)
            }
        })
        GlideFx.attach(this, player, sessionId)
        overlap = GlideOverlap(this, player)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(displayReceiver)
        GlidePcmTap.captureEnabled = false
        if (noisyRegistered) { unregisterReceiver(noisyReceiver); noisyRegistered = false }
        overlap?.close()
        overlap = null
        resumeHandler.removeCallbacks(saveResume)
        session?.player?.let { GlideEngine.savePlayback(this, it) }
        GlideFx.detach()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** P5: "open with Glide" for audio files from any app; plays in the Glide player and closes at once. */
class GlidePlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { GlideEngine.playUri(this, it) }
        finish()
        overridePendingTransition(0, 0)
    }
}
