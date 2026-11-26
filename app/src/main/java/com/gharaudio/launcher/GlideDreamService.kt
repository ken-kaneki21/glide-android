package com.gharaudio.launcher

import android.app.Service
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.content.res.Configuration
import android.widget.TextClock
import android.widget.TextView

/** Optional charging screensaver selected in Android Settings > Display > Screensaver.
 *  No background monitoring, wake-ups or custom keyguard implementation.
 */
class GlideDreamService : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private var art: ImageView? = null
    private var title: TextView? = null
    private var artist: TextView? = null
    private var playButton: TextView? = null
    private var controller: MediaController? = null
    private var displayedArt: Bitmap? = null

    /** Keep artwork square even when its parent fills a tall portrait dock area. */
    private class SquareCover(context: android.content.Context) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            val height = View.MeasureSpec.getSize(heightMeasureSpec)
            val side = minOf(width, height).coerceAtLeast(1)
            super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
            )
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            updateMedia()
            handler.postDelayed(this, 2000L)
        }
    }

    // L: Zune HD dock look — Zegoe type, OLED black, accent details, slow drift against burn-in
    private val zLight by lazy { runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.selawik_light) }.getOrNull() ?: Typeface.DEFAULT }
    private val zRegular by lazy { runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.selawik_regular) }.getOrNull() ?: Typeface.DEFAULT }
    private var root: View? = null
    private val drift = object : Runnable {
        override fun run() {
            root?.animate()?.translationX(((Math.random() - .5) * dp(24)).toFloat())?.translationY(((Math.random() - .5) * dp(16)).toFloat())
                ?.setDuration(4000L)?.start()
            handler.postDelayed(this, 60_000L)
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = zLight
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun control(value: String, click: () -> Unit): TextView =
        text(value, 30f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(8), dp(15), dp(8))
            setOnClickListener { click() }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = false
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setBackgroundColor(Color.BLACK)
        }
        val artFrame = FrameLayout(this).apply {
            // Atmospheric color fills surplus space; the album cover remains centered and square.
            setBackgroundColor(Color.BLACK)
        }
        art = SquareCover(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        artFrame.addView(art, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        if (portrait) root.addView(artFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        else root.addView(artFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if(portrait) 0 else dp(27), if(portrait) dp(12) else 0, 0, 0)
        }
        if(portrait) root.addView(right, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        else root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        val accent = GlideMetroStyle.accentArgb(this)
        right.addView(text("glide", 14f, accent).apply { typeface = zRegular })
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            textSize = if (portrait) 64f else 96f
            typeface = zLight
            includeFontPadding = false
            setTextColor(Color.WHITE)
        }
        right.addView(clock)
        right.addView(TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"
            format12Hour = "EEEE, d MMMM"
            textSize = 18f
            typeface = zLight
            setTextColor(Color.rgb(153, 153, 153))
            setPadding(0, dp(3), 0, dp(16))
        })
        title = text("nothing playing", 29f)
        artist = text("Open LG Music or Poweramp", 16f, Color.rgb(153, 153, 153))
        right.addView(title)
        right.addView(artist)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(control("⏮") { controller?.transportControls?.skipToPrevious() })
        playButton = control("▶") {
            val c = controller ?: return@control
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }
        buttons.addView(playButton)
        buttons.addView(control("⏭") { controller?.transportControls?.skipToNext() })
        right.addView(buttons)
        right.addView(text("charging · tap the controls", 12f, Color.rgb(102, 102, 102)))
        this.root = root
        setContentView(root)
    }

    private fun updateMedia() {
        val sessions = try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager.getActiveSessions(ComponentName(this, PlaybackListenerService::class.java))
        } catch (_: Exception) { emptyList<MediaController>() }
        // Retain the active player's session while it remains valid: background
        // players may briefly report PLAYING after another player takes over.
        val held = controller?.let { previous ->
            sessions.firstOrNull { it.sessionToken == previous.sessionToken }
        }
        controller = when {
            held?.playbackState?.state == PlaybackState.STATE_PLAYING -> held
            else -> sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: held ?: sessions.firstOrNull { it.metadata != null }
        }
        val info = controller?.metadata
        title?.text = info?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "nothing playing"
        artist?.text = info?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: "Start music in LG Music or Poweramp"
        playButton?.text = if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) "⏸" else "▶"
        val bitmap: Bitmap? = info?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: info?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bitmap !== displayedArt) {
            displayedArt = bitmap
            if (bitmap != null && !bitmap.isRecycled) art?.setImageBitmap(bitmap)
            else art?.setImageDrawable(null)
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        handler.post(refresh)
        handler.postDelayed(drift, 60_000L)
    }
    override fun onDreamingStopped() {
        handler.removeCallbacks(refresh); handler.removeCallbacks(drift)
        super.onDreamingStopped()
    }
    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }
}
