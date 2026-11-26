package com.gharaudio.launcher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

/*
 * v1.11 Phase 1, step 1: Zune / Metro 2020 Now Playing.
 * Visual reference: Saurabh's "Metro 2020 series" screenshots (Madelena's MusicBee Zune skin).
 * Original Compose implementation; no files from the skin are used.
 * Personal-device typography: Zegoe UI via Zune.family; Selawik resources remain available.
 */

internal object Zune {
    val family = FontFamily(
        Font(R.font.zegoe_light, FontWeight.Light),
        Font(R.font.zegoe_light, FontWeight(350)),
        Font(R.font.zegoe_regular, FontWeight.Normal),
        Font(R.font.zegoe_semibold, FontWeight.SemiBold),
        Font(R.font.zegoe_bold, FontWeight.Bold)
    )
    val Semilight = FontWeight(350)
    // Theme step: text colours follow the dark / light background
    val white: Color get() = if (GlideMetroStyle.lightTheme) Color(0xFF111111) else Color.White
    val light: Color get() = if (GlideMetroStyle.lightTheme) Color(0xFF333333) else Color(0xFFC0C0C0)
    val grey: Color get() = if (GlideMetroStyle.lightTheme) Color(0xFF666666) else Color(0xFF7E7E7E)
    val dim: Color get() = if (GlideMetroStyle.lightTheme) Color(0x59000000) else Color(0x59FFFFFF)
    val track: Color get() = if (GlideMetroStyle.lightTheme) Color(0x66000000) else Color(0x66FFFFFF)
    val heart = Color(0xFFE81123)
    val magenta: Color get() = GlideMetroStyle.zuneAccent // B2: follows your accent colour

    fun style(size: TextUnit, weight: FontWeight, color: Color = white, spacing: TextUnit = 0.sp) =
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = size, color = color,
            letterSpacing = spacing, lineHeight = size * 1.12f)
}

