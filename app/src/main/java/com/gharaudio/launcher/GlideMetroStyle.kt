package com.gharaudio.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Single palette source. No recoloring large bitmap objects when changing palette. */
internal object GlideMetroStyle {
    var palette by mutableIntStateOf(0)
    var wallpaper by mutableIntStateOf(1) // Artist/album ambience; original Glide ink waves when idle
    var wallpaperRevision by mutableIntStateOf(0)
    var artworkRevision by mutableIntStateOf(0)
    // B2: Windows Phone accent colours. The chosen one drives every highlight (Zune.magenta reads it).
    // Windows Phone 7.1 accents, original names and values (MSDN "Theme resources for Windows Phone").
    val names = listOf("magenta", "purple", "teal", "lime", "brown", "pink", "mango", "blue", "red", "green", "zune pink",
        // F3: Windows Phone 8 additions + Zune orange
        "cobalt", "indigo", "violet", "crimson", "amber", "steel", "emerald", "lime (wp8)", "zune orange")
    internal val wpAccents = listOf(0xFFD80073, 0xFFA200FF, 0xFF00ABA9, 0xFFA2C139, 0xFFA05000, 0xFFE671B8, 0xFFF09609, 0xFF1BA1E2, 0xFFE51400, 0xFF339933, 0xFFEC008C,
        0xFF0050EF, 0xFF6A00FF, 0xFFAA00FF, 0xFFA20025, 0xFFF0A30A, 0xFF647687, 0xFF008A00, 0xFFA4C400, 0xFFF15A24)
    val zuneAccent: Color get() = Color(wpAccents[palette.coerceIn(0, wpAccents.lastIndex)])
    fun accentArgb(context: Context): Int =
        wpAccents[context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getInt("metro_palette_v15", 0).coerceIn(0, wpAccents.lastIndex)].toInt()
    /** B2: background gallery — several of your own images, rotated every 30 minutes. */
    fun galleryDir(context: Context) = File(context.filesDir, "glide_gallery").apply { mkdirs() }
    fun galleryFiles(context: Context): List<File> = galleryDir(context).listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name }.orEmpty()
    // B2: Windows Phone dark theme — black canvas, #141414 surfaces, grey secondary text; colour comes
    // ONLY from the chosen accent (the old per-palette tints made "themes" look like they did nothing).
    // Theme step: Windows Phone / Zune Desktop light background (white canvas, #F2F2F2 surfaces, dark text).
    var lightTheme by mutableStateOf(false)
    var cornerArt by mutableStateOf(false)
    fun cornerDir(context: Context) = File(context.filesDir, "glide_corner_art").apply { mkdirs() }
    fun cornerFiles(context: Context): List<File> = cornerDir(context).listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name }.orEmpty()
    val ink: Color get() = if (lightTheme) Color(0xFFFFFFFF) else Color(0xFF000000)
    val surface: Color get() = if (lightTheme) Color(0xFFF2F2F2) else Color(0xFF141414)
    val accent: Color get() = zuneAccent
    val cobalt: Color get() = zuneAccent.copy(red = zuneAccent.red * .55f, green = zuneAccent.green * .55f, blue = zuneAccent.blue * .55f)
    val coral: Color get() = zuneAccent
    val secondary: Color get() = if (lightTheme) Color(0xFF666666) else Color(0xFF999999)
    val paper: Color get() = if (lightTheme) Color(0xFF111111) else Color(0xFFF5F6F8)
    fun restore(context: Context) {
        val p = context.getSharedPreferences("ghar_ui",Context.MODE_PRIVATE)
        palette = p.getInt("metro_palette_v15",0).coerceIn(0,19)
        wallpaper = p.getInt("metro_wallpaper_v15",1).coerceIn(0,8)
        lightTheme = p.getBoolean("metro_light", false)
        cornerArt = p.getBoolean("metro_corner_art", false)
    }
    fun setPalette(context: Context, value: Int) {
        palette = value.coerceIn(0,19)
        context.getSharedPreferences("ghar_ui",Context.MODE_PRIVATE).edit().putInt("metro_palette_v15",palette).apply()
    }
    fun setWallpaper(context: Context, value: Int) {
        wallpaper = value.coerceIn(0,8)
        context.getSharedPreferences("ghar_ui",Context.MODE_PRIVATE).edit().putInt("metro_wallpaper_v15",wallpaper).apply()
    }
    fun wallpaperFile(context: Context) = File(context.filesDir, "glide_user_wallpaper.img")
    fun setLight(context: Context, on: Boolean) {
        lightTheme = on; context.getSharedPreferences("ghar_ui",Context.MODE_PRIVATE).edit().putBoolean("metro_light",on).apply()
    }
    fun setCornerArt(context: Context, on: Boolean) {
        cornerArt = on; context.getSharedPreferences("ghar_ui",Context.MODE_PRIVATE).edit().putBoolean("metro_corner_art",on).apply()
    }
}

