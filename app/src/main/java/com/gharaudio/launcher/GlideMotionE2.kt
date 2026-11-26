package com.gharaudio.launcher

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * E2: Metro motion fidelity (curves from his spec: arrive = (0.16,1,0.3,1), leave = (0.7,0,0.84,0)).
 *  - continuum entrance: after opening an artist / album, the header slides up into place and the first rows
 *    arrive from 70 dp to the right, 25 ms apart (only rows built in the first ~450 ms of the page, so scrolling
 *    later costs nothing)
 *  - Metro context menu: the page behind shrinks to 94 % while a dark box unfolds from 20 % height (180 ms)
 */
internal object GlideMotionState {
    /** time the last artist / album page opened; rows built shortly after it stagger in */
    @Volatile var drillAt = 0L
    /** Settings › motion; off = no stagger / hero entrance */
    @Volatile var motionOn = true
    /** > 0 while a Metro context menu is open: the panorama shrinks behind it */
    val menuDepth = mutableIntStateOf(0)
    /** F4a: where the finger last went down (window px) — context menus open next to it */
    @Volatile var lastTouchY = -1f
}

/** Continuum row entrance: 70 dp from the right + fade, 25 ms per row, only right after a page opens. */
internal fun Modifier.metroStagger(index: Int): Modifier =
    // F4p: decided with a cheap clock check, so ordinary scrolling adds no per-row modifier state at all
    if (index >= 14 || !GlideMotionState.motionOn || SystemClock.elapsedRealtime() - GlideMotionState.drillAt >= 450) this
    else composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(260, delayMillis = 60 + index * 25, easing = MetroEaseOut)) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    this.graphicsLayer { translationX = (1f - p.value) * 70f * density; alpha = p.value }
}

/** Continuum header: the page title rises into place (as if flown from the tapped row). */
internal fun Modifier.metroHeroEntrance(): Modifier =
    if (!GlideMotionState.motionOn || SystemClock.elapsedRealtime() - GlideMotionState.drillAt >= 450) this
    else composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(300, easing = MetroEaseOut)) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    this.graphicsLayer {
        translationY = (1f - p.value) * 120f * density
        val s = .66f + .34f * p.value; scaleX = s; scaleY = s
        transformOrigin = TransformOrigin(0f, 0f)
        alpha = .4f + .6f * p.value
    }
}

/** Metro context menu, drop-in for the old AlertDialog menus (same slots). */
@Composable
internal fun MetroMenu(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: @Composable () -> Unit = {}
) {
    val unfold = remember { Animatable(0f) }
    LaunchedEffect(Unit) { unfold.animateTo(1f, tween(180, easing = MetroEaseOut)) }
    DisposableEffect(Unit) {
        GlideMotionState.menuDepth.intValue++
        onDispose { GlideMotionState.menuDepth.intValue-- }
    }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveDialog()
        val touchY = remember { GlideMotionState.lastTouchY }
        val screenH = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
        val below = touchY >= 0f && touchY < screenH / 2f
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onDismissRequest)) {
            Column(Modifier.fillMaxWidth()
                // F4a 7.7: the menu opens next to the item you held (below it in the top half, above it lower down)
                .layout { m, c ->
                    val p = m.measure(c.copy(minHeight = 0))
                    val gap = 12.dp.roundToPx()
                    val y = when {
                        touchY < 0f -> c.maxHeight - p.height
                        below -> touchY.toInt() + gap
                        else -> touchY.toInt() - p.height - gap
                    }.coerceIn(0, (c.maxHeight - p.height).coerceAtLeast(0))
                    layout(c.maxWidth, c.maxHeight) { p.place(0, y) }
                }
                .graphicsLayer {
                    scaleY = .2f + .8f * unfold.value; alpha = unfold.value
                    transformOrigin = TransformOrigin(.5f, if (below) 0f else 1f)
                }
                .background(Color(0xFF1A1A1A)).border(1.dp, Color(0xFF444444))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)) {
                title()
                Spacer(Modifier.height(8.dp))
                text()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton(); confirmButton() }
            }
        }
    }
}
