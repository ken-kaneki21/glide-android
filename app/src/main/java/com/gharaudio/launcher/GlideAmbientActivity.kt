package com.gharaudio.launcher

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Optional user-initiated media screen. Never dismisses or bypasses secure keyguard. */
class GlideAmbientActivity : ComponentActivity() {
    private var dimAfterIdle = false
    private val dimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dimScreen = Runnable { window.attributes = window.attributes.apply { screenBrightness = 0.15f } }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetDockBrightness()
    }

    private fun resetDockBrightness() {
        if (!dimAfterIdle) return
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        dimHandler.removeCallbacks(dimScreen)
        dimHandler.postDelayed(dimScreen, 30000L)
    }

    private var powerReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        dimHandler.removeCallbacks(dimScreen)
        powerReceiver?.let(::unregisterReceiver)
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dock = intent.getStringExtra("glide_mode") == "dock"
        if (dock) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val battery = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val chargingDock = dock && (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        dimAfterIdle = dock
        resetDockBrightness()
        if (chargingDock) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    finish()
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(this, receiver,
                android.content.IntentFilter(android.content.Intent.ACTION_POWER_DISCONNECTED),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            powerReceiver = receiver
        }
        setShowWhenLocked(!dock)
        setTurnScreenOn(false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        setContent {
            MaterialTheme {
                GlideAmbientScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun GlideAmbientScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val info = rememberPlayerInfo(context, 0)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, animationSpec = tween(420, easing = FastOutSlowInEasing))
    }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(GlideMetroStyle.ink).graphicsLayer {
        alpha = entrance.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }) {
        ImmersiveNowPlaying(
            player = info,
            motion = info.playing,
            immersive = true,
            fullPlayer = true,
            ambientBars = false,
            onToggleFull = onClose,
            onClose = onClose
        )
    }
}
