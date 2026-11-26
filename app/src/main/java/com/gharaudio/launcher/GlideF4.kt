package com.gharaudio.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import androidx.core.graphics.drawable.toBitmap

/*
 * F4a / F4b — Zune HD manual behaviours + remaining polish.
 *  - Quickplay: pins (artists / albums you pin, fixed order) and "new" (recently added albums) on Home
 *  - cross-page "open this artist / album" requests (pins → Library)
 *  - artist "related" pivot (Deezer related artists; the ones in your library first)
 *  - turnstile entrance for hubs / sheets
 *  - album wall (3-row cover mosaic, horizontal)
 *  - Glide recent-apps switcher (needs "usage access" once; icon + name cards, swipe up to hide)
 */

internal data class GlidePin(val isArtist: Boolean, val name: String, val artist: String)

internal object GlidePins {
    private fun prefs(c: Context) = c.getSharedPreferences("glide_pins", Context.MODE_PRIVATE)
    val revision = mutableIntStateOf(0)
    fun all(c: Context): List<GlidePin> = runCatching {
        val a = JSONArray(prefs(c).getString("pins", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { GlidePin(it.getBoolean("a"), it.getString("n"), it.optString("r")) }
    }.getOrDefault(emptyList())
    private fun save(c: Context, list: List<GlidePin>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("a", it.isArtist).put("n", it.name).put("r", it.artist)) }
        prefs(c).edit().putString("pins", a.toString()).apply(); revision.intValue++
    }
    fun merge(c: Context, incoming: List<GlidePin>) = save(c, (all(c) + incoming).distinct().take(8))
    fun isPinned(c: Context, p: GlidePin) = all(c).any { it.isArtist == p.isArtist && it.name == p.name && it.artist == p.artist }
    fun toggle(c: Context, p: GlidePin) {
        val list = all(c)
        save(c, if (isPinned(c, p)) list.filterNot { it.isArtist == p.isArtist && it.name == p.name && it.artist == p.artist } else (list + p).take(8))
        GlideToast.show(if (isPinned(c, p)) "pinned to home" else "unpinned")
    }
}

/** A request from another page to open an artist / album in Library. */
internal object GlideOpen {
    val request = mutableStateOf<GlidePin?>(null)
}

internal data class NewAlbum(val album: String, val artist: String, val songId: Long)

internal fun newAlbums(context: Context, max: Int = 6): List<NewAlbum> = runCatching {
    val out = LinkedHashMap<String, NewAlbum>()
    context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST),
        "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
        while (c.moveToNext() && out.size < max) {
            val album = c.getString(1).orEmpty(); val artist = c.getString(2).orEmpty()
            val key = (album + "|" + artist).lowercase(Locale.ROOT)
            if (album.isNotBlank() && key !in out) out[key] = NewAlbum(album, artist, c.getLong(0))
        }
    }
    out.values.toList()
}.getOrDefault(emptyList())

