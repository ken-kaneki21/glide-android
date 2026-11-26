package com.gharaudio.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.viewinterop.AndroidView
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private fun audioOutputDescriptor(device: AudioDeviceInfo): AccessoryDescriptor? {
    val kind = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET -> AccessoryKind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AccessoryKind.BLUETOOTH
        else -> AccessoryKind.OTHER
    }
    return accessoryDescriptor(device.productName?.toString(), kind, device.isSink)
}

private object AccessoryPreview {
    data class Request(val token: Long, val label: String)
    var request by mutableStateOf<Request?>(null)
    private var token = 0L
    fun show(label: String) { request = Request(++token, label) }
}

@Composable
internal fun GlideAudioConnectionBanner(modifier: Modifier = Modifier, motionEnabled: Boolean = true) {
    val context = LocalContext.current
    data class Connection(val id: Int, val descriptor: AccessoryDescriptor, val preview: Boolean, val token: Long)
    var connection by remember { mutableStateOf<Connection?>(null) }
    var last by remember { mutableStateOf<Connection?>(null) }
    var sequence by remember { mutableLongStateOf(0L) }
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val profiles = remember(context) { context.getSharedPreferences("glide_audio_profiles", Context.MODE_PRIVATE) }
    fun show(id: Int, descriptor: AccessoryDescriptor, preview: Boolean) {
        val next = Connection(id, descriptor, preview, ++sequence)
        last = next
        connection = next
    }
    val preview = AccessoryPreview.request
    LaunchedEffect(preview) {
        if (preview != null) {
            show(-1, AccessoryDescriptor(preview.label, AccessoryKind.BLUETOOTH), true)
            AccessoryPreview.request = null
        }
    }
    DisposableEffect(audio) {
        if (audio == null) return@DisposableEffect onDispose {}
        val initial = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .associate { it.id to audioOutputDescriptor(it)?.label }
        val connected = AccessoryConnections(initial)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                addedDevices.forEach { device ->
                    val descriptor = audioOutputDescriptor(device)
                    if (connected.added(device.id, descriptor?.label) && descriptor != null) {
                        show(device.id, descriptor, false)
                    }
                }
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                removedDevices.forEach { device ->
                    connected.removed(device.id)
                    val visible = connection
                    if (visible != null && !visible.preview &&
                        !connected.hasLabel(visible.descriptor.label)) connection = null
                }
            }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    LaunchedEffect(connection) {
        if (connection != null) { delay(4500); connection = null }
    }
    AnimatedVisibility(
        visible = connection != null,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        enter = if (motionEnabled) fadeIn() + slideInVertically { -it } else EnterTransition.None,
        exit = if (motionEnabled) fadeOut() else ExitTransition.None
    ) {
        val shown = last ?: return@AnimatedVisibility
        val label = shown.descriptor.label
        val videos = accessoryVideoPrefs(context)
        val clip = videos.getString(label, null) ?: if (shown.descriptor.kind == AccessoryKind.BLUETOOTH)
            videos.getString("Bluetooth audio", null) else null
        var videoFailed by remember(shown.token, clip) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().background(if (GlideMetroStyle.lightTheme) Color.White else Color(0xFF17171C)).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (clip != null && motionEnabled && !videoFailed) key(shown.token, clip) {
                    AndroidView(factory = { ctx -> VideoView(ctx).apply {
                        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        setOnPreparedListener { player ->
                            runCatching { player.setVolume(0f, 0f); player.isLooping = true; start() }
                                .onFailure { videoFailed = true }
                        }
                        setOnErrorListener { _, _, _ -> videoFailed = true; true }
                        runCatching { setVideoURI(Uri.parse(clip)) }.onFailure { post { videoFailed = true } }
                    } }, modifier = Modifier.size(54.dp), onRelease = { runCatching { it.stopPlayback() } })
                } else key(shown.token) { AccessoryIllustration(label, motionEnabled) }
                Column(Modifier.weight(1f)) {
                    Text(label, style = Zune.style(23.sp, FontWeight.Light), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(if (shown.preview) "PREVIEW — no accessory connection" else "audio device connected",
                        style = Zune.style(13.sp, FontWeight.Normal, Zune.light))
                }
            }
            if (!shown.preview && audio != null) {
                val saved = profiles.getInt(label, -1)
                Row(Modifier.fillMaxWidth()) {
                    if (saved >= 0) TextButton(modifier = Modifier.weight(1f), onClick = {
                        if (connection?.token == shown.token) audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                            saved.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                        connection = null
                    }) { Text("restore volume", style = Zune.style(14.sp, FontWeight.Normal, Zune.light)) }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        if (connection?.token == shown.token) profiles.edit().putInt(label,
                            audio.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
                        connection = null
                    }) { Text("remember volume", style = Zune.style(14.sp, FontWeight.Normal, Zune.light)) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                if (!shown.preview) TextButton(modifier = Modifier.weight(1f), onClick = {
                    connection = null
                    launchApp(context, "com.pittvandewitt.wavelet")
                }) { Text("Wavelet", style = Zune.style(16.sp, FontWeight.Normal, Zune.magenta)) }
                TextButton(modifier = Modifier.weight(1f), onClick = { connection = null }) {
                    Text(if (shown.preview) "dismiss preview" else "dismiss", style = Zune.style(14.sp, FontWeight.Normal, Zune.light))
                }
            }
        }
    }
}