/** Original Glide-generated backdrop, not a Microsoft/Zune stock-image copy. */
@Composable
internal fun GlideInkWaves(modifier: Modifier = Modifier) {
    val accent = GlideMetroStyle.accent
    val cobalt = GlideMetroStyle.cobalt
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(brush = Brush.verticalGradient(listOf(GlideMetroStyle.surface, GlideMetroStyle.ink)))
        val ribbon = Path().apply {
            moveTo(-w * .2f, h * .25f)
            cubicTo(w * .35f, h * .04f, w * .54f, h * .71f, w * 1.2f, h * .25f)
            lineTo(w * 1.2f, h * .38f)
            cubicTo(w * .47f, h * .88f, w * .37f, h * .14f, -w * .2f, h * .42f)
            close()
        }
        drawPath(ribbon, brush = Brush.linearGradient(listOf(cobalt.copy(alpha=.16f), accent.copy(alpha=.09f), Color.Transparent)))
        val ribbon2 = Path().apply {
            moveTo(-w * .2f, h * .72f)
            cubicTo(w * .33f, h * .42f, w * .55f, h * 1.05f, w * 1.2f, h * .53f)
            lineTo(w * 1.2f, h * .64f)
            cubicTo(w * .47f, h * 1.09f, w * .35f, h * .61f, -w * .2f, h * .86f)
            close()
        }
        drawPath(ribbon2, accent.copy(alpha=.055f))
    }
}

private fun decodeImported(file: File): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path,bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        var s = 1
        while(maxOf(bounds.outWidth,bounds.outHeight) / s > 1280) s *= 2
        BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=s })
    }
} catch (_:Exception) { null }

/** Modes: 0 Glide waves, 1 dynamic artist/album with waves fallback, 2 imported photo, 3 black.
 *  v1.10e 1.0: the waves, the 50% artwork and the scrim are flattened ONCE (half resolution, off the
 *  main thread) into a single image that is re-rendered only when artwork, palette, mode or size
 *  changes. Every frame then draws one bitmap instead of three full-screen layers plus two paths.
 *  The whole flattened layer carries the parallax (previously only the artwork moved). */
@Composable
internal fun GlideMetroBackdrop(context: Context, artwork: Bitmap?, offset: () -> Float, animate: Boolean) {
    val mode = GlideMetroStyle.wallpaper
    val rev = GlideMetroStyle.wallpaperRevision
    // B2 mode 4: your gallery, a different image every 30 minutes (re-rendered only when the slot changes)
    val slot = if (mode == 4) (System.currentTimeMillis() / 1_800_000L).toInt() else 0
    val imported by produceState<Bitmap?>(null,mode,rev,slot) {
        value = when (mode) {
            2 -> withContext(Dispatchers.IO) { GlideMetroStyle.wallpaperFile(context).takeIf(File::isFile)?.let(::decodeImported) }
            4 -> withContext(Dispatchers.IO) { GlideMetroStyle.galleryFiles(context).let { f -> if (f.isEmpty()) null else decodeImported(f[slot % f.size]) } }
            else -> null
        }
    }
    val chosen = when(mode) { 1, 8 -> artwork; 2, 4 -> imported; else -> null }
    val design = if (mode in 5..7) mode - 4 else 0
    val light = GlideMetroStyle.lightTheme
    // v1.10e 0.4: Pure black mode draws one fill only.
    if (mode == 3) {
        Box(Modifier.fillMaxSize().background(GlideMetroStyle.ink)) { GlideCornerArt(context) }
        return
    }
    val surface = GlideMetroStyle.surface.toArgb()
    val ink = GlideMetroStyle.ink.toArgb()
    val accent = (if (design > 0) GlideMetroStyle.zuneAccent else GlideMetroStyle.accent).toArgb()
    val cobalt = GlideMetroStyle.cobalt.toArgb()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = (constraints.maxWidth / 2).coerceAtLeast(1)
        val h = (constraints.maxHeight / 2).coerceAtLeast(1)
        // The previous image stays on screen until the new one is ready (no flash on track change).
        val flat by produceState<ImageBitmap?>(null, chosen, surface, ink, accent, cobalt, w, h, design, mode, light) {
            value = withContext(Dispatchers.Default) {
                runCatching { (when {
                    // F1: light theme is a clean Zune-desktop white page (photos washed out behind dark text looked bad)
                    light -> renderLightCanvas(w, h, accent)
                    design > 0 -> renderZuneDesign(design, w, h, accent, ink)
                    mode == 8 -> renderAlbumWash(chosen, w, h, ink)
                    else -> renderBackdrop(chosen, w, h, surface, ink, accent, cobalt) }).asImageBitmap() }.getOrNull()
            }
        }
        val image = flat
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.045f; scaleY = 1.045f
                    translationX = if (animate) -offset() * 15f * density else 0f
                })
        } else {
            LayeredBackdrop(chosen, offset, animate) // first frame only, until the flattened image exists
        }
        GlideCornerArt(context)
    }
}