/** Keep player metadata on one line and indicate when more text is available. */
@Composable
private fun ZuneLine(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(text, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

// ---------------------------------------------------------------- icons (original thin-line set)

@Composable
internal fun ZuneIcon(kind: String, tint: Color, modifier: Modifier) {
    // F4a: screen readers hear the icon's name (semantics are only built when one is actually running)
    Canvas(modifier.semantics { contentDescription = kind }) { drawZuneIcon(kind, tint) }
}

private fun DrawScope.drawZuneIcon(kind: String, c: Color) {
    val w = size.width
    val h = size.height
    val sw = (w * 0.075f).coerceAtLeast(2f)
    val line = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, c, style = line)
    when (kind) {
        "previous" -> {
            drawLine(c, Offset(w * .2f, h * .12f), Offset(w * .2f, h * .88f), sw)
            tri(w * .82f, h * .14f, w * .82f, h * .86f, w * .34f, h * .5f)
        }
        "next" -> {
            drawLine(c, Offset(w * .8f, h * .12f), Offset(w * .8f, h * .88f), sw)
            tri(w * .18f, h * .14f, w * .18f, h * .86f, w * .66f, h * .5f)
        }
        "play" -> tri(w * .26f, h * .1f, w * .26f, h * .9f, w * .84f, h * .5f)
        "pause" -> {
            drawLine(c, Offset(w * .36f, h * .12f), Offset(w * .36f, h * .88f), sw)
            drawLine(c, Offset(w * .64f, h * .12f), Offset(w * .64f, h * .88f), sw)
        }
        "heart" -> {
            val p = Path().apply {
                moveTo(w * .5f, h * .88f)
                cubicTo(w * .14f, h * .62f, w * .02f, h * .42f, w * .1f, h * .24f)
                cubicTo(w * .2f, h * .04f, w * .44f, h * .08f, w * .5f, h * .28f)
                cubicTo(w * .56f, h * .08f, w * .8f, h * .04f, w * .9f, h * .24f)
                cubicTo(w * .98f, h * .42f, w * .86f, h * .62f, w * .5f, h * .88f)
                close()
            }
            drawPath(p, c)
        }
        "shuffle" -> {
            val a = Path().apply { moveTo(w * .08f, h * .3f); cubicTo(w * .45f, h * .3f, w * .5f, h * .7f, w * .9f, h * .7f) }
            val b = Path().apply { moveTo(w * .08f, h * .7f); cubicTo(w * .45f, h * .7f, w * .5f, h * .3f, w * .9f, h * .3f) }
            drawPath(a, c, style = line); drawPath(b, c, style = line)
            drawLine(c, Offset(w * .9f, h * .3f), Offset(w * .78f, h * .2f), sw); drawLine(c, Offset(w * .9f, h * .3f), Offset(w * .78f, h * .4f), sw)
            drawLine(c, Offset(w * .9f, h * .7f), Offset(w * .78f, h * .6f), sw); drawLine(c, Offset(w * .9f, h * .7f), Offset(w * .78f, h * .8f), sw)
        }
        "repeat", "repeat1" -> {
            drawArc(c, 120f, 300f, false, Offset(w * .14f, h * .14f), Size(w * .72f, h * .72f), style = line)
            drawLine(c, Offset(w * .32f, h * .82f), Offset(w * .44f, h * .94f), sw)
            drawLine(c, Offset(w * .32f, h * .82f), Offset(w * .46f, h * .74f), sw)
            if (kind == "repeat1") drawLine(c, Offset(w * .5f, h * .36f), Offset(w * .5f, h * .64f), sw)
            else drawCircle(c, w * .06f, Offset(w * .5f, h * .5f))
        }
        "search" -> {
            drawCircle(c, w * .24f, Offset(w * .58f, h * .42f), style = line)
            drawLine(c, Offset(w * .41f, h * .59f), Offset(w * .16f, h * .84f), sw * 1.2f)
        }
        "info" -> {
            for (i in 0 until 4) drawLine(c, Offset(w * .12f, h * (.2f + i * .16f)), Offset(w * (if (i < 2) .86f else .56f), h * (.2f + i * .16f)), sw)
            drawCircle(c, w * .08f, Offset(w * .74f, h * .78f))
            drawLine(c, Offset(w * .81f, h * .78f), Offset(w * .81f, h * .5f), sw)
        }
        "back" -> {
            drawLine(c, Offset(w * .16f, h * .5f), Offset(w * .84f, h * .5f), sw)
            drawLine(c, Offset(w * .16f, h * .5f), Offset(w * .42f, h * .24f), sw)
            drawLine(c, Offset(w * .16f, h * .5f), Offset(w * .42f, h * .76f), sw)
        }
        "forward" -> {
            drawLine(c, Offset(w * .16f, h * .5f), Offset(w * .84f, h * .5f), sw)
            drawLine(c, Offset(w * .84f, h * .5f), Offset(w * .58f, h * .24f), sw)
            drawLine(c, Offset(w * .84f, h * .5f), Offset(w * .58f, h * .76f), sw)
        }
        "volume" -> {
            val p = Path().apply {
                moveTo(w * .12f, h * .38f); lineTo(w * .32f, h * .38f)
                lineTo(w * .55f, h * .18f); lineTo(w * .55f, h * .82f)
                lineTo(w * .32f, h * .62f); lineTo(w * .12f, h * .62f); close()
            }
            drawPath(p, c, style = line)
            drawArc(c, -52f, 104f, false, Offset(w * .45f, h * .23f), Size(w * .32f, h * .54f), style = line)
            drawArc(c, -52f, 104f, false, Offset(w * .42f, h * .08f), Size(w * .53f, h * .84f), style = line)
        }
        "more" -> {
            for (x in listOf(.21f, .5f, .79f)) drawCircle(c, sw * .75f, Offset(w * x, h * .5f))
        }
        "plus" -> {
            drawLine(c, Offset(w * .5f, h * .15f), Offset(w * .5f, h * .85f), sw)
            drawLine(c, Offset(w * .15f, h * .5f), Offset(w * .85f, h * .5f), sw)
        }
        "camera" -> {
            val p = Path().apply {
                moveTo(w * .1f, h * .34f); lineTo(w * .29f, h * .34f)
                lineTo(w * .39f, h * .22f); lineTo(w * .65f, h * .22f)
                lineTo(w * .74f, h * .34f); lineTo(w * .9f, h * .34f)
                lineTo(w * .9f, h * .82f); lineTo(w * .1f, h * .82f); close()
            }
            drawPath(p, c, style = line)
            drawCircle(c, w * .16f, Offset(w * .5f, h * .54f), style = line)
        }
        "mic" -> {
            drawLine(c, Offset(w * .5f, h * .13f), Offset(w * .5f, h * .62f), sw * 3f)
            drawArc(c, 0f, 180f, false, Offset(w * .2f, h * .30f), Size(w * .6f, h * .46f), style = line)
            drawLine(c, Offset(w * .5f, h * .76f), Offset(w * .5f, h * .89f), sw)
            drawLine(c, Offset(w * .31f, h * .89f), Offset(w * .69f, h * .89f), sw)
        }
        "settings" -> {
            drawCircle(c, w * .29f, Offset(w * .5f, h * .5f), style = line)
            drawCircle(c, w * .09f, Offset(w * .5f, h * .5f), style = line)
            for (i in 0 until 8) {
                val a = Math.PI * i / 4.0
                val dx = kotlin.math.cos(a).toFloat()
                val dy = kotlin.math.sin(a).toFloat()
                drawLine(c, Offset(w * (.5f + dx * .31f), h * (.5f + dy * .31f)),
                    Offset(w * (.5f + dx * .44f), h * (.5f + dy * .44f)), sw)
            }
        }
        "refresh" -> {
            drawArc(c, 45f, 290f, false, Offset(w * .18f, h * .18f), Size(w * .64f, h * .64f), style = line)
            drawLine(c, Offset(w * .79f, h * .29f), Offset(w * .79f, h * .08f), sw)
            drawLine(c, Offset(w * .79f, h * .29f), Offset(w * .59f, h * .27f), sw)
        }
        "external" -> {
            val p = Path().apply {
                moveTo(w * .57f, h * .14f); lineTo(w * .87f, h * .14f)
                lineTo(w * .87f, h * .44f)
            }
            drawPath(p, c, style = line)
            drawLine(c, Offset(w * .87f, h * .14f), Offset(w * .43f, h * .59f), sw)
            val base = Path().apply {
                moveTo(w * .59f, h * .34f); lineTo(w * .13f, h * .34f)
                lineTo(w * .13f, h * .87f); lineTo(w * .67f, h * .87f)
                lineTo(w * .67f, h * .61f)
            }
            drawPath(base, c, style = line)
        }
        "check" -> {
            drawLine(c, Offset(w * .14f, h * .52f), Offset(w * .4f, h * .78f), sw)
            drawLine(c, Offset(w * .4f, h * .78f), Offset(w * .88f, h * .22f), sw)
        }
        "close" -> {
            drawLine(c, Offset(w * .2f, h * .2f), Offset(w * .8f, h * .8f), sw)
            drawLine(c, Offset(w * .8f, h * .2f), Offset(w * .2f, h * .8f), sw)
        }
        // v1.11e2: app and menu glyphs (original drawings, same stroke and optical size).
        "note" -> {
            drawLine(c, Offset(w * .4f, h * .2f), Offset(w * .4f, h * .72f), sw)
            drawLine(c, Offset(w * .82f, h * .12f), Offset(w * .82f, h * .64f), sw)
            drawLine(c, Offset(w * .4f, h * .2f), Offset(w * .82f, h * .12f), sw)
            drawCircle(c, w * .13f, Offset(w * .28f, h * .74f), style = line)
            drawCircle(c, w * .13f, Offset(w * .7f, h * .66f), style = line)
        }
        "disc" -> {
            drawCircle(c, w * .4f, Offset(w * .5f, h * .5f), style = line)
            drawCircle(c, w * .11f, Offset(w * .5f, h * .5f), style = line)
            drawArc(c, 200f, 70f, false, Offset(w * .24f, h * .24f), Size(w * .52f, h * .52f), style = line)
        }
        "wave" -> {
            val p = Path().apply {
                moveTo(w * .08f, h * .5f)
                cubicTo(w * .2f, h * .1f, w * .3f, h * .1f, w * .38f, h * .5f)
                cubicTo(w * .46f, h * .9f, w * .56f, h * .9f, w * .64f, h * .5f)
                cubicTo(w * .72f, h * .1f, w * .82f, h * .1f, w * .92f, h * .5f)
            }
            drawPath(p, c, style = line)
        }
        "pin" -> {
            val p = Path().apply {
                moveTo(w * .5f, h * .92f)
                cubicTo(w * .2f, h * .58f, w * .16f, h * .44f, w * .16f, h * .38f)
                cubicTo(w * .16f, h * .18f, w * .32f, h * .06f, w * .5f, h * .06f)
                cubicTo(w * .68f, h * .06f, w * .84f, h * .18f, w * .84f, h * .38f)
                cubicTo(w * .84f, h * .44f, w * .8f, h * .58f, w * .5f, h * .92f)
                close()
            }
            drawPath(p, c, style = line)
            drawCircle(c, w * .11f, Offset(w * .5f, h * .38f), style = line)
        }
        "globe" -> {
            drawCircle(c, w * .4f, Offset(w * .5f, h * .5f), style = line)
            drawOval(c, Offset(w * .32f, h * .1f), Size(w * .36f, h * .8f), style = line)
            drawLine(c, Offset(w * .1f, h * .5f), Offset(w * .9f, h * .5f), sw)
            drawLine(c, Offset(w * .18f, h * .3f), Offset(w * .82f, h * .3f), sw * .8f)
            drawLine(c, Offset(w * .18f, h * .7f), Offset(w * .82f, h * .7f), sw * .8f)
        }
        "weather" -> {
            drawCircle(c, w * .17f, Offset(w * .5f, h * .5f), style = line)
            for (i in 0 until 8) {
                val a = Math.PI * i / 4.0
                val dx = kotlin.math.cos(a).toFloat()
                val dy = kotlin.math.sin(a).toFloat()
                drawLine(c, Offset(w * (.5f + dx * .28f), h * (.5f + dy * .28f)),
                    Offset(w * (.5f + dx * .42f), h * (.5f + dy * .42f)), sw)
            }
        }
        "library" -> {
            drawLine(c, Offset(w * .16f, h * .14f), Offset(w * .16f, h * .86f), sw)
            drawLine(c, Offset(w * .36f, h * .14f), Offset(w * .36f, h * .86f), sw)
            drawLine(c, Offset(w * .56f, h * .2f), Offset(w * .84f, h * .84f), sw)
            drawLine(c, Offset(w * .08f, h * .86f), Offset(w * .92f, h * .86f), sw)
        }
        "apps" -> {
            val s = w * .3f
            for (r in 0..1) for (k in 0..1) {
                drawRect(c, Offset(w * .14f + k * w * .42f, h * .14f + r * h * .42f), Size(s, s), style = line)
            }
        }
        "store" -> {
            val p = Path().apply {
                moveTo(w * .14f, h * .34f); lineTo(w * .86f, h * .34f)
                lineTo(w * .8f, h * .88f); lineTo(w * .2f, h * .88f); close()
            }
            drawPath(p, c, style = line)
            drawArc(c, 180f, 180f, false, Offset(w * .34f, h * .1f), Size(w * .32f, h * .46f), style = line)
        }
        "phone" -> {
            drawRect(c, Offset(w * .28f, h * .08f), Size(w * .44f, h * .84f), style = line)
            drawLine(c, Offset(w * .44f, h * .8f), Offset(w * .56f, h * .8f), sw)
        }
        "message" -> {
            val p = Path().apply {
                moveTo(w * .1f, h * .16f); lineTo(w * .9f, h * .16f); lineTo(w * .9f, h * .7f)
                lineTo(w * .36f, h * .7f); lineTo(w * .2f, h * .88f); lineTo(w * .2f, h * .7f)
                lineTo(w * .1f, h * .7f); close()
            }
            drawPath(p, c, style = line)
        }
        "picture" -> {
            drawRect(c, Offset(w * .1f, h * .18f), Size(w * .8f, h * .64f), style = line)
            val p = Path().apply {
                moveTo(w * .14f, h * .76f); lineTo(w * .4f, h * .48f); lineTo(w * .56f, h * .64f)
                lineTo(w * .68f, h * .54f); lineTo(w * .86f, h * .76f)
            }
            drawPath(p, c, style = line)
            drawCircle(c, w * .06f, Offset(w * .7f, h * .34f), style = line)
        }
        "clock" -> {
            drawCircle(c, w * .4f, Offset(w * .5f, h * .5f), style = line)
            drawLine(c, Offset(w * .5f, h * .5f), Offset(w * .5f, h * .24f), sw)
            drawLine(c, Offset(w * .5f, h * .5f), Offset(w * .68f, h * .6f), sw)
        }
        "tile" -> drawRect(c, Offset(w * .16f, h * .16f), Size(w * .68f, h * .68f), style = line)
    }
}

/** Windows Phone app-bar button: thin ring, glyph inside, tiny lowercase label. */
@Composable
internal fun ZuneRingButton(kind: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Zune.white, size.minDimension / 2f - 1.4.dp.toPx(), style = Stroke(2.4.dp.toPx()))
            }
            ZuneIcon(kind, Zune.white, Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = Zune.style(12.sp, FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------- data for the Zune screen

internal data class QueueRow(val number: String, val artist: String, val title: String, val current: Boolean, val queueId: Long = -1L)

private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

/** The local file behind what is playing (for favourites, album order and lyrics). */
private fun localSongFor(context: Context, player: PlayerInfo): Song? {
    if (player.controller == null || player.title.isBlank()) return null
    val songs = getOrLoadMusicIndex(context).songs
    return songs.firstOrNull { same(it.title, player.title) && same(it.artist, player.artist) }
        ?: songs.firstOrNull { same(it.title, player.title) && (player.album.isBlank() || same(it.album, player.album)) }
}

private fun trackLabel(track: Int): String {
    if (track <= 0) return ""
    val disc = (track / 1000).coerceAtLeast(1)
    return "$disc-${String.format(Locale.ROOT, "%02d", track % 1000)}"
}

/** Up-next rows: the player's own queue when it shares one, else the album in track order. */
private fun queueFor(context: Context, player: PlayerInfo, local: Song?): List<QueueRow> {
    val controller = player.controller ?: return emptyList()
    val queue = runCatching { controller.queue }.getOrNull()
    if (!queue.isNullOrEmpty()) {
        val active = controller.playbackState?.activeQueueItemId ?: -1L
        return queue.mapIndexed { i, item ->
            val d = item.description
            QueueRow("${i + 1}", d.subtitle?.toString() ?: "", d.title?.toString() ?: "", item.queueId == active, item.queueId)
        }
    }
    val song = local ?: return emptyList()
    val rows = ArrayList<QueueRow>()
    val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.TRACK)
    runCatching {
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
            "${MediaStore.Audio.Media.ALBUM_ID} = ? AND ${MediaStore.Audio.Media.IS_MUSIC} != 0", arrayOf(song.albumId.toString()),
            "${MediaStore.Audio.Media.TRACK} ASC")?.use { c ->
            while (c.moveToNext()) {
                rows.add(QueueRow(trackLabel(c.getInt(3)), c.getString(2) ?: "", c.getString(1) ?: "", c.getLong(0) == song.id))
            }
        }
    }
    return rows
}

