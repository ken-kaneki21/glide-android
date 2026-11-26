package com.gharaudio.launcher

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.pager.PagerState
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * Test hook for Test-GlideFull.ps1. Only adb (holder of DUMP) can reach it, through the existing DIAG broadcast:
 *   adb shell am broadcast -a com.gharaudio.launcher.DIAG --es cmd report        -> writes files/glide_report.json
 *   --es cmd page --ei v 3 | play_all | next | prev | pause | play | sing --ei v 0..100 | eq --ei v -1.. |
 *   speed --es f 1.25 | sleep --ei v 0/-1/15 | rg --ez on true | fade --ez on false
 * Reads Glide's real state (pages, player, effects, memory) so the test never has to guess from the screen.
 */
@OptIn(UnstableApi::class)
internal object GlideTestHook {
    @Volatile var pager: PagerState? = null
    @Volatile var scope: CoroutineScope? = null

    fun run(c: Context, cmd: String, i: Intent) {
        val p = GlideFx.player
        val prefs = GlideFx.prefs(c)
        when (cmd) {
            "page" -> { val n = i.getIntExtra("v", 2); scope?.launch { pager?.scrollToPage(n) } }
            "play_all" -> {
                val songs = cachedMusicIndex?.songs ?: runCatching { getOrLoadMusicIndex(c).songs }.getOrDefault(emptyList())
                if (songs.isNotEmpty()) GlideEngine.playList(c, songs, 0)
            }
            "next" -> p?.seekToNextMediaItem()
            "prev" -> p?.seekToPreviousMediaItem()
            "pause" -> p?.pause()
            "play" -> p?.play()
            "sing" -> { prefs.edit().putInt("engine_sing", i.getIntExtra("v", 0)).apply(); GlideFx.apply(c) }
            "eq" -> { prefs.edit().putInt("engine_eq", i.getIntExtra("v", -1)).apply(); GlideFx.apply(c) }
            "speed" -> { prefs.edit().putFloat("engine_speed", i.getStringExtra("f")?.toFloatOrNull() ?: 1f).apply(); GlideFx.apply(c) }
            "sleep" -> GlideFx.setSleep(c, i.getIntExtra("v", 0))
            "rg" -> { prefs.edit().putBoolean("engine_rg", i.getBooleanExtra("on", false)).apply(); GlideFx.apply(c) }
            "fade" -> { prefs.edit().putBoolean("engine_fade", i.getBooleanExtra("on", false)).apply(); GlideFx.apply(c) }
        }
        if (cmd == "report") write(c)
    }

    private fun write(c: Context) {
        val o = JSONObject()
        val prefs = GlideFx.prefs(c)
        o.put("time", System.currentTimeMillis())
        o.put("page", pager?.settledPage ?: -1)
        o.put("version", runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrDefault(""))
        val p = GlideFx.player
        val e = JSONObject()
        e.put("enabled", GlideEngine.enabled(c))
        e.put("connected", p != null)
        if (p != null) {
            e.put("playing", p.isPlaying)
            e.put("items", p.mediaItemCount)
            e.put("index", p.currentMediaItemIndex)
            e.put("title", p.mediaMetadata.title?.toString() ?: "")
            e.put("speed", p.playbackParameters.speed.toDouble())
            e.put("volume", p.volume.toDouble())
            e.put("pauseAtEnd", p.pauseAtEndOfMediaItems)
            e.put("shuffle", p.shuffleModeEnabled)
            val f = p.audioFormat ?: p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)
            if (f != null) e.put("format", JSONObject()
                .put("mime", f.sampleMimeType ?: "").put("sampleRate", f.sampleRate).put("channels", f.channelCount)
                .put("bitrate", f.bitrate).put("pcmEncoding", f.pcmEncoding))
        }
        e.put("pure", prefs.getBoolean("engine_pure", false))
        e.put("singPref", prefs.getInt("engine_sing", 0))
        e.put("singApplied", ((GlideFx.vocal?.amount ?: -1f) * 100).toDouble())
        e.put("stemActive", GlideStems.isStemPlaying(p))
        e.put("validStemPacks", GlideStems.availableCount(c))
        e.put("stemModeEnabled", prefs.getBoolean("engine_stems", false))
        e.put("singMethod", if (GlideStems.isStemPlaying(p)) "vocal/accompaniment stem mixer; instruments at unity" else "centre cancellation fallback; instruments may change")
        val volumePrefs = c.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE)
        e.put("volumeLastEvent", volumePrefs.getString("volume_last_event", "no event observed"))
        e.put("volumeLastEventAt", volumePrefs.getLong("volume_last_event_at", 0L))
        e.put("eqPref", prefs.getInt("engine_eq", -1))
        val eq = GlideFx.eqState()
        e.put("eqEnabled", eq.first); e.put("eqPreset", eq.second)
        e.put("eqPresets", JSONArray(GlideFx.presetNames()))
        e.put("rg", prefs.getBoolean("engine_rg", false))
        e.put("fade", prefs.getBoolean("engine_fade", false))
        e.put("pcmTapActive", GlidePcmTap.active())
        e.put("pcmCaptureEnabled", GlidePcmTap.captureEnabled)
        e.put("pcmAnalysisSampleRate", GlidePcmTap.sampleRate)
        e.put("outputReport", GlideFx.outputReport(c))
        o.put("engine", e)
        val ui = c.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE)
        o.put("settings", JSONObject().put("zune_volume", ui.getBoolean("zune_volume", true))
            .put("zune_lock", ui.getBoolean("zune_lock", false)).put("motion", ui.getBoolean("motion", true))
            .put("light", GlideMetroStyle.lightTheme).put("accent", GlideMetroStyle.palette))
        val mi = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(mi)
        o.put("memory", JSONObject().put("pssMB", mi.totalPss / 1024.0)
            .put("javaHeapMB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0)
            .put("nativeHeapMB", android.os.Debug.getNativeHeapAllocatedSize() / 1048576.0))
        o.put("pins", GlidePins.all(c).size)
        o.put("diag", GlideDiag.flags.intValue)
        val dir = c.getExternalFilesDir(null) ?: return
        File(dir, "glide_report.json").writeText(o.toString(2))
    }
}
