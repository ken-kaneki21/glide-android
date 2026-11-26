package com.gharaudio.launcher

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import java.io.File

/**
 * Android authorizes media-session access after the user enables notification access.
 * B2: also a quiet play-history log (for smart playlists, Smart DJ and the monthly recap later).
 * One line per song you actually listened to (>= 30 s or >= half the song), from any player
 * (Poweramp, LG Music, …). Stored only on the phone: files/history/plays.tsv. Audio files are never touched.
 * Columns: epoch ms, listened ms, duration ms, package, artist, album, title.
 */
class PlaybackListenerService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val watched = HashMap<MediaSession_Token, Track>()

    private class Track(val controller: MediaController) {
        var artist = ""; var album = ""; var title = ""; var duration = 0L
        var playingSince = 0L; var listened = 0L
        var callback: MediaController.Callback? = null
    }
    private class MediaSession_Token(val token: Any) {
        override fun equals(other: Any?) = other is MediaSession_Token && other.token == token
        override fun hashCode() = token.hashCode()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    // L: Zune lock screen — shown when the screen turns on (only if turned on in Glide settings)
    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { GlideLockScreen.onScreenOn(applicationContext) }
    }
    private var screenReceiverOn = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!screenReceiverOn) runCatching {
            registerReceiver(screenOn, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON)); screenReceiverOn = true
        }
        runCatching {
            val m = getSystemService(MediaSessionManager::class.java) ?: return
            msm = m
            val me = ComponentName(this, PlaybackListenerService::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, me, main)
            attach(m.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        if (screenReceiverOn) { runCatching { unregisterReceiver(screenOn) }; screenReceiverOn = false }
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        watched.values.forEach { finish(it); runCatching { it.callback?.let(it.controller::unregisterCallback) } }
        watched.clear()
        super.onListenerDisconnected()
    }

    private fun attach(list: List<MediaController>) {
        val keys = list.map { MediaSession_Token(it.sessionToken) }.toSet()
        watched.keys.filter { it !in keys }.forEach { k -> watched.remove(k)?.let { finish(it); runCatching { it.callback?.let(it.controller::unregisterCallback) } } }
        for (c in list) {
            val k = MediaSession_Token(c.sessionToken)
            if (watched.containsKey(k)) continue
            val t = Track(c)
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { finish(t); read(t, metadata); state(t, c.playbackState) }
                override fun onPlaybackStateChanged(s: PlaybackState?) { state(t, s) }
                override fun onSessionDestroyed() { finish(t) }
            }
            t.callback = cb
            read(t, c.metadata); state(t, c.playbackState)
            runCatching { c.registerCallback(cb, main) }
            watched[k] = t
        }
    }

    private fun read(t: Track, m: MediaMetadata?) {
        t.artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        t.album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        t.title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        t.duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        t.listened = 0L
        t.playingSince = 0L
    }

    private fun state(t: Track, s: PlaybackState?) {
        val now = SystemClock.elapsedRealtime()
        val playing = s?.state == PlaybackState.STATE_PLAYING
        if (playing && t.playingSince == 0L) t.playingSince = now
        if (!playing && t.playingSince != 0L) { t.listened += now - t.playingSince; t.playingSince = 0L }
    }

    /** Write the song that just ended if it was really listened to. */
    private fun finish(t: Track) {
        if (t.playingSince != 0L) { t.listened += SystemClock.elapsedRealtime() - t.playingSince; t.playingSince = SystemClock.elapsedRealtime() }
        val heard = t.listened
        if (t.title.isBlank() || heard < 30_000L && (t.duration <= 0 || heard < t.duration / 2)) { t.listened = 0L; return }
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        val line = listOf(System.currentTimeMillis(), heard, t.duration, t.controller.packageName,
            clean(t.artist), clean(t.album), clean(t.title)).joinToString("\t") + "\n"
        t.listened = 0L
        val dir = File(filesDir, "history")
        Thread { runCatching { dir.mkdirs(); File(dir, "plays.tsv").appendText(line) } }.start()
    }
}