private fun lyricsFor(context: Context, local: Song?): String? {
    val song = local ?: return null
    val path = runCatching {
        context.contentResolver.query(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id),
            arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: return null
    val base = File(path)
    val candidates = listOf(File(base.parentFile, base.nameWithoutExtension + ".lrc"), File(base.parentFile, song.title + ".lrc"))
    val file = candidates.firstOrNull { it.isFile && it.length() in 1..256_000 } ?: return null
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    return text.lines()
        .map { it.replace(Regex("\\[[^\\]]*\\]"), "").trim() }
        .filterNot { it.startsWith("by:", true) }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .ifBlank { null }
}

// ---------------------------------------------------------------- Now Playing

/**
 * Portrait Now Playing, Zune layout: clock, transport, artist / album (cut off at the edge),
 * cover with heart / shuffle / repeat beside it, thin progress bar, title, next three tracks,
 * optional visualizer, and the search / info app bar. The background bitmap is the same
 * flattened image as before (drawn by the caller), so this screen costs no extra frames.
 */
@Composable
internal fun ZuneNowPlaying(
    player: PlayerInfo,
    active: Boolean,
    ambientBars: Boolean,
    background: ImageBitmap?,
    maxWidth: Dp,
    maxHeight: Dp,
    art: @Composable (Modifier) -> Unit,
    onSearch: () -> Unit
) {
    val context = LocalContext.current
    val controller = player.controller
    var infoOpen by remember { mutableStateOf(false) }
    var infoPage by remember { mutableIntStateOf(0) }
    var barOpen by remember { mutableStateOf(false) }
    var chaptersOpen by remember(player.title, player.artist) { mutableStateOf(false) }
    LaunchedEffect(GlideNav.nowPlayingReset.intValue) { infoOpen = false; barOpen = false } // F2: closes when you swipe away
    val local by produceState<Song?>(null, player.title, player.artist, player.album, controller) {
        value = withContext(Dispatchers.IO) { runCatching { localSongFor(context, player) }.getOrNull() }
    }
    val queue by produceState(emptyList<QueueRow>(), player.title, controller, local) {
        value = withContext(Dispatchers.IO) { runCatching { queueFor(context, player, local) }.getOrDefault(emptyList()) }
    }
    val upNext = remember(queue) {
        val i = queue.indexOfFirst { it.current }
        if (i >= 0) queue.drop(i + 1).take(3) else emptyList()
    }
    val cover = (maxWidth * 0.66f).coerceAtMost(maxHeight - 560.dp).coerceAtLeast(150.dp)

    Box(Modifier.fillMaxSize()) {
        ZuneClock(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 16.dp))
        Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 42.dp)) {
            if (controller == null) {
                Spacer(Modifier.height(80.dp))
                ZuneLine("nothing playing", Zune.style(46.sp, Zune.Semilight))
                ZuneLine("pick a player", Zune.style(36.sp, FontWeight.Light))
                Spacer(Modifier.height(28.dp))
                if (GlideEngine.enabled(context)) {
                    // FX: Glide's own player first — shuffle the whole library without leaving Glide
                    Text("shuffle all", style = Zune.style(30.sp, FontWeight.Light, Zune.magenta), modifier = Modifier.padding(bottom = 18.dp).clickable {
                        val songs = cachedMusicIndex?.songs.orEmpty()
                        if (songs.isNotEmpty()) GlideEngine.playList(context, songs, songs.indices.random(), shuffle = true)
                        else GlideToast.show("open library once so glide can read your songs")
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("lg music", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.clickable { launchApp(context, "com.lge.music") })
                    Text("poweramp", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.clickable { launchApp(context, "com.maxmpz.audioplayer") })
                }
                return@Column
            }
            val actions = player.actions
            val prevOk = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
            val nextOk = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
            Row(Modifier.width(cover).height(44.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                ZuneTap("previous", if (prevOk) Zune.white else Zune.dim, 30.dp) { controller.transportControls.skipToPrevious() }
                ZuneTap(if (player.playing) "pause" else "play", Zune.white, 30.dp) {
                    if (player.playing) controller.transportControls.pause() else controller.transportControls.play()
                }
                ZuneTap("next", if (nextOk) Zune.white else Zune.dim, 30.dp) { controller.transportControls.skipToNext() }
            }
            Spacer(Modifier.height(18.dp))
            // v1.11j Zune HD: ARTIST in bold capitals, ALBUM in light capitals, both cut off at the screen edge.
            ZuneLine(player.artist.uppercase(Locale.getDefault()), Zune.style(40.sp, FontWeight.Bold, spacing = (-0.6).sp), Modifier.fillMaxWidth())
            ZuneLine(player.album.ifBlank { player.source }.uppercase(Locale.getDefault()),
                Zune.style(34.sp, FontWeight.Light, spacing = (-0.4).sp), Modifier.fillMaxWidth().offset(y = (-6).dp))
            Spacer(Modifier.height(4.dp))
            art(Modifier.size(cover))
            if (player.duration > 0L) ZuneProgress(player, active, controller, cover)
            Spacer(Modifier.height(6.dp))
            // V: Sing mic sits next to the song (and its lyrics) while Glide's own player plays
            val singHere = controller.packageName == GlideEngine.PKG && GlideEngine.enabled(LocalContext.current)
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ZuneLine(player.title, Zune.style(34.sp, Zune.Semilight, spacing = (-0.3).sp), Modifier.weight(1f))
                if (singHere) GlideSingMic()
            }
            // C1/C2: synced lyrics with karaoke fill take the up-next space while the song has them.
            val lyrics by rememberLyrics(player, local)
            val lyr = lyrics
            if (lyr != null && lyr.synced && !GlideDiag.has(128)) { // 128 = diagnostics: lyrics strip off
                Spacer(Modifier.height(4.dp))
                // V: floating Apple-style lyrics (DIAG 8192 = the previous strip)
                if (GlideDiag.has(8192)) ZuneLyricsStrip(player, lyr, active, Modifier.fillMaxWidth().padding(end = 20.dp))
                else GlideFloatLyrics(player, lyr, active, Modifier.fillMaxWidth().padding(end = 20.dp))
            } else upNext.forEach { ZuneLine(it.title, Zune.style(15.sp, FontWeight.Normal, Zune.light), Modifier.fillMaxWidth().padding(start = 22.dp)) }
            // The visualizer surface sits above the window, so it must leave while the pivots cover the screen.
            if (ambientBars && !infoOpen && !barOpen && active && !GlideDiag.has(8)) { // B2: no visualizer surface off-screen (it leaked onto Live)
                Spacer(Modifier.height(14.dp))
                val currentPlayer by rememberUpdatedState(player)
                Box(Modifier.width(cover)) {
                    GlideLiveSpectrum(enabled = active && player.playing, title = player.title, artist = player.artist,
                        durationMs = player.duration, positionMs = { currentPlayer.livePositionMs() }, compact = true)
                }
            }
        }
        if (controller != null) ZuneBottomToggles(player, local,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 34.dp, end = 34.dp, bottom = 22.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 10.dp)) {
            ZuneTap("search", Zune.light, 22.dp, onSearch)
            Spacer(Modifier.width(6.dp))
            ZuneTap("info", Zune.light, 22.dp) { infoOpen = true }
        }
        // F3: Windows Phone app bar "…" — expands a menu above the ring buttons
        if (controller != null) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 92.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { barOpen = !barOpen }
                .padding(10.dp)) {
                Text("•••", style = Zune.style(14.sp, FontWeight.Bold, Zune.light, 2.sp))
            }
            if (barOpen) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 92.dp)
                .background(Color(0xF21F1F1F)).padding(start = 24.dp, top = 10.dp, bottom = 10.dp)) {
                listOf("queue" to 0, "lyrics" to 1, "artist bio" to 2, "player & visualizer" to 3).forEach { (label, page) ->
                    Text(label, style = Zune.style(18.sp, FontWeight.Normal), modifier = Modifier.fillMaxWidth()
                        .clickable { barOpen = false; infoPage = page; infoOpen = true }.padding(vertical = 8.dp))
                }
                val localSong = local
                if (localSong != null && controller.packageName == GlideEngine.PKG) {
                    val fade = GlideFx.albumFade(ctx, localSong.album)
                    if (localSong.album.isNotBlank()) Text(if (fade) "disable fade for this album" else "enable fade for this album",
                        style = Zune.style(18.sp, FontWeight.Normal), modifier = Modifier.fillMaxWidth().clickable {
                            GlideFx.setAlbumFade(ctx, localSong.album, !fade)
                            barOpen = false
                        }.padding(vertical = 8.dp))
                    val bookmarks = remember(ctx) {
                        ctx.getSharedPreferences("glide_long_listening", android.content.Context.MODE_PRIVATE)
                    }
                    Text("chapters", style = Zune.style(18.sp, FontWeight.Normal),
                        modifier = Modifier.fillMaxWidth().clickable {
                            barOpen = false
                            chaptersOpen = true
                        }.padding(vertical = 8.dp))
                    val longSpeed = GlideEngine.longSpeed(ctx, localSong.id)
                    Text(if (longSpeed == null) "enable long-form resume" else "long-form speed · ${longSpeed}×",
                        style = Zune.style(18.sp, FontWeight.Normal), modifier = Modifier.fillMaxWidth().clickable {
                            val next = when (longSpeed) {
                                null -> 1f
                                1f -> 1.25f
                                1.25f -> 1.5f
                                else -> null
                            }
                            GlideEngine.setLongSpeed(ctx, localSong.id, next)
                            GlideToast.show(if (next == null) "music speed restored" else "long-form · ${next}×")
                            barOpen = false
                        }.padding(vertical = 8.dp))
                    val bookmarkKey = "bookmark_" + localSong.id
                    Text("bookmark this position", style = Zune.style(18.sp, FontWeight.Normal),
                        modifier = Modifier.fillMaxWidth().clickable {
                            bookmarks.edit().putLong(bookmarkKey, player.livePositionMs()).apply()
                            barOpen = false
                            GlideToast.show("position bookmarked")
                        }.padding(vertical = 8.dp))
                    if (bookmarks.contains(bookmarkKey)) Text("jump to bookmark", style = Zune.style(18.sp, FontWeight.Normal),
                        modifier = Modifier.fillMaxWidth().clickable {
                            controller.transportControls.seekTo(bookmarks.getLong(bookmarkKey, 0L))
                            barOpen = false
                        }.padding(vertical = 8.dp))
                }
                if (localSong != null) Text("add to quick list", style = Zune.style(18.sp, FontWeight.Normal), modifier = Modifier.fillMaxWidth()
                    .clickable { barOpen = false; GlideLocalLists.addQuick(ctx, listOf(localSong.id)); GlideToast.show("added to quick list") }
                    .padding(vertical = 8.dp))
            }
        }
        val chapterSong = local
        if (chaptersOpen && chapterSong != null && controller != null) {
            val ctx = LocalContext.current
            val chapters by androidx.compose.runtime.produceState<List<GlideChapter>?>(null, chapterSong) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadCueChapters(ctx, chapterSong) }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { chaptersOpen = false },
                title = { Text("Chapters") },
                text = {
                    val current = chapters
                    when {
                        current == null -> Text("Reading chapters…")
                        current.isEmpty() -> Text("No matching single-file CUE sheet found.")
                        else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            itemsIndexed(current) { _, chapter ->
                                Text(chapter.title + " · " + clock(chapter.position), modifier = Modifier.fillMaxWidth()
                                    .clickable { controller.transportControls.seekTo(chapter.position); chaptersOpen = false }
                                    .padding(vertical = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { chaptersOpen = false }) { Text("Close") } }
            )
        }
        if (infoOpen && controller != null) {
            ZunePivots(player, background, queue, local, infoPage, onClose = { infoOpen = false; infoPage = 0 })
        }
    }
}