/** Theme step: Zune Desktop "chrome" — one of your corner-art images at ~10% in the top-right corner,
 *  a different one every 30 minutes. Off unless turned on in settings. */
@Composable
private fun GlideCornerArt(context: Context) {
    if (!GlideMetroStyle.cornerArt) return
    val rev = GlideMetroStyle.wallpaperRevision
    val slot = (System.currentTimeMillis() / 1_800_000L).toInt()
    val art by produceState<ImageBitmap?>(null, rev, slot) {
        value = withContext(Dispatchers.IO) {
            GlideMetroStyle.cornerFiles(context).let { f -> if (f.isEmpty()) null else decodeSmall(f[slot % f.size], 640) }?.asImageBitmap()
        }
    }
    val img = art ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Image(img, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(.78f).graphicsLayer { alpha = .11f })
    }
}

private fun decodeSmall(file: File, maxSide: Int): Bitmap? = try {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, b)
    var s = 1
    while (maxOf(b.outWidth, b.outHeight) / s > maxSide) s *= 2
    if (b.outWidth <= 0) null else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
} catch (_: Exception) { null }

/** F1: light theme canvas — white, a faint accent glow in the top-right corner, nothing behind the text. */
private fun renderLightCanvas(w: Int, h: Int, accent: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.WHITE)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.shader = android.graphics.RadialGradient(w * .95f, h * .02f, h * .45f, intArrayOf(withAlpha(accent, .16f), 0), null,
        android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    return out
}

/** Theme step: the playing album cover, blurred (tiny + scaled up), saturated and washed over the canvas. */
private fun renderAlbumWash(src: Bitmap?, w: Int, h: Int, ink: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(ink)
    if (src == null || src.isRecycled) return out
    val bmp = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) ?: return out else src
    val tiny = Bitmap.createScaledBitmap(bmp, 12, 12, true)
    val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    p.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.4f) })
    p.alpha = 92
    c.drawBitmap(tiny, null, android.graphics.RectF(-w * .2f, -h * .1f, w * 1.2f, h * .75f), p)
    if (tiny !== bmp) tiny.recycle()
    if (bmp !== src) bmp.recycle() // Temporary HARDWARE -> software copy; source remains cached.
    val clamp = android.graphics.Shader.TileMode.CLAMP
    val q = android.graphics.Paint()
    q.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(withAlpha(ink, .1f), withAlpha(ink, .55f), ink), floatArrayOf(0f, .4f, .85f), clamp)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), q)
    return out
}

