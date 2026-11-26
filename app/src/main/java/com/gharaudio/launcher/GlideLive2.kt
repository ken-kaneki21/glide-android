package com.gharaudio.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Install B: Live 2.0 (Zune HD style).
 *  following  - new releases (Deezer, newest first) and news (Google News RSS) only for artists you follow.
 *               The follow list starts from your most-played library artists; edit it under "artists".
 *  sports     - EPL scores + news (ESPN public JSON, unofficial), cricket news for India (Google News RSS).
 *  concerts   - concert / tour news for Bengaluru and your followed artists (Google News RSS). No ticket API.
 *  weather    - Open-Meteo (unchanged source) with readable day names. Animated scenes come in Install C.
 * Everything is fetched only when you open Live and it is older than its refresh time, or when you tap refresh.
 */

internal data class LiveItem(val title: String, val detail: String, val url: String = "", val time: Long = 0L)

private const val UA = "GlidePersonal/2.0 (personal LG G6 music launcher; noncommercial)"
private fun enc2(s: String) = URLEncoder.encode(s, "UTF-8")

private fun getText(url: String, max: Int = 1_500_000): String {
    require(url.startsWith("https://"))
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 9000
    c.setRequestProperty("User-Agent", UA)
    try {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { s -> val b = ByteArray(8192); while (true) { val n = s.read(b); if (n < 0) break; out.write(b, 0, n); if (out.size() > max) break } }
        return out.toString("UTF-8")
    } finally { c.disconnect() }
}

private val rssDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
private fun unescape(s: String) = s.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
private fun tag(item: String, name: String) = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::unescape).orEmpty()

/** Google News RSS search (free, no key). Titles come as "headline - Source". */
private fun news(query: String, limit: Int): List<LiveItem> {
    val xml = getText("https://news.google.com/rss/search?q=${enc2(query)}&hl=en-IN&gl=IN&ceid=IN:en")
    return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(limit).mapNotNull { m ->
        val it = m.groupValues[1]
        val full = tag(it, "title").ifBlank { return@mapNotNull null }
        val source = tag(it, "source")
        val headline = if (source.isNotBlank() && full.endsWith(" - $source")) full.removeSuffix(" - $source") else full
        val t = runCatching { rssDate.parse(tag(it, "pubDate"))?.time ?: 0L }.getOrDefault(0L)
        LiveItem(headline, source.lowercase(Locale.ROOT), tag(it, "link"), t)
    }.toList()
}

/** Newest releases of one artist from Deezer (exact name match only). */
private fun deezerReleases(artist: String, limit: Int): List<LiveItem> {
    val search = JSONObject(getText("https://api.deezer.com/search/artist?q=${enc2(artist)}&limit=5")).optJSONArray("data") ?: return emptyList()
    val key = artist.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    val hit = (0 until search.length()).mapNotNull { search.optJSONObject(it) }
        .firstOrNull { it.optString("name").lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "") == key } ?: return emptyList()
    val albums = JSONObject(getText("https://api.deezer.com/artist/${hit.optLong("id")}/albums?limit=$limit")).optJSONArray("data") ?: return emptyList()
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    return (0 until albums.length()).mapNotNull { albums.optJSONObject(it) }.map { a ->
        val date = a.optString("release_date")
        LiveItem(a.optString("title"), "${hit.optString("name").lowercase(Locale.ROOT)} · ${a.optString("record_type")} · $date",
            a.optString("link").replace("http://", "https://"), runCatching { day.parse(date)?.time ?: 0L }.getOrDefault(0L))
    }
}

