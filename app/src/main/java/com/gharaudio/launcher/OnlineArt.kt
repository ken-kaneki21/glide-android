package com.gharaudio.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Optional, bounded and read-only artwork enrichment. Never rewrites audio tags or originals. */
internal object OnlineArt {
    private val networkGate = java.util.concurrent.Semaphore(1)
    // v1.11d3: separate budgets so a Library fling full of cover-less albums can't starve artist photos.
    private val albumLookups = AtomicInteger(0)
    private val artistLookups = AtomicInteger(0)
    private const val ALBUM_BUDGET = 96
    private const val ARTIST_BUDGET = 64
    private val mbArtistIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var lastMusicBrainzAt = 0L
    private val userAgent = "GlidePersonal/0.9 (noncommercial Android music UI; personal device)"
    @Volatile private var lastDiagnostic = "No request made"
    fun diagnostic(): String = lastDiagnostic
    private fun report(message: String) { lastDiagnostic = message.take(160) }
    private fun allowed(host: String): Boolean = host == "musicbrainz.org" ||
        host == "coverartarchive.org" || host == "archive.org" || host.endsWith(".archive.org") ||
        host == "en.wikipedia.org" || host == "upload.wikimedia.org" || host.endsWith(".wikimedia.org") ||
        host == "www.wikidata.org" ||
        // v1.11f2: Deezer (public catalogue, no key) and TheAudioDB (shared free key) for artist photos/bios.
        host == "api.deezer.com" || host.endsWith(".dzcdn.net") ||
        host == "www.theaudiodb.com" || host.endsWith(".theaudiodb.com") ||
        // C1: LRCLIB synced lyrics (free, open, no key)
        host == "lrclib.net"


    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getBoolean("verified_online_art", false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("verified_online_art", value).apply()
    }

