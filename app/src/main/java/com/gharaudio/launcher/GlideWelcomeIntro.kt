package com.gharaudio.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** One-shot, isolated welcome screen. No backing pager or visualizer is composed during it. */
@Composable
internal fun GlideWelcomeIntro(onStart: () -> Unit) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(1650L) // including entrance: under two seconds; Start skips immediately.
        onStart()
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 26.dp, bottom = 42.dp)
                .graphicsLayer {
                    alpha = entrance.value
                    translationX = (1f - entrance.value) * -64f
                }
        ) {
            listOf("welcome", "back", "to", "glide.").forEach { word ->
                Text(
                    word,
                    style = Zune.style(76.sp, FontWeight.Light,
                        if (word == "back") Zune.magenta else Zune.white,
                        (-2.5).sp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
            Spacer(Modifier.height(22.dp))
            Text("MUSIC IN MOTION", style = Zune.style(11.sp, FontWeight.Bold, Zune.light, 2.sp))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 38.dp)) {
            ZuneRingButton("forward", "start", onStart)
        }
    }
}
