package com.gharaudio.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.res.ResourcesCompat

/*
 * F1: Zune HD "quick controls" instead of the LG volume panel.
 * An accessibility service (you turn it on once in Android Settings > Accessibility > Glide volume) watches ONLY the
 * volume keys. While music is playing, a press changes the music volume without the LG panel and slides down the
 * Zune overlay: song, artist, previous / play-pause / next, and a thin accent volume bar with - and +.
 * It hides itself after 3.5 s. When no music is playing (calls, ringer, alarms) the keys go to Android as normal.
 * The service reads no screen content and sends nothing anywhere.
 */
class GlideVolumeService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var panel: ZuneVolumeView? = null
    private val hide = Runnable { panel?.slideAway { removePanel() } }
    private fun diagnostic(event: String) {
        Log.i("GlideVolume", event)
        getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).edit()
            .putString("volume_last_event", event)
            .putLong("volume_last_event_at", System.currentTimeMillis()).apply()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            // Keep the service observing an event type so LG's accessibility
            // manager does not deactivate its key-filter registration.
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        diagnostic("service connected; key filtering requested")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getBoolean("zune_volume", true)) return false
        val audio = getSystemService(AudioManager::class.java) ?: return false
        // Calls and ringing: leave them to Android. Otherwise the Zune panel always shows and sets media volume
        // (like the Zune HD), whether or not music is playing.
        if (audio.mode != AudioManager.MODE_NORMAL) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            diagnostic("volume key " + if (code == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down")
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            show()
        }
        return true
    }

    private fun controller(): MediaController? = runCatching {
        getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(this, PlaybackListenerService::class.java))
            ?.let { list -> list.firstOrNull { it.packageName == packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull { it.packageName == packageName } ?: list.firstOrNull() }
    }.getOrNull()

    private fun show() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        if (panel == null) {
            val v = ZuneVolumeView(this, ::onButton)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, (150 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP }
            try {
                wm.addView(v, lp); panel = v; v.slideIn()
                diagnostic("overlay added")
            } catch (e: Exception) {
                diagnostic("overlay failed: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
        refresh()
        main.removeCallbacks(hide); main.postDelayed(hide, 3500L)
    }

    private fun refresh() {
        val v = panel ?: return
        val audio = getSystemService(AudioManager::class.java)
        val c = controller()
        val md = c?.metadata
        v.title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "music" }
        v.artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        v.playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        v.level = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        v.max = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
        v.accent = GlideMetroStyle.accentArgb(this)
        v.invalidate()
    }

    private fun onButton(which: String) {
        val audio = getSystemService(AudioManager::class.java)
        val t = controller()?.transportControls
        when (which) {
            "prev" -> t?.skipToPrevious()
            "next" -> t?.skipToNext()
            "play" -> if (panel?.playing == true) t?.pause() else t?.play()
            "minus" -> audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "plus" -> audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            // F4a (Zune HD manual): drag to set the volume, hold back / next to scan through the song
            "seek+", "seek-" -> controller()?.let { c ->
                val st = c.playbackState
                val pos = if (st == null) 0L else st.position + if (st.state == PlaybackState.STATE_PLAYING)
                    ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
                c.transportControls.seekTo((pos + if (which == "seek+") 10_000L else -10_000L).coerceAtLeast(0L))
            }
            else -> if (which.startsWith("vol:")) {
                which.removePrefix("vol:").toIntOrNull()?.let { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                panel?.let { it.level = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: it.level; it.invalidate() }
                main.removeCallbacks(hide); main.postDelayed(hide, 3500L); return
            }
        }
        when (which) {
            "open" -> runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                removePanel(); return
            }
        }
        main.postDelayed({ refresh() }, 250L)
        main.removeCallbacks(hide); main.postDelayed(hide, 3500L)
    }

    private fun removePanel() {
        val v = panel ?: return
        panel = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); removePanel(); super.onDestroy() }
}

