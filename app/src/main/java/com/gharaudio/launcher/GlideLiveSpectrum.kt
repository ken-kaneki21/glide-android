package com.gharaudio.launcher

import android.content.ContentUris
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// v1.10e step 0.7: continuous local-file analysis.
// One decoder per track decodes forward into a mono ring buffer; an FFT runs ~30x/s on the
// 1024 samples at the current playback position; the UI smooths toward the latest analysis on
// every display frame and only invalidates the Canvas draw (no recomposition per update).

private const val FFT_SIZE = 1024
private const val RING_SIZE = 32768
// v1.10e 0.7b: 20 analyses/s is plenty with per-frame smoothing and halves analysis CPU.
private const val ANALYSIS_INTERVAL_MS = 50L
// Start analysis only after Now Playing has stayed on screen this long (swiping past costs nothing).
private const val START_DELAY_MS = 1000L
private const val BIN_COUNT = 32
private const val WAVE_POINTS = 128
private val hannWindow = FloatArray(FFT_SIZE) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat() }

private data class TrackMatch(val uri: Uri?, val message: String)

/** v1.10e 0.7b: one background-priority thread for matching, decoding and FFT, so the UI thread
 *  and RenderThread always win the CPU on the 4-core Snapdragon 821. */
private val analysisDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, "GlideSpectrum").apply { isDaemon = true }
}.asCoroutineDispatcher()

/** Latest analysed frame. Written by the decoder thread, read by the frame loop. */
internal class SpectrumTarget {
    @Volatile var bins: FloatArray = FloatArray(BIN_COUNT)
    @Volatile var wave: FloatArray = FloatArray(WAVE_POINTS)
    fun clear() { bins = FloatArray(BIN_COUNT); wave = FloatArray(WAVE_POINTS) }
}

/** Decodes the matched local file forward once, keeping recent mono PCM in a ring buffer. */
private class PcmStream(context: Context, uri: Uri) : java.io.Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    var sampleRate = 44100
        private set
    private var channels = 2
    private var pcmFloat = false
    private val ring = FloatArray(RING_SIZE)
    private var written = 0L
    /** Presentation time (us) just after the newest decoded sample; -1 when empty. */
    var endUs = -1L
        private set
    private var inputDone = false
    private var ended = false
    private val info = MediaCodec.BufferInfo()

    init {
        extractor.setDataSource(context, uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; format = f; break }
        }
        if (track < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("No audio track")
        }
        extractor.selectTrack(track)
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release(); throw IllegalStateException("No MIME type")
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
        } catch (e: Exception) {
            extractor.release(); throw e
        }
    }

    /** Duration (us) currently held in the ring. */
    val spanUs: Long
        get() = if (written <= 0L) 0L else minOf(written, RING_SIZE.toLong()) * 1_000_000L / sampleRate

    fun seek(targetUs: Long) {
        extractor.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        ended = false
        written = 0L
        endUs = -1L
    }

    /** Decode forward until the newest sample reaches [untilUs]. Work per call is bounded. */
    fun decodeUntil(untilUs: Long) {
        var guard = 0
        while (!ended && endUs < untilUs && guard++ < 96) {
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(2000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)
                    if (buf != null) {
                        buf.clear()
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 2000)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (out >= 0) {
                val buf = codec.getOutputBuffer(out)
                if (buf != null && info.size > 0) append(buf, info.offset, info.size, info.presentationTimeUs)
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(out, false)
                if (eos) ended = true
            } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                return
            }
        }
    }

    private fun append(buf: ByteBuffer, offset: Int, size: Int, timeUs: Long) {
        val ch = channels.coerceIn(1, 8)
        val data = buf.duplicate().order(ByteOrder.nativeOrder())
        data.position(offset)
        data.limit(offset + size)
        var frames = 0
        if (pcmFloat) {
            val fb = data.asFloatBuffer()
            while (fb.remaining() >= ch) {
                var mono = 0f
                for (c in 0 until ch) mono += fb.get()
                put(mono / ch); frames++
            }
        } else {
            val sb = data.asShortBuffer()
            while (sb.remaining() >= ch) {
                var mono = 0f
                for (c in 0 until ch) mono += sb.get() / 32768f
                put(mono / ch); frames++
            }
        }
        endUs = timeUs + frames * 1_000_000L / sampleRate
    }

    private fun put(v: Float) {
        ring[(written % RING_SIZE).toInt()] = v
        written++
    }

    /** Copies FFT_SIZE samples ending at [atUs] into [dest]. False if not in the ring. */
    fun window(atUs: Long, dest: FloatArray): Boolean {
        if (written < FFT_SIZE || endUs < 0L) return false
        val behind = (endUs - atUs) * sampleRate / 1_000_000L
        if (behind < 0L) return false
        val startIdx = written - behind - FFT_SIZE
        if (startIdx < 0L || startIdx < written - RING_SIZE) return false
        for (i in 0 until FFT_SIZE) dest[i] = ring[((startIdx + i) % RING_SIZE).toInt()]
        return true
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { extractor.release() } catch (_: Exception) { }
    }
}

