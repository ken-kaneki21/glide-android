package com.gharaudio.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/** Glide-only metadata. All identifiers are MediaStore IDs; no audio file or tag is modified. */
internal object GlideMixData {
    private const val PREF = "glide_mix_v1"
    private const val CAP = 10000
    enum class Heart { LOVE, NEUTRAL, NEVER }
    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun heart(c: Context, id: Long): Heart = when (prefs(c).getInt("heart_$id", 0)) {
        1 -> Heart.LOVE; -1 -> Heart.NEVER; else -> Heart.NEUTRAL
    }
    fun setHeart(c: Context, id: Long, h: Heart) {
        prefs(c).edit().putInt("heart_$id", when (h) { Heart.LOVE -> 1; Heart.NEVER -> -1; Heart.NEUTRAL -> 0 }).apply()
    }
    fun cycle(c: Context, id: Long): Heart {
        val next = when (heart(c,id)) { Heart.NEUTRAL -> Heart.LOVE; Heart.LOVE -> Heart.NEVER; Heart.NEVER -> Heart.NEUTRAL }
        setHeart(c,id,next)
        return next
    }
    private fun normalized(s: String) = OnlineArt.primaryArtist(s).trim().lowercase(Locale.ROOT)
    data class Listen(val timestamp: Long, val duration: Long, val artist: String, val title: String)
    private data class HistorySnapshot(val path: String, val modified: Long, val length: Long, val rows: List<Listen>)
    private var historySnapshot: HistorySnapshot? = null