@SuppressLint("ViewConstructor")
private class ZuneVolumeView(context: Context, private val onButton: (String) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val regular: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.zegoe_regular) }.getOrNull() ?: Typeface.DEFAULT
    private val bold: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.zegoe_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var title = ""; var artist = ""; var playing = false; var level = 0; var max = 15; var accent = 0xFFD80073.toInt()
    private val prev = RectF(); private val play = RectF(); private val next = RectF()
    private val minus = RectF(); private val plus = RectF(); private val head = RectF()

    fun slideIn() { translationY = -150 * d; animate().translationY(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start() }
    fun slideAway(done: () -> Unit) { animate().translationY(-height.toFloat()).setDuration(180).withEndAction(done).start() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xEB141414.toInt())
        val left = 20 * d
        // row 1: song + artist (tap to open Glide)
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); p.typeface = bold; p.textSize = 15 * d; p.textAlign = Paint.Align.LEFT
        c.drawText(ellipsize(title, w * .62f), left, 30 * d, p)
        p.typeface = regular; p.textSize = 13 * d; p.color = 0xFF999999.toInt()
        c.drawText(ellipsize(artist.lowercase(), w * .62f), left, 50 * d, p)
        head.set(0f, 0f, w * .66f, 60 * d)
        // row 2: three outline circle buttons, right side
        val r = 18 * d; val cy = 44 * d
        val cx3 = w - 34 * d; val cx2 = cx3 - 48 * d; val cx1 = cx2 - 48 * d
        prev.set(cx1 - r, cy - r, cx1 + r, cy + r); play.set(cx2 - r, cy - r, cx2 + r, cy + r); next.set(cx3 - r, cy - r, cx3 + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0xFFFFFFFF.toInt()
        for (b in listOf(prev, play, next)) c.drawCircle(b.centerX(), b.centerY(), r, p)
        p.style = Paint.Style.FILL
        fun tri(x: Float, y: Float, s: Float, dir: Int) { val path = Path(); path.moveTo(x - s * dir, y - s); path.lineTo(x - s * dir, y + s); path.lineTo(x + s * dir, y); path.close(); c.drawPath(path, p) }
        val s = 5.5f * d
        tri(prev.centerX() - 1 * d, cy, s, -1); c.drawRect(prev.centerX() - 8 * d, cy - s, prev.centerX() - 6.5f * d, cy + s, p)
        tri(next.centerX() + 1 * d, cy, s, 1); c.drawRect(next.centerX() + 6.5f * d, cy - s, next.centerX() + 8 * d, cy + s, p)
        if (playing) { c.drawRect(cx2 - 5 * d, cy - s, cx2 - 2 * d, cy + s, p); c.drawRect(cx2 + 2 * d, cy - s, cx2 + 5 * d, cy + s, p) }
        else tri(cx2 + 1.5f * d, cy, s, 1)
        // row 3: - [bar] +  level
        val by = h - 34 * d
        p.typeface = regular; p.textSize = 26 * d; p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER
        c.drawText("–", left + 8 * d, by + 9 * d, p); minus.set(0f, by - 24 * d, left + 30 * d, h)
        c.drawText("+", w - 54 * d, by + 9 * d, p); plus.set(w - 80 * d, by - 24 * d, w - 30 * d, h)
        p.textSize = 14 * d; p.color = 0xFF999999.toInt(); c.drawText("$level", w - 20 * d, by + 5 * d, p)
        val x0 = left + 30 * d; val x1 = w - 80 * d
        p.color = 0x55FFFFFF; c.drawRect(x0, by - 1.5f * d, x1, by + 1.5f * d, p)
        p.color = accent; c.drawRect(x0, by - 1.5f * d, x0 + (x1 - x0) * level / max, by + 1.5f * d, p)
    }

    private fun ellipsize(s: String, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s; while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1); return "$t…"
    }

    private var downX = 0f; private var downY = 0f; private var downLevel = 0; private var dragging = false; private var held = false
    private val scan = object : Runnable {
        override fun run() {
            held = true
            if (hitBox(prev, downX, downY)) onButton("seek-") else if (hitBox(next, downX, downY)) onButton("seek+") else return
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            postDelayed(this, 400L)
        }
    }
    private fun hitBox(r: RectF, x: Float, y: Float): Boolean { val slop = 8 * d; return x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downLevel = level; dragging = false; held = false
                if (hitBox(prev, e.x, e.y) || hitBox(next, e.x, e.y)) postDelayed(scan, 500L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (!held && (dragging || kotlin.math.abs(dx) > 12 * d || kotlin.math.abs(dy) > 12 * d)) {
                    if (!dragging) { dragging = true; removeCallbacks(scan) }
                    // drag up (or right) = louder, one step per 14 dp
                    val steps = ((dx - dy) / (14 * d)).toInt()
                    val target = (downLevel + steps).coerceIn(0, max)
                    if (target != level) { level = target; invalidate(); onButton("vol:$target") }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(scan); return true }
            MotionEvent.ACTION_UP -> { removeCallbacks(scan); if (dragging || held) return true }
            else -> return true
        }
        val x = e.x; val y = e.y
        val slop = 8 * d
        fun hit(r: RectF) = x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
        when {
            hit(prev) -> onButton("prev")
            hit(play) -> onButton("play")
            hit(next) -> onButton("next")
            hit(minus) -> onButton("minus")
            hit(plus) -> onButton("plus")
            head.contains(x, y) -> onButton("open")
        }
        return true
    }
}
