package com.gharaudio.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.os.PowerManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

/*
 * V — Apple Music style lyrics and Sing, in Glide's Zune look.
 *  - Lyrics float: the line rises into place with a soft spring when the song moves on, the sung part fills
 *    smoothly through each word (not word-by-word jumps), and a held note keeps filling slowly and lifts the line
 *    a little while it is held. Real word timings ("<mm:ss.xx>" in .lrc files) give exact held notes; plain line
 *    timings are spread by word length, so held notes there are an estimate.
 *  - Sing: a mic next to the lyrics. Tap = voice back to full / back to your last level. Drag up or down on it = a
 *    vertical slider (down = less voice, all the way down = voice gone).
 * Cost: the fill redraws ~30 times a second only while Now Playing is on screen and the song plays (draw phase only).
 */

private fun wordEnd(line: LyricLine, next: LyricLine?, i: Int): Long =
    line.words.getOrNull(i + 1)?.startMs ?: (next?.startMs ?: (line.startMs + 5000L))

/** Fractional character position sung so far in [line] at [pos], plus how "held" the current note is (0..1). */
private fun fillAt(line: LyricLine, next: LyricLine?, pos: Long): Pair<Float, Float> {
    val words = line.words
    if (words.isEmpty()) return (if (pos >= line.startMs) line.text.length.toFloat() else 0f) to 0f
    var chars = 0
    for (i in words.indices) {
        val w = words[i]; val end = wordEnd(line, next, i)
        val len = w.text.length
        if (pos < w.startMs) return chars.toFloat().coerceAtMost(line.text.length.toFloat()) to 0f
        if (pos < end) {
            val dur = (end - w.startMs).coerceAtLeast(1L)
            // the visible letters fill in the first part; a long note keeps glowing for the rest
            val fillDur = dur.coerceAtMost(900L)
            val f = ((pos - w.startMs).toFloat() / fillDur).coerceIn(0f, 1f)
            val held = if (dur > 1000L && pos - w.startMs > 500L) (((pos - w.startMs).toFloat() / dur).coerceIn(0f, 1f)) else 0f
            return (chars + f * w.text.trimEnd().length).coerceAtMost(line.text.length.toFloat()) to held
        }
        chars += len
    }
    return line.text.length.toFloat() to 0f
}