    @Synchronized fun history(c: Context): List<Listen> = runCatching {
        val f = File(File(c.filesDir, "history"), "plays.tsv")
        if (!f.isFile) return@runCatching emptyList()
        val modified = f.lastModified()
        val length = f.length()
        historySnapshot?.takeIf { it.path == f.path && it.modified == modified && it.length == length }
            ?.let { return it.rows }
        val latest = java.util.ArrayDeque<Listen>(CAP)
        // Keep the latest records while bounding memory on long-running devices.
        f.useLines { lines -> lines.mapNotNull { line ->
            val cols = line.split('\t')
            if (cols.size < 7) null else {
                val ts = cols[0].toLongOrNull() ?: 0L
                val ms = cols[1].toLongOrNull() ?: 0L
                if (ts <= 0L || ms < 0L) null else Listen(ts,ms,cols[4],cols[6])
            }
        }.forEach { listen ->
            if (latest.size == CAP) latest.removeFirst()
            latest.addLast(listen)
        } }
        latest.toList().also { historySnapshot = HistorySnapshot(f.path, modified, length, it) }
    }.getOrDefault(emptyList())
    fun monthly(c: Context): Pair<List<Listen>, String> {
        val now = Calendar.getInstance()
        val label = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now.time)
        val month = now.get(Calendar.MONTH); val year = now.get(Calendar.YEAR)
        return history(c).filter { l ->
            val t = Calendar.getInstance().also { it.timeInMillis = l.timestamp }
            t.get(Calendar.MONTH) == month && t.get(Calendar.YEAR) == year
        } to label
    }
    private fun uniquePlayable(songs: List<Song>, c: Context) = songs.asSequence()
        .filter { heart(c,it.id) != Heart.NEVER }.distinctBy { it.id }.toList()
    fun smart(c: Context, songs: List<Song>, kind: String): List<Song> {
        val available = uniquePlayable(songs,c)
        val h = history(c)
        val counts = h.groupingBy { normalized(it.artist) to it.title.trim().lowercase(Locale.ROOT) }.eachCount()
        return when(kind) {
            "loved" -> available.filter { heart(c,it.id) == Heart.LOVE }
            "never played" -> {
                val listened = counts.keys
                available.filter { (normalized(it.artist) to it.title.trim().lowercase(Locale.ROOT)) !in listened }
            }
            "on repeat" -> available.map { song ->
                val historyKey = normalized(song.artist) to song.title.trim().lowercase(Locale.ROOT)
                val heartBonus = if (heart(c, song.id) == Heart.LOVE) 2 else 0
                song to ((counts[historyKey] ?: 0) + heartBonus)
            }.sortedByDescending { it.second }.take(100).map { it.first }
            "recently played" -> {
                val order = h.asReversed().map { normalized(it.artist) to it.title.trim().lowercase(Locale.ROOT) }.distinct()
                val lookup = available.associateBy { normalized(it.artist) to it.title.trim().lowercase(Locale.ROOT) }
                order.mapNotNull(lookup::get).take(100)
            }
            else -> emptyList()
        }
    }
    /** Local-only recommendation. 'Discovery' selects unfamiliar tracks ALREADY on this phone. */
    fun dj(c: Context, all: List<Song>, seed: Song, rng: Random = Random.Default): List<Song> {
        val pool = uniquePlayable(all,c)
        if (pool.isEmpty()) return emptyList()
        val seedArtist = normalized(seed.artist)
        val h = history(c)
        // Build artist-neighbour scores from adjacent listening-history entries.
        val neighbors = HashMap<String,Int>()
        h.zipWithNext().forEach { (a,b) ->
            val aa = normalized(a.artist); val bb = normalized(b.artist)
            if (aa == seedArtist && bb != seedArtist) neighbors[bb] = (neighbors[bb] ?: 0) + 1
            if (bb == seedArtist && aa != seedArtist) neighbors[aa] = (neighbors[aa] ?: 0) + 1
        }
        val alreadyHeard = h.map { normalized(it.artist) to it.title.trim().lowercase(Locale.ROOT) }.toSet()
        val own = pool.filter { normalized(it.artist) == seedArtist }.shuffled(rng)
        val others = pool.filter { normalized(it.artist) != seedArtist }
        // Shuffle once so equal-ranked tracks stay random without changing comparison results.
        val lovedIds = others.filter { heart(c, it.id) == Heart.LOVE }.map { it.id }.toSet()
        val related = others.shuffled(rng).sortedWith(compareByDescending<Song> { neighbors[normalized(it.artist)] ?: 0 }
            .thenByDescending { it.id in lovedIds })
        val fresh = others.filter { (normalized(it.artist) to it.title.trim().lowercase(Locale.ROOT)) !in alreadyHeard }.shuffled(rng)
        val selected = LinkedHashMap<Long,Song>()
        fun addFrom(xs: List<Song>, n: Int) { for (s in xs) { if (selected.size >= n) break; selected[s.id] = s } }
        if (heart(c,seed.id) != Heart.NEVER) selected[seed.id] = seed
        // 25 songs: 8 seed artist, 12 related in-library, 5 unfamiliar in-library.
        addFrom(own,8)
        val relatedGoal = (selected.size + 12).coerceAtMost(25)
        for (song in related) { if (selected.size >= relatedGoal) break; selected.putIfAbsent(song.id,song) }
        val discoveryGoal = (selected.size + 5).coerceAtMost(25)
        for (song in fresh) { if (selected.size >= discoveryGoal) break; selected.putIfAbsent(song.id,song) }
        addFrom(related+own+pool.shuffled(rng),25)
        return selected.values.take(25)
    }
    fun relatedArtists(c: Context, songs: List<Song>, seed: String): List<Pair<String,Int>> {
        val name = normalized(seed)
        val listening = history(c)
        val counts = HashMap<String,Int>()
        listening.zipWithNext().forEach { (a,b) ->
            val aa = normalized(a.artist); val bb = normalized(b.artist)
            if (aa == name && bb != name) counts[bb] = (counts[bb] ?: 0) + 1
            if (bb == name && aa != name) counts[aa] = (counts[aa] ?: 0) + 1
        }
        val inLibrary = songs.groupBy { normalized(it.artist) }
        return inLibrary.keys.filter { it != name }
            .sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenByDescending { inLibrary[it]?.size ?: 0 })
            .take(12).map { it to (counts[it] ?: 0) }
    }
    fun backup(c: Context, songs: List<Song>): String {
        val hearts = JSONArray()
        for (s in songs) {
            val state = heart(c,s.id)
            if (state != Heart.NEUTRAL) hearts.put(JSONObject().put("id",s.id).put("state",state.name)
                .put("artist",s.artist).put("title",s.title))
        }
        val byId = songs.associateBy { it.id }
        val playlists = JSONObject()
        GlideLocalLists.playlistNames(c).forEach { name ->
            val tracks = JSONArray()
            GlideLocalLists.playlistIds(c, name).mapNotNull(byId::get).forEach { song ->
                tracks.put(JSONObject().put("id", song.id).put("artist", song.artist)
                    .put("album", song.album).put("title", song.title))
            }
            playlists.put(name, tracks)
        }
        val appearance = JSONObject()
            .put("light", GlideMetroStyle.lightTheme)
            .put("palette", GlideMetroStyle.palette)
            .put("wallpaper", GlideMetroStyle.wallpaper)
            .put("cornerArt", GlideMetroStyle.cornerArt)
        val lyricTiming = JSONObject()
        c.getSharedPreferences("glide_lyric_timing", Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (value is Int) lyricTiming.put(key, value)
        }
        return JSONObject().put("format","glide-mix-1").put("hearts",hearts)
            .put("playlists", playlists).put("appearance", appearance).put("lyricTiming", lyricTiming)
            .put("pins", JSONArray(GlidePins.all(c).map { pin ->
                JSONObject().put("a", pin.isArtist).put("n", pin.name).put("r", pin.artist)
            })).toString(2)
    }
    /** Restore heart preferences only for matching local MediaStore songs. Never writes media files. */
    fun restore(c: Context, songs: List<Song>, raw: String): Int {
        val root = JSONObject(raw)
        require(root.optString("format") == "glide-mix-1") { "Invalid Glide backup" }
        val rows = root.getJSONArray("hearts")
        require(rows.length() <= CAP) { "Backup too large" }
        val byId = songs.associateBy { it.id }
        fun matchKey(artist: String, title: String) =
            artist.trim().lowercase(Locale.ROOT) to title.trim().lowercase(Locale.ROOT)
        val byName = songs.groupBy { matchKey(it.artist, it.title) }
        var restored = 0
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i); val id = item.optLong("id",-1L)
            val key = matchKey(item.optString("artist"), item.optString("title"))
            if (key.first.isBlank() || key.second.isBlank()) continue
            val song = byId[id]?.takeIf { matchKey(it.artist, it.title) == key }
                ?: byName[key]?.singleOrNull() ?: continue
            val h = runCatching { Heart.valueOf(item.optString("state")) }.getOrNull() ?: continue
            setHeart(c,song.id,h); restored++
        }
        return restored
    }

    data class Extras(val playlists: Int, val unmatched: Int)

    fun restoreExtras(c: Context, songs: List<Song>, root: JSONObject): Extras {
        val byId = songs.associateBy { it.id }
        val byMetadata = songs.groupBy { Triple(it.artist, it.album, it.title) }
        var restored = 0
        var unmatched = 0
        val playlists = root.optJSONObject("playlists")
        playlists?.keys()?.asSequence()?.take(100)?.forEach { name ->
            val rows = playlists.optJSONArray(name) ?: return@forEach
            val ids = (0 until rows.length().coerceAtMost(5000)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val metadata = Triple(row.optString("artist"), row.optString("album"), row.optString("title"))
                val direct = byId[row.optLong("id")]?.takeIf { Triple(it.artist, it.album, it.title) == metadata }
                val match = direct ?: byMetadata[metadata]?.singleOrNull()
                if (match == null) unmatched++
                match?.id
            }
            if (GlideLocalLists.savePlaylist(c, name, ids)) restored++
        }
        root.optJSONObject("appearance")?.let { ui ->
            val edit = c.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).edit()
            if (ui.opt("light") is Boolean) edit.putBoolean("metro_light", ui.getBoolean("light"))
            if (ui.opt("cornerArt") is Boolean) edit.putBoolean("metro_corner_art", ui.getBoolean("cornerArt"))
            if (ui.opt("palette") is Number) edit.putInt("metro_palette_v15", ui.getInt("palette").coerceIn(0,19))
            if (ui.opt("wallpaper") is Number) edit.putInt("metro_wallpaper_v15", ui.getInt("wallpaper").coerceIn(0,8))
            edit.apply()
            GlideMetroStyle.restore(c)
        }
        root.optJSONObject("lyricTiming")?.let { timing ->
            val edit = c.getSharedPreferences("glide_lyric_timing", Context.MODE_PRIVATE).edit()
            timing.keys().asSequence().take(5000).forEach { key ->
                val value = timing.opt(key)
                if (key.length <= 100 && value is Number) edit.putInt(key, value.toInt().coerceIn(-30000,30000))
            }
            edit.apply()
        }
        root.optJSONArray("pins")?.let { rows ->
            val pins = (0 until rows.length().coerceAtMost(8)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val name = row.optString("n").take(200).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GlidePin(row.optBoolean("a"), name, row.optString("r").take(200))
            }
            GlidePins.merge(c, pins)
        }
        return Extras(restored, unmatched)
    }
}