@Composable
private fun ZuneTap(kind: String, tint: Color, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size + 18.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        ZuneIcon(kind, tint, Modifier.size(size))
    }
}

@Composable
private fun ZuneClock(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L) // wake once per minute, on the minute
        }
    }
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    Text(format.format(Date(now)), style = Zune.style(16.sp, FontWeight.Normal), modifier = modifier)
}

/** Heart (local favourite), shuffle and repeat stacked beside the cover; grey when off. */
@Composable
private fun ZuneBottomToggles(player: PlayerInfo, local: Song?, modifier: Modifier) {
    val context = LocalContext.current
    val controller = player.controller ?: return
    var favourite by remember(local) { mutableStateOf(local != null && GlideLocalLists.isFavorite(context, local.id)) }
    val compat = remember(controller) {
        runCatching { MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(controller.sessionToken)) }.getOrNull()
    }
    var shuffle by remember(compat) { mutableIntStateOf(compat?.shuffleMode ?: PlaybackStateCompat.SHUFFLE_MODE_INVALID) }
    var repeat by remember(compat) { mutableIntStateOf(compat?.repeatMode ?: PlaybackStateCompat.REPEAT_MODE_INVALID) }
    DisposableEffect(compat) {
        val cb = object : MediaControllerCompat.Callback() {
            override fun onShuffleModeChanged(mode: Int) { shuffle = mode }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode }
        }
        compat?.registerCallback(cb)
        onDispose { compat?.unregisterCallback(cb) }
    }
    val shuffleOn = shuffle == PlaybackStateCompat.SHUFFLE_MODE_ALL || shuffle == PlaybackStateCompat.SHUFFLE_MODE_GROUP
    val repeatOn = repeat == PlaybackStateCompat.REPEAT_MODE_ALL || repeat == PlaybackStateCompat.REPEAT_MODE_GROUP ||
        repeat == PlaybackStateCompat.REPEAT_MODE_ONE
    // Zune HD: shuffle, repeat, heart as ring buttons along the bottom. "On" = filled white ring with a black glyph.
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ZuneRingToggle("shuffle", shuffleOn, enabled = compat != null) {
            compat?.transportControls?.setShuffleMode(
                if (shuffleOn) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
        ZuneRingToggle(if (repeat == PlaybackStateCompat.REPEAT_MODE_ONE) "repeat1" else "repeat", repeatOn, enabled = compat != null) {
            compat?.transportControls?.setRepeatMode(when (repeat) {
                PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> PlaybackStateCompat.REPEAT_MODE_ONE
                PlaybackStateCompat.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_NONE
                else -> PlaybackStateCompat.REPEAT_MODE_ALL
            })
        }
        ZuneRingToggle("heart", favourite, enabled = local != null, onTint = Zune.heart) {
            if (local != null) { GlideLocalLists.toggleFavorite(context, local.id); favourite = !favourite }
        }
    }
}