/** Radix-2 FFT on windowed samples, mapped to 32 log-spaced bins plus a 128-point waveform. */
private fun analyse(samples: FloatArray, re: DoubleArray, im: DoubleArray, sampleRate: Int, target: SpectrumTarget) {
    val n = FFT_SIZE
    for (i in 0 until n) { re[i] = (samples[i] * hannWindow[i]).toDouble(); im[i] = 0.0 }
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) {
            val x = re[i]; re[i] = re[j]; re[j] = x
            val y = im[i]; im[i] = im[j]; im[j] = y
        }
    }
    var len = 2
    while (len <= n) {
        val angle = -2.0 * PI / len
        val wlenR = cos(angle); val wlenI = sin(angle)
        var a = 0
        while (a < n) {
            var wr = 1.0; var wi = 0.0
            val half = len / 2
            for (k in 0 until half) {
                val u = a + k; val v = u + half
                val vr = re[v] * wr - im[v] * wi
                val vi = re[v] * wi + im[v] * wr
                re[v] = re[u] - vr; im[v] = im[u] - vi
                re[u] += vr; im[u] += vi
                val twr = wr * wlenR - wi * wlenI
                wi = wr * wlenI + wi * wlenR; wr = twr
            }
            a += len
        }
        len *= 2
    }
    val bins = FloatArray(BIN_COUNT)
    val maxBin = n / 2 - 1
    for (b in 0 until BIN_COUNT) {
        val low = (1 + (maxBin - 1) * (b / 32.0) * (b / 32.0)).toInt().coerceIn(1, maxBin)
        val high = (1 + (maxBin - 1) * ((b + 1) / 32.0) * ((b + 1) / 32.0)).toInt().coerceIn(low, maxBin)
        var peak = 0.0
        for (k in low..high) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            if (m > peak) peak = m
        }
        bins[b] = (ln(1.0 + peak) / 5.3).toFloat().coerceIn(0f, 1f)
    }
    val wave = FloatArray(WAVE_POINTS) { i -> samples[(i * (n - 1)) / (WAVE_POINTS - 1)].coerceIn(-1f, 1f) }
    target.bins = bins
    target.wave = wave
}