/** EPL scoreboard from ESPN's public JSON (unofficial; may change without notice). */
private fun eplScores(): List<LiveItem> {
    val events = JSONObject(getText("https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard")).optJSONArray("events") ?: return emptyList()
    return (0 until events.length()).mapNotNull { events.optJSONObject(it) }.mapNotNull { e ->
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
        val teams = comp.optJSONArray("competitors") ?: return@mapNotNull null
        val sides = (0 until teams.length()).mapNotNull { teams.optJSONObject(it) }
        val home = sides.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
        val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
        fun n(o: JSONObject) = o.optJSONObject("team")?.optString("shortDisplayName").orEmpty().lowercase(Locale.ROOT)
        val status = e.optJSONObject("status")?.optJSONObject("type")
        val state = status?.optString("state").orEmpty()
        val title = if (state == "pre") "${n(home)} v ${n(away)}" else "${n(home)} ${home.optString("score")} – ${away.optString("score")} ${n(away)}"
        LiveItem(title, "epl · ${status?.optString("shortDetail").orEmpty().lowercase(Locale.ROOT)}",
            e.optJSONArray("links")?.optJSONObject(0)?.optString("href").orEmpty())
    }
}

private fun dayName(iso: String, i: Int): String = when (i) {
    0 -> "today"; 1 -> "tomorrow"
    else -> runCatching { SimpleDateFormat("EEEE", Locale.ENGLISH).format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso)!!).lowercase(Locale.ROOT) }.getOrDefault(iso)
}
private fun sky(code: Int) = when (code) { 0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "cloudy"; 45, 48 -> "fog"; in 51..67 -> "rain"
    in 71..77 -> "snow"; in 80..82 -> "showers"; in 95..99 -> "thunderstorm"; else -> "—" }

private fun weather(city: String): List<LiveItem> {
    val geo = JSONObject(getText("https://geocoding-api.open-meteo.com/v1/search?name=${enc2(city)}&count=1&format=json"))
        .optJSONArray("results")?.optJSONObject(0) ?: error("city not found")
    val doc = JSONObject(getText("https://api.open-meteo.com/v1/forecast?latitude=${geo.optDouble("latitude")}&longitude=${geo.optDouble("longitude")}" +
        "&current=temperature_2m,relative_humidity_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4&timezone=auto"))
    val cur = doc.optJSONObject("current") ?: error("no weather")
    val out = mutableListOf(LiveItem("${cur.optDouble("temperature_2m").toInt()}° ${sky(cur.optInt("weather_code"))}",
        "${city.lowercase(Locale.ROOT)} · humidity ${cur.optInt("relative_humidity_2m")}%",
        "wx:${cur.optInt("weather_code")}:${cur.optInt("is_day", 1)}"))
    val d = doc.optJSONObject("daily")
    val days = d?.optJSONArray("time"); val hi = d?.optJSONArray("temperature_2m_max"); val lo = d?.optJSONArray("temperature_2m_min"); val codes = d?.optJSONArray("weather_code")
    if (days != null && hi != null && lo != null && codes != null) for (i in 0 until minOf(4, days.length()))
        out += LiveItem("${dayName(days.optString(i), i)}  ${hi.optDouble(i).toInt()}° / ${lo.optDouble(i).toInt()}°", sky(codes.optInt(i)))
    return out
}

/** B2: artists you actually played most (Glide's play history), then fill from library size. */
private fun mostPlayedArtists(context: Context, n: Int): List<String> {
    val counts = runCatching {
        java.io.File(java.io.File(context.filesDir, "history"), "plays.tsv").takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { it.split('\t').getOrNull(4)?.let(OnlineArt::primaryArtist) }.filter(String::isNotBlank)
            .groupingBy { it }.eachCount()
    }.getOrDefault(emptyMap())
    val played = counts.entries.sortedByDescending { it.value }.map { it.key }
    val lib = topLibraryArtists(context, n + 10)
    return (played + lib).distinctBy { it.lowercase(Locale.ROOT) }.take(n)
}

