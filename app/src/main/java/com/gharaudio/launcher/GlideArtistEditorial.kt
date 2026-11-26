package com.gharaudio.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/** Read-only, opt-in biography.
 *  v1.11f2: uses the SAME artist identity as the photo (OnlineArt.artistIdFor: several artists with one name are
 *  told apart by your own album titles), follows MusicBrainz -> Wikipedia OR Wikidata -> English Wikipedia,
 *  and falls back to TheAudioDB's English biography. Never guesses compound credits; never touches audio files. */
internal object GlideArtistEditorial {
    data class Result(val name: String, val summary: String, val url: String, val source: String, val updated: Long)
    private val gate = java.util.concurrent.Semaphore(1)
    private const val WEEK = 7L * 86400000L
    @Volatile private var diagnosticText = "No biography request made"
    fun diagnostic(): String = diagnosticText

    private fun cacheFile(context: Context, artist: String): java.io.File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(artist.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(context.filesDir, "glide_artist_bios"), "$hash.json")
    }
    private fun parse(raw: String): Result? = try {
        val j = JSONObject(raw)
        val text = j.optString("summary")
        val link = j.optString("url")
        if (text.isBlank() || !link.startsWith("https://")) null
        else Result(j.optString("name"), text, link, j.optString("source", "wikipedia"), j.optLong("updated"))
    } catch (_: Exception) { null }

    /** Install C+: cached biography text only (no network), for the screen saver. */
    fun cachedSummary(context: Context, artist: String): String? =
        runCatching { cacheFile(context, artist).takeIf { it.isFile }?.readText()?.let(::parse)?.summary }.getOrNull()

    fun invalidate(context: Context, artist: String) {
        cacheFile(context, artist).delete()
        diagnosticText = "Retry requested"
    }

    private fun wikipediaSummary(title: String): Pair<String, String>? {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "_").replace("%2F", "/")
        val wp = OnlineArt.json("https://en.wikipedia.org/api/rest_v1/page/summary/$path", 180_000) ?: return null
        if (wp.optString("type") == "disambiguation") return null
        val summary = wp.optString("extract").trim().take(1400)
        if (summary.length < 40) return null
        return summary to "https://en.wikipedia.org/wiki/$path"
    }

    fun load(context: Context, artist: String, force: Boolean = false): Result? {
        if (artist.isBlank() || artist.equals("unknown artist", true) || artist.equals("<unknown>", true) || artist.length > 120) return null
        val who = OnlineArt.primaryArtist(artist) // v1.11k: "A.R. Rahman, Shreya Ghoshal" -> A.R. Rahman
        val file = cacheFile(context, artist)
        val old = if (file.isFile) try { parse(file.readText()) } catch (_: Exception) { null } else null
        if (!force && old != null && System.currentTimeMillis() - old.updated < WEEK) return old
        gate.acquire()
        return try {
            val id = OnlineArt.artistIdFor(context, who)
            if (id == null) { diagnosticText = OnlineArt.diagnostic(); return old }
            var title: String? = null
            var qid: String? = null
            val relations = OnlineArt.musicBrainz("https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json")
                ?.optJSONArray("relations")
            for (i in 0 until (relations?.length() ?: 0)) {
                val rel = relations!!.optJSONObject(i) ?: continue
                val resource = rel.optJSONObject("url")?.optString("resource").orEmpty()
                when (rel.optString("type")) {
                    "wikipedia" -> if (title == null && resource.startsWith("https://en.wikipedia.org/wiki/"))
                        title = Uri.decode(resource.substringAfter("/wiki/"))
                    "wikidata" -> if (qid == null) resource.substringAfterLast("/").takeIf { it.matches(Regex("Q[0-9]{1,12}")) }?.let { qid = it }
                }
            }
            if (title == null && qid != null) {
                title = OnlineArt.json("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid&props=sitelinks&sitefilter=enwiki&format=json", 64 * 1024)
                    ?.optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("sitelinks")
                    ?.optJSONObject("enwiki")?.optString("title")?.takeIf { it.isNotBlank() }
            }
            var result: Result? = title?.let(::wikipediaSummary)?.let { (text, url) ->
                Result(artist, text, url, "wikipedia", System.currentTimeMillis())
            }
            if (result == null) {
                val a = OnlineArt.audioDbArtist(id)
                val text = a?.optString("strBiographyEN")?.trim().orEmpty()
                if (text.length >= 40) {
                    val cut = text.take(1400).let { t -> t.lastIndexOf(". ").takeIf { it > 300 }?.let { t.take(it + 1) } ?: t }
                    result = Result(artist, cut, "https://www.theaudiodb.com/artist/${a!!.optString("idArtist")}",
                        "theaudiodb", System.currentTimeMillis())
                }
            }
            if (result == null) { diagnosticText = "No biography on Wikipedia or TheAudioDB"; return old }
            diagnosticText = "Biography updated"
            try {
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("name", result.name).put("summary", result.summary)
                    .put("url", result.url).put("source", result.source).put("updated", result.updated).toString())
            } catch (_: Exception) { /* Cache failure is not content failure. */ }
            result
        } catch (_: Exception) { old }
        finally { gate.release() }
    }
}

/** Zune-styled biography block: Light body text, grey source line, quiet retry glyph. */
@Composable
internal fun GlideVerifiedArtistBio(context: Context, artist: String, enabled: Boolean, revision: Int = 0) {
    if (!enabled) {
        Text("turn on online artwork in settings to see biographies",
            style = Zune.style(15.sp, FontWeight.Normal, Zune.grey))
        return
    }
    var retries by remember(artist, revision) { mutableIntStateOf(0) }
    var note by remember(artist, revision, retries) { mutableStateOf("") }
    var loading by remember(artist, retries) { mutableStateOf(true) }
    var bio by remember(artist, retries) { mutableStateOf<GlideArtistEditorial.Result?>(null) }
    LaunchedEffect(artist, retries, revision) {
        loading = true
        bio = withContext(Dispatchers.IO) {
            GlideArtistEditorial.load(context, artist, force = retries > 0 || revision > 0)
        }
        note = GlideArtistEditorial.diagnostic()
        loading = false
    }
    when {
        loading -> Text("looking up…", style = Zune.style(15.sp, FontWeight.Normal, Zune.grey))
        bio == null -> {
            Text("no biography found", style = Zune.style(20.sp, FontWeight.Light))
            Text(note.lowercase(Locale.getDefault()), style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
        }
        else -> {
            Text(bio!!.summary, style = Zune.style(16.sp, FontWeight.Light), lineHeight = 24.sp)
            Spacer(Modifier.height(12.dp))
            Text("source: ${bio!!.source}", style = Zune.style(13.sp, FontWeight.Normal, Zune.grey),
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bio!!.url)))
                }.padding(vertical = 6.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.clickable(enabled = !loading) { retries++ }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ZuneIcon("refresh", Zune.grey, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("refresh", style = Zune.style(15.sp, FontWeight.Normal, Zune.grey))
    }
}