@Composable
private fun AccessoryIllustration(label: String, motionEnabled: Boolean) {
    val entrance = remember(label) { Animatable(0.72f) }
    val signal = remember(label) { Animatable(0f) }
    LaunchedEffect(label, motionEnabled) {
        if (!motionEnabled) {
            entrance.snapTo(1f)
            signal.snapTo(0f)
            return@LaunchedEffect
        }
        entrance.snapTo(0.72f)
        entrance.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 350f))
        repeat(3) {
            signal.snapTo(0f)
            signal.animateTo(1f, tween(900, easing = LinearEasing))
        }
    }
    val accent = Zune.magenta
    val ink = Zune.white
    Canvas(Modifier.size(42.dp)) {
        val u = size.width / 42f
        val pulse = if (motionEnabled) signal.value else 0f
        if (pulse > 0f && pulse < 1f) {
            drawCircle(accent.copy(alpha = (1f - pulse) * 0.34f),
                radius = (14f + 7f * pulse) * u, center = center, style = Stroke(1.2f * u))
        }
        scale(if (motionEnabled) entrance.value else 1f) {
            when (label) {
                "JBL Flip 5" -> {
                    drawRoundRect(accent, Offset(5*u, 12*u), Size(32*u, 18*u), CornerRadius(8*u))
                    for (i in 0..3) drawLine(ink, Offset((13+i*5)*u, 16*u), Offset((13+i*5)*u, 26*u), u)
                }
                "Sennheiser MOMENTUM 4", "Sony WH-1000XM4" -> {
                    drawArc(ink, 180f, 180f, false, Offset(8*u, 6*u), Size(26*u, 26*u), style = Stroke(3*u))
                    drawRoundRect(accent, Offset(6*u, 18*u), Size(8*u, 14*u), CornerRadius(3*u))
                    drawRoundRect(accent, Offset(28*u, 18*u), Size(8*u, 14*u), CornerRadius(3*u))
                }
                "Sennheiser IE 200", "Wired headphones" -> {
                    drawLine(ink, Offset(12*u, 16*u), Offset(21*u, 34*u), 2*u)
                    drawLine(ink, Offset(30*u, 16*u), Offset(21*u, 34*u), 2*u)
                    drawLine(ink, Offset(21*u, 34*u), Offset(21*u, 40*u), 2*u)
                    drawCircle(accent, 6*u, Offset(12*u, 14*u))
                    drawCircle(accent, 6*u, Offset(30*u, 14*u))
                }
                "Sony WF-1000XM4" -> {
                    drawRoundRect(ink, Offset(4*u, 9*u), Size(15*u, 17*u), CornerRadius(7*u))
                    drawRoundRect(ink, Offset(23*u, 16*u), Size(15*u, 17*u), CornerRadius(7*u))
                    drawCircle(accent, 3*u, Offset(11*u, 17*u))
                    drawCircle(accent, 3*u, Offset(30*u, 24*u))
                }
                else -> {
                    val radius = 7f
                    drawCircle(accent, radius*u, Offset(12*u, 18*u))
                    drawCircle(accent, radius*u, Offset(30*u, 24*u))
                    drawCircle(ink, 2*u, Offset(12*u, 18*u))
                    drawCircle(ink, 2*u, Offset(30*u, 24*u))
                }
            }
        }
    }
}
private fun accessoryVideoPrefs(context: Context) =
    context.getSharedPreferences("glide_accessory_videos", Context.MODE_PRIVATE)

@Composable
internal fun GlideAccessoryVideos(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { accessoryVideoPrefs(context) }
    val models = listOf("Bluetooth audio", "Sennheiser MOMENTUM 4", "Galaxy Buds2 Pro", "Sony WF-1000XM4", "Sony WH-1000XM4",
        "JBL Flip 5", "Sennheiser IE 200", "Wired headphones")
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var clips by remember { mutableStateOf(prefs.all) }
    var error by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val model = selection
        selection = null
        if (uri != null && model != null) {
            error = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(model, uri.toString()).apply()
                clips = prefs.all
            }.isFailure
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Accessory videos") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Text("Choose a local clip to play silently when that device connects. Bluetooth audio is the fallback for devices without their own clip.")
                    TextButton(onClick = { AccessoryPreview.show("Bluetooth audio"); onClose() }) {
                        Text("Preview connection animation")
                    }
                }
                items(models.size) { index ->
                    val model = models[index]
                    androidx.compose.material3.TextButton(onClick = {
                        selection = model
                        picker.launch(arrayOf("video/*"))
                    }) { Text(if (model in clips) "$model · change video" else model) }
                }
                if (error) item { Text("Glide could not keep access to that file.") }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Done") } }
    )
}
