package com.gharaudio.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/*
 * v1.11e2: Zune-style monochrome app icons.
 * 1. Known apps get an original thin-line glyph from ZuneIcon (same stroke as Now Playing).
 * 2. Any other app: a white silhouette of its adaptive icon's FOREGROUND layer (no background
 *    square), rendered once off the main thread and cached.
 * 3. Old-style icons with no foreground layer would become a white square, so they get the
 *    neutral "tile" glyph instead.
 */

private val glyphs = mapOf(
    "com.lge.music" to "note",
    "com.maxmpz.audioplayer" to "disc",
    "com.lge.hifirecorder" to "mic",
    "com.pittvandewitt.wavelet" to "wave",
    "com.companyname.andriodapp1" to "search",
    "com.google.android.apps.maps" to "pin",
    "app.organicmaps" to "pin",
    "net.osmand" to "pin",
    "net.osmand.plus" to "pin",
    "org.mozilla.fennec_fdroid" to "globe",
    "org.mozilla.firefox" to "globe",
    "com.android.chrome" to "globe",
    "org.breezyweather" to "weather",
    "glide:camera" to "camera",
    "com.lge.camera" to "camera",
    "com.aurora.store" to "store",
    "com.looker.droidify" to "store",
    "com.android.vending" to "store",
    "com.android.settings" to "settings",
    "com.android.dialer" to "phone",
    "com.lge.phone" to "phone",
    "com.android.mms" to "message",
    "com.google.android.apps.messaging" to "message",
    "com.lge.gallery" to "picture",
    "com.google.android.apps.photos" to "picture",
    "com.lge.clock" to "clock",
    "com.lge.voicerecorder" to "mic",
    "glide:library" to "library",
    "glide:apps" to "apps"
)

/** The glyph name for a package, or null when it should use its own silhouette. */
internal fun zuneGlyphFor(pkg: String): String? = glyphs[pkg]

private val monoCache = ConcurrentHashMap<String, ImageBitmap>()
private val noMono = ConcurrentHashMap.newKeySet<String>()

/** White silhouette of the app's adaptive-icon foreground (blocking; call on Dispatchers.IO). */
private fun loadMonoIcon(context: Context, pkg: String, px: Int): ImageBitmap? {
    monoCache[pkg]?.let { return it }
    if (pkg in noMono) return null
    val result = runCatching {
        val icon = context.packageManager.getApplicationIcon(pkg)
        val fg = (icon as? AdaptiveIconDrawable)?.foreground ?: return@runCatching null
        // The foreground is drawn on a 108-unit canvas of which the middle 72 are visible: scale 1.5x.
        val raw = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(raw)
        val extra = (px * 0.25f).roundToInt()
        fg.setBounds(-extra, -extra, px + extra, px + extra)
        fg.draw(canvas)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(raw, 0f, 0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN) })
        raw.recycle()
        out.asImageBitmap()
    }.getOrNull()
    if (result != null) monoCache[pkg] = result else noMono.add(pkg)
    return result
}

/** Warm the silhouettes for a set of packages (call on Dispatchers.IO at startup). */
internal fun prefetchZuneIcons(context: Context, packages: List<String>) {
    val px = (32f * context.resources.displayMetrics.density).roundToInt()
    packages.forEach { if (zuneGlyphFor(it) == null && !it.startsWith("glide:")) loadMonoIcon(context, it, px) }
}

/** v1.11g: glyphs the user can pick for any app (long-press an app -> change icon). */
internal val zuneGlyphChoices = listOf("note", "disc", "mic", "wave", "search", "pin", "globe", "weather",
    "camera", "store", "settings", "phone", "message", "picture", "clock", "library", "apps", "heart", "info", "tile")

/** v1.11g: per-app icon choice. null = Glide's default, "own" = the app's own icon in white, else a glyph kind. */
internal object ZuneIconOverrides {
    var revision by mutableIntStateOf(0)
        private set
    private fun prefs(context: Context) = context.getSharedPreferences("glide_icon_overrides", Context.MODE_PRIVATE)
    fun get(context: Context, pkg: String): String? = prefs(context).getString(pkg, null)
    fun set(context: Context, pkg: String, kind: String?) {
        prefs(context).edit().apply { if (kind == null) remove(pkg) else putString(pkg, kind) }.apply()
        revision++
    }
}

/** v1.11g: every launchable app, A-Z, for the Zune HD style "all apps" list. Blocking; call on IO. */
internal data class ZuneAppEntry(val label: String, val pkg: String)
@Volatile private var allAppsCache: List<ZuneAppEntry>? = null
internal fun loadAllApps(context: Context, refresh: Boolean = false): List<ZuneAppEntry> {
    if (!refresh) allAppsCache?.let { return it }
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    val list = pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ZuneAppEntry(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName) }
        .sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
    allAppsCache = list
    return list
}

/** One monochrome icon for an app or a Glide destination. */
@Composable
internal fun ZuneAppGlyph(pkg: String, size: Dp = 32.dp, tint: Color = Zune.white) {
    val context = LocalContext.current
    val override = remember(pkg, ZuneIconOverrides.revision) { ZuneIconOverrides.get(context, pkg) }
    val glyph = when (override) { null -> zuneGlyphFor(pkg); "own" -> null; else -> override }
    if (glyph != null || pkg.startsWith("glide:")) {
        ZuneIcon(glyph ?: "tile", tint, Modifier.size(size))
        return
    }
    val px = (size.value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(24)
    val mono by produceState(monoCache[pkg], pkg) {
        if (value == null && pkg !in noMono) value = withContext(Dispatchers.IO) { loadMonoIcon(context, pkg, px) }
    }
    val image = mono
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(size))
    else ZuneIcon("tile", tint, Modifier.size(size))
}