/** Home-accessible Zune-styled Mixview: local smart mixes, three-state hearts and monthly listening recap. */
@Composable
internal fun GlideMixScreen(context: Context, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var section by remember { mutableStateOf("mix") }
    var seed by remember { mutableStateOf<Song?>(null) }
    var playlist by remember { mutableStateOf<List<Song>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(context) {
        songs = withContext(Dispatchers.IO) { getOrLoadMusicIndex(context).songs }
        loading = false
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                output.bufferedWriter().use { it.write(GlideMixData.backup(context,songs)) }
            }
            withContext(Dispatchers.Main) {
                status = result.fold({ "preferences exported" },{ "export failed: ${it.javaClass.simpleName}" })
            }
        }
    }
    var pendingImport by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
                    val content = input.bufferedReader().use { reader ->
                        val text = StringBuilder()
                        val chars = CharArray(8192)
                        while (true) {
                            val size = reader.read(chars)
                            if (size < 0) break
                            require(text.length + size <= 2_000_000) { "Backup too large" }
                            text.append(chars, 0, size)
                        }
                        text.toString()
                    }
                    val backup = JSONObject(content)
                    require(backup.optString("format") == "glide-mix-1")
                    require(backup.getJSONArray("hearts").length() <= 10000)
                    content to backup
                }
            }
            result.onSuccess { pendingImport = it }
                .onFailure { status = "could not open Glide backup" }
        }
    }
    pendingImport?.let { (content, backup) ->
        val preview = remember(backup, songs) {
            val available = songs.groupingBy { Triple(it.artist, it.album, it.title) }.eachCount()
            val byId = songs.associateBy { it.id }
            val playlists = backup.optJSONObject("playlists")
            var unmatched = 0
            var tracks = 0
            playlists?.keys()?.asSequence()?.take(100)?.forEach { name ->
                val rows = playlists.optJSONArray(name) ?: return@forEach
                for (index in 0 until rows.length().coerceAtMost(5000)) {
                    val row = rows.optJSONObject(index) ?: continue
                    val metadata = Triple(row.optString("artist"), row.optString("album"), row.optString("title"))
                    val direct = byId[row.optLong("id")]?.let { Triple(it.artist, it.album, it.title) == metadata } == true
                    tracks++
                    if (!direct && available[metadata] != 1) unmatched++
                }
            }
            "${playlists?.length() ?: 0} playlists · $tracks tracks · $unmatched unmatched"
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Review Glide backup") },
            text = { Text(preview + "\nImport also applies hearts, pins, lyric timing and appearance in this file.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingImport = null
                    val result = runCatching {
                        val count = GlideMixData.restore(context, songs, content)
                        val extras = GlideMixData.restoreExtras(context, songs, backup)
                        revision++
                        "$count hearts · ${extras.playlists} playlists imported · ${extras.unmatched} unmatched tracks"
                    }
                    status = result.getOrElse { "could not import Glide backup" }
                }) { Text("Import") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            }
        )
    }
    Column(Modifier.fillMaxSize().padding(start = 22.dp,end = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("mix", style = Zune.style(48.sp,FontWeight.Light,Zune.white), modifier=Modifier.weight(1f))
            Text("close",style=Zune.style(15.sp,FontWeight.Normal,Zune.grey),modifier=Modifier.clickable(onClick=onClose).padding(14.dp))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            listOf("mix","smart","recap","mixview","sync").forEach { tab ->
                Text(tab,style=Zune.style(17.sp,if(section==tab) FontWeight.Bold else FontWeight.Light,
                    if(section==tab) Zune.white else Zune.grey),modifier=Modifier.clickable { section=tab }.padding(vertical=12.dp))
            }
        }
        if (status.isNotBlank()) Text(status,style=Zune.style(13.sp,FontWeight.Normal,Zune.grey))
        if (loading) { Text("reading your collection...",style=Zune.style(20.sp,FontWeight.Light,Zune.grey)); return@Column }
        when(section) {
            "mix" -> {
                Text("smart dj",style=Zune.style(31.sp,FontWeight.Light,Zune.white))
                Text("25 tracks · seed artist, related listening, local discovery",style=Zune.style(13.sp,FontWeight.Normal,Zune.grey))
                val choice = seed ?: songs.firstOrNull()
                if (choice != null) {
                    Text("seed: ${choice.artist} — ${choice.title}",style=Zune.style(16.sp,FontWeight.Light,Zune.white),
                        modifier=Modifier.clickable { section="seed" }.padding(vertical=12.dp))
                    Text("create mix",style=Zune.style(25.sp,FontWeight.Light,Zune.white),modifier=Modifier.clickable {
                        playlist=GlideMixData.dj(context,songs,choice); section="queue"
                    }.padding(vertical=12.dp))
                }
                Text("discovery uses songs already on this phone; no fabricated streaming catalog",style=Zune.style(12.sp,FontWeight.Normal,Zune.grey))
            }
            "seed" -> {
                Text("choose a seed",style=Zune.style(26.sp,FontWeight.Light,Zune.white))
                LazyColumn { items(songs.take(600),key={it.id}) { s ->
                    GlideMixRow(context,s,revision, { revision++ }) { seed=s;section="mix" }
                } }
            }
            "queue" -> {
                Text("${playlist.size} tracks",style=Zune.style(30.sp,FontWeight.Light,Zune.white))
                Text("play mix",style=Zune.style(26.sp,FontWeight.Light,Zune.white),modifier=Modifier.clickable {
                    if (playlist.isNotEmpty()) GlideEngine.playList(context,playlist,0)
                }.padding(vertical=10.dp))
                LazyColumn { items(playlist,key={it.id}) { s -> GlideMixRow(context,s,revision,{revision++}) {
                    GlideEngine.playList(context,playlist,playlist.indexOf(s))
                } } }
            }
            "smart" -> {
                LazyColumn { items(listOf("loved","on repeat","never played","recently played")) { kind ->
                    val count = remember(kind,revision,songs) { GlideMixData.smart(context,songs,kind).size }
                    Column(Modifier.fillMaxWidth().clickable {
                        playlist=GlideMixData.smart(context,songs,kind); section="queue"
                    }.padding(vertical=12.dp)) {
                        Text(kind,style=Zune.style(27.sp,FontWeight.Light,Zune.white))
                        Text("$count songs",style=Zune.style(13.sp,FontWeight.Normal,Zune.grey))
                    }
                } }
            }
            "recap" -> {
                val data = remember(revision) { GlideMixData.monthly(context) }
                val entries = data.first
                val top = entries.groupingBy { OnlineArt.primaryArtist(it.artist) }.eachCount()
                    .entries.sortedByDescending { it.value }.take(10)
                Text(data.second.lowercase(Locale.getDefault()),style=Zune.style(30.sp,FontWeight.Light,Zune.white))
                Text("${entries.size} qualifying listens · ${entries.sumOf { it.duration }/60000} minutes",
                    style=Zune.style(18.sp,FontWeight.Normal,Zune.grey))
                Text("based on recorded listens, not track opens",style=Zune.style(12.sp,FontWeight.Normal,Zune.grey))
                LazyColumn { items(top) { (name,count) ->
                    Text("$name  ·  $count",style=Zune.style(22.sp,FontWeight.Light,Zune.white),modifier=Modifier.padding(vertical=8.dp))
                } }
            }
            "mixview" -> {
                val artist = seed?.artist ?: songs.firstOrNull()?.artist.orEmpty()
                Text("related to ${OnlineArt.primaryArtist(artist)}",style=Zune.style(23.sp,FontWeight.Light,Zune.white))
                Text("connections from your listening history; fallback by collection size",style=Zune.style(12.sp,FontWeight.Normal,Zune.grey))
                val artists = remember(seed,songs) { GlideMixData.relatedArtists(context,songs,artist) }
                LazyColumn { items(artists) { (name,score) ->
                    Column(Modifier.fillMaxWidth().clickable {
                        seed=songs.firstOrNull { OnlineArt.primaryArtist(it.artist).equals(name,true) }
                    }.padding(vertical=11.dp)) {
                        Text(name,style=Zune.style(26.sp,FontWeight.Light,Zune.white))
                        Text(if (score>0) "$score listening transitions" else "in your library",style=Zune.style(12.sp,FontWeight.Normal,Zune.grey))
                    }
                } }
            }
            "sync" -> {
                Text("private library preferences",style=Zune.style(27.sp,FontWeight.Light,Zune.white))
                Text("Back up hearts, named playlists, artist and album pins, lyric timing, and appearance.",style=Zune.style(16.sp,FontWeight.Light,Zune.grey))
                Text("export backup",style=Zune.style(26.sp,FontWeight.Light,Zune.white),modifier=Modifier.clickable {
                    exporter.launch("glide-backup.json")
                }.padding(vertical=18.dp))
                Text("import backup",style=Zune.style(26.sp,FontWeight.Light,Zune.white),modifier=Modifier.clickable {
                    importer.launch(arrayOf("application/json","text/plain"))
                }.padding(vertical=18.dp))
            }
        }
    }
}

@Composable
private fun GlideMixRow(context: Context, song: Song, revision: Int, onHeart: () -> Unit, onPlay: () -> Unit) {
    var state by remember(song.id,revision) { mutableStateOf(GlideMixData.heart(context,song.id)) }
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick=onPlay)) {
            Text(song.title,style=Zune.style(20.sp,FontWeight.Light,Zune.white),maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(song.artist,style=Zune.style(12.sp,FontWeight.Normal,Zune.light),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Text(when(state) { GlideMixData.Heart.LOVE -> "♥"; GlideMixData.Heart.NEVER -> "×"; GlideMixData.Heart.NEUTRAL -> "○" },
            style=Zune.style(26.sp,FontWeight.Light,if(state==GlideMixData.Heart.LOVE) Zune.magenta else Zune.white),
            modifier=Modifier.clickable { state=GlideMixData.cycle(context,song.id); onHeart() }.padding(12.dp))
    }
}
