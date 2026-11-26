package com.gharaudio.launcher

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/*
 * C1 + C2: synced lyrics and karaoke.
 * Source order: a .lrc file next to the song (yours wins) -> cache -> LRCLIB (free, open, no key).
 * Timing: line timestamps from the LRC; word timing from "<mm:ss.xx>" tags when the file has them,
 * otherwise estimated inside each line by word length (close to Apple Music's fill).
 * Performance: no frame loop. The UI updates only when the next word or line starts (a few times a second),
 * and a line change animates for 280 ms.
 */

internal data class LyricWord(val startMs: Long, val text: String)
internal data class LyricLine(val startMs: Long, val text: String, val words: List<LyricWord>)
internal data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)

internal object GlideLyrics {
    private const val NONE_RETRY_MS = 3L * 86400000L
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Lyrics>()

    private fun key(artist: String, title: String) = MessageDigest.getInstance("SHA-256")
        .digest("${artist.trim().lowercase(Locale.ROOT)}\u001f${title.trim().lowercase(Locale.ROOT)}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun dir(context: Context) = File(context.filesDir, "glide_lyrics").apply { mkdirs() }

    /** Blocking; call on Dispatchers.IO. */
    fun load(context: Context, artist: String, title: String, album: String, durationMs: Long, local: Song?): Lyrics? {
        if (title.isBlank() || title == "Nothing playing") return null
        val k = key(artist, title)
        memory[k]?.let { return it }
        // 1) a .lrc file next to the song file
        localLrc(context, local)?.let { raw -> parse(raw, "your .lrc file")?.let { memory[k] = it; return it } }
        // 2) cache
        val cached = File(dir(context), "$k.lrc")
        val none = File(dir(context), "$k.none")
        if (cached.isFile) parse(cached.readText(), "lrclib")?.let { memory[k] = it; return it }
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < NONE_RETRY_MS) return null
        if (!OnlineArt.enabled(context)) return null
        // 3) LRCLIB: exact get (with duration), then search and take the closest length within 3 s
        val raw = fetchLrclib(artist, title, album, durationMs)
        if (raw == null) { runCatching { none.writeText("") }; return null }
        runCatching { cached.writeText(raw) }
        return parse(raw, "lrclib")?.also { memory[k] = it }
    }

    private val timingRevision = androidx.compose.runtime.mutableIntStateOf(0)
    fun timing(context: Context, artist: String, title: String): Int {
        timingRevision.intValue
        return context.getSharedPreferences("glide_lyric_timing", Context.MODE_PRIVATE)
            .getInt(key(artist, title), 0)
    }
    fun adjustTiming(context: Context, artist: String, title: String, offset: Int) {
        context.getSharedPreferences("glide_lyric_timing", Context.MODE_PRIVATE)
            .edit().putInt(key(artist, title), offset.coerceIn(-30000, 30000)).apply()
        timingRevision.intValue++
    }

    fun forget(context: Context, artist: String, title: String) {
        val k = key(artist, title)
        memory.remove(k); File(dir(context), "$k.lrc").delete(); File(dir(context), "$k.none").delete()
    }