/** The original three-layer drawing; used only before the flattened image is ready. */
@Composable
private fun LayeredBackdrop(chosen: Bitmap?, offset: () -> Float, animate: Boolean) {
    Box(Modifier.fillMaxSize()) {
        GlideInkWaves(Modifier.fillMaxSize())
        if (chosen != null && !chosen.isRecycled) {
            val image = remember(chosen) { chosen.asImageBitmap() }
            Image(image,contentDescription=null,contentScale=ContentScale.Crop,
                modifier=Modifier.fillMaxSize().graphicsLayer {
                    scaleX=1.045f; scaleY=1.045f
                    translationX=if(animate) -offset() * 15f * density else 0f
                    alpha=.5f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                })
        }
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(GlideMetroStyle.ink.copy(alpha=.19f),GlideMetroStyle.ink.copy(alpha=.74f),GlideMetroStyle.ink.copy(alpha=.94f)))
        ))
    }
}

/** B2: original Zune-HD-spirited backgrounds drawn by Glide (not Microsoft artwork), tinted with your accent.
 *  1 = light rays, 2 = colour bands, 3 = soft glow. Rendered once at half resolution. */
private fun renderZuneDesign(design: Int, w: Int, h: Int, accent: Int, ink: Int = android.graphics.Color.BLACK): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val fw = w.toFloat(); val fh = h.toFloat()
    c.drawColor(ink)
    val clamp = android.graphics.Shader.TileMode.CLAMP
    when (design) {
        1 -> { // rays from the upper left
            p.shader = android.graphics.RadialGradient(fw * .1f, fh * .05f, fh * .9f, intArrayOf(withAlpha(accent, .55f), withAlpha(accent, .12f), 0), floatArrayOf(0f, .45f, 1f), clamp)
            c.drawRect(0f, 0f, fw, fh, p); p.shader = null
            p.color = android.graphics.Color.WHITE
            for (i in 0 until 9) {
                val a = Math.toRadians(18.0 + i * 7.5); val len = fh * 1.6f
                p.alpha = if (i % 2 == 0) 16 else 9
                val path = android.graphics.Path().apply {
                    moveTo(fw * .05f, -fh * .02f)
                    lineTo(fw * .05f + (len * kotlin.math.cos(a)).toFloat(), (len * kotlin.math.sin(a)).toFloat())
                    lineTo(fw * .05f + (len * kotlin.math.cos(a + 0.035)).toFloat(), (len * kotlin.math.sin(a + 0.035)).toFloat()); close()
                }
                c.drawPath(path, p)
            }
        }
        2 -> { // broad diagonal colour bands
            for (i in 0 until 5) {
                val y = fh * (0.1f + i * 0.22f)
                p.shader = android.graphics.LinearGradient(0f, y - fh * .12f, 0f, y + fh * .12f, intArrayOf(0, withAlpha(accent, .18f + .06f * (i % 2)), 0), null, clamp)
                c.save(); c.rotate(-24f, fw / 2, fh / 2); c.drawRect(-fw, y - fh * .12f, fw * 2, y + fh * .12f, p); c.restore()
            }
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, fh, intArrayOf(withAlpha(ink, .2f), withAlpha(ink, .67f)), null, clamp); c.drawRect(0f, 0f, fw, fh, p)
        }
        else -> { // two soft glows
            p.shader = android.graphics.RadialGradient(fw * .85f, fh * .82f, fh * .7f, intArrayOf(withAlpha(accent, .6f), withAlpha(accent, .15f), 0), floatArrayOf(0f, .5f, 1f), clamp)
            c.drawRect(0f, 0f, fw, fh, p)
            p.shader = android.graphics.RadialGradient(fw * .05f, fh * .1f, fh * .45f, intArrayOf(withAlpha(accent, .28f), 0), null, clamp)
            c.drawRect(0f, 0f, fw, fh, p)
        }
    }
    return out
}

private fun withAlpha(argb: Int, alpha: Float): Int =
    (argb and 0x00FFFFFF) or ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24)

