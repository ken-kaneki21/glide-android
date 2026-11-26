package com.gharaudio.launcher

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Install C+: Zune HD "Live" screen saver on Now Playing.
 * After 20 s on Now Playing with music playing and no touch, the controls fade out and kinetic type takes over:
 *  - grayscale artist photo bottom-right, slowly panning
 *  - the artist name, huge and faint, drifting right-to-left
 *  - the album title drifting left-to-right at a third of the height
 *  - the cached biography rising slowly on the left
 *  - the song title fixed bottom-left in your accent, always readable
 * Any touch brings the player back. Animation runs at ~30 fps and only while the saver is visible;
 * the time is read in graphicsLayer (draw phase), so text is not recomposed per frame.
 * Settings: "screen saver" on / off.
 */
@Composable
internal fun GlideScreenSaverHost(context: Context, player: PlayerInfo, active: Boolean, content: @Composable (Boolean) -> Unit) {
    val prefs = remember { context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE) }
    var touch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showing by remember { mutableStateOf(false) }
    val enabled = prefs.getBoolean("screen_saver", true)
    LaunchedEffect(active, player.playing, touch, enabled) {
        showing = false
        if (!active || !player.playing || !enabled || player.controller == null || GlideDiag.has(1024)) return@LaunchedEffect
        delay(20_000L)
        showing = true
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) { awaitPointerEvent(PointerEventPass.Initial); touch = SystemClock.elapsedRealtime() }
        }
    }) {
        // F2 perf: while the saver covers the screen, the player underneath stops its visualizer / lyrics / breathing
        content(showing)
        AnimatedVisibility(showing, enter = fadeIn(tween(400)), exit = fadeOut(tween(250))) {
            GlideScreenSaver(context, player)
        }
    }
}

@Composable
private fun GlideScreenSaver(context: Context, player: PlayerInfo) {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) { t.floatValue = (SystemClock.elapsedRealtime() - start) / 1000f; delay(66L) } // ~15 fps: slow drift needs no more
    }
    // F2 perf: grayscale baked once into a small bitmap (no colour filter re-applied every frame)
    val photo by produceState<Bitmap?>(null, player.artist) {
        value = withContext(Dispatchers.IO) { runCatching { loadArtistBackdropPatiently(context, player.artist)?.let(::grayscaleSmall) }.getOrNull() }
    }
    val bio by produceState<String?>(null, player.artist) {
        value = withContext(Dispatchers.IO) { GlideArtistEditorial.cachedSummary(context, player.artist) }
    }
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize().background(GlideMetroStyle.ink).pointerInput(Unit) { detectTapGestures { } }) {
        val wPx = constraints.maxWidth.toFloat()
        // Layer 1: artist photo, grayscale, panning 40 dp back and forth over 20 s
        photo?.takeUnless { it.isRecycled }?.let { bmp ->
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(.85f).fillMaxHeight(.7f).align(Alignment.BottomEnd).graphicsLayer {
                    val p = (t.floatValue % 40f) / 20f
                    translationX = -40f * density * (if (p < 1f) p else 2f - p)
                    alpha = .35f
                })
        }
        // Layer 2: giant faint artist name, right-to-left
        DriftLine(player.artist.lowercase(), 140, FontWeight.Bold, .06f, -12f, wPx, density, t, Modifier.align(Alignment.TopStart).padding(top = 40.dp))
        // Layer 3: album title, left-to-right at ~35% height
        if (player.album.isNotBlank())
            Box(Modifier.fillMaxHeight(.35f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                DriftLine(player.album.lowercase(), 48, FontWeight.Light, .25f, 22f, wPx, density, t, Modifier)
            }
        // Layer 3b: biography rising slowly on the left
        bio?.let { text ->
            var h by remember(text) { mutableIntStateOf(1) }
            Text(text, style = Zune.style(13.sp, FontWeight.Normal), modifier = Modifier.fillMaxWidth(.62f).padding(start = 24.dp)
                .align(Alignment.TopStart).onSizeChanged { h = it.height.coerceAtLeast(1) }
                .graphicsLayer {
                    val travel = h + constraints.maxHeight
                    translationY = constraints.maxHeight - ((t.floatValue * 8f * density) % travel)
                    alpha = .45f
                })
        }
        // Layer 4: what is playing, always readable
        Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, end = 24.dp, bottom = 96.dp)) {
            Text(player.title, style = Zune.style(32.sp, FontWeight.SemiBold, Zune.magenta), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(player.artist.lowercase(), style = Zune.style(16.sp, FontWeight.Normal, Zune.light), maxLines = 1)
        }
    }
}

/** One line of text that loops across the screen; speed in dp/s (negative = right-to-left). */
@Composable
private fun DriftLine(text: String, sizeSp: Int, weight: FontWeight, alpha: Float, speedDp: Float, screenW: Float, density: Float,
                      t: State<Float>, modifier: Modifier) {
    var w by remember(text) { mutableIntStateOf(1) }
    Text(text, style = Zune.style(sizeSp.sp, weight), maxLines = 1, softWrap = false,
        modifier = modifier.wrapContentWidth(Alignment.Start, unbounded = true).onSizeChanged { w = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                val span = w + screenW
                val d = (t.value * kotlin.math.abs(speedDp) * density) % span
                translationX = if (speedDp < 0) screenW - d else d - w
                this.alpha = alpha
            })
}

private fun grayscaleSmall(src: Bitmap): Bitmap? {
    if (src.isRecycled || src.width <= 0) return null
    val scale = (540f / maxOf(src.width, src.height)).coerceAtMost(1f)
    val w = (src.width * scale).toInt().coerceAtLeast(1); val h = (src.height * scale).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
    val c = android.graphics.Canvas(out)
    val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    p.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
    val s = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) ?: return null else src
    c.drawBitmap(s, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
    return out
}
