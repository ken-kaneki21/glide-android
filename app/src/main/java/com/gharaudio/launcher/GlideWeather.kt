package com.gharaudio.launcher

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * Install C: animated weather (original drawing, no image assets).
 * Scenes: clear day (turning sun), clear night (moon + twinkling stars), partly cloudy, cloudy, fog, rain,
 * snow, thunderstorm (rain + lightning flash). ~25 fps and ONLY while the Live page is on screen;
 * the time value is read in the draw phase, so nothing recomposes per frame.
 */
internal enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

internal fun skyFor(code: Int): Sky = when (code) {
    0 -> Sky.CLEAR; 1, 2 -> Sky.PARTLY; 3 -> Sky.CLOUDY; 45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN; in 71..77, 85, 86 -> Sky.SNOW; in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

@Composable
internal fun GlideWeatherScene(code: Int, day: Boolean, active: Boolean, modifier: Modifier) {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active || GlideDiag.has(512)) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime() - (t.floatValue * 1000f).toLong()
        while (true) { t.floatValue = (SystemClock.elapsedRealtime() - start) / 1000f; delay(50L) } // ~20 fps
    }
    val sky = skyFor(code)
    val accent = Zune.magenta
    val light = GlideMetroStyle.lightTheme
    Canvas(modifier.clipToBounds()) { drawSky(sky, day, t.floatValue, accent, light) } // laptop test: clouds stay inside the scene
}

private fun rnd(i: Int, k: Int): Float { val v = sin(i * 12.9898 + k * 78.233) * 43758.5453; return (v - floor(v)).toFloat() }
private fun wrap(v: Float, m: Float): Float = ((v % m) + m) % m

private fun DrawScope.drawSky(sky: Sky, day: Boolean, time: Float, accent: Color, light: Boolean) {
    val w = size.width; val h = size.height
    val ink = if (light) Color(0xFF222222) else Color.White
    // sky wash
    val top = when {
        sky == Sky.STORM -> Color(0xFF1C1F2A)
        !day -> Color(0xFF0B1530)
        sky == Sky.CLEAR || sky == Sky.PARTLY -> accent.copy(alpha = .45f)
        else -> Color(0xFF59606B)
    }
    drawRect(Brush.verticalGradient(listOf(top, top.copy(alpha = 0f))))
    val sun = Offset(w * .78f, h * .36f); val r = h * .17f
    if (sky == Sky.CLEAR || sky == Sky.PARTLY) {
        if (day) {
            val a0 = time * 8f * (PI.toFloat() / 180f)
            for (i in 0 until 12) {
                val a = a0 + i * (PI.toFloat() / 6f)
                drawLine(Color(0xFFFFD54F).copy(alpha = .8f), Offset(sun.x + cos(a) * r * 1.3f, sun.y + sin(a) * r * 1.3f),
                    Offset(sun.x + cos(a) * r * (1.75f + .1f * sin(time * 2 + i)), sun.y + sin(a) * r * (1.75f + .1f * sin(time * 2 + i))),
                    strokeWidth = r * .09f, cap = StrokeCap.Round)
            }
            drawCircle(Color(0xFFFFD54F), r, sun)
        } else {
            for (i in 0 until 26) {
                val a = .25f + .75f * abs(sin(time * (0.6f + rnd(i, 3)) + i))
                drawCircle(Color.White.copy(alpha = a), 1.2f + rnd(i, 4) * 1.8f, Offset(rnd(i, 1) * w, rnd(i, 2) * h * .8f))
            }
            drawCircle(Color(0xFFF2F0E6), r * .9f, sun)
            drawCircle(Color(0xFF0B1530), r * .8f, Offset(sun.x + r * .45f, sun.y - r * .2f))
        }
    } else if (!day) {
        for (i in 0 until 10) drawCircle(Color.White.copy(alpha = .25f), 1.4f, Offset(rnd(i, 1) * w, rnd(i, 2) * h * .5f))
    }
    // clouds
    val clouds = when (sky) { Sky.CLEAR -> 0; Sky.PARTLY -> 2; Sky.FOG -> 0; else -> 4 }
    val cloudColor = when (sky) { Sky.STORM -> Color(0xFF4A4F5C); Sky.RAIN -> Color(0xFF8A919C); else -> if (day) Color(0xFFF4F6F8) else Color(0xFF9AA3B5) }
    for (i in 0 until clouds) {
        val cw = w * (.32f + rnd(i, 5) * .18f)
        val cx = wrap(rnd(i, 6) * w + time * (8f + rnd(i, 7) * 10f) * density, w + cw * 2) - cw
        val cy = h * (.18f + rnd(i, 8) * .32f)
        val c = cloudColor.copy(alpha = .78f + .2f * rnd(i, 9))
        drawCircle(c, cw * .22f, Offset(cx, cy))
        drawCircle(c, cw * .3f, Offset(cx + cw * .28f, cy - cw * .1f))
        drawCircle(c, cw * .2f, Offset(cx + cw * .56f, cy + cw * .02f))
        drawRect(c, Offset(cx, cy), androidx.compose.ui.geometry.Size(cw * .56f, cw * .2f))
    }
    when (sky) {
        Sky.RAIN, Sky.STORM -> {
            val n = if (sky == Sky.STORM) 70 else 55
            val len = 14f * density
            for (i in 0 until n) {
                val x = rnd(i, 10) * w
                val y = wrap(rnd(i, 11) * h + time * h * (1.1f + rnd(i, 12) * .5f), h + len) - len
                drawLine(Color(0xFFBFD7FF).copy(alpha = .7f), Offset(x, y), Offset(x - len * .25f, y + len), strokeWidth = 1.4f * density)
            }
            if (sky == Sky.STORM) {
                val phase = wrap(time, 5.2f)
                if (phase < .14f) {
                    drawRect(Color.White.copy(alpha = .28f * (1f - phase / .14f)))
                    val p = Path().apply {
                        moveTo(w * .55f, 0f); lineTo(w * .48f, h * .35f); lineTo(w * .56f, h * .35f); lineTo(w * .44f, h * .82f)
                    }
                    drawPath(p, Color(0xFFFFF59D), style = Stroke(width = 3f * density))
                }
            }
        }
        Sky.SNOW -> for (i in 0 until 45) {
            val y = wrap(rnd(i, 13) * h + time * h * (.12f + rnd(i, 14) * .1f), h)
            val x = rnd(i, 15) * w + sin(time * 1.2f + i) * 8f * density
            drawCircle(Color.White.copy(alpha = .85f), (1.5f + rnd(i, 16) * 2f) * density, Offset(x, y))
        }
        Sky.FOG -> for (i in 0 until 4) {
            val y = h * (.25f + i * .18f)
            val x = wrap(time * (10f + i * 6f) * density, w) - w
            drawRect(ink.copy(alpha = .10f), Offset(x, y), androidx.compose.ui.geometry.Size(w * 2, h * .07f))
        }
        else -> Unit
    }
}