    private fun key(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u001f").lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun albumFile(context: Context, artist: String, album: String): File =
        File(File(context.filesDir, "online_covers"), key(artist, album) + ".jpg")

    fun cachedArtistFile(context: Context, artist: String): File =
        File(File(context.filesDir, "online_portraits"), key(artist) + ".jpg")

    /** Install B: the main photo plus any extra photos of the same artist (rotated per song). */
    fun artistPhotoFiles(context: Context, artist: String): List<File> {
        val dir = File(context.filesDir, "online_portraits"); val k = key(artist)
        return (listOf(File(dir, "$k.jpg")) + (1..4).map { File(dir, "${k}_alt$it.jpg") }).filter { it.isFile }
    }

    /** Install B: download up to 4 extra photos (TheAudioDB fan art / thumb) once per artist. Blocking; IO only. */
    fun fetchArtistAlternates(context: Context, artist: String) {
        if (!enabled(context)) return
        val dir = File(context.filesDir, "online_portraits"); val k = key(artist)
        val done = File(dir, "${k}_alt.done")
        if (done.isFile) return
        val id = artistIdFor(context, primaryArtist(artist)) ?: run { dir.mkdirs(); done.writeText(""); return }
        val a = audioDbArtist(id) ?: run { dir.mkdirs(); done.writeText(""); return }
        var n = 0
        for (field in listOf("strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4", "strArtistThumb")) {
            if (n >= 4) break
            val url = a.optString(field).takeIf { it.startsWith("https://") } ?: continue
            val bytes = request(url, 4 * 1024 * 1024) ?: continue
            saveValidated(File(dir, "${k}_alt${n + 1}.jpg"), bytes, 360)?.recycle() // B2: decode ≤720 px, not 1440
            n++
        }
        dir.mkdirs(); done.writeText("$n")
    }

    private fun negativeKey(prefix: String, name: String): String = "fail_${prefix}_${key(name)}"

    private fun eligible(context: Context, prefix: String, name: String): Boolean {
        if (!enabled(context)) return false
        val last = context.getSharedPreferences("glide_enrichment", Context.MODE_PRIVATE)
            .getLong(negativeKey(prefix, name), 0L)
        return System.currentTimeMillis() - last > 10L * 60 * 1000
    }

    private fun markUnavailable(context: Context, prefix: String, name: String) {
        context.getSharedPreferences("glide_enrichment", Context.MODE_PRIVATE).edit()
            .putLong(negativeKey(prefix, name), System.currentTimeMillis()).apply()
    }

    /** Explicit user refresh. Preserve successful cached photos and covers; clear negative retry timers. */
    fun clearFailure(context: Context, artist: String, albums: List<String>) {
        report("Refreshing verified sources for $artist")
        val e = context.getSharedPreferences("glide_enrichment", Context.MODE_PRIVATE).edit()
        e.remove(negativeKey("artist", artist))
        // v1.11f2: re-decide the artist identity too (e.g. after the Library loaded your album titles).
        mbArtistIds.remove(artist.trim().lowercase(Locale.ROOT))
        idPrefs(context).edit().remove(artist.trim().lowercase(Locale.ROOT)).apply()
        albums.forEach { e.remove(negativeKey("album", "$artist / $it")) }
        e.apply()
    }

    private fun quota(context: Context, counter: AtomicInteger, budget: Int): Boolean =
        enabled(context) && (batch || counter.get() < budget)

    private val deferred = ThreadLocal<Boolean>()
    /** True if this thread's last lookup was skipped because another lookup was in flight. */
    fun wasDeferred(): Boolean = deferred.get() == true

    /** One provider lookup at a time, bounded per process. Cache and explicit retry are preferred.
     *  v1.10e 0.6: never park a Dispatchers.IO thread waiting for the gate; skip and retry later. */
    private inline fun <T> gated(counter: AtomicInteger, budget: Int, block: () -> T?): T? {
        if (batch) networkGate.acquire() else if (!networkGate.tryAcquire()) { deferred.set(true); return null }
        return try { if (counter.incrementAndGet() > budget && !batch) { report("Request limit reached; restart Glide to retry"); null } else block() }
        finally { networkGate.release() }
    }

    internal fun request(url: String, maxBytes: Int): ByteArray? = requestRedirect(url, maxBytes, 0)
    internal fun json(url: String, maxBytes: Int = 512 * 1024): JSONObject? =
        try { request(url, maxBytes)?.let { JSONObject(String(it, Charsets.UTF_8)) } } catch (_: Exception) { null }

    private fun requestRedirect(url: String, maxBytes: Int, redirects: Int): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val parsed = URL(url)
            if (parsed.protocol != "https" || !allowed(parsed.host.lowercase(Locale.ROOT)) || redirects > 3) {
                report("Provider redirect rejected")
                return null
            }
            conn = parsed.openConnection() as HttpURLConnection
            conn.connectTimeout = 6500
            conn.readTimeout = 8500
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json, image/jpeg, image/png, */*")
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: return null
                return requestRedirect(URL(parsed, location).toString(), maxBytes, redirects + 1)
            }
            if (code != 200) {
                report("${parsed.host}: HTTP $code")
                return null
            }
            if (conn.contentLengthLong > maxBytes.toLong()) {
                report("${parsed.host}: response exceeds size limit")
                return null
            }
            val bytes = conn.inputStream.use { readBounded(it, maxBytes) }
            if (bytes == null) report("${parsed.host}: response exceeded size limit")
            bytes
        } catch (e: Exception) {
            report("Network error: ${e.javaClass.simpleName}")
            null
        } finally { conn?.disconnect() }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (output.size() + count > maxBytes) return null
            output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }

    @Synchronized
    internal fun musicBrainz(url: String): JSONObject? {
        val now = System.currentTimeMillis()
        val sleep = 1200L - (now - lastMusicBrainzAt)
        if (sleep > 0) Thread.sleep(sleep)
        lastMusicBrainzAt = System.currentTimeMillis()
        return request(url, 512 * 1024)?.let { JSONObject(String(it, Charsets.UTF_8)) }
    }

    /** v1.11k: "a.r. rahman" == "A. R. Rahman": compare letters and digits only. */
    private fun nameKey(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    private fun exactly(a: String, b: String): Boolean = nameKey(a).isNotEmpty() && nameKey(a) == nameKey(b)

    /** v1.11k: the lead artist of a credit like "A.R. Rahman, Shreya Ghoshal" or "a. r. rahman & chinmayi". */
    internal fun primaryArtist(name: String): String =
        name.split(Regex("\\s*(?:,|;|/|&|\\bfeat\\.?|\\bft\\.|\\bfeaturing\\b|\\sx\\s|\\swith\\s)\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim() ?: name.trim()

    /** v1.11k: one-tap "get all artwork": lifts the per-session budgets and waits its turn instead of skipping. */
    @Volatile var batch = false

    /** v1.11f2: album titles reduced to comparable keys: "Minutes to Midnight (Deluxe Edition)" -> "minutes to midnight". */
    private fun albumKey(title: String): String = title.lowercase(Locale.ROOT)
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun localAlbumKeys(context: Context, artist: String): Set<String> =
        try { localAlbumsFor(context, artist).map(::albumKey).filter { it.length >= 2 }.toSet() } catch (_: Exception) { emptySet() }

    private fun idPrefs(context: Context) = context.getSharedPreferences("glide_artist_ids", Context.MODE_PRIVATE)

    /** One MusicBrainz identity per artist name, shared by photo and biography. With several artists of the
     *  same name, the one whose release groups match YOUR album titles wins; no match = no guess. */
    internal fun artistIdFor(context: Context, name: String): String? {
        val nameKey = name.trim().lowercase(Locale.ROOT)
        mbArtistIds[nameKey]?.let { return it }
        idPrefs(context).getString(nameKey, null)?.let { mbArtistIds[nameKey] = it; return it }
        if (name.isBlank() || name.equals("unknown artist", true)) return null
        val query = URLEncoder.encode("artist:\"${name.replace('.', ' ').replace(Regex("\\s+"), " ").trim()}\"", "UTF-8")
        val arr = musicBrainz("https://musicbrainz.org/ws/2/artist/?query=$query&fmt=json&limit=8")
            ?.optJSONArray("artists") ?: return null
        val matches = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
            .filter { exactly(it.optString("name"), name) &&
                it.optString("id").matches(Regex("[a-fA-F0-9-]{36}")) }
        val id = when {
            matches.isEmpty() -> { report("No MusicBrainz artist named $name"); null }
            matches.size == 1 -> matches.single().optString("id")
            else -> {
                val mine = localAlbumKeys(context, name)
                if (mine.isEmpty()) { report("${matches.size} artists named $name; open the Library once so albums can decide"); null }
                else {
                    val scored = matches.take(4).map { m ->
                        val id = m.optString("id")
                        val groups = musicBrainz("https://musicbrainz.org/ws/2/release-group?artist=$id&limit=100&fmt=json")
                            ?.optJSONArray("release-groups")
                        val theirs = (0 until (groups?.length() ?: 0)).mapNotNull { groups?.optJSONObject(it)?.optString("title") }
                            .map(::albumKey).toSet()
                        id to mine.count { it in theirs }
                    }.sortedByDescending { it.second }
                    val best = scored.firstOrNull()
                    if (best == null || best.second == 0 || scored.getOrNull(1)?.second == best.second) {
                        report("${matches.size} artists named $name; none matches your albums"); null
                    } else best.first
                }
            }
        } ?: return null
        mbArtistIds[nameKey] = id
        idPrefs(context).edit().putString(nameKey, id).apply()
        return id
    }

    /** Deezer artist photo (1000 px), accepted only when the name is exact AND (with a local library) at least
     *  one Deezer album matches yours, or when it is the only exact-name artist and no library is loaded. */
    private fun deezerPhoto(context: Context, name: String): Pair<ByteArray, String>? {
        val q = URLEncoder.encode(name, "UTF-8")
        val list = json("https://api.deezer.com/search/artist?q=$q&limit=10")?.optJSONArray("data") ?: return null
        val exact = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { exactly(it.optString("name"), name) }
        if (exact.isEmpty()) return null
        val mine = localAlbumKeys(context, name)
        val chosen = if (mine.isEmpty()) exact.singleOrNull() else exact.take(3).firstOrNull { a ->
            val albums = json("https://api.deezer.com/artist/${a.optLong("id")}/albums?limit=100")?.optJSONArray("data")
            (0 until (albums?.length() ?: 0)).any { albumKey(albums!!.optJSONObject(it)?.optString("title").orEmpty()) in mine }
        }
        val url = chosen?.optString("picture_xl").orEmpty()
        // Deezer's grey placeholder has an empty image hash: ".../images/artist//1000x1000..."
        if (!url.startsWith("https://") || url.contains("/artist//")) return null
        return request(url, 3 * 1024 * 1024)?.let { it to url }
    }

    /** TheAudioDB (shared free key "123", 30 req/min) by MusicBrainz ID: wide fanart, else thumb. */
    internal fun audioDbArtist(mbid: String): JSONObject? =
        json("https://www.theaudiodb.com/api/v1/json/123/artist-mb.php?i=$mbid", 256 * 1024)
            ?.optJSONArray("artists")?.optJSONObject(0)

    private fun audioDbPhoto(mbid: String): Pair<ByteArray, String>? {
        val a = audioDbArtist(mbid) ?: return null
        for (field in listOf("strArtistThumb", "strArtistFanart", "strArtistFanart2")) {
            val url = a.optString(field).takeIf { it.startsWith("https://") } ?: continue
            request(url, 4 * 1024 * 1024)?.let { return it to url }
        }
        return null
    }

    fun albumCover(context: Context, artist: String, album: String): Bitmap? {
        deferred.set(false)
        val cached = albumFile(context, artist, album)
        if (cached.isFile) return decode(cached, 512)
        val combo = "$artist / $album"
        if (!eligible(context, "album", combo) || !quota(context, albumLookups, ALBUM_BUDGET) ||
            album.isBlank() || artist.isBlank() || album.equals("unknown album", true)) return null
        return gated(albumLookups, ALBUM_BUDGET) {
            try {
                val artistMbId = artistIdFor(context, artist) ?: return@gated null
                val query = URLEncoder.encode("releasegroup:\"$album\" AND arid:$artistMbId", "UTF-8")
                val result = musicBrainz("https://musicbrainz.org/ws/2/release-group/?query=$query&fmt=json&limit=10")
                    ?.optJSONArray("release-groups") ?: return@gated null
                var match: String? = null
                for (i in 0 until result.length()) {
                    val record = result.optJSONObject(i) ?: continue
                    val candidate = record.optString("title")
                    val typ = record.optString("primary-type")
                    if (!exactly(candidate, album) || (typ.isNotBlank() && typ !in listOf("Album", "EP", "Single"))) continue
                    val credits = record.optJSONArray("artist-credit") ?: continue
                    val first = credits.optJSONObject(0)?.optJSONObject("artist") ?: continue
                    if (first.optString("id") != artistMbId) continue
                    val id = record.optString("id")
                    if (id.matches(Regex("[a-fA-F0-9-]{36}"))) { match = id; break }
                }
                val mbid = match ?: run { report("No matching release group for $album"); return@gated null }
                val bytes = request("https://coverartarchive.org/release-group/$mbid/front-500", 2 * 1024 * 1024)
                    ?: return@gated null
                saveValidated(cached, bytes, 512)?.also {
                    report("Album cover cached: $album")
                    File(cached.parentFile, cached.nameWithoutExtension + ".source.txt").writeText(
                        "MusicBrainz release-group: https://musicbrainz.org/release-group/$mbid\nCover Art Archive: https://coverartarchive.org/release-group/$mbid\n")
                }
            } catch (_: Exception) { null }
            finally { markUnavailable(context, "album", combo) }
        }
    }

    fun artistBackdrop(context: Context, artist: String): Bitmap? {
        deferred.set(false)
        val who = primaryArtist(artist)
        val cached = cachedArtistFile(context, artist)
        if (cached.isFile) return decode(cached, 720)
        if (!eligible(context, "artist", artist) || !quota(context, artistLookups, ARTIST_BUDGET)) return null
        return gated(artistLookups, ARTIST_BUDGET) {
            try {
                deezerPhoto(context, who)?.let { (bytes, url) ->
                    saveValidated(cached, bytes, 720)?.let { bmp ->
                        report("Artist photo from Deezer: $artist")
                        File(cached.parentFile, cached.nameWithoutExtension + ".source.txt").writeText("Photo: $url (Deezer)\n")
                        return@gated bmp
                    }
                }
                val id = artistIdFor(context, who) ?: return@gated null
                audioDbPhoto(id)?.let { (bytes, url) ->
                    saveValidated(cached, bytes, 720)?.let { bmp ->
                        report("Artist photo from TheAudioDB: $artist")
                        File(cached.parentFile, cached.nameWithoutExtension + ".source.txt").writeText(
                            "Artist MusicBrainz: https://musicbrainz.org/artist/$id\nPhoto: $url (TheAudioDB)\n")
                        return@gated bmp
                    }
                }
                val relations = musicBrainz("https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json")
                    ?.optJSONArray("relations") ?: return@gated null
                // v1.11d2: MusicBrainz now links most artists to Wikidata, not Wikipedia. Read both.
                var pageTitle: String? = null
                var qid: String? = null
                for (i in 0 until relations.length()) {
                    val rel = relations.optJSONObject(i) ?: continue
                    val resource = rel.optJSONObject("url")?.optString("resource").orEmpty()
                    when (rel.optString("type")) {
                        "wikipedia" -> if (pageTitle == null && resource.startsWith("https://en.wikipedia.org/wiki/"))
                            pageTitle = resource.substringAfterLast("/")
                        "wikidata" -> if (qid == null) {
                            val q = resource.substringAfterLast("/")
                            if (q.matches(Regex("Q[0-9]{1,12}"))) qid = q
                        }
                    }
                }
                // Wikidata gives the English Wikipedia title (sitelink) and the photo file (P18).
                var commonsFile: String? = null
                if (qid != null) {
                    val entity = request("https://www.wikidata.org/wiki/Special:EntityData/$qid.json", 4 * 1024 * 1024)
                        ?.let { JSONObject(String(it, Charsets.UTF_8)) }
                        ?.optJSONObject("entities")?.optJSONObject(qid)
                    if (pageTitle == null) {
                        pageTitle = entity?.optJSONObject("sitelinks")?.optJSONObject("enwiki")?.optString("title")
                            ?.takeIf { it.isNotBlank() }?.replace(' ', '_')
                            ?.let { URLEncoder.encode(it, "UTF-8").replace("+", "_").replace("%2F", "/") }
                    }
                    commonsFile = entity?.optJSONObject("claims")?.optJSONArray("P18")?.optJSONObject(0)
                        ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
                        ?.takeIf { it.isNotBlank() && !it.contains('/') }
                }
                // Photo: Wikidata P18 at 1080 px (sharp enough for a full-screen background),
                // else the Wikipedia summary's original image, else its thumbnail.
                var source = ""
                var bytes: ByteArray? = null
                if (commonsFile != null) {
                    val file = URLEncoder.encode(commonsFile.replace(' ', '_'), "UTF-8").replace("+", "_")
                    source = "https://commons.wikimedia.org/wiki/Special:FilePath/$file?width=1080"
                    bytes = request(source, 4 * 1024 * 1024)
                }
                if (bytes == null && pageTitle != null) {
                    val summary = request("https://en.wikipedia.org/api/rest_v1/page/summary/$pageTitle", 128 * 1024)
                        ?.let { JSONObject(String(it, Charsets.UTF_8)) }
                    if (summary != null && summary.optString("type") != "disambiguation") {
                        val original = summary.optJSONObject("originalimage")?.optString("source").orEmpty()
                        val thumb = summary.optJSONObject("thumbnail")?.optString("source").orEmpty()
                        for (candidate in listOf(original, thumb)) {
                            if (!candidate.startsWith("https://upload.wikimedia.org/")) continue
                            bytes = request(candidate, 6 * 1024 * 1024)
                            if (bytes != null) { source = candidate; break }
                        }
                    }
                }
                val photo = bytes ?: run {
                    report(if (qid == null && pageTitle == null) "No Wikipedia or Wikidata link for $artist" else "No photo found for $artist")
                    return@gated null
                }
                saveValidated(cached, photo, 720)?.also {
                    report("Artist portrait cached: $artist")
                    File(cached.parentFile, cached.nameWithoutExtension + ".source.txt").writeText(
                        "Artist MusicBrainz: https://musicbrainz.org/artist/$id\n" +
                        (if (qid != null) "Wikidata: https://www.wikidata.org/wiki/$qid\n" else "") +
                        (if (pageTitle != null) "Wikipedia: https://en.wikipedia.org/wiki/$pageTitle\n" else "") +
                        "Photo: $source\nCheck photo license and attribution before redistribution.\n")
                }
            } catch (_: Exception) { null }
            finally { markUnavailable(context, "artist", artist) }
        }
    }

    private fun saveValidated(file: File, bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 64 || bounds.outHeight < 64 || bounds.outWidth > 9000 || bounds.outHeight > 9000) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        file.parentFile?.mkdirs()
        if ((file.parentFile?.listFiles()?.sumOf { it.length() } ?: 0L) > 40L * 1024 * 1024) return bitmap
        val tmp = File(file.absolutePath + ".partial")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: Exception) { tmp.delete() }
        return bitmap
    }

    private fun decode(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) { null }
}
