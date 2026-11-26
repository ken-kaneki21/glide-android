package com.gharaudio.launcher

import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/*
 * Install B3: library fixer — INSIDE GLIDE ONLY.
 * Audio files and their tags are never written. Fixes are stored as Glide display overrides
 * (the same "glide_sidecar_tags" store the manual "display metadata" editor uses) and every
 * applied batch can be undone.
 *  safe fixes (offline): download-site junk in names ("www.x.com", "songspk", "320kbps"...), track numbers in
 *    titles ("01 - song"), "<unknown>" artist with "artist - title" in the title or file name, file-name-only titles.
 *  needs your OK (online, MusicBrainz 1 req/s): album for songs with an unknown album, only on an exact
 *    artist + title match.
 */
internal data class LibraryFix(val songId: Long, val field: String, val old: String, val new: String,
                               val reason: String, val safe: Boolean)

internal object GlideLibraryFixer {
    private const val TAGS = "glide_sidecar_tags"
    private const val UNDO = "glide_fixer_undo"
    private fun key(id: Long, field: String) = "tag_${id}_$field"

    private val junk = listOf(
        Regex("""\s*[\[(]?\s*(?:www\.)?[a-z0-9-]+\.(?:com|in|net|info|pk|co|org|me|cc|io|mobi|ws)\b\s*[\])]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\[(]?\s*\b(?:songs\.?pk|pagalworld|pagal\s?world|mr-?jatt|djmaza|dj\s?maza|mp3mad|downloadming|starmusiq|masstamilan|""" +
            """isaimini|naa\s?songs|webmusic|freshmaza|wapking|djpunjab|mymp3song|songslover|bestwap|raag\.fm|apunkabollywood)\b\s*[\])]?""",
            RegexOption.IGNORE_CASE),
        Regex("""\s*[\[(]?\s*\b(?:320|256|192|128)\s?kbps\b\s*[\])]?""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\[(]\s*(?:official\s+)?(?:full\s+)?(?:audio|video|lyrics?|lyrical)\s*(?:song)?\s*[\])]""", RegexOption.IGNORE_CASE)
    )
    private val trackPrefix = Regex("""^\s*\d{1,4}\s*(?:[.)_]|\s?-\s)\s*(?=\S)""")
    private fun unknown(s: String) = s.isBlank() || s.equals("<unknown>", true) || s.equals("unknown artist", true) ||
        s.equals("unknown", true) || s.equals("various artists", true)
    private fun unknownAlbum(s: String) = s.isBlank() || s.equals("<unknown>", true) || s.equals("unknown album", true) ||
        s.equals("music", true) || s.equals("download", true) || s.equals("0", true)

    private fun clean(s: String): String {
        var t = s
        junk.forEach { t = t.replace(it, " ") }
        t = t.replace('_', ' ').replace(Regex("\\s{2,}"), " ").trim().trim('-', '|', ' ', '.')
        return t
    }