@Composable
private fun ZuneRingToggle(kind: String, on: Boolean, enabled: Boolean, onTint: Color = Color.Black, onClick: () -> Unit) {
    Box(Modifier.size(58.dp).clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() },
        indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        val ring = if (enabled) Zune.white else Zune.dim
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 1.6.dp.toPx()
            if (on) drawCircle(ring, r) else drawCircle(ring, r, style = Stroke(2.8.dp.toPx()))
        }
        ZuneIcon(kind, if (on) onTint else ring, Modifier.size(26.dp))
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** Thin white bar on a faint track; tap or drag to seek. Only this recomposes on the 1 s tick. */
@Composable
private fun ZuneProgress(player: PlayerInfo, active: Boolean, controller: MediaController, width: Dp) {
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val tickOff = GlideDiag.has(4)
    LaunchedEffect(player.controller, player.playing, active, tickOff) {
        if (active) tick = SystemClock.elapsedRealtime()
        while (active && player.playing && !tickOff) {
            tick = SystemClock.elapsedRealtime()
            delay(1000L)
        }
    }
    var drag by remember(player.title) { mutableStateOf<Float?>(null) }
    val duration = player.duration.coerceAtLeast(1L)
    val fraction = drag ?: (player.livePositionMs(tick).toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.width(width).padding(top = 4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)
            .pointerInput(controller, duration) {
                detectTapGestures { o -> controller.transportControls.seekTo((o.x / size.width * duration).toLong()) }
            }
            .pointerInput(controller, duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f); change.consume() },
                    onDragEnd = { drag?.let { controller.transportControls.seekTo((it * duration).toLong()) }; drag = null },
                    onDragCancel = { drag = null })
            }) {
            val h = 4.dp.toPx()
            val y = 2.dp.toPx()
            drawRect(Zune.track, Offset(0f, y), Size(size.width, h))
            drawRect(Zune.magenta, Offset(0f, y), Size(size.width * fraction, h))
        }
        Row(Modifier.fillMaxWidth().offset(y = (-6).dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock((fraction * duration).toLong()), style = Zune.style(12.sp, FontWeight.SemiBold))
            Text("-" + clock(player.duration - (fraction * duration).toLong()), style = Zune.style(12.sp, FontWeight.SemiBold))
        }
    }
}