@Composable
internal fun GlideFloatLyrics(player: PlayerInfo, lyrics: Lyrics, active: Boolean, modifier: Modifier = Modifier) {
    val controller = player.controller
    val lines = lyrics.lines
    val li = rememberLyricCursor(player, lyrics, active).value.first
    val current by rememberUpdatedState(player)
    val pos = remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    LaunchedEffect(active, player.playing, li) {
        if (!active) return@LaunchedEffect
        while (current.playing) {
            // Display off: do not run the lyric animation clock at 30 Hz.
            if (power?.isInteractive == false) {
                delay(500L)
                continue
            }
            pos.longValue = current.livePositionMs()
            // Text fill is visually smooth at 20 fps; expensive RenderThread
            // work stays below the spectrum's independent 30 fps rendering.
            delay(50L)
        }
    }
    val density = LocalDensity.current
    val rise = remember { Animatable(0f) }
    var lastLine by remember { mutableIntStateOf(li) }
    LaunchedEffect(li) {
        if (li != lastLine && GlideMotionState.motionOn) {
            rise.snapTo(1f); rise.animateTo(0f, spring(dampingRatio = .82f, stiffness = 180f))
        }
        lastLine = li
    }
    val stepPx = with(density) { 34.dp.toPx() }
    Column(modifier.height(150.dp).clipToBounds().graphicsLayer { translationY = rise.value * stepPx }) {
        lines.getOrNull(li - 1)?.let { prev ->
            Text(prev.text.ifBlank { "♪" }, style = Zune.style(16.sp, FontWeight.Normal, Zune.white.copy(alpha = .30f)),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val cur = lines.getOrNull(li)
        if (cur == null) {
            Text(lines.firstOrNull()?.text.orEmpty(), style = Zune.style(26.sp, Zune.Semilight, Zune.dim), maxLines = 2)
        } else {
            val next = lines.getOrNull(li + 1)
            var layout by remember(li) { mutableStateOf<TextLayoutResult?>(null) }
            // Reuse one mutable clipping path; never allocate or draw the text twice per row.
            val sungClip = remember(li) { Path() }
            val text = cur.text.ifBlank { "♪" }
            Text(text, style = Zune.style(28.sp, Zune.Semilight, Zune.white.copy(alpha = .26f), (-0.3).sp),
                lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer {
                        // a held note lifts the line a touch (Apple's "breath")
                        val held = fillAt(cur, next, pos.longValue).second
                        val s = 1f + .035f * held
                        scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, .5f)
                    }
                    .drawWithContent {
                        drawContent()
                        val l = layout ?: return@drawWithContent
                        val fill = fillAt(cur, next, pos.longValue).first.coerceAtMost(text.length.toFloat())
                        if (fill <= 0f) return@drawWithContent
                        sungClip.reset()
                        for (row in 0 until l.lineCount) {
                            val start = l.getLineStart(row); val end = l.getLineEnd(row, visibleEnd = true)
                            if (fill <= start) break
                            if (end <= start) continue
                            val right = if (fill >= end) l.getLineRight(row) else {
                                val ci = fill.toInt().coerceIn(start, end - 1)
                                val x0 = l.getHorizontalPosition(ci, true)
                                val x1 = l.getHorizontalPosition((ci + 1).coerceAtMost(text.length), true)
                                x0 + (x1 - x0) * (fill - ci)
                            }
                            sungClip.addRect(Rect(0f, l.getLineTop(row), right.coerceAtLeast(0f), l.getLineBottom(row)))
                        }
                        // One GPU text draw, clipped to all filled lines in a single pass.
                        clipPath(sungClip) { drawText(l, color = Zune.white) }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        controller?.transportControls?.seekTo(cur.startMs)
                    })
        }
        for (k in 1..2) {
            val line = lines.getOrNull(li + k) ?: break
            Text(line.text.ifBlank { "♪" }, style = Zune.style(17.sp, FontWeight.Normal, if (k == 1) Zune.light else Zune.grey),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                modifier = Modifier.padding(top = 2.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    controller?.transportControls?.seekTo(line.startMs)
                })
        }
    }
}

/** V: Apple-style Sing mic. Tap toggles full voice / last level; drag vertically for the slider. */
@Composable
internal fun GlideSingMic(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { GlideFx.prefs(ctx) }
    var amount by remember { mutableIntStateOf(prefs.getInt("engine_sing", 0)) }       // 0 = full voice, 100 = no voice
    var last by remember { mutableIntStateOf(prefs.getInt("engine_sing_last", 70)) }
    var sliding by remember { mutableStateOf(false) }
    fun set(v: Int) {
        amount = v.coerceIn(0, 100)
        if (amount > 0) last = amount
        prefs.edit().putInt("engine_sing", amount).putInt("engine_sing_last", last).apply()
        GlideFx.apply(ctx)
    }
    val density = LocalDensity.current
    val trackPx = with(density) { 170.dp.toPx() }
    val accent = Zune.magenta
    val on = amount > 0
    Box(modifier) {
        Canvas(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) accent.copy(alpha = .25f) else Color.Transparent)
            .pointerInput(Unit) { detectTapGestures { set(if (amount > 0) 0 else last) } }
            .pointerInput(Unit) {
                var startAmount = 0; var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { sliding = true; startAmount = amount; dy = 0f },
                    onDragEnd = { sliding = false }, onDragCancel = { sliding = false }) { change, d ->
                    change.consume(); dy += d
                    set((startAmount + dy / trackPx * 100f).toInt()) // drag down = less voice
                }
            }) {
            // microphone glyph
            val w = size.width; val h = size.height
            val c = if (on) accent else Zune.white
            val sw = 2.dp.toPx()
            drawRoundRect(c, topLeft = Offset(w * .38f, h * .18f), size = Size(w * .24f, h * .40f), cornerRadius = CornerRadius(w * .12f), style = Stroke(sw))
            val arc = Path().apply {
                moveTo(w * .28f, h * .46f); cubicTo(w * .28f, h * .72f, w * .72f, h * .72f, w * .72f, h * .46f)
            }
            drawPath(arc, c, style = Stroke(sw))
            drawLine(c, Offset(w * .5f, h * .66f), Offset(w * .5f, h * .80f), sw)
            if (on) { // small sparkle like Apple's mic
                drawCircle(c, 1.6.dp.toPx(), Offset(w * .80f, h * .20f)); drawCircle(c, 1.1.dp.toPx(), Offset(w * .88f, h * .34f))
            }
        }
        if (sliding) {
            val up = with(density) { (170 + 16).dp.roundToPx() }
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -up)) {
                Column(Modifier.width(52.dp).height(178.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xE61F1F1F)),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.padding(vertical = 14.dp).width(10.dp).weight(1f)) {
                        val voice = (100 - amount) / 100f
                        drawRoundRect(Zune.track, cornerRadius = CornerRadius(5.dp.toPx()))
                        val fh = size.height * voice
                        drawRoundRect(accent, topLeft = Offset(0f, size.height - fh), size = Size(size.width, fh), cornerRadius = CornerRadius(5.dp.toPx()))
                    }
                    Text("${100 - amount}%", style = Zune.style(11.sp, FontWeight.Normal, Zune.white), modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
}