/** Same geometry and colours as GlideInkWaves + 50% centre-cropped artwork + scrim, drawn once. */
private fun renderBackdrop(src: Bitmap?, w: Int, h: Int, surface: Int, ink: Int, accent: Int, cobalt: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    val fw = w.toFloat(); val fh = h.toFloat()
    val clamp = android.graphics.Shader.TileMode.CLAMP
    // v1.11e2 Zune: with a photo, it IS the background (no coloured ribbons), under a black scrim
    // that deepens toward the bottom so white text stays readable without any boxes.
    if (src != null && !src.isRecycled) {
        val bmp = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            c.drawColor(ink)
            val lightBg = ink == -1 // theme step: white scrim on the light theme
            val scale = maxOf(fw / bmp.width, fh / bmp.height)
            val sw = fw / scale; val sh = fh / scale
            val sx = (bmp.width - sw) / 2f; val sy = (bmp.height - sh) / 3f // keep faces near the top
            c.drawBitmap(bmp,
                android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()),
                android.graphics.RectF(0f, 0f, fw, fh), p)
            // v1.11i: bright photos (light sky, white studio) get a deeper scrim so thin white text stays readable.
            val luma = run {
                val tiny = Bitmap.createScaledBitmap(bmp, 12, 12, true)
                var sum = 0.0
                for (y in 0 until 12) for (x in 0 until 12) {
                    val c = tiny.getPixel(x, y)
                    sum += 0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)
                }
                if (tiny !== bmp) tiny.recycle()
                (sum / 144.0 / 255.0).toFloat()
            }
            val extra = (if (lightBg) (0.65f - luma) / 0.4f else (luma - 0.35f) / 0.4f).coerceIn(0f, 1f) // contrast against the text colour
            fun a(base: Int, max: Int) = ((base + (max - base) * extra).toInt() shl 24) or (if (lightBg) 0xFFFFFF else 0)
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, fh,
                intArrayOf(a(0x99, 0xC2), a(0xAD, 0xCC), a(0xD6, 0xE0), a(0xEB, 0xF0)),
                floatArrayOf(0f, .35f, .7f, 1f), clamp)
            c.drawRect(0f, 0f, fw, fh, p)
            if (bmp !== src) bmp.recycle() // Only the one-off software copy.
            return out
        }
        if (bmp != null && bmp !== src) bmp.recycle()
    }
    p.shader = android.graphics.LinearGradient(0f, 0f, 0f, fh, surface, ink, clamp)
    c.drawRect(0f, 0f, fw, fh, p)
    val ribbon = android.graphics.Path().apply {
        moveTo(-fw * .2f, fh * .25f)
        cubicTo(fw * .35f, fh * .04f, fw * .54f, fh * .71f, fw * 1.2f, fh * .25f)
        lineTo(fw * 1.2f, fh * .38f)
        cubicTo(fw * .47f, fh * .88f, fw * .37f, fh * .14f, -fw * .2f, fh * .42f)
        close()
    }
    p.shader = android.graphics.LinearGradient(0f, 0f, fw, fh,
        intArrayOf(withAlpha(cobalt, .16f), withAlpha(accent, .09f), 0), null, clamp)
    c.drawPath(ribbon, p)
    val ribbon2 = android.graphics.Path().apply {
        moveTo(-fw * .2f, fh * .72f)
        cubicTo(fw * .33f, fh * .42f, fw * .55f, fh * 1.05f, fw * 1.2f, fh * .53f)
        lineTo(fw * 1.2f, fh * .64f)
        cubicTo(fw * .47f, fh * 1.09f, fw * .35f, fh * .61f, -fw * .2f, fh * .86f)
        close()
    }
    p.shader = null
    p.color = withAlpha(accent, .055f)
    c.drawPath(ribbon2, p)
    if (src != null && !src.isRecycled) {
        val bmp = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val scale = maxOf(fw / bmp.width, fh / bmp.height)
            val sw = fw / scale; val sh = fh / scale
            val sx = (bmp.width - sw) / 2f; val sy = (bmp.height - sh) / 2f
            p.color = android.graphics.Color.WHITE
            p.alpha = 128
            c.drawBitmap(bmp,
                android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()),
                android.graphics.RectF(0f, 0f, fw, fh), p)
            p.alpha = 255
        }
        if (bmp != null && bmp !== src) bmp.recycle()
    }
    p.shader = android.graphics.LinearGradient(0f, 0f, 0f, fh,
        intArrayOf(withAlpha(ink, .19f), withAlpha(ink, .74f), withAlpha(ink, .94f)), null, clamp)
    c.drawRect(0f, 0f, fw, fh, p)
    return out
}