/** One display-priority thread shared by every visualizer surface. */
private val spectrumDrawThread: HandlerThread by lazy {
    HandlerThread("GlideSpectrumDraw", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
}

/**
 * v1.10e 1.6: the visualizer draws into its own SurfaceView on its own thread.
 * Measured on the G6 (1.4d/1.5 probes): while the visualizer drew through Compose, every update
 * redrew the WHOLE window - about 60 frames/s and ~45% of a core on Now Playing, whatever the
 * pacing. A separate surface is composited by SurfaceFlinger, so visualizer frames no longer
 * touch the app window, its main thread or its RenderThread at all.
 * Paced by this thread's own Choreographer (vsync-aligned), 30 fps by default or 60 fps.
 */
internal class SpectrumRenderer(private val density: Float) {
    val target = SpectrumTarget()
    @Volatile private var enabled = false
    @Volatile private var mode = 0
    @Volatile private var fps60 = false
    @Volatile private var accent = 0
    @Volatile private var warm = 0
    @Volatile private var needsDraw = true

    private val handler = Handler(spectrumDrawThread.looper)
    private val lock = Any()
    private var holder: SurfaceHolder? = null // guarded by lock
    private var width = 0
    private var height = 0

    // Render-thread only.
    private var running = false
    private var lastNs = 0L
    private var frameCount = 0L
    private val bins = FloatArray(BIN_COUNT)
    private val wave = FloatArray(WAVE_POINTS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() } // created on the render thread
    private val callback = Choreographer.FrameCallback { onFrame(it) }
    private val resume = Runnable { choreographer.postFrameCallback(callback) }

    fun update(enabled: Boolean, mode: Int, fps60: Boolean, accent: Int, warm: Int) {
        if (mode != this.mode || accent != this.accent || warm != this.warm) needsDraw = true
        this.enabled = enabled; this.mode = mode; this.fps60 = fps60; this.accent = accent; this.warm = warm
        kick()
    }

    fun attach(h: SurfaceHolder, w: Int, hgt: Int) {
        synchronized(lock) { holder = h; width = w; height = hgt }
        needsDraw = true
        kick()
    }

    /** Returns only when no draw is in flight, so the surface can be destroyed safely. */
    fun detach() { synchronized(lock) { holder = null } }

    private fun kick() {
        handler.post {
            if (!running) {
                running = true
                lastNs = 0L
                handler.removeCallbacks(resume)
                choreographer.postFrameCallback(callback)
            }
        }
    }

    private fun onFrame(frameNs: Long) {
        if (synchronized(lock) { holder == null }) { running = false; return }
        frameCount++
        if (!fps60 && frameCount % 2L != 0L) { choreographer.postFrameCallback(callback); return }
        val steps = if (lastNs == 0L) 1f else ((frameNs - lastNs) / 16_666_667f).coerceIn(1f, 4f)
        lastNs = frameNs
        val attack = 1f - 0.4f.pow(steps)
        val release = 1f - 0.84f.pow(steps)
        val follow = 1f - 0.55f.pow(steps)
        val on = enabled
        val tb = target.bins
        val tw = target.wave
        var moving = false
        for (i in 0 until BIN_COUNT) {
            val t = if (on) tb[i] else 0f
            val c = bins[i]
            val next = if (t > c) c + (t - c) * attack else c + (t - c) * release
            if (abs(next - c) > 0.0005f) moving = true
            bins[i] = next
        }
        for (i in 0 until WAVE_POINTS) {
            val t = if (on) tw[i] else 0f
            val c = wave[i]
            val next = c + (t - c) * follow
            if (abs(next - c) > 0.0005f) moving = true
            wave[i] = next
        }
        if (moving || needsDraw) { needsDraw = false; draw() }
        when {
            moving -> choreographer.postFrameCallback(callback)
            on -> { lastNs = 0L; handler.postDelayed(resume, 120L) } // decoder starting / silence
            else -> running = false // settled at rest: no more frames until something changes
        }
    }

    private fun draw() {
        synchronized(lock) {
            val h = holder ?: return
            // v1.10e 1.6b: software canvas. 1.6 measured lockHardwareCanvas at ~30% of a core for
            // 30 fps (it runs a full HWUI frame on the app's shared RenderThread every time).
            // A few dozen rects/lines on this small surface rasterise on this thread in ~1 ms.
            val canvas = try { h.lockCanvas() } catch (e: Exception) { null } ?: return
            try {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                render(canvas, width.toFloat(), height.toFloat())
            } finally {
                h.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun render(c: android.graphics.Canvas, w: Float, h: Float) {
        val d = density
        val a = accent
        val wm = warm
        paint.alpha = 255
        when (mode) {
            0 -> {
                paint.style = Paint.Style.FILL
                val gap = 3f * d
                val bar = ((w - gap * 31) / 32f).coerceAtLeast(1f)
                for (i in 0 until BIN_COUNT) {
                    val bh = max(2f * d, h * bins[i])
                    paint.color = if (i % 7 == 0) wm else a
                    val x = i * (bar + gap)
                    c.drawRect(x, h - bh, x + bar, h, paint)
                }
            }
            1 -> {
                val cy = h / 2f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f * d
                paint.color = a
                paint.alpha = 64
                c.drawLine(0f, cy, w, cy, paint)
                path.reset()
                for (i in 0 until WAVE_POINTS) {
                    val x = w * i / (WAVE_POINTS - 1f)
                    val y = cy - wave[i] * (h * 0.43f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.alpha = 255
                paint.strokeWidth = 2f * d
                c.drawPath(path, paint)
            }
            2 -> {
                val cx = w / 2f
                val cy = h / 2f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                val base = min(h, w) * 0.22f
                val length = min(h, w) * 0.26f
                for (i in 0 until 64) {
                    val angle = 2f * PI.toFloat() * i / 64f - PI.toFloat() / 2f
                    val reach = base + max(2f * d, bins[i % BIN_COUNT] * length)
                    paint.color = if (i % 9 == 0) wm else a
                    c.drawLine(cx + cos(angle) * base, cy + sin(angle) * base,
                        cx + cos(angle) * reach, cy + sin(angle) * reach, paint)
                }
            }
            else -> {
                paint.style = Paint.Style.FILL
                val columns = 16
                val rows = 4
                val dx = w / columns
                val dy = h / rows
                for (r in 0 until rows) for (col in 0 until columns) {
                    val amp = bins[(col * 2 + r * 7) % BIN_COUNT]
                    val radius = max(1.5f * d, (1.2f + amp * 8f) * d)
                    paint.color = if ((r + col) % 7 == 0) wm else a
                    paint.alpha = ((0.3f + amp * 0.7f).coerceIn(0f, 1f) * 255).toInt()
                    c.drawCircle(dx * (col + 0.5f), dy * (r + 0.5f), radius, paint)
                }
            }
        }
    }
}

/**
 * Now Playing frame clock (v1.10e 1.4). Since 1.6 the visualizer draws on its own surface and
 * never ticks this, so breathing art runs its own ~12 updates/s. Kept so another Compose
 * animation can share frames later instead of adding its own.
 */
internal object NowPlayingFrameClock {
    val tick = mutableIntStateOf(0)
    @Volatile var lastFrameMs = 0L
    /** True while the visualizer is producing frames; other animations then follow [tick]. */
    fun driving(now: Long = SystemClock.elapsedRealtime()): Boolean = now - lastFrameMs < 150L
    fun frame() { lastFrameMs = SystemClock.elapsedRealtime(); tick.intValue++ }
}

/**
 * Diagnostics only (v1.10e 1.4-diag): switches parts of Now Playing off so the probe can find what
 * keeps redrawing. Set from adb (shell holds DUMP):
 *   adb shell am broadcast -a com.gharaudio.launcher.DIAG --ei flags N
 * 1 visualizer frame loop, 2 breathing, 4 progress tick, 8 whole visualizer, 16 progress bar,
 * 32 album art, 64 backdrop image. 0 = normal. Not persisted; resets when the app restarts.
 */
internal object GlideDiag {
    val flags = mutableIntStateOf(0)
    fun has(bit: Int): Boolean = flags.intValue and bit != 0
}

/** Visualizer mode and frame rate, shared by the visualizer and the Now Playing "player" pivot. */
internal object VisualizerSettings {
    val labels = listOf("bars", "wave", "radial", "pulse")
    val mode = mutableIntStateOf(0)
    val fps60 = mutableStateOf(false)
    val revision = mutableIntStateOf(0)
    val status = mutableStateOf("file analysis idle")
    private var loaded = false
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("glide_visualizer", 0)
    fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        mode.intValue = p.getInt("mode", 0).coerceIn(0, 3)
        fps60.value = p.getBoolean("fps60", false)
        loaded = true
    }
    fun setMode(context: Context, value: Int) { mode.intValue = value; prefs(context).edit().putInt("mode", value).apply() }
    fun setFps60(context: Context, value: Boolean) { fps60.value = value; prefs(context).edit().putBoolean("fps60", value).apply() }
}

/** Decodes the local music file; does not use the microphone, global mix or PCM from external players. */
@Composable
internal fun GlideLiveSpectrum(enabled: Boolean, title: String, artist: String, durationMs: Long, positionMs: () -> Long,
                               compact: Boolean = false) {
    val context = LocalContext.current
    remember(context) { VisualizerSettings.load(context); 0 }
    val mode = VisualizerSettings.mode.intValue
    var status by VisualizerSettings.status
    val revision = VisualizerSettings.revision.intValue
    // v1.10e 1.2/1.4: visualizer frame rate. 30 fps (default) halves Now Playing redraws;
    // 60 fps is one tap away.
    val fps60 = VisualizerSettings.fps60.value
    val positionProvider by rememberUpdatedState(positionMs)
    val density = LocalContext.current.resources.displayMetrics.density
    val renderer = remember { SpectrumRenderer(density) }
    val target = renderer.target
    val accentArgb = GlideMetroStyle.accent.toArgb()
    val warmArgb = GlideMetroStyle.coral.toArgb()
    val drawOn = enabled && !GlideDiag.has(1)
    SideEffect { renderer.update(drawOn, mode, fps60, accentArgb, warmArgb) }
    DisposableEffect(renderer) { onDispose { renderer.detach() } }

    // Analysis: one decoder for the whole track, FFT about 30 times per second.
    LaunchedEffect(enabled, title, artist, durationMs, revision) {
        target.clear()
        if (!enabled) { status = "PAUSED"; return@LaunchedEffect }
        if (durationMs <= 0 || title.isBlank() || artist.isBlank()) {
            status = "No track identity or playback duration"; return@LaunchedEffect
        }
        // Debounce: a swipe that only passes over Now Playing cancels this before any work starts.
        delay(START_DELAY_MS)
        // FX: when Glide's own player is playing, use the sound it already decoded (no second decoder)
        if (GlidePcmTap.active()) { runTapLoop(target) { status = it }; return@LaunchedEffect }
        val match = withContext(analysisDispatcher) { matchLocalTrack(context, title, artist, durationMs) }
        val uri = match.uri
        if (uri == null) { status = match.message; return@LaunchedEffect }
        status = "Decoding local audio..."
        withContext(analysisDispatcher) {
            var stream: PcmStream? = null
            val samples = FloatArray(FFT_SIZE)
            val re = DoubleArray(FFT_SIZE)
            val im = DoubleArray(FFT_SIZE)
            try {
                val s = PcmStream(context, uri)
                stream = s
                var positioned = false
                var announced = false
                while (isActive) {
                    if (GlidePcmTap.active()) break // Glide player took over: switch to its PCM below
                    val atUs = positionProvider() * 1000L
                    val outOfRange = s.endUs >= 0L &&
                        (atUs > s.endUs + 1_500_000L || atUs < s.endUs - s.spanUs + 30_000L)
                    if (!positioned || outOfRange) {
                        s.seek(atUs - 60_000L)
                        positioned = true
                    }
                    s.decodeUntil(atUs + 120_000L)
                    if (s.window(atUs, samples)) {
                        analyse(samples, re, im, s.sampleRate, target)
                        if (!announced) { announced = true; status = "LOCAL FILE FFT (not DAC output)" }
                    }
                    delay(ANALYSIS_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                target.clear()
                status = "File analysis: ${e.javaClass.simpleName}: ${e.message ?: "unavailable"}"
            } finally {
                stream?.close()
            }
        }
        if (GlidePcmTap.active()) runTapLoop(target) { status = it }
    }

    val surface: @Composable (Modifier) -> Unit = { m ->
        // v1.10e 1.6: own surface + own thread (see SpectrumRenderer). Z-ordered on top of the
        // window and translucent, so the Now Playing background shows through as before.
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    setZOrderOnTop(true)
                    holder.setFormat(PixelFormat.TRANSLUCENT)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {}
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) { renderer.attach(h, w, hgt) }
                        override fun surfaceDestroyed(h: SurfaceHolder) { renderer.detach() }
                    })
                }
            },
            modifier = m
        )
    }
    if (compact) {
        // Zune Now Playing (v1.11): just the visualizer; its settings live in the "player" pivot.
        surface(Modifier.fillMaxWidth().height(64.dp))
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VisualizerSettings.labels.forEachIndexed { index, label ->
                Text(label.uppercase(), color = if (mode == index) GlideMetroStyle.accent else GlideMetroStyle.secondary,
                    fontSize = 10.sp, modifier = Modifier.weight(1f).clickable {
                        VisualizerSettings.setMode(context, index)
                    }.padding(vertical = 8.dp))
            }
        }
        surface(Modifier.fillMaxWidth().height(128.dp).padding(top = 10.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(status, color = if (status.startsWith("LOCAL FILE")) GlideMetroStyle.accent else GlideMetroStyle.secondary,
                fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(if (fps60) "60 FPS" else "30 FPS", color = GlideMetroStyle.secondary, fontSize = 10.sp,
                modifier = Modifier.clickable {
                    VisualizerSettings.setFps60(context, !fps60)
                }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
            Text("RECHECK", color = GlideMetroStyle.accent, fontSize = 10.sp,
                modifier = Modifier.clickable { VisualizerSettings.revision.intValue++ }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        }
        Text("Real local-file FFT; synced to playback position. No audio recording or simulated data.",
            color = GlideMetroStyle.secondary, fontSize = 9.sp)
    }
}

private fun canonical(value: String): String = value.trim().lowercase(java.util.Locale.ROOT)
    .replace(Regex("\\s+"), " ")

private fun matchLocalTrack(context: Context, title: String, artist: String, durationMs: Long): TrackMatch {
    val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
    val candidates = mutableListOf<Long>()
    return try {
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Audio.Media.TITLE} = ?", arrayOf(title), null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                if (canonical(cursor.getString(titleCol) ?: "") != canonical(title)) continue
                if (canonical(cursor.getString(artistCol) ?: "") != canonical(artist)) continue
                if (abs(cursor.getLong(durationCol) - durationMs) > 2500L) continue
                candidates += cursor.getLong(idCol)
                if (candidates.size > 1) break
            }
        }
        when (candidates.size) {
            0 -> TrackMatch(null, "No matching local file: exact title/artist/duration required")
            1 -> TrackMatch(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, candidates[0]), "")
            else -> TrackMatch(null, "Multiple matching files; cannot safely identify playback")
        }
    } catch (e: SecurityException) {
        TrackMatch(null, "Storage access denied: ${e.message ?: "check music permission"}")
    } catch (e: Exception) {
        TrackMatch(null, "MediaStore unavailable: ${e.javaClass.simpleName}")
    }
}


/** FX: FFT on the Glide player's own PCM (see GlidePcmTap), about 20 times a second, on the background thread. */
private suspend fun runTapLoop(target: SpectrumTarget, setStatus: (String) -> Unit) {
    setStatus("GLIDE PLAYER PCM (no second decode)")
    withContext(analysisDispatcher) {
        val samples = FloatArray(FFT_SIZE)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        while (isActive) {
            if (!GlidePcmTap.active()) { target.clear(); delay(250L); continue }
            if (GlidePcmTap.copyLatest(samples)) analyse(samples, re, im, GlidePcmTap.sampleRate, target)
            delay(ANALYSIS_INTERVAL_MS)
        }
    }
}
