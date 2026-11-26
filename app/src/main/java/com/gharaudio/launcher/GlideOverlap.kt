package com.gharaudio.launcher

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** The primary player owns audio focus, the queue and the media session. */
@OptIn(UnstableApi::class)
internal class GlideOverlap(private val context: Context, private val primary: ExoPlayer) : Player.Listener {
    private val handler = Handler(primary.applicationLooper)
    private val settings = GlideFx.prefs(context)
    private var secondary: ExoPlayer? = null
    private var fromIndex = C.INDEX_UNSET
    private var nextIndex = C.INDEX_UNSET
    private var nextItem: MediaItem? = null
    private var failed = false
    private var fading = false
    private var handoffPosition = C.TIME_UNSET
    private var handoffDeadline = 0L
    private var failedIndex = C.INDEX_UNSET
    private val enableListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "engine_overlap") handler.post {
            if (!closed) onIsPlayingChanged(primary.isPlaying)
        }
    }
    private var closed = false
    private fun enabled() = settings.getBoolean("engine_overlap", false)
    private val tick = object : Runnable {
        override fun run() {
            advance()
            if (enabled() && (primary.isPlaying || handoffPosition != C.TIME_UNSET)) {
                val untilPreparation = primary.duration - primary.currentPosition - 5500L
                handler.postDelayed(this, if (secondary != null) 50L else untilPreparation.coerceIn(100L, 1000L))
            }
        }
    }

    init {
        primary.addListener(this)
        settings.registerOnSharedPreferenceChangeListener(enableListener)
    }

    private fun advance() {
        if (handoffPosition != C.TIME_UNSET) {
            val other = secondary
            if (failed || other == null || !other.isPlaying ||
                primary.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
                android.os.SystemClock.elapsedRealtime() >= handoffDeadline) {
                cancel()
            } else if (primary.isPlaying && primary.currentPosition > handoffPosition) {
                // Keep the incoming track audible while the main player repositions.
                cancel()
            }
            return
        }
        val position = primary.currentPosition
        val remaining = primary.duration - position
        val next = primary.nextMediaItemIndex
        if (!primary.isPlaying || !enabled() || failedIndex == primary.currentMediaItemIndex ||
            settings.getBoolean("engine_pure", false) || settings.getBoolean("engine_rg", false) ||
            settings.getBoolean("engine_fade", false) || settings.getInt("engine_sing", 0) != 0 ||
            GlideFx.hasActiveSleepTimer() || primary.playbackParameters.speed != 1f ||
            primary.pauseAtEndOfMediaItems || primary.repeatMode == Player.REPEAT_MODE_ONE ||
            next !in 0 until primary.mediaItemCount || primary.duration <= 8000L || remaining > 5500L) {
            cancel()
            return
        }
        val upcoming = primary.getMediaItemAt(next)
        if (upcoming.mediaId.toLongOrNull() == null || primary.currentMediaItem?.mediaId?.toLongOrNull() == null ||
            GlideStems.isStemPlaying(primary) ||
            upcoming.localConfiguration?.uri?.scheme != "content") {
            cancel()
            return
        }
        if (secondary == null) {
            if (remaining < 2700L) return
            fromIndex = primary.currentMediaItemIndex
            nextIndex = next
            nextItem = upcoming
            secondary = ExoPlayer.Builder(context)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                .build().also {
                    it.addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
                    })
                    it.volume = 0f
                    it.setMediaItem(upcoming)
                    it.prepare()
                }
        }
        val other = secondary ?: return
        if (failed) {
            failedIndex = primary.currentMediaItemIndex
            cancel()
            return
        }
        if (nextItem != upcoming || fromIndex != primary.currentMediaItemIndex || nextIndex != next) {
            cancel()
            return
        }
        if (!fading && remaining in 2500L..3000L && other.playbackState == Player.STATE_READY) {
            fading = true
            other.play()
        }
        if (fading) {
            val mix = (other.currentPosition / 3000f).coerceIn(0f, 1f)
            primary.volume = 1f - mix
            other.volume = mix
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        if (enabled() && (isPlaying || handoffPosition != C.TIME_UNSET && primary.playWhenReady)) {
            handler.post(tick)
        } else cancel()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) cancel()
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) cancel()
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (handoffPosition != C.TIME_UNSET && reason == Player.DISCONTINUITY_REASON_SEEK &&
            new.mediaItemIndex == nextIndex && new.positionMs == handoffPosition) return
        failedIndex = C.INDEX_UNSET
        val other = secondary
        val handoff = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && fading &&
            !failed && new.mediaItemIndex == nextIndex && new.mediaItem == nextItem && other != null
        val elapsed = if (handoff) other?.currentPosition ?: 0L else 0L
        if (handoff && elapsed > 0) {
            handoffPosition = elapsed
            handoffDeadline = android.os.SystemClock.elapsedRealtime() + 750L
            primary.volume = 0f
            other.volume = 1f
            primary.seekTo(new.mediaItemIndex, elapsed)
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 25L)
        } else cancel()
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) cancel()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { cancel() }
    override fun onRepeatModeChanged(repeatMode: Int) { cancel() }
    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) { cancel() }

    private fun cancel() {
        if (fading) primary.volume = 1f
        fading = false
        handoffPosition = C.TIME_UNSET
        failed = false
        secondary?.release()
        secondary = null
        nextItem = null
        fromIndex = C.INDEX_UNSET
        nextIndex = C.INDEX_UNSET
    }

    fun close() {
        closed = true
        settings.unregisterOnSharedPreferenceChangeListener(enableListener)
        handler.removeCallbacks(tick)
        primary.removeListener(this)
        cancel()
    }
}