/** One elastic edge response per scroll container; no per-row animations or bitmap allocations. */
internal fun Modifier.metroElastic(enabled: Boolean): Modifier = composed {
    val displacement = remember { mutableFloatStateOf(0f) }
    val max = 38.dp.value * androidx.compose.ui.platform.LocalDensity.current.density
    val connection = remember(enabled,max) {
        object: NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if(enabled && source==NestedScrollSource.UserInput && available.y != 0f) {
                    displacement.floatValue=(displacement.floatValue + available.y*.22f).coerceIn(-max,max)
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (enabled && displacement.floatValue != 0f) {
                    val springValue = Animatable(displacement.floatValue)
                    springValue.animateTo(0f, spring(dampingRatio=Spring.DampingRatioMediumBouncy,stiffness=Spring.StiffnessMediumLow)) {
                        displacement.floatValue=value
                    }
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    this.nestedScroll(connection).graphicsLayer { translationY = if(enabled) displacement.floatValue else 0f }
}

/** Dedicated spring response for Library lists nested inside the horizontal pivot.
 * Observe the list's actual edge, so vertical overscroll is not lost to the pager.
 * One graphics-layer translation per whole list; no per-row allocation or animation.
 */
internal fun Modifier.metroLibraryElastic(state: LazyListState): Modifier = composed {
    val edgeOffset = remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val maxPull = 54f * density
    val connection = remember(state, maxPull) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    val atTop = !state.canScrollBackward && available.y > 0f
                    val atBottom = !state.canScrollForward && available.y < 0f
                    if (atTop || atBottom) {
                        edgeOffset.floatValue = (edgeOffset.floatValue + available.y * 0.43f)
                            .coerceIn(-maxPull, maxPull)
                    }
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity
            ): androidx.compose.ui.unit.Velocity {
                if (edgeOffset.floatValue != 0f) {
                    val bounce = Animatable(edgeOffset.floatValue)
                    bounce.animateTo(0f, spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )) { edgeOffset.floatValue = value }
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    // E: Windows Phone "squish" instead of a rubber band — the list compresses toward the edge you hit,
    // so no empty gap opens above the first row (or below the last).
    this.nestedScroll(connection).graphicsLayer {
        val o = edgeOffset.floatValue
        if (o != 0f) {
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, if (o > 0f) 0f else 1f)
            scaleY = 1f - (kotlin.math.abs(o) / maxPull) * .06f
        }
    }
}

/** Single family of original, geometry-aligned Metro transport controls (no glyph substitutions). */
@Composable
internal fun MetroTransport(kind: String, enabled: Boolean = true, tint: Color = GlideMetroStyle.paper, onClick: () -> Unit) {
    Canvas(Modifier.size(52.dp).clickable(enabled=enabled,onClick=onClick).padding(12.dp)) {
        val w=size.width; val h=size.height; val color=tint.copy(alpha=if(enabled) 1f else .34f)
        fun triangle(x1:Float,y1:Float,x2:Float,y2:Float,x3:Float,y3:Float) {
            val p=Path().apply { moveTo(x1,y1);lineTo(x2,y2);lineTo(x3,y3);close() }
            drawPath(p,color)
        }
        when(kind) {
            "play" -> triangle(w*.25f,h*.1f,w*.25f,h*.9f,w*.85f,h*.5f)
            "pause" -> {
                drawRect(color,topLeft=Offset(w*.22f,h*.14f),size=Size(w*.18f,h*.72f))
                drawRect(color,topLeft=Offset(w*.62f,h*.14f),size=Size(w*.18f,h*.72f))
            }
            "previous" -> {
                drawRect(color,topLeft=Offset(w*.10f,h*.10f),size=Size(w*.11f,h*.8f))
                triangle(w*.83f,h*.12f,w*.83f,h*.88f,w*.26f,h*.5f)
            }
            "next" -> {
                drawRect(color,topLeft=Offset(w*.79f,h*.10f),size=Size(w*.11f,h*.8f))
                triangle(w*.17f,h*.12f,w*.17f,h*.88f,w*.74f,h*.5f)
            }
            "volume" -> {
                val p=Path().apply {moveTo(w*.1f,h*.36f);lineTo(w*.35f,h*.36f);lineTo(w*.58f,h*.13f);lineTo(w*.58f,h*.87f);lineTo(w*.35f,h*.64f);lineTo(w*.1f,h*.64f);close()}
                drawPath(p,color)
                drawArc(color,-70f,140f,false,topLeft=Offset(w*.42f,h*.14f),size=Size(w*.48f,h*.72f),style=Stroke(width=2.dp.toPx()))
            }
        }
    }
}

/** E: Metro list fling — a steady exponential glide (velocity × ~0.94 every 16 ms) instead of Android's spline,
 *  so long lists feel weighted and deliberate. */
@Composable
internal fun rememberMetroFling(): androidx.compose.foundation.gestures.FlingBehavior = remember {
    object : androidx.compose.foundation.gestures.FlingBehavior {
        override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float): Float {
            if (kotlin.math.abs(initialVelocity) < 1f) return initialVelocity
            var left = initialVelocity
            var last = 0f
            androidx.compose.animation.core.AnimationState(0f, initialVelocity).animateDecay(
                androidx.compose.animation.core.exponentialDecay(frictionMultiplier = 1.3f)) {
                val delta = value - last
                val used = scrollBy(delta)
                last = value; left = velocity
                if (kotlin.math.abs(delta - used) > 0.5f) cancelAnimation()
            }
            return left
        }
    }
}

/** F3: Windows Phone indeterminate progress — five accent dots fly in fast, crawl through the middle together
 *  and shoot off the right edge, 140 ms apart. Only runs while it is on screen. */
@Composable
internal fun MetroFlyingDots(modifier: Modifier = Modifier) {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = android.os.SystemClock.elapsedRealtime()
        while (true) { t.floatValue = (android.os.SystemClock.elapsedRealtime() - start) / 1000f; kotlinx.coroutines.delay(16L) }
    }
    val accent = GlideMetroStyle.zuneAccent
    Canvas(modifier.height(8.dp)) {
        val w = size.width; val d = 4.dp.toPx(); val y = (size.height - d) / 2
        val cycle = 2.6f
        for (i in 0 until 5) {
            val p = ((t.floatValue - i * 0.14f) % cycle + cycle) % cycle / 2.0f // 0..1 travel, rest pause
            if (p > 1f) continue
            // fast in, crawl in the middle 30 %, fast out
            val x = when {
                p < .3f -> { val q = p / .3f; .35f * (1f - (1f - q) * (1f - q) * (1f - q)) }
                p < .7f -> .35f + .3f * ((p - .3f) / .4f)
                else -> { val q = (p - .7f) / .3f; .65f + .4f * q * q * q }
            }
            drawRect(accent, Offset(x * w, y), Size(d, d))
        }
    }
}

