package com.gharaudio.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/*
 * F3: Zune / Windows Phone feedback.
 *  Haptics — a crisp tap on press, a double pulse when a long-press menu opens. Uses the view's haptic engine
 *  and ignores the system "touch vibration" switch (that switch is why nothing buzzed before).
 *  Sounds — tiny tones made in code (no audio files): a soft ~320 Hz matte tick for pivot snaps and letter jumps,
 *  a muted ~450 Hz tap for keys, and a two-tone chime for lock / unlock. Quiet, never while the ringer is silent
 *  or on vibrate. Settings: "touch vibration" and "ui sounds".
 */
internal object GlideFeedback {
    private const val RATE = 44100
    @Volatile private var tracks: Map<String, AudioTrack>? = null

    private fun prefs(c: Context) = c.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE)
    fun hapticsOn(c: Context) = prefs(c).getBoolean("ui_haptics", true)
    fun soundsOn(c: Context) = prefs(c).getBoolean("ui_sounds", true)

    private const val FLAGS = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING

    /** 15 ms-style tap on press. */
    fun tap(view: View) {
        if (hapticsOn(view.context)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, FLAGS)
    }

    /** Double pulse when a press-and-hold menu opens. */
    fun hold(view: View) {
        if (!hapticsOn(view.context)) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, FLAGS)
        view.postDelayed({ view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, FLAGS) }, 45L)
    }

    fun tick(c: Context) = play(c, "tick")
    fun key(c: Context) = play(c, "key")
    fun lock(c: Context) = play(c, "lock")
    fun unlock(c: Context) = play(c, "unlock")

    // F4p: sounds are built and played on their own thread — never on the UI thread during a swipe
    private val worker by lazy { android.os.HandlerThread("GlideSounds").apply { start() }.let { android.os.Handler(it.looper) } }

    private fun play(c: Context, name: String) {
        if (!soundsOn(c)) return
        val app = c.applicationContext
        worker.post {
            val audio = app.getSystemService(AudioManager::class.java) ?: return@post
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return@post
            val t = (tracks ?: build().also { tracks = it })[name] ?: return@post
            runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop(); t.reloadStaticData(); t.play() }
        }
    }

    @Synchronized
    private fun build(): Map<String, AudioTrack> {
        tracks?.let { return it }
        fun tone(ms: Int, gain: Float, f: (Double) -> Double): ShortArray {
            val n = RATE * ms / 1000
            return ShortArray(n) { i ->
                val t = i.toDouble() / RATE
                (f(t).coerceIn(-1.0, 1.0) * gain * Short.MAX_VALUE).toInt().toShort()
            }
        }
        fun tri(x: Double) = 2.0 / PI * kotlin.math.asin(sin(x))
        val tick = tone(12, .22f) { t -> sin(2 * PI * 320 * t) * exp(-t / 0.0035) }
        val key = tone(18, .20f) { t -> (0.6 * sin(2 * PI * 450 * t) + 0.4 * tri(2 * PI * 450 * t)) * exp(-t / 0.005) }
        val lock = tone(40, .18f) { t -> (if (t < 0.02) sin(2 * PI * 880 * t) else sin(2 * PI * 660 * t)) * exp(-(t % 0.02) / 0.008) }
        val unlock = tone(40, .18f) { t -> (if (t < 0.02) sin(2 * PI * 660 * t) else sin(2 * PI * 880 * t)) * exp(-(t % 0.02) / 0.008) }
        fun track(pcm: ShortArray): AudioTrack {
            val tr = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            tr.write(pcm, 0, pcm.size)
            return tr
        }
        return mapOf("tick" to track(tick), "key" to track(key), "lock" to track(lock), "unlock" to track(unlock))
    }

    @Suppress("unused") private fun silence(x: Double) = abs(x)
}