/** Home: pins row then "new" row — square art, lowercase labels, no cards. */
@Composable
internal fun GlideHomeQuickplay(context: Context, onOpenLibrary: () -> Unit) {
    val rev = GlidePins.revision.intValue
    val pins = remember(rev) { GlidePins.all(context) }
    val index by produceState(cachedMusicIndex, context) { if (value == null) value = withContext(Dispatchers.IO) { runCatching { getOrLoadMusicIndex(context) }.getOrNull() } }
    val fresh by produceState<List<NewAlbum>>(emptyList(), context) { value = withContext(Dispatchers.IO) { newAlbums(context) } }
    val byId = remember(index) { index?.songs?.associateBy { it.id }.orEmpty() }
    Column(Modifier.fillMaxWidth()) {
        if (pins.isNotEmpty()) {
            Text("pins", style = Zune.style(34.sp, FontWeight.Light, Zune.white, (-1.2).sp))
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(pins, key = { (if (it.isArtist) "a:" else "b:") + it.name + it.artist }) { p ->
                    val song = remember(p, index) { index?.songs?.firstOrNull { if (p.isArtist) (it.artist == p.name || OnlineArt.primaryArtist(it.artist).equals(p.name, true)) else it.album == p.name && it.artist == p.artist } }
                    Column(Modifier.width(104.dp).motionTap(onTap = { GlideOpen.request.value = p; onOpenLibrary() })) {
                        Box(Modifier.size(104.dp).background(Zune.dim.copy(alpha = .15f))) { if (song != null) AlbumCover(context, song, 104.dp) }
                        Spacer(Modifier.height(5.dp))
                        Text(p.name.lowercase(Locale.getDefault()), style = Zune.style(14.sp, FontWeight.Light), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (fresh.isNotEmpty()) {
            Text("new", style = Zune.style(34.sp, FontWeight.Light, Zune.white, (-1.2).sp))
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(fresh, key = { it.album + it.artist }) { a ->
                    val song = byId[a.songId]
                    Column(Modifier.width(104.dp).motionTap(onTap = { GlideOpen.request.value = GlidePin(false, a.album, a.artist); onOpenLibrary() })) {
                        Box(Modifier.size(104.dp).background(Zune.dim.copy(alpha = .15f))) { if (song != null) AlbumCover(context, song, 104.dp) }
                        Spacer(Modifier.height(5.dp))
                        Text(a.album.lowercase(Locale.getDefault()), style = Zune.style(14.sp, FontWeight.Light), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                        Text(a.artist.lowercase(Locale.getDefault()), style = Zune.style(11.sp, FontWeight.Normal, Zune.grey), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/* ---------- related artists (Deezer) ---------- */

private fun httpJson(url: String): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "Glide/1.0 (personal launcher)")
    return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
}

/** Related artists for [artist]; cached a week. Blocking — call on IO. */
internal fun relatedArtists(context: Context, artist: String): List<String> {
    val prefs = context.getSharedPreferences("glide_related", Context.MODE_PRIVATE)
    val key = OnlineArt.primaryArtist(artist).lowercase(Locale.ROOT)
    prefs.getString(key, null)?.let { cached ->
        val o = JSONObject(cached)
        if (System.currentTimeMillis() - o.optLong("t") < 7L * 86_400_000L) {
            val a = o.optJSONArray("n") ?: JSONArray(); return (0 until a.length()).map { a.getString(it) }
        }
    }
    if (!OnlineArt.enabled(context)) return emptyList()
    val q = URLEncoder.encode(OnlineArt.primaryArtist(artist), "UTF-8")
    val id = httpJson("https://api.deezer.com/search/artist?q=$q&limit=1").optJSONArray("data")?.optJSONObject(0)?.optLong("id") ?: return emptyList()
    val data = httpJson("https://api.deezer.com/artist/$id/related?limit=24").optJSONArray("data") ?: JSONArray()
    val names = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
    prefs.edit().putString(key, JSONObject().put("t", System.currentTimeMillis()).put("n", JSONArray(names)).toString()).apply()
    return names
}

/** Artist page pivot "related": in-library artists first (tap to open), then others (dim). */
@Composable
internal fun GlideRelatedArtists(context: Context, artist: String, onOpen: (String) -> Unit) {
    val related by produceState<List<String>?>(null, artist) {
        value = withContext(Dispatchers.IO) { runCatching { relatedArtists(context, artist) }.getOrDefault(emptyList()) }
    }
    val inLibrary = remember { cachedMusicIndex?.songs?.map { OnlineArt.primaryArtist(it.artist).lowercase(Locale.ROOT) }?.toSet().orEmpty() }
    val list = related
    Column(Modifier.fillMaxWidth().metroElastic(true).verticalScroll(rememberScrollState())) {
        when {
            list == null -> MetroFlyingDots(Modifier.fillMaxWidth())
            list.isEmpty() -> Text("no related artists found" + if (!OnlineArt.enabled(context)) " (online artwork is off)" else "",
                style = Zune.style(16.sp, FontWeight.Light, Zune.grey))
            else -> {
                val (mine, others) = list.partition { it.lowercase(Locale.ROOT) in inLibrary }
                if (mine.isNotEmpty()) Text("in your library", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
                mine.forEach { name ->
                    Text(name.lowercase(Locale.getDefault()), style = Zune.style(24.sp, FontWeight.Light, Zune.magenta),
                        modifier = Modifier.fillMaxWidth().motionTap(onTap = { onOpen(name) }).padding(vertical = 7.dp))
                }
                if (others.isNotEmpty()) Text("more like this", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.padding(top = 12.dp))
                others.forEach { name ->
                    Text(name.lowercase(Locale.getDefault()), style = Zune.style(20.sp, FontWeight.Light, Zune.light), modifier = Modifier.padding(vertical = 5.dp))
                }
                Text("from deezer", style = Zune.style(11.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.padding(top = 14.dp, bottom = 24.dp))
            }
        }
    }
}

/* ---------- turnstile entrance for hubs / sheets (section 7.6) ---------- */

internal fun Modifier.metroTurnstileIn(): Modifier =
    if (!GlideMotionState.motionOn) this else composed {
        val p = remember { Animatable(0f) }
        LaunchedEffect(Unit) { p.animateTo(1f, tween(260, delayMillis = 100, easing = MetroEaseOut)) }
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        this.graphicsLayer {
            rotationY = 85f * (1f - p.value)
            transformOrigin = TransformOrigin(-40f * density / size.width.coerceAtLeast(1f), .5f)
            cameraDistance = 14f * density
            alpha = p.value
        }
    }

/* ---------- album wall (F4b) ---------- */

@Composable
internal fun GlideAlbumWall(context: Context, albums: List<Pair<String, List<Song>>>, onOpen: (String, List<Song>) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveDialog()
        // laptop test: covers fill their cell edge to edge (3 rows), 4 dp gaps
        val cell = ((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 56 - 8) / 3).dp
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LazyHorizontalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(top = 16.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(albums, key = { it.first }) { (name, songs) ->
                    Box(Modifier.size(cell).motionTap(onTap = { onClose(); onOpen(name, songs) })) {
                        AlbumCover(context, songs.firstOrNull(), cell)
                    }
                }
            }
            Text("album wall · tap to open · back to close", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 12.dp))
        }
    }
}

/* ---------- recent apps switcher (F4b) ---------- */

internal object GlideSwitcher { val open = mutableStateOf(false) }

internal fun recentApps(context: Context, max: Int = 10): List<String>? {
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
    val now = System.currentTimeMillis()
    val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3 * 86_400_000L, now) }.getOrNull()
    if (stats.isNullOrEmpty()) return null // no usage access yet
    val pm = context.packageManager
    return stats.filter { it.lastTimeUsed > 0 && it.packageName != context.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
        .sortedByDescending { it.lastTimeUsed }.map { it.packageName }.distinct().take(max)
}

@Composable
internal fun GlideAppSwitcher(context: Context, onClose: () -> Unit) {
    var apps by remember { mutableStateOf<List<String>?>(emptyList()) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { recentApps(context) } }
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(Unit) { zoom.animateTo(1f, tween(260, easing = MetroEaseOut)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveDialog()
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { onClose() }) {
            val list = apps
            if (list == null) Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("recent apps needs usage access", style = Zune.style(22.sp, FontWeight.Light, Color.White))
                Text("tap here to allow glide once", style = Zune.style(14.sp, FontWeight.Normal, Zune.magenta),
                    modifier = Modifier.padding(top = 8.dp).clickable {
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        onClose()
                    })
            } else LazyRow(Modifier.align(Alignment.Center).fillMaxWidth().graphicsLayer {
                    val s = .9f + .1f * zoom.value; scaleX = s; scaleY = s; alpha = zoom.value },
                contentPadding = PaddingValues(horizontal = 60.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(list.filter { it !in hidden }, key = { it }) { pkg ->
                    val label = remember(pkg) { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
                    val icon = remember(pkg) { runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() }
                    val drag = remember { Animatable(0f) }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.width(240.dp).height(380.dp).graphicsLayer { translationY = drag.value }
                        .background(Color(0xFF1A1A1A))
                        .pointerInput(pkg) {
                            detectVerticalDragGestures(onDragEnd = {
                                scope.launch {
                                    if (drag.value < -size.height * .3f) { drag.animateTo(-size.height * 1.2f, tween(180)); hidden = hidden + pkg }
                                    else drag.animateTo(0f, tween(200, easing = MetroEaseOut))
                                }
                            }) { change, dy -> change.consume(); scope.launch { drag.snapTo((drag.value + dy).coerceAtMost(0f)) } }
                        }
                        .clickable { onClose(); launchApp(context, pkg) }.padding(18.dp)) {
                        Text(label.lowercase(Locale.getDefault()), style = Zune.style(22.sp, FontWeight.Light, Color.White), maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        icon?.let { d ->
                            val bmp = remember(pkg) { runCatching { d.toBitmap(144, 144).asImageBitmap() }.getOrNull() }
                            if (bmp != null) Image(bmp, contentDescription = label, modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("swipe up to close", style = Zune.style(11.sp, FontWeight.Normal, Zune.grey))
                    }
                }
            }
        }
    }
}

/** Full-screen Glide dialogs keep Android's bars hidden, like the rest of Glide. */
@Composable
internal fun ImmersiveDialog() {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