/** F3: Windows Phone toast — a full-width accent strip slides down from the top, bold "glide" + message,
 *  swipe right to dismiss, otherwise it goes away after 4 s. Replaces Android's grey toasts inside Glide. */
internal object GlideToast {
    val message = mutableStateOf<String?>(null)
    val serial = mutableIntStateOf(0)
    fun show(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { message.value = text; serial.intValue++ }
    }
}

@Composable
internal fun GlideToastHost() {
    val text = GlideToast.message.value ?: return
    val serial = GlideToast.serial.intValue
    val y = remember(serial) { Animatable(-1f) }
    val x = remember(serial) { Animatable(0f) }
    LaunchedEffect(serial) {
        y.animateTo(0f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)))
        kotlinx.coroutines.delay(4000L)
        y.animateTo(-1f, androidx.compose.animation.core.tween(180))
        if (GlideToast.serial.intValue == serial) GlideToast.message.value = null
    }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().graphicsLayer {
            translationY = y.value * size.height; translationX = x.value * size.width
        }
        .background(GlideMetroStyle.zuneAccent)
        .pointerInput(serial) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (x.value > .25f) { x.animateTo(1f, androidx.compose.animation.core.tween(160)); GlideToast.message.value = null }
                        else x.animateTo(0f)
                    }
                }) { change: androidx.compose.ui.input.pointer.PointerInputChange, dx: Float -> change.consume(); scope.launch { x.snapTo((x.value + dx / size.width).coerceAtLeast(0f)) } }
        }
        .padding(start = 20.dp, end = 16.dp, top = 30.dp, bottom = 12.dp)) {
        androidx.compose.material3.Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)); append("glide  "); pop()
                append(text.lowercase())
            },
            style = Zune.style(15.sp, androidx.compose.ui.text.font.FontWeight.Normal, Color.White), maxLines = 2)
    }
}