    private fun paths(context: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = c.getString(1).orEmpty()
            }
        }
        return out
    }

    /** Offline scan. Blocking — call on Dispatchers.IO. */
    fun scan(context: Context): List<LibraryFix> {
        val songs = getOrLoadMusicIndex(context).songs
        val files = paths(context)
        val out = ArrayList<LibraryFix>()
        for (s in songs) {
            val stem = files[s.id]?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
            var title = s.title; var artist = s.artist
            // 1. "artist - title" when the artist is unknown (from the title, else from the file name)
            if (unknown(artist)) {
                val src = if (title.contains(" - ")) title else stem.takeIf { it.contains(" - ") }
                if (src != null) {
                    val parts = clean(src).split(" - ", limit = 2).map { it.trim() }
                    val a = parts[0].replace(trackPrefix, "").trim()
                    if (parts.size == 2 && a.length in 2..60 && parts[1].isNotBlank() && !a.all(Char::isDigit)) {
                        out += LibraryFix(s.id, "artist", artist, a, "artist was unknown; taken from \"$src\"", true)
                        out += LibraryFix(s.id, "title", title, parts[1], "title without the artist", true)
                        artist = a; title = parts[1]
                    }
                }
            }
            // 2. title is just the file name
            if (title == stem && title.contains('_')) {
                val t = clean(title.replace('_', ' '))
                if (t != title && t.isNotBlank()) { out += LibraryFix(s.id, "title", s.title, t, "title was the file name", true); title = t }
            }
            // 3. junk + track numbers in the title
            val t2 = clean(title).replace(trackPrefix, "").trim()
            if (t2.isNotBlank() && t2 != title && t2.length >= 2) {
                out.removeAll { it.songId == s.id && it.field == "title" }
                out += LibraryFix(s.id, "title", s.title, t2, "removed track number / site name", true)
            }
            // 4. junk in artist / album
            val a2 = clean(artist)
            if (a2.isNotBlank() && a2 != artist && out.none { it.songId == s.id && it.field == "artist" })
                out += LibraryFix(s.id, "artist", s.artist, a2, "removed site name", true)
            val al2 = clean(s.album)
            if (al2.isNotBlank() && al2 != s.album) out += LibraryFix(s.id, "album", s.album, al2, "removed site name", true)
        }
        return out
    }

    /** Online album lookup for unknown albums. Exact artist + title match only. Blocking; 1 request / 1.2 s. */
    fun lookupAlbums(context: Context, limit: Int, progress: (Int, Int) -> Unit, keepGoing: () -> Boolean): List<LibraryFix> {
        val songs = getOrLoadMusicIndex(context).songs.filter { unknownAlbum(it.album) && !unknown(it.artist) }.take(limit)
        val out = ArrayList<LibraryFix>()
        fun k(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
        songs.forEachIndexed { i, s ->
            if (!keepGoing()) return out
            progress(i + 1, songs.size)
            val artist = OnlineArt.primaryArtist(s.artist)
            val q = URLEncoder.encode("recording:\"${s.title}\" AND artist:\"$artist\"", "UTF-8")
            val doc = runCatching { OnlineArt.musicBrainz("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=5") }.getOrNull() ?: return@forEachIndexed
            val recs = doc.optJSONArray("recordings") ?: return@forEachIndexed
            for (r in 0 until recs.length()) {
                val rec = recs.optJSONObject(r) ?: continue
                if (k(rec.optString("title")) != k(s.title)) continue
                val credit = rec.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name").orEmpty()
                if (k(credit) != k(artist)) continue
                val releases = rec.optJSONArray("releases") ?: continue
                val rel = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
                    .firstOrNull { it.optJSONObject("release-group")?.optString("primary-type") == "Album" } ?: releases.optJSONObject(0) ?: continue
                val album = rel.optString("title")
                if (album.isNotBlank()) {
                    val year = rel.optString("date").take(4)
                    out += LibraryFix(s.id, "album", s.album, album, "musicbrainz: ${s.title} · $artist" + if (year.isNotBlank()) " · $year" else "", false)
                }
                break
            }
        }
        return out
    }

    /** Apply as Glide display overrides and remember the previous values for undo. */
    fun apply(context: Context, fixes: List<LibraryFix>) {
        if (fixes.isEmpty()) return
        val tags = context.getSharedPreferences(TAGS, Context.MODE_PRIVATE)
        val undo = JSONArray()
        val e = tags.edit()
        for (f in fixes) {
            val k = key(f.songId, f.field)
            undo.put(JSONObject().put("k", k).put("v", tags.getString(k, null) ?: JSONObject.NULL))
            e.putString(k, f.new)
        }
        e.apply()
        val u = context.getSharedPreferences(UNDO, Context.MODE_PRIVATE)
        val stack = runCatching { JSONArray(u.getString("stack", "[]")) }.getOrDefault(JSONArray())
        stack.put(undo)
        while (stack.length() > 10) stack.remove(0)
        u.edit().putString("stack", stack.toString()).apply()
    }

    fun canUndo(context: Context): Boolean =
        runCatching { JSONArray(context.getSharedPreferences(UNDO, Context.MODE_PRIVATE).getString("stack", "[]")).length() > 0 }.getOrDefault(false)

    /** Undo the last applied batch. Returns how many values were restored. */
    fun undoLast(context: Context): Int {
        val u = context.getSharedPreferences(UNDO, Context.MODE_PRIVATE)
        val stack = runCatching { JSONArray(u.getString("stack", "[]")) }.getOrDefault(JSONArray())
        if (stack.length() == 0) return 0
        val last = stack.getJSONArray(stack.length() - 1)
        val e = context.getSharedPreferences(TAGS, Context.MODE_PRIVATE).edit()
        for (i in 0 until last.length()) {
            val o = last.getJSONObject(i)
            if (o.isNull("v")) e.remove(o.getString("k")) else e.putString(o.getString("k"), o.getString("v"))
        }
        e.apply()
        stack.remove(stack.length() - 1)
        u.edit().putString("stack", stack.toString()).apply()
        return last.length()
    }
}