/** ESPN cricket header feed (public but unofficial; may change). Returns live/recent matches. */
private fun cricketScores(): List<LiveItem> {
    val root = JSONObject(getText("https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&region=in&tz=Asia/Calcutta"))
    val out = mutableListOf<LiveItem>()
    val sports = root.optJSONArray("sports") ?: return out
    for (i in 0 until sports.length()) {
        val leagues = sports.optJSONObject(i)?.optJSONArray("leagues") ?: continue
        for (j in 0 until leagues.length()) {
            val lg = leagues.optJSONObject(j) ?: continue
            val league = (lg.optString("shortName").ifBlank { lg.optString("name") }).lowercase(Locale.ROOT)
            val events = lg.optJSONArray("events") ?: continue
            for (k in 0 until events.length()) {
                val e = events.optJSONObject(k) ?: continue
                val comps = e.optJSONArray("competitors")
                val sides = if (comps == null) emptyList() else (0 until comps.length()).mapNotNull { comps.optJSONObject(it) }
                val title = if (sides.size >= 2) sides.joinToString("  v  ") { c ->
                        val nm = c.optString("abbreviation").ifBlank { c.optString("displayName") }
                        (nm + " " + c.optString("score")).trim() }
                    else e.optString("shortName").ifBlank { e.optString("name") }
                if (title.isBlank()) continue
                val st = e.optString("summary").ifBlank { e.optJSONObject("fullStatus")?.optJSONObject("type")?.optString("shortDetail").orEmpty() }
                    .ifBlank { e.optString("status") }
                out += LiveItem(title, listOf("cricket", league, st.lowercase(Locale.ROOT)).filter(String::isNotBlank).joinToString(" · "),
                    e.optString("link").takeIf { it.startsWith("https://") }.orEmpty())
            }
        }
    }
    return out
}

/** Most-played artists in the library (by number of songs, lead artist only) — the default follow list. */
private fun topLibraryArtists(context: Context, n: Int): List<String> = runCatching {
    getOrLoadMusicIndex(context).songs.map { OnlineArt.primaryArtist(it.artist) }
        .filter { it.isNotBlank() && !it.equals("<unknown>", true) && !it.equals("unknown artist", true) }
        .groupingBy { it.lowercase(Locale.ROOT) }.eachCount().entries.sortedByDescending { it.value }.take(n)
        .map { e -> getOrLoadMusicIndex(context).songs.first { OnlineArt.primaryArtist(it.artist).equals(e.key, true) }.let { OnlineArt.primaryArtist(it.artist) } }
}.getOrDefault(emptyList())

private object LiveCache {
    fun save(context: Context, name: String, items: List<LiveItem>) {
        val arr = JSONArray(); items.forEach { arr.put(JSONObject().put("t", it.title).put("d", it.detail).put("u", it.url).put("w", it.time)) }
        context.getSharedPreferences("glide_live2", 0).edit().putString(name, arr.toString()).putLong("${name}_at", System.currentTimeMillis()).apply()
    }
    fun load(context: Context, name: String): Pair<List<LiveItem>, Long> {
        val p = context.getSharedPreferences("glide_live2", 0)
        val items = runCatching { val a = JSONArray(p.getString(name, "[]")); (0 until a.length()).map { a.getJSONObject(it) }
            .map { LiveItem(it.optString("t"), it.optString("d"), it.optString("u"), it.optLong("w")) } }.getOrDefault(emptyList())
        return items to p.getLong("${name}_at", 0L)
    }
}

@Composable
private fun LiveHeader(text: String) = Text(text, style = Zune.style(36.sp, FontWeight.Light, Zune.white, (-1).sp),
    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))

@Composable
private fun LiveLine(item: LiveItem, open: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = item.url.startsWith("https://")) { open(item.url) }.padding(vertical = 7.dp)) {
        Text(item.title.lowercase(Locale.getDefault()), style = Zune.style(20.sp, FontWeight.Light), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.detail.isNotBlank()) Text(item.detail, style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), maxLines = 1)
    }
}

@Composable
private fun LiveWord(text: String, color: androidx.compose.ui.graphics.Color = Zune.light, onClick: () -> Unit) =
    Text(text, style = Zune.style(16.sp, FontWeight.Normal, color), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp))