// ---------------------------------------------------------------- info pivots

private val pivotNames = listOf("queue", "lyrics", "bio", "player")

/** Full-screen pivots over Now Playing: queue, lyrics, bio, player. Back or the ring closes it. */
@Composable
private fun ZunePivots(player: PlayerInfo, background: ImageBitmap?, queue: List<QueueRow>, local: Song?, startPage: Int = 0, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = startPage, pageCount = { pivotNames.size })
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().metroTurnstileIn().background(Color.Black)
        .pointerInput(Unit) { detectTapGestures { } }) { // swallow taps meant for the screen below
        if (background != null) Image(background, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0xB0000000)))
        Column(Modifier.fillMaxSize().padding(top = 44.dp)) {
            ZuneLine(player.title, Zune.style(18.sp, FontWeight.SemiBold), Modifier.padding(start = 20.dp))
            val current = pager.currentPage
            Row(Modifier.fillMaxWidth().clipToBounds().padding(start = 18.dp, top = 2.dp)) {
                for (k in pivotNames.indices) {
                    val page = (current + k) % pivotNames.size
                    Text(pivotNames[page], style = Zune.style(62.sp, FontWeight.Light, if (k == 0) Zune.white else Zune.grey, (-1).sp),
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(end = 18.dp).clickable { scope.launch { pager.animateScrollToPage(page) } })
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> ZuneQueuePage(player, queue)
                    1 -> ZuneLyricsPage(player, local)
                    2 -> ZuneBioPage(player.artist)
                    else -> ZunePlayerPage()
                }
            }
            Spacer(Modifier.height(96.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
            ZuneRingButton("back", "now playing", onClose)
        }
    }
}