    private fun localLrc(context: Context, song: Song?): String? {
        song ?: return null
        val path = runCatching {
            context.contentResolver.query(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id),
                arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: return null
        val base = File(path)
        val file = listOf(File(base.parentFile, base.nameWithoutExtension + ".lrc"), File(base.parentFile, song.title + ".lrc"))
            .firstOrNull { it.isFile && it.length() in 1..256_000 } ?: return null
        return runCatching { file.readText() }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetchLrclib(artist: String, title: String, album: String, durationMs: Long): String? {
        val secs = (durationMs / 1000L).toInt()
        val primaryArtist = artist.split(Regex("\\s*(,|&| feat\\.? | ft\\.? | x )\\s*", RegexOption.IGNORE_CASE)).first().trim()
        val cleanTitle = title.replace(Regex("\\s*[(\\[](feat|ft|with)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "").trim()
        if (secs > 0) {
            val url = "https://lrclib.net/api/get?artist_name=${enc(primaryArtist)}&track_name=${enc(cleanTitle)}" +
                (if (album.isNotBlank()) "&album_name=${enc(album)}" else "") + "&duration=$secs"
            OnlineArt.json(url, 400 * 1024)?.let { j ->
                j.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
                j.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            }
        }
        val bytes = OnlineArt.request("https://lrclib.net/api/search?track_name=${enc(cleanTitle)}&artist_name=${enc(primaryArtist)}", 1024 * 1024)
            ?: return null
        val arr = runCatching { JSONArray(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        val candidates = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { secs <= 0 || kotlin.math.abs(it.optDouble("duration", 0.0) - secs) <= 3.0 }
        val best = candidates.firstOrNull { it.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" } }
            ?: candidates.firstOrNull { it.optString("plainLyrics").let { s -> s.isNotBlank() && s != "null" } } ?: return null
        return best.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: best.optString("plainLyrics")
    }

    private val lineTag = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private fun ms(m: MatchResult): Long {
        val frac = m.groupValues[3]
        val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return m.groupValues[1].toLong() * 60000L + m.groupValues[2].toLong() * 1000L + f
    }

    fun parse(raw: String, source: String): Lyrics? {
        val timed = ArrayList<Pair<Long, String>>()
        val plain = ArrayList<String>()
        raw.lineSequence().forEach { l ->
            val tags = lineTag.findAll(l).toList()
            if (tags.isEmpty()) { val t = l.trim(); if (t.isNotEmpty() && !t.startsWith("[")) plain += t; return@forEach }
            val text = l.substring(tags.last().range.last + 1).trim()
            tags.forEach { timed += ms(it) to text }
        }
        if (timed.isEmpty()) {
            if (plain.isEmpty()) return null
            return Lyrics(plain.map { LyricLine(-1L, it, emptyList()) }, synced = false, source = source)
        }
        timed.sortBy { it.first }
        val lines = timed.mapIndexed { i, (start, text) ->
            val end = timed.getOrNull(i + 1)?.first ?: (start + 5000L)
            LyricLine(start, wordTag.replace(text, "").replace(Regex("\\s+"), " ").trim(), words(start, end, text))
        }
        return Lyrics(lines, synced = true, source = source)
    }

    /** Word timing: real "<mm:ss.xx>" tags if present, else spread across the line by word length. */
    private fun words(start: Long, end: Long, text: String): List<LyricWord> {
        val tagged = wordTag.findAll(text).toList()
        if (tagged.isNotEmpty()) {
            val out = ArrayList<LyricWord>()
            tagged.forEachIndexed { i, m ->
                val stop = tagged.getOrNull(i + 1)?.range?.first ?: text.length
                val w = text.substring(m.range.last + 1, stop)
                if (w.isNotBlank()) out += LyricWord(ms(m), w)
            }
            return out
        }
        val parts = text.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val span = (end - start).coerceIn(600L, 7000L) * 0.9
        val weights = parts.map { it.length + 2.0 }
        val total = weights.sum()
        var t = start.toDouble()
        return parts.mapIndexed { i, w -> LyricWord(t.toLong(), if (i < parts.lastIndex) "$w " else w).also { t += span * weights[i] / total } }
    }
}

/** Which line / word is playing at a position. */
private fun lyricIndex(lines: List<LyricLine>, pos: Long): Int {
    var lo = 0; var hi = lines.lastIndex; var ans = -1
    while (lo <= hi) { val mid = (lo + hi) / 2; if (lines[mid].startMs <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
    return ans
}

/**
 * Drives (lineIndex, wordIndex) from the playback position without a frame loop:
 * it sleeps until the next word or line boundary, re-checking at least once a second.
 */
@Composable
internal fun rememberLyricCursor(player: PlayerInfo, lyrics: Lyrics?, active: Boolean = true): State<Pair<Int, Int>> {
    val cursor = remember(lyrics) { mutableStateOf(-1 to -1) }
    val current by rememberUpdatedState(player)
    // A4: only tick while the screen is actually visible. All panorama pages stay composed, so before this the
    // lyrics kept redrawing during Home/Library/Apps swipes (measured: +170 navigation frames per lap).
    LaunchedEffect(lyrics, player.playing, player.title, player.position, player.updatedAt, active) {
        if (!active) return@LaunchedEffect
        val lines = lyrics?.lines ?: return@LaunchedEffect
        if (lyrics.synced.not()) return@LaunchedEffect
        while (true) {
            val pos = current.livePositionMs()
            val li = lyricIndex(lines, pos)
            val words = lines.getOrNull(li)?.words.orEmpty()
            var wi = -1
            for (i in words.indices) if (words[i].startMs <= pos) wi = i else break
            if (cursor.value != li to wi) cursor.value = li to wi
            if (!current.playing) break
            val nextWord = words.getOrNull(wi + 1)?.startMs
            val nextLine = lines.getOrNull(li + 1)?.startMs
            val next = listOfNotNull(nextWord, nextLine).minOrNull()
            delay(((next ?: (pos + 1000L)) - pos).coerceIn(40L, 1000L))
        }
    }
    return cursor
}

@Composable
internal fun rememberLyrics(player: PlayerInfo, local: Song?): State<Lyrics?> {
    val context = LocalContext.current
    val raw = produceState<Lyrics?>(null, player.title, player.artist, local) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { GlideLyrics.load(context, player.artist, player.title, player.album, player.duration, local) }.getOrNull()
        }
    }
    val offset = GlideLyrics.timing(context, player.artist, player.title)
    return remember(raw, offset) {
        androidx.compose.runtime.derivedStateOf {
            raw.value?.let { lyrics ->
                if (!lyrics.synced || offset == 0) lyrics else lyrics.copy(lines = lyrics.lines.map { line ->
                    line.copy(startMs = (line.startMs + offset).coerceAtLeast(0L), words = line.words.map { word ->
                        word.copy(startMs = (word.startMs + offset).coerceAtLeast(0L))
                    })
                })
            }
        }
    }
}

/**
 * Now Playing strip (replaces the up-next lines while a song has synced lyrics): the current line large with
 * karaoke fill (sung words white, the rest dim), the next two lines small. Tap a line to jump the song there.
 */
@Composable
internal fun ZuneLyricsStrip(player: PlayerInfo, lyrics: Lyrics, active: Boolean, modifier: Modifier = Modifier) {
    val controller = player.controller
    val (li, wi) = rememberLyricCursor(player, lyrics, active).value
    val lines = lyrics.lines
    // a short rise when the line changes (Apple-style), not a continuous animation
    var shownLine by remember { mutableIntStateOf(li) }
    val rise = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(li) {
        // A4: no line animation. Measured on the G6, every animated frame re-composites the whole Now Playing
        // screen (~+11% CPU with lyrics on); a clean cut to the next line keeps it at 1 frame per change.
        if (li != shownLine) shownLine = li
    }
    Column(modifier.height(124.dp)) {
        val cur = lines.getOrNull(li)
        val curText = buildAnnotatedString {
            if (cur == null) { withStyle(SpanStyle(color = Zune.dim)) { append(lines.firstOrNull()?.text.orEmpty()) } }
            else if (cur.text.isBlank()) { withStyle(SpanStyle(color = Zune.dim)) { append("♪") } }
            // C2: sung words bright white, unsung words faint, so the fill reads clearly (Apple-style)
            else cur.words.forEachIndexed { i, w -> withStyle(SpanStyle(color = if (i <= wi) Zune.white else Zune.white.copy(alpha = .22f))) { append(w.text) } }
        }
        Text(curText, style = Zune.style(28.sp, Zune.Semilight, spacing = (-0.3).sp), lineHeight = 31.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                cur?.let { controller?.transportControls?.seekTo(it.startMs) }
            })
        Spacer(Modifier.height(6.dp))
        for (k in 1..2) {
            val line = lines.getOrNull(li + k) ?: break
            Text(line.text.ifBlank { "♪" }, style = Zune.style(16.sp, FontWeight.Normal, if (k == 1) Zune.light else Zune.grey),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    controller?.transportControls?.seekTo(line.startMs)
                })
        }
    }
}

/** Full lyrics page (info -> lyrics): every line, the current one white and kept in view; tap any line to jump. */
@Composable
internal fun ZuneLyricsFull(player: PlayerInfo, lyrics: Lyrics?, onRetry: () -> Unit) {
    if (lyrics == null) {
        Column {
            Text("no lyrics found", style = Zune.style(26.sp, FontWeight.Light))
            Text("glide looks for a .lrc file next to the song, then lrclib.net (needs online info turned on in settings).",
                style = Zune.style(13.sp, FontWeight.Normal, Zune.grey))
            Text("try again", style = Zune.style(20.sp, FontWeight.Light), modifier = Modifier.padding(top = 14.dp).clickable(onClick = onRetry))
        }
        return
    }
    val (li, wi) = rememberLyricCursor(player, lyrics).value
    val state = rememberLazyListState()
    LaunchedEffect(li) { if (li >= 2) state.animateScrollToItem(li - 1) else if (li >= 0) state.animateScrollToItem(0) }
    val context = LocalContext.current
    val offset = GlideLyrics.timing(context, player.artist, player.title)
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 120.dp)) {
        if (lyrics.synced) item {
            androidx.compose.foundation.layout.Row {
                listOf("earlier" to -250, "reset (${offset} ms)" to -offset, "later" to 250).forEach { (label, delta) ->
                    Text(label, style = Zune.style(15.sp, FontWeight.Normal, Zune.magenta),
                        modifier = Modifier.clickable {
                            GlideLyrics.adjustTiming(context, player.artist, player.title, offset + delta)
                        }.padding(8.dp))
                }
            }
        }
        itemsIndexed(lyrics.lines) { i, line ->
            val text = if (i == li && lyrics.synced) buildAnnotatedString {
                line.words.forEachIndexed { n, w -> withStyle(SpanStyle(color = if (n <= wi) Zune.white else Zune.light)) { append(w.text) } }
            } else buildAnnotatedString { append(line.text.ifBlank { "♪" }) }
            Text(text, style = Zune.style(if (i == li) 24.sp else 19.sp, if (i == li) Zune.Semilight else FontWeight.Light,
                when { !lyrics.synced -> Zune.light; i == li -> Zune.white; i < li -> Zune.grey; else -> Zune.dim }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(enabled = lyrics.synced && line.startMs >= 0) {
                    player.controller?.transportControls?.seekTo(line.startMs)
                })
        }
        item { Text("lyrics: ${lyrics.source}", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.padding(top = 16.dp)) }
    }
}