@Composable
internal fun GlideLive2(context: Context, active: Boolean = true) {
    var weatherPage by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE) }
    var city by remember { mutableStateOf(prefs.getString("live_city", "Bengaluru") ?: "Bengaluru") }
    var followed by remember { mutableStateOf((prefs.getString("live_followed", "") ?: "").split("|").filter(String::isNotBlank)) }
    var edit by remember { mutableStateOf(false) }
    // B2 setup window: sports you want and teams to put first
    var sports by remember { mutableStateOf((prefs.getString("live_sports", "cricket|football") ?: "").split("|").filter(String::isNotBlank).toSet()) }
    var teams by remember { mutableStateOf(prefs.getString("live_teams", "india") ?: "") }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var newArtist by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val sections = remember { mutableStateMapOf<String, Pair<List<LiveItem>, Long>>() }
    fun saveFollow(list: List<String>) { followed = list; prefs.edit().putString("live_followed", list.joinToString("|")).apply() }
    LaunchedEffect(Unit) {
        for (n in listOf("releases", "news", "sports", "concerts", "weather")) sections[n] = withContext(Dispatchers.IO) { LiveCache.load(context, n) }
        val top = withContext(Dispatchers.IO) { mostPlayedArtists(context, 30) }
        suggestions = top
        // B2: an almost-empty list (e.g. only one artist) is filled up to 12 from your most-played artists.
        if (followed.size < 3 && top.isNotEmpty()) saveFollow((followed + top).distinctBy { it.lowercase(Locale.ROOT) }.take(12))
    }
    LaunchedEffect(refresh, followed, sports, teams) {
        if (followed.isEmpty() && refresh == 0) return@LaunchedEffect
        loading = true
        val problems = mutableListOf<String>()
        val now = System.currentTimeMillis()
        suspend fun section(name: String, maxAgeMs: Long, load: () -> List<LiveItem>) {
            val old = sections[name]
            if (refresh == 0 && old != null && old.first.isNotEmpty() && now - old.second < maxAgeMs) return
            try {
                val fresh = withContext(Dispatchers.IO) { load() }
                if (fresh.isNotEmpty()) { sections[name] = fresh to now; withContext(Dispatchers.IO) { LiveCache.save(context, name, fresh) } }
            } catch (e: Exception) { problems += "$name: ${e.message ?: e.javaClass.simpleName}" }
        }
        val who = followed.take(12)
        section("releases", 12 * 3600_000L) {
            val yearAgo = now - 365L * 86400_000L
            who.flatMap { a -> runCatching { deezerReleases(a, 6) }.getOrDefault(emptyList()) }
                .filter { it.time >= yearAgo }.sortedByDescending { it.time }.take(15)
        }
        section("news", 3 * 3600_000L) {
            who.take(8).flatMap { a -> runCatching { news("\"$a\" when:14d", 3) }.getOrDefault(emptyList()) }
                .distinctBy { it.title }.sortedByDescending { it.time }.take(15)
        }
        section("sports", 1800_000L) {
            val team = teams.split(",").map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank)
            fun mine(i: LiveItem) = team.any { t -> i.title.lowercase(Locale.ROOT).contains(t) }
            val out = mutableListOf<LiveItem>()
            if ("cricket" in sports) {
                val sc = runCatching { cricketScores() }.onFailure { problems += "cricket scores: ${it.message}" }.getOrDefault(emptyList())
                out += sc.sortedByDescending(::mine).take(6)
                out += runCatching { news("India cricket when:2d", 4) }.getOrDefault(emptyList()).map { it.copy(detail = "cricket · ${it.detail}") }
            }
            if ("football" in sports) {
                out += runCatching { eplScores() }.getOrDefault(emptyList()).sortedByDescending(::mine).take(8)
                out += runCatching { news("Premier League when:2d", 3) }.getOrDefault(emptyList()).map { it.copy(detail = "epl · ${it.detail}") }
            }
            if ("f1" in sports) out += runCatching { news("Formula 1 when:3d", 3) }.getOrDefault(emptyList()).map { it.copy(detail = "f1 · ${it.detail}") }
            if ("tennis" in sports) out += runCatching { news("tennis ATP WTA when:2d", 3) }.getOrDefault(emptyList()).map { it.copy(detail = "tennis · ${it.detail}") }
            for (t in team.take(4)) out += runCatching { news("\"$t\" when:2d", 2) }.getOrDefault(emptyList()).map { it.copy(detail = "$t · ${it.detail}") }
            out.distinctBy { it.title }
        }
        section("concerts", 12 * 3600_000L) {
            (runCatching { news("concert $city when:30d", 6) }.getOrDefault(emptyList()) +
                who.take(6).flatMap { a -> runCatching { news("\"$a\" (tour OR concert) India when:60d", 2) }.getOrDefault(emptyList()) })
                .distinctBy { it.title }.sortedByDescending { it.time }.take(12)
        }
        section("weather", 1800_000L) { weather(city.trim().ifBlank { "Bengaluru" }) }
        status = if (problems.isEmpty()) "" else "some sources didn't answer: " + problems.joinToString("; ").take(140)
        loading = false
    }
    fun open(link: String) { if (link.startsWith("https://")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    fun ago(t: Long): String { if (t <= 0) return ""; val m = (System.currentTimeMillis() - t) / 60000; return when { m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" } }

    LazyColumn(Modifier.fillMaxSize().metroElastic(true), contentPadding = PaddingValues(start = 22.dp, end = 18.dp, top = 4.dp, bottom = 40.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading) "updating…" else "your music · your city", style = Zune.style(13.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.weight(1f))
                LiveWord("refresh") { if (!loading) refresh++ }
                Spacer(Modifier.width(14.dp))
                LiveWord(if (edit) "done" else "setup") { edit = !edit }
            }
            if (status.isNotBlank()) Text(status, style = Zune.style(11.sp, FontWeight.Normal, Zune.grey))
        }
        if (edit) item {
            // B2 setup window: sports, teams, and artist suggestions from what you play most
            Text("sports", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (sp in listOf("cricket", "football", "f1", "tennis")) {
                    val on = sp in sports
                    LiveWord(sp, if (on) Zune.magenta else Zune.grey) {
                        sports = if (on) sports - sp else sports + sp
                        prefs.edit().putString("live_sports", sports.joinToString("|")).apply()
                        LiveCache.save(context, "sports", emptyList()); sections.remove("sports")
                    }
                }
            }
            Text("teams (comma separated, shown first)", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.padding(top = 10.dp))
            BasicTextField(teams, { teams = it.take(120); prefs.edit().putString("live_teams", teams).apply() }, singleLine = true,
                textStyle = Zune.style(18.sp, FontWeight.Light), cursorBrush = SolidColor(Zune.white),
                modifier = Modifier.fillMaxWidth().drawBehind { drawLine(Zune.dim, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }.padding(vertical = 8.dp))
            Text("suggested artists · tap to follow", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.padding(top = 10.dp))
            Text("from what you play most", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
            Column {
                suggestions.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { a ->
                            val on = followed.any { it.equals(a, true) }
                            Box(Modifier.weight(1f)) {
                                LiveWord(a.lowercase(Locale.getDefault()), if (on) Zune.magenta else Zune.light) {
                                    saveFollow(if (on) followed.filterNot { it.equals(a, true) } else followed + a)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text("city", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.padding(top = 10.dp))
            BasicTextField(city, { city = it.take(60); prefs.edit().putString("live_city", city).apply() }, singleLine = true,
                textStyle = Zune.style(18.sp, FontWeight.Light), cursorBrush = SolidColor(Zune.white),
                modifier = Modifier.fillMaxWidth().drawBehind { drawLine(Zune.dim, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }.padding(vertical = 8.dp))
        }
        item { LiveHeader("weather") }
        val wx = sections["weather"]?.first.orEmpty()
        val wxCode = wx.firstOrNull()?.url?.takeIf { it.startsWith("wx:") }?.split(":")
        if (wxCode != null) item {
            // Install C: animated sky; tap for the full weather page
            Box(Modifier.fillMaxWidth().height(150.dp).clickable { weatherPage = true }) {
                GlideWeatherScene(wxCode.getOrNull(1)?.toIntOrNull() ?: 3, wxCode.getOrNull(2) != "0", active && !weatherPage, Modifier.fillMaxSize())
                Text(wx.first().title.substringBefore(' '), style = Zune.style(56.sp, FontWeight.Light),
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp))
            }
        }
        items(wx, key = { "w" + it.title }) { LiveLine(it.copy(url = ""), ::open) }
        item { LiveHeader("following") }
        val rel = sections["releases"]?.first.orEmpty()
        if (rel.isEmpty()) item { Text(if (followed.isEmpty()) "follow artists below to see their new music" else "no new releases this year", style = Zune.style(14.sp, FontWeight.Normal, Zune.grey)) }
        else { item { Text("new music", style = Zune.style(15.sp, FontWeight.Normal, Zune.light)) }; items(rel, key = { "r" + it.url + it.title }) { LiveLine(it, ::open) } }
        val nws = sections["news"]?.first.orEmpty()
        if (nws.isNotEmpty()) { item { Text("news", style = Zune.style(15.sp, FontWeight.Normal, Zune.light), modifier = Modifier.padding(top = 10.dp)) }
            items(nws, key = { "n" + it.url }) { LiveLine(it.copy(detail = listOf(it.detail, ago(it.time)).filter(String::isNotBlank).joinToString(" · ")), ::open) } }
        item { LiveHeader("sports") }
        val sp = sections["sports"]?.first.orEmpty()
        if (sp.isEmpty()) item { Text("nothing yet — tap refresh", style = Zune.style(14.sp, FontWeight.Normal, Zune.grey)) }
        else {
            // F1: grouped by sport, each with its own drawn logo; scores first, then news
            val groups = sp.groupBy { sportOf(it.detail) }
            for (sport in listOf("cricket", "football", "f1", "tennis", "teams")) {
                val list = groups[sport] ?: continue
                item(key = "sh$sport") { SportHeader(sport) }
                items(list.sortedBy { if (it.detail.contains("·") && it.title.any(Char::isDigit)) 0 else 1 },
                    key = { "s" + it.title + it.url }) { LiveLine(it.copy(detail = it.detail.substringAfter(" · ", it.detail)), ::open) }
            }
        }
        item { LiveHeader("concerts") }
        val cc = sections["concerts"]?.first.orEmpty()
        if (cc.isEmpty()) item { Text("no concert news near ${city.lowercase(Locale.ROOT)} yet", style = Zune.style(14.sp, FontWeight.Normal, Zune.grey)) }
        else items(cc, key = { "c" + it.url }) { LiveLine(it.copy(detail = listOf(it.detail, ago(it.time)).filter(String::isNotBlank).joinToString(" · ")), ::open) }
        item { LiveHeader("artists") }
        item {
            Text("you follow ${followed.size}. new music and news above come only from these.", style = Zune.style(13.sp, FontWeight.Normal, Zune.grey))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(newArtist, { newArtist = it.take(80) }, singleLine = true, textStyle = Zune.style(18.sp, FontWeight.Light),
                    cursorBrush = SolidColor(Zune.white), modifier = Modifier.weight(1f)
                        .drawBehind { drawLine(Zune.dim, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }.padding(vertical = 8.dp),
                    decorationBox = { inner -> Box { if (newArtist.isEmpty()) Text("add an artist", style = Zune.style(18.sp, FontWeight.Light, Zune.grey)); inner() } })
                LiveWord("follow") { val a = newArtist.trim(); if (a.isNotEmpty() && followed.none { it.equals(a, true) }) { saveFollow(followed + a); newArtist = "" } }
            }
        }
        items(followed, key = { "a$it" }) { name ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name.lowercase(Locale.getDefault()), style = Zune.style(20.sp, FontWeight.Light), modifier = Modifier.weight(1f), maxLines = 1)
                LiveWord("unfollow", Zune.grey) { saveFollow(followed.filterNot { it == name }) }
            }
        }
        item { Text("sources: deezer, google news, espn (unofficial), open-meteo. fetched only when live is opened or refreshed.",
            style = Zune.style(11.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.padding(top = 18.dp)) }
    }
    if (weatherPage) {
        val wx = sections["weather"]?.first.orEmpty()
        val code = wx.firstOrNull()?.url?.takeIf { it.startsWith("wx:") }?.split(":")
        androidx.compose.ui.window.Dialog(onDismissRequest = { weatherPage = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().metroTurnstileIn().background(GlideMetroStyle.ink).clickable { weatherPage = false }) {
                GlideWeatherScene(code?.getOrNull(1)?.toIntOrNull() ?: 3, code?.getOrNull(2) != "0", true, Modifier.fillMaxWidth().fillMaxHeight(.55f))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 20.dp, bottom = 40.dp)) {
                    Text("weather", style = Zune.style(40.sp, FontWeight.Light))
                    wx.firstOrNull()?.let {
                        Text(it.title, style = Zune.style(64.sp, FontWeight.Light))
                        Text(it.detail, style = Zune.style(14.sp, FontWeight.Normal, Zune.grey))
                    }
                    Spacer(Modifier.height(16.dp))
                    wx.drop(1).forEach {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(it.title, style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.weight(1f))
                            Text(it.detail, style = Zune.style(16.sp, FontWeight.Normal, Zune.grey))
                        }
                    }
                    Text("open-meteo · tap to close", style = Zune.style(11.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }
}


/** F1: which sport a Live sports line belongs to (from its detail prefix). */
private fun sportOf(detail: String): String = when (detail.substringBefore(" · ").trim()) {
    "cricket" -> "cricket"; "epl" -> "football"; "f1" -> "f1"; "tennis" -> "tennis"; else -> "teams"
}

@Composable
private fun SportHeader(sport: String) {
    Row(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val accent = Zune.magenta
        val ink = Zune.white
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            val w = size.width; val h = size.height
            val line = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
            when (sport) {
                "cricket" -> { // bat + ball
                    rotate(-35f) { drawRoundRect(ink, Offset(w * .42f, h * .05f), androidx.compose.ui.geometry.Size(w * .2f, h * .62f),
                        androidx.compose.ui.geometry.CornerRadius(3f), style = line)
                        drawLine(ink, Offset(w * .52f, h * .67f), Offset(w * .52f, h * .95f), strokeWidth = 1.6.dp.toPx()) }
                    drawCircle(accent, w * .13f, Offset(w * .8f, h * .8f))
                }
                "football" -> {
                    drawCircle(ink, w * .45f, Offset(w / 2, h / 2), style = line)
                    val p = androidx.compose.ui.graphics.Path()
                    for (i in 0 until 5) { val a = Math.toRadians(-90.0 + i * 72).toFloat()
                        val x = w / 2 + kotlin.math.cos(a) * w * .17f; val y = h / 2 + kotlin.math.sin(a) * h * .17f
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                    p.close(); drawPath(p, accent)
                }
                "f1" -> { // chequered flag
                    drawLine(ink, Offset(w * .15f, h * .05f), Offset(w * .15f, h * .95f), strokeWidth = 1.6.dp.toPx())
                    val cw = w * .2f
                    for (r in 0 until 3) for (k in 0 until 3) if ((r + k) % 2 == 0)
                        drawRect(if (r == 0 && k == 0) accent else ink, Offset(w * .2f + k * cw, h * .1f + r * cw), androidx.compose.ui.geometry.Size(cw, cw))
                }
                "tennis" -> {
                    drawCircle(ink, w * .45f, Offset(w / 2, h / 2), style = line)
                    drawArc(accent, -60f, 120f, false, Offset(-w * .35f, h * .05f), androidx.compose.ui.geometry.Size(w * .9f, h * .9f), style = line)
                    drawArc(accent, 120f, 120f, false, Offset(w * .45f, h * .05f), androidx.compose.ui.geometry.Size(w * .9f, h * .9f), style = line)
                }
                else -> { // star for your teams
                    val p = androidx.compose.ui.graphics.Path()
                    for (i in 0 until 10) { val r = if (i % 2 == 0) w * .46f else w * .2f
                        val a = Math.toRadians(-90.0 + i * 36).toFloat()
                        val x = w / 2 + kotlin.math.cos(a) * r; val y = h / 2 + kotlin.math.sin(a) * r
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                    p.close(); drawPath(p, accent)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(if (sport == "teams") "your teams" else sport, style = Zune.style(22.sp, FontWeight.Light))
    }
}