@Composable
private fun ZuneQueuePage(player: PlayerInfo, queue: List<QueueRow>) {
    if (queue.isEmpty()) {
        ZuneNote("this player doesn't share its queue, and the song isn't in your local library.")
        return
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (queue.indexOfFirst { it.current } - 1).coerceAtLeast(0))
    val context = LocalContext.current
    val localQueue = player.controller?.packageName == GlideEngine.PKG
    var pendingPlaylist by remember { mutableStateOf<List<Long>>(emptyList()) }
    var playlistName by remember { mutableStateOf("") }
    var playlistError by remember { mutableStateOf(false) }
    if (pendingPlaylist.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingPlaylist = emptyList() },
            title = { Text("Save playlist") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it.take(60); playlistError = false },
                    isError = playlistError,
                    label = { Text("Playlist name") },
                    supportingText = { if (playlistError) Text("That name already exists") },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = playlistName.isNotBlank(), onClick = {
                    if (GlideLocalLists.savePlaylist(context, playlistName, pendingPlaylist)) {
                        pendingPlaylist = emptyList()
                        GlideToast.show("playlist saved in library")
                    } else playlistError = true
                }) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingPlaylist = emptyList() }) { Text("Cancel") }
            }
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (localQueue) Text("save queue as playlist", style = Zune.style(16.sp, FontWeight.Normal, Zune.magenta),
            modifier = Modifier.clickable {
                val engine = GlideFx.player
                val ids = engine?.let {
                    (0 until it.mediaItemCount).mapNotNull { index -> it.getMediaItemAt(index).mediaId.toLongOrNull() }
                }.orEmpty()
                if (ids.isNotEmpty()) {
                    playlistName = ""; playlistError = false; pendingPlaylist = ids
                }
            }.padding(horizontal = 20.dp, vertical = 10.dp))
        LazyColumn(state = list, modifier = Modifier.weight(1f).padding(start = 20.dp)) {
        itemsIndexed(queue) { _, row ->
            val colour = if (row.current) Zune.white else Zune.grey
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)
                .clickable(enabled = row.queueId >= 0) { player.controller?.transportControls?.skipToQueueItem(row.queueId) }) {
                Row {
                    if (row.number.isNotBlank()) Text(row.number + "  ", style = Zune.style(13.sp, FontWeight.SemiBold, colour))
                    Text(row.artist, style = Zune.style(13.sp, FontWeight.Normal, colour), maxLines = 1)
                }
                ZuneLine(row.title, Zune.style(38.sp, FontWeight.Light, colour, (-0.5).sp), Modifier.fillMaxWidth())
            }
        }
    }
    }
}

@Composable
private fun ZuneNote(text: String) {
    Text(text, style = Zune.style(17.sp, FontWeight.Normal, Zune.light), modifier = Modifier.padding(start = 20.dp, end = 24.dp, top = 18.dp))
}