/** Full-screen Zune-style fixer. onChanged() reloads the library. */
@Composable
internal fun GlideLibraryFixerSheet(context: Context, onClose: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var fixes by remember { mutableStateOf<List<LibraryFix>?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var status by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(GlideLibraryFixer.canUndo(context)) }
    LaunchedEffect(Unit) {
        val f = withContext(Dispatchers.IO) { runCatching { GlideLibraryFixer.scan(context) }.getOrDefault(emptyList()) }
        fixes = f
        picked = f.indices.filter { f[it].safe }.toSet()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().metroTurnstileIn().background(GlideMetroStyle.ink).padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 12.dp)) {
            Text("fix library", style = Zune.style(40.sp, FontWeight.Light))
            Text("only how glide shows your music changes. audio files and tags are never touched. every fix can be undone.",
                style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
            Spacer(Modifier.height(8.dp))
            val list = fixes
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (list != null) {
                    FixWord("apply ${picked.size}", Zune.magenta) {
                        val chosen = picked.sorted().mapNotNull { list.getOrNull(it) }
                        GlideLibraryFixer.apply(context, chosen)
                        status = "${chosen.size} fixes applied"; canUndo = true
                        fixes = list.filterIndexed { i, _ -> i !in picked }; picked = emptySet(); onChanged()
                    }
                    FixWord(if (picked.size == list.size) "none" else "all") {
                        picked = if (picked.size == list.size) emptySet() else list.indices.toSet()
                    }
                }
                if (canUndo) FixWord("undo last") {
                    val n = GlideLibraryFixer.undoLast(context); status = "$n values restored"
                    canUndo = GlideLibraryFixer.canUndo(context); onChanged()
                }
                FixWord("close", Zune.grey, onClose)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FixWord(if (online) "stop album lookup" else "find unknown albums online") {
                    if (online) { stop = true } else if (!OnlineArt.enabled(context)) status = "turn on online artwork in settings first"
                    else { online = true; stop = false
                        scope.launch {
                            val found = withContext(Dispatchers.IO) {
                                GlideLibraryFixer.lookupAlbums(context, 150, { i, n -> status = "looking up $i / $n" }) { !stop && isActive }
                            }
                            fixes = (fixes ?: emptyList()) + found
                            status = "${found.size} albums found — check them, then apply"
                            online = false
                        }
                    }
                }
            }
            if (status.isNotBlank()) Text(status, style = Zune.style(12.sp, FontWeight.Normal, Zune.light))
            Spacer(Modifier.height(6.dp))
            when {
                list == null -> Text("checking your library…", style = Zune.style(16.sp, FontWeight.Light, Zune.grey))
                list.isEmpty() -> Text("nothing to fix", style = Zune.style(20.sp, FontWeight.Light))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list.indices.toList(), key = { it }) { i ->
                        val f = list[i]; val on = i in picked
                        Column(Modifier.fillMaxWidth().clickable { picked = if (on) picked - i else picked + i }.padding(vertical = 7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (on) "■ " else "□ ", style = Zune.style(16.sp, FontWeight.Normal, if (on) Zune.magenta else Zune.grey))
                                Text("${f.field}: ${f.new}", style = Zune.style(17.sp, FontWeight.Light), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("was \"${f.old}\" · ${f.reason}" + if (f.safe) "" else " · needs your ok",
                                style = Zune.style(11.sp, FontWeight.Normal, Zune.grey), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixWord(text: String, color: androidx.compose.ui.graphics.Color = Zune.light, onClick: () -> Unit) =
    Text(text, style = Zune.style(16.sp, FontWeight.Normal, color), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))
