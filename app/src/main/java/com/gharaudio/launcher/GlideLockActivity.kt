package com.gharaudio.launcher

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * L: Zune HD lock screen ("curtain").
 * Shown by Glide when the screen turns on (Settings › "zune lock screen"). It sits ABOVE Android's lock screen and
 * never replaces it: with no lock / swipe it unlocks straight into Glide; with a PIN or pattern Android's own prompt
 * appears after the curtain. Drag the curtain up: let go early and it drops back with a bounce (lock chime); past
 * 35 % (or a quick flick) it flies off the top, Glide's Home turns in.
 */
class GlideLockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(false)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        GlideMetroStyle.restore(this)
        setContent { GlideCurtain(onUnlocked = ::unlock) }
    }

    private fun unlock() {
        GlideFeedback.unlock(this)
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            // Android decides: no lock / swipe → dismissed; PIN / pattern → its own prompt. Never bypassed.
            km.requestDismissKeyguard(this, null)
        }
        GlideMotion.swingIn(this)
        finish()
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun GlideCurtain(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val player = rememberPlayerInfo(context, 0)
    val scope = rememberCoroutineScope()
    val y = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    var clock by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { clock = Date(); delay(10_000L) } }
    BackHandler { } // the curtain stays until you lift it
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer { translationY = y.value }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onVerticalDrag = { change, dy ->
                        change.consume(); tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { y.snapTo((y.value + dy).coerceAtMost(0f)) }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().y
                        scope.launch {
                            if (-y.value > height * .35f || v < -1500f) {
                                y.animateTo(-height, tween(180, easing = MetroEaseIn)); onUnlocked()
                            } else {
                                // heavy curtain: drops back with a double bounce
                                y.animateTo(0f, spring(dampingRatio = .38f, stiffness = 420f)); GlideFeedback.lock(context)
                            }
                        }
                    })
            }) {
            GlideMetroBackdrop(context, player.artwork, { 0f }, false)
            Box(Modifier.fillMaxSize().background(Color(0x55000000)))
            // now playing + transport at the top, like the Zune HD lock screen
            if (player.controller != null) Column(Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 36.dp, end = 24.dp)) {
                Text(player.title, style = Zune.style(18.sp, FontWeight.SemiBold, Color.White), maxLines = 1)
                Text(player.artist.lowercase(), style = Zune.style(14.sp, FontWeight.Normal, Color(0xFFCCCCCC)), maxLines = 1)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    val t = player.controller.transportControls
                    LockTap("previous") { t.skipToPrevious() }
                    LockTap(if (player.playing) "pause" else "play") { if (player.playing) t.pause() else t.play() }
                    LockTap("next") { t.skipToNext() }
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 48.dp)) {
                Text(SimpleDateFormat("H:mm", Locale.getDefault()).format(clock), style = Zune.style(96.sp, FontWeight.Light, Color.White, (-3).sp))
                Text(SimpleDateFormat("EEEE", Locale.getDefault()).format(clock).lowercase(), style = Zune.style(26.sp, FontWeight.Light, Color.White))
                Text(SimpleDateFormat("d MMMM", Locale.getDefault()).format(clock).lowercase(), style = Zune.style(26.sp, FontWeight.Light, Color.White))
                Spacer(Modifier.height(18.dp))
                Text("slide up to unlock", style = Zune.style(12.sp, FontWeight.Normal, Color(0xFF999999)))
            }
        }
    }
}

@Composable
private fun LockTap(kind: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        ZuneIcon(kind, Color.White, Modifier.size(22.dp))
    }
}

/** L: decides whether to show the curtain when the screen comes on. */
internal object GlideLockScreen {
    fun enabled(c: Context) = c.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getBoolean("zune_lock", false)
    fun onScreenOn(c: Context) {
        if (!enabled(c)) return
        val audio = c.getSystemService(android.media.AudioManager::class.java)
        if (audio != null && audio.mode != android.media.AudioManager.MODE_NORMAL) return // calls
        runCatching {
            c.startActivity(android.content.Intent(c, GlideLockActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
    }
}