@Composable
private fun ZuneReading(text: String, footer: String? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 26.dp, top = 18.dp)) {
        Text(text, style = Zune.style(17.sp, FontWeight.Normal).copy(lineHeight = 25.sp))
        if (footer != null) {
            Spacer(Modifier.height(14.dp))
            Text(footer, style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ZuneLyricsPage(player: PlayerInfo, local: Song?) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val lyrics by produceState<Lyrics?>(null, player.title, player.artist, local, retry) {
        value = withContext(Dispatchers.IO) {
            runCatching { GlideLyrics.load(context, player.artist, player.title, player.album, player.duration, local) }.getOrNull()
        }
    }
    ZuneLyricsFull(player, lyrics) { GlideLyrics.forget(context, player.artist, player.title); retry++ }
}

@Composable
private fun ZuneBioPage(artist: String) {
    val context = LocalContext.current
    val online = remember { OnlineArt.enabled(context) }
    val bio by produceState<GlideArtistEditorial.Result?>(null, artist, online) {
        value = if (!online) null else withContext(Dispatchers.IO) { runCatching { GlideArtistEditorial.load(context, artist) }.getOrNull() }
    }
    val result = bio
    when {
        !online -> ZuneNote("artist info is off. turn on online art & info in settings to read about $artist.")
        result == null -> ZuneNote("looking up $artist...")
        else -> ZuneReading(result.summary, "from wikipedia")
    }
}

/** Source, volume and visualizer settings (moved here from the old Now Playing). */
@Composable
private fun ZunePlayerPage() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("glide_player_selection", 0) }
    var chosen by remember { mutableStateOf(prefs.getString("preferred", "AUTO") ?: "AUTO") }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    remember(context) { VisualizerSettings.load(context); 0 }
    val mode = VisualizerSettings.mode.intValue
    val fps60 = VisualizerSettings.fps60.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 24.dp, top = 12.dp)) {
        Text("SOURCE", style = Zune.style(12.sp, FontWeight.Bold, Zune.light, 1.sp))
        listOf("auto" to "AUTO", "lg music" to "com.lge.music", "poweramp" to "com.maxmpz.audioplayer").forEach { (label, pkg) ->
            Text(label, style = Zune.style(30.sp, FontWeight.Light, if (chosen == pkg) Zune.magenta else Zune.white),
                modifier = Modifier.padding(top = 4.dp).clickable {
                    chosen = pkg
                    prefs.edit().putString("preferred", pkg).apply()
                    context.sendBroadcast(Intent("com.gharaudio.launcher.SELECT_PLAYER").setPackage(context.packageName))
                })
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZuneIcon("volume", Zune.light, Modifier.size(15.dp))
            Text("VOLUME  ${(volume / maxVol * 100).toInt()}%", style = Zune.style(12.sp, FontWeight.Bold, Zune.light, 1.sp))
        }
        // F3: Metro slider — thin 4 px track, accent fill, sharp white thumb; tap or drag
        fun setFrom(x: Float, w: Int) {
            val v = (x / w.coerceAtLeast(1) * maxVol).roundToInt().coerceIn(0, maxVol)
            volume = v.toFloat(); audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        }
        Canvas(Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(maxVol) { detectTapGestures { o -> setFrom(o.x, size.width) } }
            .pointerInput(maxVol) { detectHorizontalDragGestures { change, _ -> setFrom(change.position.x, size.width); change.consume() } }) {
            val f = (volume / maxVol.coerceAtLeast(1)).coerceIn(0f, 1f)
            val y = size.height / 2; val h = 4.dp.toPx()
            drawRect(Zune.track, Offset(0f, y - h / 2), Size(size.width, h))
            drawRect(Zune.magenta, Offset(0f, y - h / 2), Size(size.width * f, h))
            val tw = 10.dp.toPx(); val th = 24.dp.toPx()
            drawRect(Zune.white, Offset((size.width * f - tw / 2).coerceIn(0f, size.width - tw), y - th / 2), Size(tw, th))
        }
        Spacer(Modifier.height(18.dp))
        Text("VISUALIZER", style = Zune.style(12.sp, FontWeight.Bold, Zune.light, 1.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
            VisualizerSettings.labels.forEachIndexed { i, label ->
                Text(label, style = Zune.style(26.sp, FontWeight.Light, if (mode == i) Zune.magenta else Zune.white),
                    modifier = Modifier.clickable { VisualizerSettings.setMode(context, i) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp)) {
            Text("30 fps", style = Zune.style(26.sp, FontWeight.Light, if (!fps60) Zune.white else Zune.grey),
                modifier = Modifier.clickable { VisualizerSettings.setFps60(context, false) })
            Text("60 fps", style = Zune.style(26.sp, FontWeight.Light, if (fps60) Zune.white else Zune.grey),
                modifier = Modifier.clickable { VisualizerSettings.setFps60(context, true) })
            Text("recheck", style = Zune.style(26.sp, FontWeight.Light, Zune.grey),
                modifier = Modifier.clickable { VisualizerSettings.revision.intValue++ })
        }
        Text(VisualizerSettings.status.value.lowercase(Locale.ROOT), style = Zune.style(13.sp, FontWeight.Normal, Zune.grey),
            modifier = Modifier.padding(top = 8.dp))
    }
}


/** S: Sing control. Tap "sing" to show the slider; drag left = voice lowered more. Saved, applied live by the Glide player. */
@Composable
internal fun GlideSingControl(modifier: Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { GlideFx.prefs(ctx) }
    var open by remember { mutableStateOf(false) }
    var amount by remember { mutableIntStateOf(prefs.getInt("engine_sing", 0)) }
    fun set(v: Int) { amount = v.coerceIn(0, 100); prefs.edit().putInt("engine_sing", amount).apply(); GlideFx.apply(ctx) }
    Column(modifier) {
        if (open) {
            Column(Modifier.width(260.dp).background(Color(0xF21F1F1F)).padding(14.dp)) {
                Text(if (amount == 0) "sing · voice untouched" else "sing · voice ${100 - amount}%",
                    style = Zune.style(15.sp, FontWeight.Normal, Zune.white))
                Spacer(Modifier.height(10.dp))
                val accent = Zune.magenta
                val track = Zune.track
                Canvas(Modifier.fillMaxWidth().height(28.dp)
                    .pointerInput(Unit) { detectTapGestures { o -> set((100 - o.x / size.width * 100).roundToInt()) } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); set((100 - change.position.x / size.width * 100).roundToInt()) } }) {
                    // full bar = voice fully there; the accent part is how much voice remains
                    val y = size.height / 2
                    drawRect(track, topLeft = Offset(0f, y - 2.dp.toPx()), size = Size(size.width, 4.dp.toPx()))
                    val x = size.width * (100 - amount) / 100f
                    drawRect(accent, topLeft = Offset(0f, y - 2.dp.toPx()), size = Size(x, 4.dp.toPx()))
                    drawRect(Color.White, topLeft = Offset((x - 5.dp.toPx()).coerceAtLeast(0f), 0f), size = Size(10.dp.toPx(), size.height))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("no voice", style = Zune.style(11.sp, FontWeight.Normal, Zune.grey))
                    Text(if (amount > 0) "off" else "", style = Zune.style(13.sp, FontWeight.Normal, Zune.magenta),
                        modifier = Modifier.clickable { set(0) })
                    Text("full voice", style = Zune.style(11.sp, FontWeight.Normal, Zune.grey))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(if (amount > 0) "sing ●" else "sing", style = Zune.style(15.sp, FontWeight.Normal, if (amount > 0) Zune.magenta else Zune.light),
            modifier = Modifier.clickable { open = !open }.padding(10.dp))
    }
}
