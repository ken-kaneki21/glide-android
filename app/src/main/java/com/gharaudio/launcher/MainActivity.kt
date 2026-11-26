package com.gharaudio.launcher

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.os.Trace
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.BatteryManager
import android.app.ActivityOptions
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.OpenableColumns
import kotlin.math.roundToInt
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.content.ContextCompat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

// GLIDE_V120_ARTIST_LIBRARY — local artist pages, Quick List and opt-in listening collections
// Ghar Motion: Zune-inspired black ink, electric cobalt, warm orange and clean type.
// Use Android's native light sans family rather than shipping a copyrighted Segoe font.
private val Ink: Color get() = GlideMetroStyle.ink
private val Surface: Color get() = GlideMetroStyle.surface
// v1.11e2: Zune is monochrome. Former cyan / coral text accents are light grey / grey.
private val Blue: Color get() = Zune.light
private val Cobalt: Color get() = Zune.magenta // jump-list tiles only
private val Coral: Color get() = Zune.grey
private val Subtle: Color get() = GlideMetroStyle.secondary
private val White: Color get() = GlideMetroStyle.paper
private val HeroGradient: Brush get() = Brush.verticalGradient(listOf(Ink.copy(alpha=.94f), Ink.copy(alpha=.78f)))
private val PlayerGradient: Brush get() = Brush.verticalGradient(listOf(Surface, Ink, Ink))

class MainActivity : ComponentActivity() {
    override fun onLowMemory() {
        super.onLowMemory()
        releaseArtworkCache()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) releaseArtworkCache()
    }

    private fun releaseArtworkCache() {
        artCache.evictAll()
        artistArtCache.evictAll()
        artistBackdropCache.evictAll()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        window.statusBarColor = android.graphics.Color.rgb(9, 10, 15)
        window.navigationBarColor = android.graphics.Color.rgb(9, 10, 15)
        setContent { GlideApp() }
        androidx.core.content.ContextCompat.registerReceiver(this, diagReceiver,
            android.content.IntentFilter("com.gharaudio.launcher.DIAG"), "android.permission.DUMP", null,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        // v1.10e 0.4: Compose draws an opaque backdrop over the whole window, so the window
        // background is pure overdraw. The dark theme background still covers app start.
        window.setBackgroundDrawable(null)
    }

    // Diagnostics only: only senders holding DUMP (adb shell) can reach this. See GlideDiag.
    private val diagReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.getBooleanExtra("import_gallery", false) == true) { importGalleryFromAdb(); return }
            i?.getStringExtra("cmd")?.let { GlideTestHook.run(applicationContext, it, i); return }
            GlideDiag.flags.intValue = i?.getIntExtra("flags", 0) ?: 0
        }
    }

    /** RW: images pushed by adb into Android/data/<glide>/files/import are copied into the private gallery (personal use). */
    private fun importGalleryFromAdb() {
        val ctx = applicationContext
        Thread {
            val src = ctx.getExternalFilesDir("import")
            val files = src?.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "bmp") }.orEmpty()
            val added = importImagesInto(ctx, files.map { Uri.fromFile(it) }, GlideMetroStyle.galleryDir(ctx))
            files.forEach { it.delete() }
            runOnUiThread {
                GlideToast.show("$added images added to the gallery")
                if (added > 0) { GlideMetroStyle.wallpaperRevision++; GlideMetroStyle.setWallpaper(ctx, 4) }
            }
        }.start()
    }

    // F4a: remember where the finger went down so Metro context menus open next to the item
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) GlideMotionState.lastTouchY = ev.y
        return super.dispatchTouchEvent(ev)
    }

    // BackHandler owns navigation. These key callbacks only track the legacy
    // Back hold for the recent-apps switcher, which has no gesture equivalent.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) event.startTracking()
        return super.onKeyDown(keyCode, event)
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) { GlideSwitcher.open.value = true; GlideFeedback.hold(window.decorView); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(diagReceiver) }
        super.onDestroy()
    }
}

internal data class PlayerInfo(
    val controller: MediaController? = null,
    val title: String = "Nothing playing",
    val artist: String = "Play music in LG Music or Poweramp",
    val album: String = "",
    val artwork: Bitmap? = null,
    val playing: Boolean = false,
    val source: String = "",
    val actions: Long = 0L,
    val duration: Long = 0L,
    val position: Long = 0L,
    val speed: Float = 1f,
    val updatedAt: Long = 0L
)

internal data class Song(val id: Long, val title: String, val artist: String, val album: String, val duration: Long, val uri: Uri, val albumId: Long)

private data class Shortcut(val name: String, val subtitle: String, val pkg: String, val mark: String)

private val primaryApps = listOf(
    Shortcut("LG MUSIC", "Quad DAC player", "com.lge.music", "♫"),
    Shortcut("POWERAMP", "Music player", "com.maxmpz.audioplayer", "♪"),
    Shortcut("HI-FI RECORDER", "High-resolution recording", "com.lge.hifirecorder", "●"),
    Shortcut("WAVELET", "Audio processing", "com.pittvandewitt.wavelet", "≋"),
    Shortcut("SEEKER", "Music discovery", "com.companyname.andriodapp1", "◎")
)
private val utilityApps = listOf(
    Shortcut("MAPS", "Navigation", "com.google.android.apps.maps", "⌖"),
    Shortcut("FENNEC", "Web browser", "org.mozilla.fennec_fdroid", "↗"),
    Shortcut("WEATHER", "Breezy Weather", "org.breezyweather", "☁"),
    Shortcut("CAMERA", "Original LG camera", "glide:camera", "camera"),
    Shortcut("AURORA", "Apps", "com.aurora.store", "▣"),
    Shortcut("DROID-IFY", "Open-source apps", "com.looker.droidify", "▥"),
    Shortcut("SETTINGS", "Android settings", "com.android.settings", "⚙")
)

@Composable
private fun GlideApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val labels = remember { listOf("live", "now playing", "home", "library", "apps", "settings") }
    val pages = rememberPagerState(initialPage = 2, pageCount = { labels.size })
    SideEffect { GlideTestHook.pager = pages; GlideTestHook.scope = scope } // test hook (adb only)
    var selectedApp by remember { mutableStateOf<Shortcut?>(null) }
    var iconPickerFor by remember { mutableStateOf<Shortcut?>(null) }
    var routeNotice by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) {
                val external = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                } ?: return
                val detected = external.productName?.toString()?.trim().orEmpty()
                val wireless = external.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                val description = if (detected.isNotEmpty() && !detected.equals("unknown", true))
                    detected else if (wireless) "Bluetooth audio" else "Wired audio"
                routeNotice = "$description available · check output & volume"
            }
            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
                if (devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE }) {
                    routeNotice = "Audio device disconnected"
                }
            }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    LaunchedEffect(routeNotice) {
        if (routeNotice != null) { delay(4200L); routeNotice = null }
    }
    val prefs = remember { context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE) }
    LaunchedEffect(context) { GlideMetroStyle.restore(context) }
    val wallpaperScope = rememberCoroutineScope()
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        if (selected != null) wallpaperScope.launch {
            val imported = withContext(Dispatchers.IO) {
                val target = GlideMetroStyle.wallpaperFile(context)
                val tmp = java.io.File(target.path + ".partial")
                try {
                    var length = 0
                    context.contentResolver.openInputStream(selected)?.use { source ->
                        tmp.outputStream().use { output ->
                            val block = ByteArray(16384)
                            while (true) {
                                val read = source.read(block)
                                if (read < 0) break
                                length += read
                                if (length > 8 * 1024 * 1024) throw IllegalArgumentException("Wallpaper exceeds 8 MB")
                                output.write(block, 0, read)
                            }
                        }
                    } ?: throw IllegalArgumentException("Cannot open selected image")
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tmp.path,opt)
                    if (opt.outWidth < 64 || opt.outHeight < 64) throw IllegalArgumentException("Not a usable image")
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) throw IllegalStateException("Could not store wallpaper")
                    true
                } catch (_: Exception) { tmp.delete(); false }
            }
            if (imported) {
                GlideMetroStyle.wallpaperRevision++
                GlideMetroStyle.setWallpaper(context,2)
            } else Toast.makeText(context,"Wallpaper import failed",Toast.LENGTH_SHORT).show()
        }
    }
    // B2: background gallery — several images, copied privately, one shown every 30 minutes.
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) wallpaperScope.launch {
            val added = withContext(Dispatchers.IO) {
                val dir = GlideMetroStyle.galleryDir(context)
                var n = 0
                uris.forEachIndexed { i, uri ->
                    val target = java.io.File(dir, "g_${System.currentTimeMillis()}_$i.img")
                    val tmp = java.io.File(target.path + ".partial")
                    try {
                        var length = 0
                        context.contentResolver.openInputStream(uri)?.use { src ->
                            tmp.outputStream().use { out ->
                                val block = ByteArray(16384)
                                while (true) {
                                    val r = src.read(block); if (r < 0) break
                                    length += r; if (length > 8 * 1024 * 1024) throw IllegalArgumentException()
                                    out.write(block, 0, r)
                                }
                            }
                        }
                        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(tmp.path, opt)
                        if (opt.outWidth >= 64 && opt.outHeight >= 64 && tmp.renameTo(target)) n++ else tmp.delete()
                    } catch (_: Exception) { tmp.delete() }
                }
                n
            }
            GlideToast.show("$added images added to the gallery")
            if (added > 0) { GlideMetroStyle.wallpaperRevision++; GlideMetroStyle.setWallpaper(context, 4) }
        }
    }
    // Theme step: corner art images (Zune Desktop chrome), same private-copy import as the gallery
    val cornerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) wallpaperScope.launch {
            val added = withContext(Dispatchers.IO) { importImagesInto(context, uris, GlideMetroStyle.cornerDir(context)) }
            GlideToast.show("$added corner images added")
            if (added > 0) { GlideMetroStyle.wallpaperRevision++; GlideMetroStyle.setCornerArt(context, true) }
        }
    }
    var motion by remember { mutableStateOf(prefs.getBoolean("motion", true)) }
    SideEffect { GlideMotionState.motionOn = motion }
    var immersive by remember { mutableStateOf(prefs.getBoolean("immersive", true)) }
    var shellFullscreen by remember { mutableStateOf(prefs.getBoolean("shell_fullscreen", true)) }
    val fullPlayer = true
    var ambientBars by remember { mutableStateOf(prefs.getBoolean("ambient_bars", true)) }
    // The music page is always immersive. There is no second fullscreen toggle.
    val playerFullscreen = pages.currentPage == 1
    // Android 9 immersive-sticky hides bars only while Glide owns the screen.
    // A swipe from the top/bottom exposes Android system controls temporarily.
    // Exiting Glide restores system UI; no secure keyguard or SystemUI modification.
    val activity = context as? Activity
    // v1.10e 1.1: keyed on the combined flag. Previously passing Now Playing re-ran this effect,
    // which briefly restored the system bars and forced a whole-window relayout mid-swipe.
    val wantImmersive = shellFullscreen || playerFullscreen
    DisposableEffect(activity, wantImmersive) {
        val decor = activity?.window?.decorView
        val prior = decor?.systemUiVisibility
        val immersiveFlags = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        // v1.11j: dialogs, the keyboard and returning from other apps can clear these flags, which left the old
        // status/navigation bars stuck on screen. Re-apply when Glide regains focus, and 2.5 s after the bars appear.
        val reapply = Runnable { if (decor != null && prior != null && wantImmersive) decor.systemUiVisibility = prior or immersiveFlags }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) decor?.post(reapply) }
        if (decor != null && prior != null && wantImmersive) {
            decor.systemUiVisibility = prior or immersiveFlags
            decor.setOnSystemUiVisibilityChangeListener { vis ->
                if (vis and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN == 0) { decor.removeCallbacks(reapply); decor.postDelayed(reapply, 2500L) }
            }
            decor.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        }
        onDispose {
            if (decor != null && prior != null && wantImmersive) {
                decor.removeCallbacks(reapply)
                decor.setOnSystemUiVisibilityChangeListener(null)
                decor.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
                decor.systemUiVisibility = prior
            }
        }
    }
    // Show once after each boot, only when the existing intro + motion settings are enabled.
    // BOOT_COUNT is readable on Android 9. Record this boot before showing the intro so a
    // process restart or a HOME press cannot repeatedly replay the welcome screen.
    val bootCount = remember(context) {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }
    var introReady by remember { mutableStateOf(false) }
    var introVisible by remember {
        mutableStateOf(
            // F1: show the intro every time Glide starts fresh (after a reboot, an install or Android closing it),
            // not just once per boot — reinstalls kept it hidden. Settings toggle still turns it off.
            prefs.getBoolean("metro_intro", true) && !GlideIntroOnce.shown.also { GlideIntroOnce.shown = true }
        )
    }
    LaunchedEffect(introVisible) {
        if (introVisible) prefs.edit().putInt("metro_intro_last_boot", bootCount).apply()
        else { delay(35L); introReady = true }
    }
    val introProgress by animateFloatAsState(
        targetValue = if (introReady && motion) 1f else if (!motion) 1f else 0f,
        animationSpec = tween(390, easing = FastOutSlowInEasing), label = "glideFlyIn")

    // The intro owns the whole composition while visible: no pager, visualizer or backdrop
    // is drawn underneath it. Once dismissed, the existing six-page shell is unchanged.
    if (introVisible) {
        GlideWelcomeIntro(onStart = { introVisible = false })
        return
    }
    // After the initial frame, warm the songs/artists/albums index on IO.
    // No database query, bitmap decode or blocking metadata request on the UI thread.
    LaunchedEffect(context) {
        // v1.10e 0.8: no fixed 1.35 s wait; this runs after the first frame anyway and only on IO.
        withContext(Dispatchers.IO) {
            prefetchAppIcons(context, primaryApps + utilityApps)
            prefetchZuneIcons(context, (primaryApps + utilityApps).map { it.pkg })
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.IO) { getOrLoadMusicIndex(context) }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumeTick++; foreground = true; GlideMotion.back() }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) foreground = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Keep playback controls responsive without publishing every external position tick
    // into the whole pivot hierarchy while Now Playing is hidden.
    val playerPositionVisible = rememberUpdatedState(pages.currentPage == 1 && foreground)
    val player = rememberPlayerInfo(context, resumeTick, playerPositionVisible)
    fun navigateTo(page: Int) {
        scope.launch {
            if (motion) {
                pages.animateScrollToPage(page, animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.9f, stiffness = 420f
                ))
            } else {
                pages.scrollToPage(page)
            }
        }
    }
    BackHandler(true) {
        if (selectedApp != null) selectedApp = null
        else navigateTo(2)
    }
    // Artist detail owns horizontal gestures; do not let the global panorama steal tab swipes.
    var libraryDetailOpen by remember { mutableStateOf(false) }
    // Install B: a new song by the same artist shows the next photo of that artist (when there are several).
    var photoTurn by remember { mutableIntStateOf(0) }
    var lastPhotoArtist by remember { mutableStateOf("") }
    LaunchedEffect(player.title) {
        if (player.artist == lastPhotoArtist) photoTurn++ else { lastPhotoArtist = player.artist; photoTurn = 0 }
    }
    val artistBackdrop by produceState<Bitmap?>(null, player.artist, photoTurn, GlideMetroStyle.artworkRevision) {
        // v1.10e 0.5: shared, cached backdrop (same bitmap as Now Playing and the artist page).
        value = if (player.controller == null) null else {
            val first = loadArtistBackdropPatiently(context, player.artist, photoTurn)
            // fetch the extra photos once, quietly, after the first photo is on screen
            if (first != null && OnlineArt.enabled(context)) withContext(Dispatchers.IO) {
                runCatching { OnlineArt.fetchArtistAlternates(context, player.artist) }
            }
            first
        }
    }
    // v1.10e 0.4b: when a settled page paints its own opaque full-screen background
    // (Now Playing, artist/album detail), the shell backdrop underneath is pure overdraw.
    // derivedStateOf only changes when the boolean flips, not on every swipe frame.
    val backdropCovered by remember {
        derivedStateOf {
            pages.currentPageOffsetFraction == 0f &&
                (pages.currentPage == 1 || (pages.currentPage == 3 && libraryDetailOpen))
        }
    }
    // v1.10e 0.4: no root Ink fill; GlideMetroBackdrop is already opaque full-screen.
    Box(Modifier.fillMaxSize()) {
        // Kept in composition (no re-decode when it reappears); only its drawing is skipped.
        Box(Modifier.fillMaxSize().drawWithContent { if (!backdropCovered) drawContent() }) {
            // v1.10e 0.3: pass the pager offset as a lambda; it is read only in graphicsLayer, so
            // swiping no longer recomposes GlideApp() on every frame.
            GlideMetroBackdrop(context, artistBackdrop ?: player.artwork,
                offset = { if (motion) pages.currentPageOffsetFraction else 0f }, animate = motion)
        }
    // v1.10e 1.1: the header is an overlay, not a layout sibling. The pager always fills the screen,
    // so entering/leaving Now Playing or Home no longer changes the pager height (which re-measured
    // every page mid-swipe). The header fades out toward Now Playing instead of popping away.
    val screenDensity = androidx.compose.ui.platform.LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val headerGone by remember {
        derivedStateOf { pages.currentPage == 1 && pages.currentPageOffsetFraction == 0f }
    }
    Box(Modifier.fillMaxSize().then(
        if (shellFullscreen || playerFullscreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()
    )) {
        // F2: reset a page's inner screens once you have swiped away from it
        var lastSettled by remember { mutableIntStateOf(pages.settledPage) }
        LaunchedEffect(pages.settledPage) {
            val now = pages.settledPage
            if (lastSettled == 1 && now != 1) GlideNav.nowPlayingReset.intValue++
            if (lastSettled == 3 && now != 3) GlideNav.libraryReset.intValue++
            if (lastSettled != now) GlideFeedback.tick(context)
            lastSettled = now
        }
        HorizontalPager(
            state = pages,
            modifier = Modifier.fillMaxSize()
                // F4a 7.1: panorama wraps — pull past "live" to reach settings and past "settings" to reach live.
                // Watches the drag only (never consumes), so it costs nothing while you swipe between pages.
                .pointerInput(Unit) {
                    val limit = 110.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dx = 0f; var dy = 0f
                        val start = pages.settledPage
                        if (start != 0 && start != labels.size - 1) return@awaitEachGesture
                        while (true) {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            // total travel from the touch-down point (counts the release position too)
                            dx = ch.position.x - down.position.x; dy = ch.position.y - down.position.y
                            if (!ch.pressed) break
                        }
                        if (kotlin.math.abs(dx) > limit && kotlin.math.abs(dx) > 2 * kotlin.math.abs(dy)) {
                            val target = if (start == 0 && dx > 0) labels.size - 1 else if (start == labels.size - 1 && dx < 0) 0 else -1
                            if (target >= 0) scope.launch {
                                delay(60L); var n = 0
                                while (pages.isScrollInProgress && n++ < 40) delay(16L) // let the edge bounce finish, or it cancels the jump
                                pages.scrollToPage(target)
                            }
                        }
                    }
                }
                .graphicsLayer {
                // E: turnstile (hinge on the left edge); 0 = resting, no layer cost
                val v = GlideMotion.turn.value
                if (GlideMotionState.menuDepth.intValue > 0) { scaleX = .94f; scaleY = .94f }
                if (v != 0f) {
                    rotationY = -85f * v
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                    cameraDistance = 14f * density
                    alpha = 1f - .5f * v
                }
            },
            // v1.10e 1.1: all six pages stay composed, so no page is ever built during a swipe.
            // Off-screen pages stay idle: Now Playing animations and the visualizer key off
            // pages.currentPage, so they only run when that page is actually shown.
            beyondViewportPageCount = labels.size - 1,
            // M: the next page peeks in 48 dp at the right edge, like the Zune HD panorama
            pageSize = if (!GlideDiag.has(4096)) androidx.compose.foundation.pager.PageSize.Fill
                else object : androidx.compose.foundation.pager.PageSize {
                    override fun androidx.compose.ui.unit.Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int =
                        availableSpace - 48.dp.roundToPx()
                },
            // F4a 7.1: Metro snap — half a page or a flick commits, settle on the Metro curve
            flingBehavior = if (GlideDiag.has(256)) androidx.compose.foundation.pager.PagerDefaults.flingBehavior(pages)
                else androidx.compose.foundation.pager.PagerDefaults.flingBehavior(pages,
                    snapPositionalThreshold = .5f,
                    snapAnimationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.92f, stiffness = 420f)),
            userScrollEnabled = true // Detail content consumes its own drags; header/footer navigate panorama
        ) { page ->
            // Keep off-screen pages composed, but only update transforms for pages in view.
            val pageVisible by remember(pages, page) {
                derivedStateOf {
                    val displacement = page - pages.currentPage - pages.currentPageOffsetFraction
                    kotlin.math.abs(displacement) < 0.999f
                }
            }
            Box(Modifier.fillMaxSize()
                .padding(top = if (page == 1) 0.dp else with(screenDensity) { headerPx.toDp() })
                .graphicsLayer {
                // Finger-following panoramic depth: text/contents float slightly
                // independently of the pager without triggering bitmap redecodes.
                translationX = if (motion && pageVisible) {
                    -(page - pages.currentPage - pages.currentPageOffsetFraction) * 33f * density
                } else 0f
                // v1.10e 1.3: pages fully off screen get alpha 0, so the RenderThread skips
                // them entirely instead of walking all six composed pages every frame.
                // v1.10e 1.6: no alpha dip while swiping. Navigation is GPU-bound on the G6
                // (app threads use ~6 ms of a 13-15 ms frame); a translucent page forces every
                // image on both visible pages to blend. Zune pivots slide without fading anyway.
                alpha = if (pageVisible) 1f else 0f
                // v1.10e 0.4: no full-page offscreen buffer per page during swipes.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }) {
            when (page) {
                0 -> GlideLive2(context, foreground && pages.currentPage == 0)
                1 -> GlideScreenSaverHost(context, player, foreground && pages.currentPage == 1) { saver ->
                    ImmersiveNowPlaying(player, motion && foreground && pages.currentPage == 1 && !saver,
                    foreground && pages.currentPage == 1 && !saver, immersive, true, ambientBars,
                    onToggleFull = {}, onClose = { navigateTo(2) },
                    onSearch = { navigateTo(3) })
                }
                2 -> HomePage(context, player,
                    onNowPlaying = { navigateTo(1) },
                    onLibrary = { navigateTo(3) },
                    onApps = { navigateTo(4) },
                    onShuffleAll = {
                        scope.launch {
                            val songs = withContext(Dispatchers.IO) { getOrLoadMusicIndex(context).songs }
                                .filter { GlideMixData.heart(context,it.id) != GlideMixData.Heart.NEVER }
                            if (songs.isEmpty()) {
                                GlideToast.show("No local music found")
                            } else {
                                // External players own their queues. Request shuffle if supported;
                                // the random first local track is guaranteed, the full queue is not.
                                player.controller?.let { controller ->
                                    runCatching {
                                        MediaSessionCompat.Token.fromToken(controller.sessionToken)?.let { token ->
                                            MediaControllerCompat(context, token).transportControls
                                                .setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
                                        }
                                    }
                                }
                                if (GlideEngine.enabled(context)) GlideEngine.playList(context, songs, songs.indices.random(), shuffle = true)
                                else playSong(context, songs.random())
                            }
                        }
                    },
                    onAppLongPress = { selectedApp = it })
                3 -> LibraryPage(context, onDetailChanged = { libraryDetailOpen = it })
                4 -> AppsPage(context, onAppLongPress = { selectedApp = it })
                else -> GharSettings(
                    motion = motion, immersive = immersive, fullPlayer = fullPlayer, ambientBars = ambientBars,
                    onFullPlayer = { /* Now Playing is always immersive. */ },
                    onAmbientBars = { ambientBars = it; prefs.edit().putBoolean("ambient_bars", it).apply() },
                    onMotion = { motion = it; prefs.edit().putBoolean("motion", it).apply() },
                    shellFullscreen = shellFullscreen,
                    onShellFullscreen = { shellFullscreen = it; prefs.edit().putBoolean("shell_fullscreen", it).apply() },
                    onImmersive = { immersive = it; prefs.edit().putBoolean("immersive", it).apply() },
                    onClose = { navigateTo(2) },
                    onPickWallpaper = { wallpaperPicker.launch("image/*") },
                    onPickGallery = { galleryPicker.launch("image/*") },
                    onPickCorner = { cornerPicker.launch("image/*") },
                    onAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                )
            }
            }
        }
        // v1.11e: Zune's text-first pivot header is one stable-height overlay.
        // Like 1.10e, the pager itself is never remeasured by page changes.
        if (!headerGone) Column(Modifier.fillMaxWidth()
            .onSizeChanged { if (it.height != headerPx) headerPx = it.height }
            .graphicsLayer {
                val distance = kotlin.math.abs(pages.currentPage + pages.currentPageOffsetFraction - 1f)
                alpha = distance.coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .padding(start = 23.dp, top = 5.dp, bottom = 6.dp)) {
            ZuneDeviceStatus(context, modifier = Modifier.fillMaxWidth().padding(end = 20.dp))
            Spacer(Modifier.height(6.dp))
            Text("glide", style = Zune.style(14.sp, FontWeight.SemiBold, Zune.white),
                modifier = Modifier.clickable { navigateTo(2) })
            val pivotScroll = rememberScrollState()
            // F4p: header moves once the page settles (G6 rule: no per-frame layout while swiping)
            LaunchedEffect(pages.settledPage) {
                val step = 144 * context.resources.displayMetrics.density
                pivotScroll.animateScrollTo((pages.settledPage * step).toInt().coerceIn(0, pivotScroll.maxValue),
                    tween(260, easing = MetroEaseOut))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(pivotScroll, enabled = false)
                .graphicsLayer {
                    // M: the header follows your finger again (0.55x of the page movement), draw-phase only —
                    // no layout or scroll per frame, so it stays cheap on the G6. DIAG 2048 turns it off.
                    if (!GlideDiag.has(2048) && GlideMotionState.motionOn) {
                        val drift = (pages.currentPage - pages.settledPage) + pages.currentPageOffsetFraction
                        translationX = -drift * 144f * density * .55f
                    } else translationX = 0f
                }, horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { index, label ->
                    val active = pages.currentPage == index
                    Text(label,
                        style = Zune.style(if (active) 54.sp else 49.sp, FontWeight.Light,
                            if (active) Zune.white else Zune.light, (-1.5).sp),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        modifier = Modifier.clickable {
                            navigateTo(if (active) 2 else index)
                        })
                }
            }
        }
        GlideAudioConnectionBanner(Modifier.align(Alignment.TopCenter), motionEnabled = motion)
        GlideToastHost() // F3: Metro accent toasts slide down from the top
        if (GlideSwitcher.open.value) GlideAppSwitcher(context, onClose = { GlideSwitcher.open.value = false }) // F4b
    }
    routeNotice?.let { message ->
        // A small ephemeral in-app prompt; no overlay permission or false model claims.
        Box(Modifier.fillMaxSize().padding(bottom = 22.dp, start = 22.dp, end = 22.dp),
            contentAlignment = Alignment.BottomCenter) {
            Row(Modifier.clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF172B41)).clickable { routeNotice = null }
                .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("♫  $message", color = White, fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 2)
                Text("×", color = Blue, fontSize = 22.sp)
            }
        }
    }
    selectedApp?.let { app ->
        // v1.11g: Zune long-press menu (black, lowercase Light items) with "change icon".
        AlertDialog(onDismissRequest = { selectedApp = null }, containerColor = GlideMetroStyle.ink,
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                ZuneAppGlyph(app.pkg, 30.dp); Spacer(Modifier.width(14.dp))
                Text(app.name.lowercase(Locale.getDefault()), style = Zune.style(30.sp, FontWeight.Light), maxLines = 1,
                    softWrap = false, overflow = TextOverflow.Clip)
            } },
            text = { Column {
                listOf("open", "change icon", "app info").forEach { item ->
                    Text(item, style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.fillMaxWidth().clickable {
                        selectedApp = null
                        when (item) {
                            "open" -> launchApp(context, app.pkg)
                            "change icon" -> iconPickerFor = app
                            else -> openAppInfo(context, app.pkg)
                        }
                    }.padding(vertical = 10.dp))
                }
            } },
            confirmButton = { TextButton(onClick = { selectedApp = null }) { Text("close", style = Zune.style(17.sp, FontWeight.Normal)) } }
        )
    }
    iconPickerFor?.let { app ->
        AlertDialog(onDismissRequest = { iconPickerFor = null }, containerColor = GlideMetroStyle.ink,
            title = { Text("icon for ${app.name.lowercase(Locale.getDefault())}", style = Zune.style(26.sp, FontWeight.Light), maxLines = 1,
                softWrap = false, overflow = TextOverflow.Clip) },
            text = { Column {
                zuneGlyphChoices.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { kind ->
                            Box(Modifier.weight(1f).height(56.dp).clickable {
                                ZuneIconOverrides.set(context, app.pkg, kind); iconPickerFor = null
                            }, contentAlignment = Alignment.Center) { ZuneIcon(kind, Zune.white, Modifier.size(28.dp)) }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("app's own icon", style = Zune.style(20.sp, FontWeight.Light), modifier = Modifier.fillMaxWidth().clickable {
                    ZuneIconOverrides.set(context, app.pkg, "own"); iconPickerFor = null
                }.padding(vertical = 9.dp))
                Text("glide default", style = Zune.style(20.sp, FontWeight.Light, Zune.grey), modifier = Modifier.fillMaxWidth().clickable {
                    ZuneIconOverrides.set(context, app.pkg, null); iconPickerFor = null
                }.padding(vertical = 9.dp))
            } },
            confirmButton = { TextButton(onClick = { iconPickerFor = null }) { Text("close", style = Zune.style(17.sp, FontWeight.Normal)) } }
        )
    }
    } // Whole-app atmospheric backdrop
}


/** Zune-style minimal status: Android sticky battery snapshot and minute broadcasts.
 * Notification shade, secure keyguard and battery icon ownership remain with Android.
 */
@Composable
private fun ZuneDeviceStatus(context: Context, modifier: Modifier = Modifier) {
    // R: start from Android's current battery level so the bar never shows "--" while waiting for a broadcast
    var level by remember { mutableIntStateOf(runCatching {
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1).let { if (it in 0..100) it else -1 }) }
    var charging by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        if (raw >= 0 && scale > 0) level = (raw * 100 / scale).coerceIn(0, 100)
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    }
                    Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                        clock = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // v1.11k Zune HD / Windows Phone status: Zegoe clock and a flat white battery. Plugging in sweeps the fill up
    // from empty and pops a bolt into the middle of the cell (like Windows Phone); unplugging shrinks the bolt away.
    // Both are one-off 0.3-0.7 s animations; nothing runs while the state is steady.
    val sweep = remember { androidx.compose.animation.core.Animatable(1f) }
    val bolt = remember { androidx.compose.animation.core.Animatable(if (charging) 1f else 0f) }
    var seenCharging by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(charging) {
        val first = seenCharging == null
        seenCharging = charging
        if (first) { bolt.snapTo(if (charging) 1f else 0f); return@LaunchedEffect }
        if (charging) {
            sweep.snapTo(0f); bolt.snapTo(0f)
            launch { sweep.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            bolt.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f))
        } else bolt.animateTo(0f, tween(260))
    }
    Row(modifier.height(22.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(clock, style = Zune.style(14.sp, Zune.Semilight))
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.width(30.dp).height(14.dp)) {
            val stroke = 1.5.dp.toPx()
            val h = size.height
            val bodyW = size.width - 3.dp.toPx()
            drawRect(color = Zune.white, topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(bodyW - stroke, h - stroke), style = Stroke(width = stroke))
            drawRect(color = Zune.white, topLeft = Offset(bodyW + 1.dp.toPx(), h * .3f), size = Size(2.dp.toPx(), h * .4f))
            val inset = 3.dp.toPx()
            val fullW = (bodyW - inset * 2).coerceAtLeast(0f)
            // F3: the fill takes your accent colour (red when low and not charging), like the Windows Phone status bar
            if (level >= 0) drawRect(color = if (level <= 15 && !charging) Color(0xFFE51400) else Zune.magenta,
                topLeft = Offset(inset, inset), size = Size(fullW * level / 100f * sweep.value, (h - inset * 2).coerceAtLeast(0f)))
            val b = bolt.value
            if (b > 0.01f) {
                // lightning bolt centred in the cell; black where it sits on the white fill, white on the empty part
                val cx = bodyW / 2f; val cy = h / 2f; val bh = (h - 2.dp.toPx()) * b; val bw = bh * 0.62f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx + bw * 0.10f, cy - bh / 2); lineTo(cx - bw * 0.45f, cy + bh * 0.06f); lineTo(cx - bw * 0.02f, cy + bh * 0.06f)
                    lineTo(cx - bw * 0.12f, cy + bh / 2); lineTo(cx + bw * 0.45f, cy - bh * 0.08f); lineTo(cx + bw * 0.02f, cy - bh * 0.08f); close()
                }
                val filledTo = inset + fullW * level / 100f * sweep.value
                drawPath(path, Zune.white)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(if (level >= 0) "$level" else "--", style = Zune.style(12.sp, FontWeight.Normal, Zune.light))
    }
}

private val appIconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/** Loads and caches one launcher icon (blocking; call on Dispatchers.IO). */
private fun loadAppIcon(context: Context, pkg: String): ImageBitmap? {
    appIconCache[pkg]?.let { return it }
    return runCatching {
        val px = (29f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(48)
        context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
    }.getOrNull()?.also { appIconCache[pkg] = it }
}

/** v1.10e 0.8: warm every Home/Apps icon at startup so the first visit has nothing to load. */
private fun prefetchAppIcons(context: Context, apps: List<Shortcut>) {
    apps.forEach { if (!it.pkg.startsWith("glide:")) loadAppIcon(context, it.pkg) }
}

/** Flat monochrome vector symbols used in the music/app tiles, no icon-font dependency. */
@Composable
private fun MetroAppIcon(app: Shortcut, tint: Color, modifier: Modifier = Modifier) {
    // App identity comes from its installed package, not a generic placeholder.
    // Keep internal Glide shortcuts vector-drawn; do not counterfeit another app icon.
    val context = LocalContext.current
    // v1.10e 0.6: icons load once on IO into an ImageBitmap cache; no PackageManager call during
    // composition and no AndroidView(ImageView) inside lists or the pager.
    val internal = app.pkg.startsWith("glide:")
    var iconFailed by remember(app.pkg) { mutableStateOf(false) }
    val appIcon by produceState<ImageBitmap?>(appIconCache[app.pkg], app.pkg) {
        if (value == null && !internal) {
            val loaded = withContext(Dispatchers.IO) { loadAppIcon(context, app.pkg) }
            if (loaded != null) value = loaded else iconFailed = true
        }
    }
    val icon = appIcon
    if (!internal && icon != null) {
        Image(icon, contentDescription = app.name, modifier = modifier.size(29.dp))
        return
    }
    if (!internal && !iconFailed) {
        Spacer(modifier.size(29.dp)) // still loading: keep layout stable, no placeholder flash
        return
    }
    Canvas(modifier.size(29.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        when {
            app.pkg == "glide:camera" -> {
                drawRoundRect(tint, topLeft = Offset(w*.1f,h*.27f),
                    size = androidx.compose.ui.geometry.Size(w*.8f,h*.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    style = Stroke(width = sw))
                drawCircle(tint, radius = w*.15f, center = Offset(w*.5f,h*.55f), style = Stroke(width = sw))
                drawLine(tint, Offset(w*.28f,h*.27f), Offset(w*.37f,h*.15f), sw)
                drawLine(tint, Offset(w*.37f,h*.15f), Offset(w*.62f,h*.15f), sw)
            }
            app.pkg == "com.lge.hifirecorder" -> {
                drawRoundRect(tint, topLeft = Offset(w*.36f,h*.08f),
                    size = androidx.compose.ui.geometry.Size(w*.28f,h*.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w*.13f),
                    style = Stroke(width = sw))
                drawLine(tint, Offset(w*.22f,h*.43f), Offset(w*.22f,h*.60f), sw)
                drawLine(tint, Offset(w*.78f,h*.43f), Offset(w*.78f,h*.60f), sw)
                drawLine(tint, Offset(w*.22f,h*.60f), Offset(w*.78f,h*.60f), sw)
                drawLine(tint, Offset(w*.5f,h*.6f), Offset(w*.5f,h*.82f), sw)
                drawLine(tint, Offset(w*.32f,h*.82f), Offset(w*.68f,h*.82f), sw)
            }
            app.pkg == "com.maxmpz.audioplayer" -> {
                // Distinct turntable-like player icon if its installed icon cannot resolve.
                drawCircle(tint, radius = w*.34f, center=Offset(w*.5f,h*.5f), style=Stroke(width=sw))
                drawCircle(tint, radius = w*.10f, center=Offset(w*.5f,h*.5f))
                drawLine(tint, Offset(w*.72f,h*.18f), Offset(w*.78f,h*.58f), sw)
            }
            app.pkg == "com.lge.music" -> {
                drawLine(tint, Offset(w*.45f,h*.18f), Offset(w*.78f,h*.1f), sw)
                drawLine(tint, Offset(w*.45f,h*.18f), Offset(w*.45f,h*.7f), sw)
                drawLine(tint, Offset(w*.78f,h*.1f), Offset(w*.78f,h*.62f), sw)
                drawCircle(tint, radius=w*.13f, center=Offset(w*.32f,h*.72f))
                drawCircle(tint, radius=w*.13f, center=Offset(w*.65f,h*.64f))
            }
            app.pkg == "com.android.settings" -> {
                drawCircle(tint, radius=w*.32f, center=Offset(w*.5f,h*.5f), style=Stroke(width=sw))
                drawCircle(tint, radius=w*.1f, center=Offset(w*.5f,h*.5f), style=Stroke(width=sw))
                for (i in 0..7) {
                    val angle = i * Math.PI / 4.0
                    val x = kotlin.math.cos(angle).toFloat()
                    val y = kotlin.math.sin(angle).toFloat()
                    drawLine(tint, Offset(w*(.5f+.34f*x),h*(.5f+.34f*y)),
                        Offset(w*(.5f+.44f*x),h*(.5f+.44f*y)), sw)
                }
            }
            else -> {
                // Consistent squared Metro-outline tile for utility apps.
                drawRoundRect(tint, topLeft=Offset(w*.12f,h*.12f),
                    size=androidx.compose.ui.geometry.Size(w*.76f,h*.76f),
                    cornerRadius=androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style=Stroke(width=sw))
                drawLine(tint, Offset(w*.29f,h*.50f), Offset(w*.70f,h*.50f), sw)
                drawLine(tint, Offset(w*.50f,h*.29f), Offset(w*.50f,h*.70f), sw)
            }
        }
    }
}

@Composable
private fun HomePage(
    context: Context, player: PlayerInfo, onNowPlaying: () -> Unit,
    onLibrary: () -> Unit, onApps: () -> Unit,
    onShuffleAll: () -> Unit,
    onAppLongPress: (Shortcut) -> Unit
) {
    // v1.11e2: Zune HD home. No tiles or slabs: big lowercase words with thin white glyphs,
    // straight on the artist photo. Every former tile is still here as a menu line.
    var showMix by remember { mutableStateOf(false) }
    var showMaps by remember { mutableStateOf(false) }
    val scrollState = rememberLazyListState()
    val dateHeading = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) }
    val menu = remember {
        val recorder = primaryApps.first { it.pkg == "com.lge.hifirecorder" }
        listOf(primaryApps[0], primaryApps[1], recorder) +
            utilityApps.filter { it.pkg in setOf("glide:camera", "org.mozilla.fennec_fdroid", "org.breezyweather", "com.google.android.apps.maps") }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().metroElastic(true),
        state = scrollState,
        contentPadding = PaddingValues(start = 22.dp, end = 0.dp, bottom = 26.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(dateHeading.uppercase(Locale.getDefault()), style = Zune.style(11.sp, FontWeight.SemiBold, Zune.light, 1.2.sp))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.motionTap(onTap = onShuffleAll)) {
                ZuneIcon("play", Zune.white, Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text("music", style = Zune.style(58.sp, FontWeight.Light, Zune.white, (-2).sp),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            Text("shuffle all", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey),
                modifier = Modifier.clickable(onClick = onShuffleAll).padding(start = 42.dp))
            Spacer(Modifier.height(18.dp))
        }
        item {
            val resumeScope = rememberCoroutineScope()
            val savedTitle = if (player.controller == null && GlideEngine.enabled(context)) GlideEngine.savedTitle(context) else null
            if (savedTitle != null) {
                ZuneMenuLine("glide:music", "continue listening", savedTitle, onTap = {
                    resumeScope.launch { GlideEngine.resumeSaved(context) }
                })
            } else {
                ZuneNowPlayingStrip(player, onNowPlaying)
            }
        }
        item { Spacer(Modifier.height(18.dp)); GlideHomeHistory(context) }
        item {
            GlideLocalLists.pinRevision.intValue
            GlideLocalLists.pinnedPlaylist(context)?.let { name ->
                ZuneMenuLine("glide:mix", name, "pinned playlist", onTap = {
                    GlideLocalLists.openPlaylist.value = name
                    onLibrary()
                })
            }
        }
        item { Spacer(Modifier.height(18.dp)); GlideHomeQuickplay(context, onOpenLibrary = onLibrary) } // F4a: pins + new
        item { Spacer(Modifier.height(10.dp)) }
        item { ZuneMenuLine("glide:library", "library", "browse local tracks", onTap = onLibrary) }
        item { ZuneMenuLine("glide:apps", "apps", "music & utilities", onTap = onApps) }
        items(menu, key = { it.pkg }) { app ->
            ZuneMenuLine(app.pkg, app.name.lowercase(Locale.getDefault()), app.subtitle.lowercase(Locale.getDefault()),
                onTap = { launchApp(context, app.pkg) }, onLongPress = { onAppLongPress(app) })
        }
        item { ZuneMenuLine("glide:mix", "mix", "smart dj · playlists · recap · mixview", onTap = { showMix = true }) }
        item { ZuneMenuLine("glide:maps", "maps", "places · saved · directions", onTap = { showMaps = true }) }
    }
    if (showMaps) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showMaps = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(if (GlideMetroStyle.lightTheme) Color(0xFFF4F4F4) else Color(0xFF101014)).statusBarsPadding().navigationBarsPadding()) {
                GlideMapsScreen(context) { showMaps = false }
            }
        }
    }
    if (showMix) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showMix = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(if (GlideMetroStyle.lightTheme) Color(0xFFF5F5F5) else Color(0xFF090909)).statusBarsPadding().navigationBarsPadding()) {
                GlideMixScreen(context) { showMix = false }
            }
        }
    }
}

/** One Zune HD menu line: thin white glyph, huge Light lowercase word cut at the edge, grey caption. */
@Composable
private fun ZuneMenuLine(pkg: String, label: String, caption: String, onTap: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().motionTap(onTap = onTap, onLongPress = onLongPress).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ZuneAppGlyph(pkg, 30.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = Zune.style(40.sp, FontWeight.Light, Zune.white, (-1.2).sp),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            Text(caption, style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), maxLines = 1,
                modifier = Modifier.offset(y = (-4).dp))
        }
    }
}

/** Now playing on Home: small cover, title / artist, thin transport glyphs. No card behind it. */
@Composable
private fun ZuneNowPlayingStrip(player: PlayerInfo, onNowPlaying: () -> Unit) {
    val controller = player.controller
    Column(Modifier.fillMaxWidth().padding(end = 22.dp)) {
        Text("now playing", style = Zune.style(34.sp, FontWeight.Light, Zune.white, (-1.2).sp),
            modifier = Modifier.clickable(enabled = controller != null, onClick = onNowPlaying))
        Spacer(Modifier.height(10.dp))
        if (controller == null) {
            Text("play something in lg music or poweramp", style = Zune.style(14.sp, FontWeight.Normal, Zune.grey))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().motionTap(onTap = onNowPlaying)) {
            val art = player.artwork
            Box(Modifier.size(92.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                val image = remember(art) { art?.takeUnless { it.isRecycled }?.asImageBitmap() }
                if (image != null) {
                    Image(image, contentDescription = "Album artwork", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else ZuneIcon("note", Zune.grey, Modifier.size(40.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(player.title, style = Zune.style(22.sp, Zune.Semilight), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Text(player.artist, style = Zune.style(15.sp, FontWeight.Normal, Zune.light), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                if (player.source.isNotEmpty()) Text(player.source.lowercase(Locale.getDefault()),
                    style = Zune.style(11.sp, FontWeight.SemiBold, Zune.grey))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(34.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val a = player.actions
            ZuneHomeTap("previous", a and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) { controller.transportControls.skipToPrevious() }
            ZuneHomeTap(if (player.playing) "pause" else "play",
                a and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) {
                if (player.playing) controller.transportControls.pause() else controller.transportControls.play()
            }
            ZuneHomeTap("next", a and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) { controller.transportControls.skipToNext() }
        }
        // F3: thin accent progress (4 px track, Windows Phone style); ticks once a second, redraw only
        val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(player.playing, player.title) { while (player.playing) { now.longValue = SystemClock.elapsedRealtime(); delay(1000L) } }
        Canvas(Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp).height(4.dp)) {
            val f = if (player.duration > 0) (player.livePositionMs(now.longValue).toFloat() / player.duration).coerceIn(0f, 1f) else 0f
            drawRect(Zune.track)
            drawRect(Zune.magenta, size = Size(size.width * f, size.height))
        }
    }
}

@Composable
private fun ZuneHomeTap(kind: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        ZuneIcon(kind, if (enabled) Zune.white else Zune.dim, Modifier.size(24.dp))
    }
}

/** Artwork-led History. Counted as opens, never fabricated listening activity. */
@Composable
private fun GlideHomeHistory(context: Context) {
    val recent by produceState<List<Song>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) {
            val ids = GlideLocalLists.idsFor(context, "recent opens").take(6)
            if (ids.isEmpty()) emptyList()
            else {
                val byId = getOrLoadMusicIndex(context).songs.associateBy { it.id }
                ids.mapNotNull(byId::get)
            }
        }
    }
    if (recent.isNotEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            Text("history", style = Zune.style(34.sp, FontWeight.Light, Zune.white, (-1.2).sp))
            Spacer(Modifier.height(10.dp))
            // laptop test: same 104 dp squares as "pins" and "new", captions straight under each cover
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(recent, key = { it.id }) { song ->
                    Column(Modifier.width(104.dp).clickable { playSong(context, song) }) {
                        Box(Modifier.size(104.dp).background(Zune.dim.copy(alpha = .15f))) {
                            AlbumCover(context, song, 104.dp)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(song.title.lowercase(Locale.getDefault()), style = Zune.style(14.sp, FontWeight.Light),
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text("recently played through glide", style = Zune.style(11.sp, FontWeight.Normal, Zune.grey),
                modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun NowPlayingCard(context: Context, player: PlayerInfo) {
    Column(Modifier.fillMaxWidth().background(Surface).padding(16.dp)) {
        val art = player.artwork
        run {
            Box(Modifier.fillMaxWidth().height(184.dp).background(Color(0xFF30333A)), contentAlignment = Alignment.Center) {
                if (art != null && !art.isRecycled) {
                    Image(art.asImageBitmap(), contentDescription = "Album artwork", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    GlideInkWaves(Modifier.fillMaxSize())
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        Text(player.title, color = White, fontSize = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(player.artist, color = Subtle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (player.source.isNotEmpty()) Text(player.source, color = Blue, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(16.dp))
        if (player.controller == null) {
            Text("play something to begin", color = Subtle, fontSize = 13.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                MetroTransport("previous", player.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) { player.controller.transportControls.skipToPrevious() }
                MetroTransport(if (player.playing) "pause" else "play",
                    player.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) {
                    if (player.playing) player.controller.transportControls.pause() else player.controller.transportControls.play()
                }
                MetroTransport("next", player.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) { player.controller.transportControls.skipToNext() }
            }
        }
    }
}


/** One-screen, orientation-aware music page. Media controls remain owned by the original player.
 *  v1.10e step 0.3: split into NowPlayingArt / NowPlayingControls / PlaybackProgress so that
 *  fast-changing values (breathing scale, playback position) no longer recompose the whole page.
 */
@Composable
internal fun ImmersiveNowPlaying(
    player: PlayerInfo,
    motion: Boolean,
    active: Boolean = true,
    immersive: Boolean,
    fullPlayer: Boolean,
    ambientBars: Boolean,
    onToggleFull: () -> Unit,
    onClose: () -> Unit,
    onSearch: () -> Unit = onClose
) {
    val context = LocalContext.current
    // v1.10e 0.8: breathing moved ~0.1 px per frame yet forced a full 60 fps redraw (about 63% of a
    // core). Updating ~12 times a second keeps the same motion (about 0.5 px steps) at a fraction of
    // the cost. The value is still read only inside graphicsLayer (draw phase).
    val breathingOn = active && motion && immersive && player.playing && player.artwork != null && !GlideDiag.has(2)
    val breathingScale = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(breathingOn) {
        if (!breathingOn) { breathingScale.floatValue = 1f; return@LaunchedEffect }
        var lastStep = -1
        while (true) {
            // v1.10e 1.6: probe showed each breathing frame redraws the whole window (~1.6% CPU
            // per frame/s; 12/s = ~20% of a core). The art edge moves under 1 px per 0.002 of
            // scale, so only publish a new value when it moves by a whole step: ~3-5 frames/s,
            // same look.
            if (!NowPlayingFrameClock.driving()) {
                val step = (breathingScaleAt(SystemClock.elapsedRealtime()) * 500f).roundToInt()
                if (step != lastStep) { lastStep = step; breathingScale.floatValue = step / 500f }
            }
            delay(83L)
        }
    }
    val breathing: State<Float>? = if (breathingOn) breathingScale else null

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Cinematic ambient art: the same cached bitmap is drawn behind the
        // foreground album cover; no new decode, no RenderEffect blur, no
        // constantly changing full-screen bitmap allocation.
        val artistBackground by produceState<Bitmap?>(null, player.artist, player.controller, GlideMetroStyle.artworkRevision) {
            value = withContext(Dispatchers.IO) {
                if (player.controller == null) null else loadArtistBackdrop(context, player.artist)
            }
        }
        // v1.10e 1.2: gradient + dimmed artist/album image + scrim are flattened ONCE (half resolution,
        // off the main thread) into one image. Each Now Playing frame (60 fps while the visualizer
        // runs) then draws one bitmap instead of three full-screen layers.
        val source = (artistBackground ?: player.artwork)?.takeUnless { it.isRecycled }
        val surfaceArgb = Surface.toArgb()
        val inkArgb = Ink.toArgb()
        val bw = (constraints.maxWidth / 2).coerceAtLeast(1)
        val bh = (constraints.maxHeight / 2).coerceAtLeast(1)
        val flatBackdrop by produceState<ImageBitmap?>(null, source, immersive, landscape, surfaceArgb, inkArgb, bw, bh) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    renderPlayerBackdrop(if (immersive || !landscape) source else null, bw, bh, surfaceArgb, inkArgb,
                        if (landscape) 0.28f else 1f, zune = !landscape).asImageBitmap()
                }.getOrNull()
            }
        }
        val flatImage = if (GlideDiag.has(64)) null else flatBackdrop
        if (flatImage != null) {
            Image(flatImage, contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(PlayerGradient)) // first frame only
        }
        // Reserve at least half the available width for title, transport and spectrum.
        val artworkSize = if (landscape) minOf(maxHeight - 38.dp, maxWidth * 0.44f).coerceAtLeast(110.dp)
            else (maxHeight * 0.49f).coerceAtMost(maxWidth - 44.dp).coerceAtLeast(135.dp)
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (player.controller != null && !GlideDiag.has(32)) NowPlayingArt(player, active, motion, breathing, Modifier.size(artworkSize))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("now playing  /  GLIDE", color = Blue, fontSize = 15.sp, letterSpacing = 1.1.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    NowPlayingControls(player, active, ambientBars, Modifier.fillMaxWidth())
                }
            }
        } else {
            // v1.11 Phase 1: Zune / Metro 2020 Now Playing (GlideZuneNowPlaying.kt).
            ZuneNowPlaying(player, active, ambientBars, flatImage, maxWidth, maxHeight,
                art = { m -> if (GlideDiag.has(32)) Spacer(m) else NowPlayingArt(player, active, motion, breathing, m) },
                onSearch = onSearch)
        }
    }
}

/** Breathing art scale: 1.0 -> 1.025 -> 1.0 over 7.6 s, from the clock (no state of its own). */
internal fun breathingScaleAt(nowMs: Long): Float {
    val t = (nowMs % 7600L) / 7600.0
    val phase = 0.5 - 0.5 * kotlin.math.cos(2.0 * kotlin.math.PI * t)
    return 1f + 0.025f * phase.toFloat()
}

/** Same look as PlayerGradient + 1.13x centre-cropped image at [imageAlpha] + scrim, drawn once. */
private fun renderPlayerBackdrop(src: Bitmap?, w: Int, h: Int, surface: Int, ink: Int, imageAlpha: Float, zune: Boolean = false): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    val fw = w.toFloat(); val fh = h.toFloat()
    val clamp = android.graphics.Shader.TileMode.CLAMP
    // v1.11 Zune: black ground, the artist photo clearly visible under a black scrim that
    // deepens toward the bottom (reference: Metro 2020 Zune skin screenshots).
    p.shader = if (zune) null else android.graphics.LinearGradient(0f, 0f, 0f, fh, intArrayOf(surface, ink, ink), null, clamp)
    val lightBg = ink == -1 // F1: light theme → white page, photo only as a faint wash so dark text reads
    if (zune) p.color = if (lightBg) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    c.drawRect(0f, 0f, fw, fh, p)
    p.shader = null
    if (src != null && !src.isRecycled) {
        val bmp = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            // Centre-crop to fill, then crop a further 1/1.13 (the old graphicsLayer scale of 1.13).
            val scale = maxOf(fw / bmp.width, fh / bmp.height) * (if (zune) 1f else 1.13f)
            val sw = fw / scale; val sh = fh / scale
            val sx = (bmp.width - sw) / 2f; val sy = (bmp.height - sh) / 2f
            p.color = android.graphics.Color.WHITE
            p.alpha = (imageAlpha * 255f).roundToInt().coerceIn(0, 255)
            c.drawBitmap(bmp,
                android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()),
                android.graphics.RectF(0f, 0f, fw, fh), p)
            p.alpha = 255
            p.shader = android.graphics.LinearGradient(0f, 0f, 0f, fh,
                if (zune && lightBg) intArrayOf(0xD0FFFFFF.toInt(), 0xDDFFFFFF.toInt(), 0xF2FFFFFF.toInt())
                else if (zune) intArrayOf(0x8C000000.toInt(), 0x9E000000.toInt(), 0xE0000000.toInt())
                else intArrayOf(0xD9090B11.toInt(), 0xA60A0B0F.toInt(), 0xF10A0B0F.toInt()), null, clamp)
            c.drawRect(0f, 0f, fw, fh, p)
        }
        if (bmp != null && bmp !== src) bmp.recycle() // Only our temporary hardware-bitmap copy.
    }
    return out
}

/** Estimated live playback position. PlaybackState.lastPositionUpdateTime uses elapsedRealtime. */
internal fun PlayerInfo.livePositionMs(now: Long = SystemClock.elapsedRealtime()): Long =
    (position + if (playing && updatedAt > 0L) ((now - updatedAt).coerceAtLeast(0L) * speed).toLong() else 0L)
        .coerceIn(0L, duration.coerceAtLeast(0L))

/** Foreground cover. One small entrance motion; breathing scale is applied in the draw phase only. */
@Composable
private fun NowPlayingArt(
    player: PlayerInfo,
    active: Boolean,
    motion: Boolean,
    breathing: State<Float>?,
    modifier: Modifier
) {
    val nextAvailable = player.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
    val prevAvailable = player.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(active, player.title) {
        appeared = false
        if (active && motion) delay(45L)
        appeared = true
    }
    val appearProgress by animateFloatAsState(
        targetValue = if (appeared || !motion) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing), label = "zuneArtworkEntrance"
    )
    var artworkSwipe by remember { mutableFloatStateOf(0f) }
    Box(modifier.graphicsLayer {
            alpha = appearProgress
            translationX = (1f - appearProgress) * -34f
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }.clipToBounds() // v1.10e 0.8: square Metro artwork; cheaper than a rounded clip each frame
        .background(Color(0xFF242632))
        .pointerInput(player.controller, player.actions) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount -> artworkSwipe += amount; change.consume() },
                onDragEnd = {
                    if (artworkSwipe < -95f && nextAvailable) player.controller?.transportControls?.skipToNext()
                    if (artworkSwipe > 95f && prevAvailable) player.controller?.transportControls?.skipToPrevious()
                    artworkSwipe = 0f
                },
                onDragCancel = { artworkSwipe = 0f }
            )
        }, contentAlignment = Alignment.Center) {
        Crossfade(targetState = player.artwork, animationSpec = tween(if (motion) 380 else 0), label = "cover") { art ->
            val image = remember(art) { art?.takeUnless { it.isRecycled }?.asImageBitmap() }
            if (image != null) {
                Image(image, contentDescription = "Album artwork",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val s = if (breathing == null) 1f else {
                            val stepped = breathing.value
                            NowPlayingFrameClock.tick.intValue // follow visualizer frames (layer-only update)
                            if (NowPlayingFrameClock.driving()) breathingScaleAt(SystemClock.elapsedRealtime()) else stepped
                        }
                        scaleX = s
                        scaleY = s
                    },
                    contentScale = ContentScale.Crop)
            } else {
                GlideInkWaves(Modifier.fillMaxSize())
            }
        }
    }
}

/** Title, player selector, progress, transport, volume and spectrum. No per-tick state lives here. */
@Composable
private fun NowPlayingControls(
    player: PlayerInfo,
    active: Boolean,
    ambientBars: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volumeOpen by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    // Track hardware volume-key changes while the volume panel is visible.
    LaunchedEffect(volumeOpen, active) {
        while (volumeOpen && active) {
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            if (kotlin.math.abs(volume - actual) >= 1f) volume = actual
            delay(400L)
        }
    }
    val nextAvailable = player.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
    val prevAvailable = player.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
    val toggleAvailable = player.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L
    val controller = player.controller
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (controller == null) {
            Text("nothing playing", color = White, fontSize = 26.sp, fontWeight = FontWeight.Light)
            Text("choose a player to start listening", color = Subtle, fontSize = 13.sp)
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("LG MUSIC", color = Blue, modifier = Modifier.clickable { launchApp(context, "com.lge.music") })
                Text("POWERAMP", color = Blue, modifier = Modifier.clickable { launchApp(context, "com.maxmpz.audioplayer") })
            }
            return@Column
        }
        Text(player.title, color = White, fontSize = 29.sp, fontWeight = FontWeight.Light,
            letterSpacing = (-0.8).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(player.artist, color = Subtle, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        val playerPrefs = remember(context) { context.getSharedPreferences("glide_player_selection", 0) }
        var chosenPlayer by remember { mutableStateOf(playerPrefs.getString("preferred", "AUTO") ?: "AUTO") }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            listOf("AUTO" to "AUTO", "LG" to "com.lge.music",
                "POWERAMP" to "com.maxmpz.audioplayer").forEach { (label, pkg) ->
                Text(label,
                    color = if (chosenPlayer == pkg) Blue else Subtle,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable {
                        chosenPlayer = pkg
                        playerPrefs.edit().putString("preferred", pkg).apply()
                        context.sendBroadcast(Intent("com.gharaudio.launcher.SELECT_PLAYER").setPackage(context.packageName))
                    })
            }
        }
        if (player.duration > 0L && !GlideDiag.has(16)) PlaybackProgress(player, active, controller)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MetroTransport("previous", prevAvailable) { controller.transportControls.skipToPrevious() }
            MetroTransport(if (player.playing) "pause" else "play", toggleAvailable) {
                if (player.playing) controller.transportControls.pause() else controller.transportControls.play()
            }
            MetroTransport("next", nextAvailable) { controller.transportControls.skipToNext() }
            MetroTransport("volume", tint = Blue) { volumeOpen = !volumeOpen }
        }
        AnimatedVisibility(volumeOpen) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFF20223A))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("music volume", color = White, fontSize = 13.sp)
                    Text("${(volume / maxVol * 100).roundToInt()}%", color = Blue, fontSize = 13.sp)
                }
                Slider(
                    value = volume.coerceIn(0f, maxVol.toFloat()),
                    onValueChange = {
                        volume = it
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt().coerceIn(0, maxVol), 0)
                    },
                    valueRange = 0f..maxVol.toFloat(),
                    steps = (maxVol - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = Coral, activeTrackColor = Blue,
                        inactiveTrackColor = Color(0xFF404258)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (ambientBars && !GlideDiag.has(8)) {
            Spacer(Modifier.height(12.dp))
            // The spectrum polls the position itself; no per-tick recomposition of this column.
            val currentPlayer by rememberUpdatedState(player)
            GlideLiveSpectrum(enabled = active && player.playing, title = player.title, artist = player.artist,
                durationMs = player.duration, positionMs = { currentPlayer.livePositionMs() })
        }
    }
}

/** The only composable that recomposes on the ~1 s position tick: slider + time labels. */
@Composable
private fun PlaybackProgress(player: PlayerInfo, active: Boolean, controller: MediaController) {
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val tickOff = GlideDiag.has(4)
    LaunchedEffect(player.controller, player.playing, active, tickOff) {
        if (active) tick = SystemClock.elapsedRealtime()
        while (active && player.playing && !tickOff) {
            tick = SystemClock.elapsedRealtime()
            delay(900L)
        }
    }
    val livePosition = player.livePositionMs(tick)
    var dragSeek by remember(player.title, player.duration) { mutableStateOf<Float?>(null) }
    val displayed = dragSeek ?: livePosition.toFloat()
    val supportedSeek = player.actions and PlaybackState.ACTION_SEEK_TO != 0L && player.duration > 0L
    // Some LG Music sessions omit ACTION_SEEK_TO despite providing a transport controller.
    // Allow an experimental seek request; the player may ignore it.
    val canRequestSeek = player.duration > 0L
    Spacer(Modifier.height(7.dp))
    Slider(value = displayed.coerceIn(0f, player.duration.toFloat()),
        onValueChange = { dragSeek = it },
        onValueChangeFinished = {
            if (canRequestSeek) dragSeek?.let { controller.transportControls.seekTo(it.toLong()) }
            dragSeek = null
        }, valueRange = 0f..player.duration.toFloat(), enabled = canRequestSeek,
        colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Surface),
        modifier = Modifier.fillMaxWidth().height(35.dp))
    if (!supportedSeek) Text("Seek request is experimental: LG Music may ignore it", color = Subtle, fontSize = 11.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(elapsedLabel(displayed.toLong()), color = Subtle, fontSize = 11.sp)
        Text("-${elapsedLabel((player.duration - displayed.toLong()).coerceAtLeast(0L))}", color = Subtle, fontSize = 11.sp)
    }
}

/** Technical values describe the actual source file, not the DAC output mode. */
private fun readTechnicalDetails(context: Context, song: Song): String {
    val lines = mutableListOf<String>()
    try {
        context.contentResolver.query(song.uri,
            arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && !cursor.isNull(column)) {
                    lines.add("File size: %.2f MiB".format(Locale.US,
                        cursor.getLong(column).toDouble() / (1024.0 * 1024.0)))
                }
            }
        }
    } catch (_: Exception) { }
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, song.uri, null)
        var found = false
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            found = true
            lines.add("Audio type: $mime")
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                lines.add("Sample rate: ${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz")
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                lines.add("Channels: ${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}")
            if (format.containsKey(MediaFormat.KEY_BIT_RATE))
                lines.add("Bitrate: ${format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000} kbps")
            // This isn't universally populated for encoded formats; do not guess 16/24 bit.
            if (format.containsKey("bits-per-sample"))
                lines.add("Source bit depth: ${format.getInteger("bits-per-sample")}-bit")
            else lines.add("Source bit depth: not reported")
            break
        }
        if (!found) lines.add("Audio stream metadata unavailable")
    } catch (_: Exception) {
        lines.add("Audio stream metadata unavailable")
    } finally {
        extractor.release()
    }
    if (lines.none { it.startsWith("Bitrate:") }) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
                lines.add("Bitrate (reported): ${it / 1000} kbps")
            }
        } catch (_: Exception) { } finally { try { retriever.release() } catch (_: Exception) {} }
    }
    return lines.joinToString("\n")
}

private fun elapsedLabel(milliseconds: Long): String {
    val total = (milliseconds.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(Locale.US, total / 60L, total % 60L)
}

@Composable
private fun GharSettings(
    motion: Boolean, immersive: Boolean, fullPlayer: Boolean, ambientBars: Boolean,
    shellFullscreen: Boolean, onShellFullscreen: (Boolean) -> Unit,
    onFullPlayer: (Boolean) -> Unit, onAmbientBars: (Boolean) -> Unit,
    onMotion: (Boolean) -> Unit, onImmersive: (Boolean) -> Unit,
    onClose: () -> Unit, onAccess: () -> Unit,
    onPickWallpaper: () -> Unit,
    onPickGallery: () -> Unit = {},
    onPickCorner: () -> Unit = {}
) {
    // v1.11h: Zune HD settings. No slabs or Material chips/switches: grey Light group headers,
    // lowercase Light items, Zune toggles, all on the shared photo backdrop.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE) }
    Column(Modifier.fillMaxSize()
        .metroElastic(motion).verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 18.dp, top = 6.dp, bottom = 28.dp)) {
        ZuneSettingsGroup("appearance")
        Text("accent", style = Zune.style(22.sp, FontWeight.Light))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            GlideMetroStyle.names.forEachIndexed { i, name ->
                Text(name.lowercase(Locale.getDefault()),
                    style = Zune.style(18.sp, if (GlideMetroStyle.palette == i) FontWeight.Bold else FontWeight.Light, Color(GlideMetroStyle.wpAccents[i]))
                        .copy(textDecoration = if (GlideMetroStyle.palette == i) androidx.compose.ui.text.style.TextDecoration.Underline else null),
                    modifier = Modifier.clickable { GlideMetroStyle.setPalette(context, i) }.padding(vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("theme", style = Zune.style(22.sp, FontWeight.Light))
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            listOf("dark" to false, "light" to true).forEach { (label, on) ->
                Row(Modifier.clickable { GlideMetroStyle.setLight(context, on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetroRadio(GlideMetroStyle.lightTheme == on); Spacer(Modifier.width(10.dp))
                    Text(label, style = Zune.style(19.sp, FontWeight.Light, Zune.white))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("background", style = Zune.style(22.sp, FontWeight.Light))
        listOf("glide waves", "artist photo", "my image", "plain", "my gallery (changes every 30 min)",
            "zune rays", "zune bands", "zune glow", "album wash").forEachIndexed { id, mode ->
            val on = GlideMetroStyle.wallpaper == id
            Row(Modifier.fillMaxWidth().clickable { GlideMetroStyle.setWallpaper(context, id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MetroRadio(on); Spacer(Modifier.width(12.dp))
                Text(mode, style = Zune.style(19.sp, FontWeight.Light, Zune.white), modifier = Modifier.weight(1f))
            }
        }
        ZuneSettingsAction("choose my image", "a private copy; your system wallpaper is not changed", onPickWallpaper)
        val galleryCount = remember(GlideMetroStyle.wallpaperRevision) { GlideMetroStyle.galleryFiles(context).size }
        ZuneSettingsAction("add images to gallery", "$galleryCount images · pick several at once (e.g. Pictures › Zune)", onPickGallery)
        var cornerOn by remember { mutableStateOf(GlideMetroStyle.cornerArt) }
        ZuneToggle("corner art", "zune desktop style: one of your corner images, faint, top-right", cornerOn) {
            cornerOn = it; GlideMetroStyle.setCornerArt(context, it)
        }
        val cornerCount = remember(GlideMetroStyle.wallpaperRevision) { GlideMetroStyle.cornerFiles(context).size }
        ZuneSettingsAction("add corner art images", "$cornerCount images · e.g. the zune originals artwork", onPickCorner)
        if (cornerCount > 0) ZuneSettingsAction("clear corner art", "removes Glide's copies only") {
            GlideMetroStyle.cornerFiles(context).forEach { it.delete() }; GlideMetroStyle.wallpaperRevision++
        }
        if (galleryCount > 0) ZuneSettingsAction("clear gallery", "removes Glide's copies only") {
            GlideMetroStyle.galleryFiles(context).forEach { it.delete() }
            GlideMetroStyle.wallpaperRevision++
            if (GlideMetroStyle.wallpaper == 4) GlideMetroStyle.setWallpaper(context, 1)
        }

        ZuneSettingsAction("recent apps", "glide's switcher · you can also hold back inside glide") { GlideSwitcher.open.value = true }
        GlidePlayerSettings(context)
        ZuneSettingsGroup("motion")
        ZuneToggle("full screen", "hide android bars inside glide; swipe from an edge to show them", shellFullscreen, onShellFullscreen)
        ZuneToggle("motion", "turnstile when opening apps, tilt on touch, page slides, artwork movement", motion, onMotion)
        ZuneToggle("breathing artwork", "slow movement while music plays", immersive, onImmersive)
        ZuneToggle("ambient bars", "decorative motion, not a live spectrum", ambientBars, onAmbientBars)
        var saverEnabled by remember { mutableStateOf(prefs.getBoolean("screen_saver", true)) }
        ZuneToggle("screen saver", "zune hd style: after 20 s on now playing, artist, album and bio drift across", saverEnabled) {
            saverEnabled = it; prefs.edit().putBoolean("screen_saver", it).apply()
        }
        var uiHaptics by remember { mutableStateOf(prefs.getBoolean("ui_haptics", true)) }
        ZuneToggle("touch vibration", "short buzz on taps, double buzz when a press-and-hold menu opens", uiHaptics) {
            uiHaptics = it; prefs.edit().putBoolean("ui_haptics", it).apply()
        }
        var uiSounds by remember { mutableStateOf(prefs.getBoolean("ui_sounds", true)) }
        ZuneToggle("ui sounds", "soft ticks on page and letter changes, muted key taps; silent when the ringer is off", uiSounds) {
            uiSounds = it; prefs.edit().putBoolean("ui_sounds", it).apply()
        }
        var zuneLock by remember { mutableStateOf(prefs.getBoolean("zune_lock", false)) }
        ZuneToggle("zune lock screen", "a zune hd curtain when the screen turns on; your pin / pattern (if any) still follows", zuneLock) {
            zuneLock = it; prefs.edit().putBoolean("zune_lock", it).apply()
        }
        var enhancePhoto by remember { mutableStateOf(false) }
        ZuneSettingsAction("enhance photo", "lift shadows and save a local copy") { enhancePhoto = true }
        if (enhancePhoto) GlidePhotoEnhance(onClose = { enhancePhoto = false })
        ZuneSettingsAction("copy diagnostics", "copy app, device and audio output details") {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val outputs = audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .map { it.productName.toString() }.distinct().joinToString()
            val report = "Glide ${info.versionName} (${info.longVersionCode})\n" +
                "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                "Audio outputs: $outputs\n" +
                "Glide playback: ${GlideFx.player?.isPlaying == true}"
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Glide diagnostics", report))
            GlideToast.show("Diagnostics copied")
        }
        var accessoryVideos by remember { mutableStateOf(false) }
        ZuneSettingsAction("accessory videos", "choose a local clip for each audio device") { accessoryVideos = true }
        if (accessoryVideos) GlideAccessoryVideos(onClose = { accessoryVideos = false })
        ZuneSettingsAction("open Wavelet", "adjust headphone EQ in Wavelet") {
            launchApp(context, "com.pittvandewitt.wavelet")
        }
        ZuneSettingsAction("dock when charging", "android › display › screen saver › glide, start when charging") {
            runCatching { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        var zuneVol by remember { mutableStateOf(prefs.getBoolean("zune_volume", true)) }
        ZuneToggle("zune volume", "volume keys show the zune controls instead of lg's panel while music plays", zuneVol) {
            zuneVol = it; prefs.edit().putBoolean("zune_volume", it).apply()
        }
        ZuneSettingsAction("turn on zune volume", "one time: accessibility › glide volume › on") {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        var kbFix by remember { mutableStateOf(prefs.getBoolean("kb_autocorrect", true)) }
        ZuneToggle("keyboard autocorrect", "fixes one-letter slips on space; backspace right after puts your word back", kbFix) {
            kbFix = it; prefs.edit().putBoolean("kb_autocorrect", it).apply()
        }
        var kbSwipe by remember { mutableStateOf(prefs.getBoolean("kb_swipe", true)) }
        ZuneToggle("keyboard swipe typing", "slide across the letters; the strip offers two other words", kbSwipe) {
            kbSwipe = it; prefs.edit().putBoolean("kb_swipe", it).apply()
        }
        var introEnabled by remember { mutableStateOf(prefs.getBoolean("metro_intro", true)) }
        ZuneToggle("startup motion", "short glide logo when the launcher opens", introEnabled) {
            introEnabled = it; prefs.edit().putBoolean("metro_intro", it).apply()
        }

        ZuneSettingsGroup("music info")
        var autoArt by remember { mutableStateOf(OnlineArt.enabled(context)) }
        ZuneToggle("online artwork", "artist photos, album covers and biographies from musicbrainz, deezer, theaudiodb and wikipedia. cached on the phone; audio files are never changed.", autoArt) {
            autoArt = it
            OnlineArt.setEnabled(context, it)
            missingAlbumArt.clear()
            artCache.evictAll()
            artistArtCache.evictAll()
            artistBackdropCache.evictAll()
            GlideMetroStyle.artworkRevision++
        }
        Text("to fix a song's title, artist or album for glide only: library → hold a song → edit display tags",
            style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.padding(top = 4.dp))

        ZuneSettingsGroup("screens")
        ZuneSettingsAction("music lock view", "a music screen over the lock screen; it never unlocks the phone") {
            context.startActivity(Intent(context, GlideAmbientActivity::class.java).putExtra("glide_mode", "lock"))
        }
        ZuneSettingsAction("landscape dock", "preview the sideways layout; back to leave") {
            context.startActivity(Intent(context, GlideAmbientActivity::class.java).putExtra("glide_mode", "dock"))
        }
        ZuneSettingsAction("charging screensaver", "pick glide dock in android's screensaver settings") {
            try { context.startActivity(Intent("android.settings.DREAM_SETTINGS")) }
            catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        }

        ZuneSettingsGroup("access")
        ZuneSettingsAction("notification access", "lets glide see and control what lg music or poweramp is playing", onAccess)
        ZuneSettingsAction("glide keyboard", "turn it on or off; hold the space bar to switch keyboards") {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Spacer(Modifier.height(16.dp))
        Text("playback stays in lg music or poweramp. the volume slider changes music volume while glide is open.",
            style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
    }
}

@Composable
private fun ZuneSettingsGroup(title: String) {
    Text(title, style = Zune.style(30.sp, FontWeight.Light, Zune.grey, (-0.8).sp),
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
}

@Composable
private fun ZuneSettingsAction(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Zune.style(22.sp, FontWeight.Light))
            Text(detail, style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
        }
        ZuneIcon("external", Zune.grey, Modifier.size(16.dp))
    }
}

/** F4b: Windows Phone radio — a square outline, filled with the accent square when chosen. */
@Composable
private fun MetroRadio(selected: Boolean) {
    Box(Modifier.size(22.dp).border(2.dp, Zune.white), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(10.dp).background(Zune.magenta))
    }
}

/** Windows Phone toggle: "on/off" word plus a flat outlined switch with an accent fill and a solid thumb. */
@Composable
private fun ZuneToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = Zune.style(22.sp, FontWeight.Light))
            Text(detail, style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
        }
        Text(if (checked) "on" else "off", style = Zune.style(15.sp, FontWeight.Normal, if (checked) Zune.white else Zune.grey),
            modifier = Modifier.width(30.dp))
        // F3: Metro toggle — 56x24 track with 2 px border, accent fill when on, 12x28 rectangular thumb
        val pos by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, tween(120), label = "toggle")
        Canvas(Modifier.size(width = 56.dp, height = 28.dp)) {
            val stroke = 2.dp.toPx(); val trackH = 24.dp.toPx(); val top = (size.height - trackH) / 2
            val thumbW = 12.dp.toPx(); val inset = 4.dp.toPx()
            drawRect(Zune.white, Offset(0f, top), Size(size.width, trackH), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            val thumbX = pos * (size.width - thumbW)
            if (pos > 0f) drawRect(Zune.magenta, Offset(inset, top + inset), Size((thumbX - inset).coerceAtLeast(0f), trackH - inset * 2))
            drawRect(Zune.white, Offset(thumbX, 0f), Size(thumbW, size.height))
            drawRect(GlideMetroStyle.ink, Offset(thumbX, 0f), Size(thumbW, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        }
    }
}

@Composable
private fun ActionTile(name: String, caption: String, mark: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.height(106.dp).motionTap(onTap = onClick).padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(mark, color = Blue, fontSize = 31.sp)
        Column {
            Text(name.lowercase(Locale.getDefault()), style = Zune.style(15.sp, FontWeight.SemiBold))
            Text(caption, style = Zune.style(10.sp, FontWeight.Normal, Zune.light), maxLines = 1)
        }
    }
}

@Composable
private fun AppTile(
    shortcut: Shortcut, context: Context, color: Color,
    modifier: Modifier = Modifier, onAppLongPress: (Shortcut) -> Unit
) {
    Column(modifier.height(106.dp)
        .motionTap(onTap = { launchApp(context, shortcut.pkg) }, onLongPress = { onAppLongPress(shortcut) })
        .padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
        ZuneAppGlyph(shortcut.pkg, 29.dp)
        Column {
            Text(shortcut.name.lowercase(Locale.getDefault()), style = Zune.style(15.sp, FontWeight.SemiBold), maxLines = 1)
            Text(shortcut.subtitle, style = Zune.style(10.sp, FontWeight.Normal, Zune.light), maxLines = 1)
        }
    }
}



internal data class LiveRelease(val title: String, val artist: String, val date: String, val id: String)

internal fun getLatestReleases(): List<LiveRelease> {
    val end = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    val begin = end.minusDays(21)
    val query = "firstreleasedate:[${begin} TO ${end}] AND primarytype:album"
    val address = "https://musicbrainz.org/ws/2/release-group/?query=" +
        URLEncoder.encode(query, "UTF-8") + "&fmt=json&limit=30"
    val conn = java.net.URL(address).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 7000
    conn.readTimeout = 9000
    conn.setRequestProperty("User-Agent", "Glide/0.8.1 (Android music discovery; noncommercial)")
    conn.setRequestProperty("Accept", "application/json")
    try {
        if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = org.json.JSONObject(body).optJSONArray("release-groups") ?: return emptyList()
        val releases = mutableListOf<LiveRelease>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val credits = obj.optJSONArray("artist-credit")
            val names = mutableListOf<String>()
            if (credits != null) for (j in 0 until credits.length()) {
                val entry = credits.optJSONObject(j) ?: continue
                val artist = entry.optJSONObject("artist")?.optString("name").orEmpty()
                if (artist.isNotBlank()) names.add(artist)
            }
            val title = obj.optString("title")
            val id = obj.optString("id")
            val date = obj.optString("first-release-date")
            if (title.isNotBlank() && id.matches(Regex("[0-9a-fA-F-]{36}"))) {
                releases.add(LiveRelease(title, names.joinToString(", ").ifBlank { "Unknown artist" }, date, id))
            }
        }
        return releases.sortedByDescending { it.date }.take(20)
    } finally { conn.disconnect() }
}

@Composable
private fun AppsPage(context: Context, onAppLongPress: (Shortcut) -> Unit) {
    // v1.11e2: Zune list. No slabs: glyph, lowercase Light name, grey caption, on the backdrop.
    // v1.11g: Zune HD apps list. Your picks first, then every installed app A-Z under letter headers.
    val allApps by produceState(emptyList<ZuneAppEntry>()) { value = withContext(Dispatchers.IO) { loadAllApps(context) } }
    val pinned = remember { (primaryApps + utilityApps).map { it.pkg }.toSet() }
    val byLetter = remember(allApps) {
        allApps.filter { it.pkg !in pinned }.groupBy { firstLetter(it.label).lowercase(Locale.ROOT) }.toSortedMap()
    }
    LazyColumn(contentPadding = PaddingValues(start = 22.dp, top = 6.dp, bottom = 24.dp)) {
        item { Text("music", style = Zune.style(34.sp, FontWeight.Light, Zune.white, (-1.2).sp)) }
        items(primaryApps, key = { it.pkg }) { app -> AppLine(app, context, onAppLongPress) }
        item { Spacer(Modifier.height(16.dp)); Text("utilities", style = Zune.style(34.sp, FontWeight.Light, Zune.white, (-1.2).sp)) }
        items(utilityApps, key = { it.pkg }) { app -> AppLine(app, context, onAppLongPress) }
        if (byLetter.isNotEmpty()) item { Spacer(Modifier.height(16.dp)); Text("all apps", style = Zune.style(34.sp, FontWeight.Light, Zune.white, (-1.2).sp)) }
        byLetter.forEach { (letter, apps) ->
            item(key = "letter_$letter") {
                Text(letter, style = Zune.style(28.sp, FontWeight.Light, Zune.magenta), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            }
            items(apps, key = { "all_" + it.pkg }) { app ->
                AppLine(Shortcut(app.label, "", app.pkg, ""), context, onAppLongPress)
            }
        }
    }
}

@Composable
private fun AppLine(app: Shortcut, context: Context, onAppLongPress: (Shortcut) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp)
        .motionTap(onTap = { launchApp(context, app.pkg) }, onLongPress = { onAppLongPress(app) }),
        verticalAlignment = Alignment.CenterVertically) {
        ZuneAppGlyph(app.pkg, 28.dp)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(app.name.lowercase(Locale.getDefault()), style = Zune.style(24.sp, FontWeight.Light),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (app.subtitle.isNotBlank())
                Text(app.subtitle.lowercase(Locale.getDefault()), style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), maxLines = 1)
        }
    }
}

internal data class GenreGroup(val name: String, val songs: List<Song>)
internal data class MusicIndex(
    val songs: List<Song>,
    val genres: List<GenreGroup>,
    val artistGroups: Map<String, List<Song>> = emptyMap(),
    val albumGroups: Map<String, List<Song>> = emptyMap()
)
@Volatile internal var cachedMusicIndex: MusicIndex? = null
private val musicIndexLoadLock = Any()
internal fun getOrLoadMusicIndex(context: Context): MusicIndex =
    synchronized(musicIndexLoadLock) {
        cachedMusicIndex ?: readMusicIndex(context).also { cachedMusicIndex = it }
    }
private var cachedGenresLoaded = false
/** v1.11f2: your album titles for an artist, used to pick the right artist when several share a name.
 *  Blocking (may read MediaStore once); called only from the online-art IO path. */
internal fun localAlbumsFor(context: Context, artist: String): List<String> =
    getOrLoadMusicIndex(context).songs.asSequence()
        .filter { OnlineArt.primaryArtist(it.artist).replace(Regex("[^\\p{L}\\p{N}]+"), "").equals(
            OnlineArt.primaryArtist(artist).replace(Regex("[^\\p{L}\\p{N}]+"), ""), true) }
        .map { it.album }.filter { it.isNotBlank() && !it.equals("unknown album", true) }
        .distinct().take(40).toList()
private val missingAlbumArt = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
// MediaStore can return the same album multiple times in a fast fling. Decode once.
// Locks are per-album (never a global lock), held only on Dispatchers.IO.
private val albumDecodeLocks = java.util.concurrent.ConcurrentHashMap<Long, Any>()
private val artCache = object : LruCache<Long, Bitmap>(8 * 1024) {
    override fun sizeOf(key: Long, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
}

private fun artistCoverFile(context: Context, artist: String): java.io.File {
    val key = java.security.MessageDigest.getInstance("SHA-256")
        .digest(artist.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return java.io.File(java.io.File(context.filesDir, "artist_art"), "$key.jpg")
}


private fun decodeBackdropFile(file: java.io.File): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / sample > 720) sample *= 2
    // B2 memory: backdrops sit under a dark scrim, so 16-bit colour is invisible and halves the size.
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
}
// v1.10e 0.5: one shared cache for 720 px artist backdrops (shell, Now Playing, artist page).
private val artistBackdropCache = object : LruCache<String, Bitmap>(12 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
}
private fun backdropKey(artist: String): String = artist.trim().lowercase(Locale.ROOT)
private fun invalidateArtistBackdrop(artist: String) { artistBackdropCache.remove(backdropKey(artist)) }
private fun invalidateArtistPortraits(artist: String) {
    val prefix = "$artist/"
    artistArtCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { artistArtCache.remove(it) }
}
/** v1.11f3: only one online lookup runs at a time, so a second screen asking for the same photo used to get
 *  null ("busy") and never asked again (Now Playing had the photo, the artist page stayed black). Wait and
 *  retry while busy; once the first lookup finishes the photo comes from the cache. Max ~15 s. */
internal suspend fun loadArtistBackdropPatiently(context: Context, artist: String, variant: Int = 0): Bitmap? =
    withContext(Dispatchers.IO) {
        repeat(10) {
            val bitmap = loadArtistBackdrop(context, artist, variant)
            if (bitmap != null || !OnlineArt.wasDeferred()) return@withContext bitmap
            delay(1500L)
        }
        null
    }

/** PHOTO+ file first, then verified online cache/lookup. Blocking: call on Dispatchers.IO only. */
private fun loadArtistBackdrop(context: Context, artist: String, variant: Int = 0): Bitmap? {
    if (artist.isBlank()) return null
    val local = artistCoverFile(context, artist)
    // Install B: rotate between several photos of the same artist (your own PHOTO+ choice always wins).
    if (variant > 0 && !local.isFile) {
        val files = OnlineArt.artistPhotoFiles(context, artist)
        if (files.size > 1) {
            val pick = files[variant % files.size]
            val vkey = backdropKey(artist) + "#" + (variant % files.size)
            artistBackdropCache.get(vkey)?.takeUnless { it.isRecycled }?.let { return it }
            // B2 memory: keep only the photo on screen, not every rotated photo of this artist.
            val prefix = backdropKey(artist) + "#"
            artistBackdropCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { artistBackdropCache.remove(it) }
            decodeBackdropFile(pick)?.let { artistBackdropCache.put(vkey, it); return it }
        }
    }
    val key = backdropKey(artist)
    artistBackdropCache.get(key)?.takeUnless { it.isRecycled }?.let { return it }
    val bitmap = if (local.isFile) decodeBackdropFile(local)
        else if (OnlineArt.enabled(context)) OnlineArt.artistBackdrop(context, artist)
            ?: OnlineArt.cachedArtistFile(context, artist).takeIf { it.isFile }?.let(::decodeBackdropFile)
        else null
    if (bitmap != null) artistBackdropCache.put(key, bitmap)
    return bitmap
}

/** v1.10e 0.5: players may send very large art; the UI never draws it larger than about 1080 px. */
private fun boundedSessionArt(src: Bitmap): Bitmap {
    val maxSide = maxOf(src.width, src.height)
    if (maxSide <= 1080) return src
    val scale = 1080f / maxSide
    return Bitmap.createScaledBitmap(src,
        (src.width * scale).roundToInt().coerceAtLeast(1),
        (src.height * scale).roundToInt().coerceAtLeast(1), true)
}

private val artistArtCache = object : LruCache<String, Bitmap>(2048) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
}

@Composable
private fun ArtistPortrait(context: Context, artist: String, revision: Int) {
    val cacheKey = "$artist/$revision/${GlideMetroStyle.artworkRevision}"
    val portrait by produceState<Bitmap?>(initialValue = artistArtCache.get(cacheKey), cacheKey) {
        if (value == null) value = withContext(Dispatchers.IO) {
            val chosen = artistCoverFile(context, artist)
            val file = if (chosen.isFile) chosen else OnlineArt.cachedArtistFile(context, artist)
            if (!file.isFile) null else {
                // Decode only the few hundred pixels needed in the artist list.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                var scale = 1
                while (maxSide / scale > 192) scale *= 2
                val bitmap = BitmapFactory.decodeFile(file.path,
                    BitmapFactory.Options().apply { inSampleSize = scale })
                if (bitmap != null) artistArtCache.put(cacheKey, bitmap)
                bitmap
            }
        }
    }
    val portraitImage = remember(portrait) { portrait?.takeUnless { it.isRecycled }?.asImageBitmap() }
    // v1.11e2: no coloured square. The artist photo when Glide has one, else a big Light letter.
    Box(Modifier.size(65.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        if (portraitImage != null) {
            Image(portraitImage, contentDescription = "User-selected artist portrait",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else Text(artist.trim().firstOrNull()?.lowercase() ?: "?",
            style = Zune.style(44.sp, FontWeight.Light, Zune.light))
    }
}

@Composable
internal fun AlbumCover(context: Context, song: Song?, size: androidx.compose.ui.unit.Dp = 66.dp) {
    val id = song?.albumId ?: -1L
    val art by produceState<Bitmap?>(initialValue = artCache.get(id), id, GlideMetroStyle.artworkRevision) {
        if (id >= 0 && value == null && song != null) {
            value = withContext(Dispatchers.IO) { loadAlbumArt(context, song) }
        }
    }
    val coverImage = remember(art) { art?.takeUnless { it.isRecycled }?.asImageBitmap() }
    Box(Modifier.size(size).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
        if (coverImage != null) {
            Image(coverImage, contentDescription = "Album artwork",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else Text("♫", color = Blue, fontSize = 28.sp)
    }
}

/** Decode covers for the maximum actually needed UI resolution, never at source size. */
private fun coverSampleSize(width: Int, height: Int, maxSide: Int = 256): Int {
    var sample = 1
    val largest = maxOf(width, height)
    while (largest / (sample * 2) >= maxSide) sample *= 2
    return sample
}

private fun decodeCoverBytes(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight)
        })
}

private fun decodeCoverFile(file: java.io.File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeFile(file.path,
        BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight)
        })
}

private fun decodeCoverUri(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight)
        })
    }
}

private fun loadAlbumArt(context: Context, song: Song): Bitmap? = tracedGlide("Glide.AlbumArtDecode") {
    val lock = albumDecodeLocks.getOrPut(song.albumId) { Any() }
    synchronized(lock) {
    artCache.get(song.albumId)?.let { return@tracedGlide it }
    if (missingAlbumArt.contains(song.albumId)) return@tracedGlide null

    var bitmap: Bitmap? = null
    // Android's indexed album thumbnail is inexpensive compared with opening
    // every audio file with MediaMetadataRetriever during a list fling.
    val albumUri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"), song.albumId)
    bitmap = try { decodeCoverUri(context, albumUri) } catch (_: Exception) { null }
    if (bitmap == null) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.uri)
            bitmap = retriever.embeddedPicture?.let(::decodeCoverBytes)
        } catch (_: Exception) { /* Tags may be missing or malformed. */ }
        finally { try { retriever.release() } catch (_: Exception) {} }
    }
    if (bitmap == null) {
        try {
            context.contentResolver.query(song.uri,
                arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(0)
                    val directory = java.io.File(path).parentFile
                    val candidates = setOf(
                        "folder.jpg", "cover.jpg", "front.jpg", "album.jpg",
                        "folder.png", "cover.png", "front.png", "album.png"
                    )
                    val cover = directory?.listFiles()?.firstOrNull {
                        it.isFile && it.name.lowercase(Locale.ROOT) in candidates
                    }
                    if (cover != null) bitmap = decodeCoverFile(cover)
                }
            }
        } catch (_: Exception) { /* Path may be inaccessible. */ }
    }
    if (bitmap == null) bitmap = OnlineArt.albumCover(context, song.artist, song.album)
    if (bitmap != null) artCache.put(song.albumId, bitmap)
    // v1.10e 0.6: a lookup skipped because another was in flight is retried later, not marked missing.
    else if (!OnlineArt.wasDeferred()) missingAlbumArt.add(song.albumId)
    bitmap
    }
}

/** v1.11k: one tap fetches every missing album cover and artist photo in the background (MusicBrainz allows
 *  one request a second, so a big library takes a while once; everything is cached on the phone afterwards). */
internal object GlideArtBatch {
    var running by mutableStateOf(false)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    @Volatile private var stop = false
    fun cancel() { stop = true }
    fun start(context: Context) {
        if (running || !OnlineArt.enabled(context)) return
        stop = false; running = true; done = 0; total = 0
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        kotlin.concurrent.thread(name = "GlideArtBatch", isDaemon = true) {
            // Install B: low priority + a short pause per item so the UI and music never compete with it
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            OnlineArt.batch = true
            try {
                val songs = getOrLoadMusicIndex(app).songs
                val albums = songs.filter { it.album.isNotBlank() }.distinctBy { it.albumId }
                val artists = songs.map { it.artist }.filter { it.isNotBlank() && !it.equals("<unknown>", true) }
                    .distinctBy { OnlineArt.primaryArtist(it).lowercase(Locale.ROOT) }
                main.post { total = albums.size + artists.size }
                for (song in albums) {
                    if (stop) break
                    // only albums with no cover of their own; nothing is decoded into memory here
                    if (!albumHasLocalArt(app, song)) runCatching { OnlineArt.albumCover(app, song.artist, song.album)?.recycle() }
                    main.post { done++ }; Thread.sleep(120L)
                }
                for (artist in artists) {
                    if (stop) break
                    runCatching {
                        if (!OnlineArt.cachedArtistFile(app, artist).isFile) OnlineArt.artistBackdrop(app, artist)?.recycle()
                        OnlineArt.fetchArtistAlternates(app, artist)
                    }
                    main.post { done++ }; Thread.sleep(120L)
                }
            } finally {
                OnlineArt.batch = false
                main.post { running = false; GlideMetroStyle.artworkRevision++ }
            }
        }
    }
}

/** Install B: cheap check (no bitmap decode) whether an album already has its own cover. */
private fun albumHasLocalArt(context: Context, song: Song): Boolean {
    if (artCache.get(song.albumId) != null) return true
    val uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
    if (runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)) return true
    val r = MediaMetadataRetriever()
    return try { r.setDataSource(context, song.uri); r.embeddedPicture != null } catch (_: Exception) { false }
    finally { runCatching { r.release() } }
}

/** Full-viewport Zune artist / album route; replaces the cramped library sub-panel.
 *  Cached artwork stays behind the text, never over it. External engines still own audio.
 */
@Composable
private fun MetroArtistAlbumPage(
    context: Context, label: String, songs: List<Song>, isArtist: Boolean,
    selectedTab: Int, onTab: (Int) -> Unit, onBack: () -> Unit,
    onAlbum: (String,List<Song>) -> Unit, onSongLong: (Song) -> Unit,
    onPortrait: () -> Unit, revision: Int, onArtist: (String) -> Unit = {}
) {
    val albums = remember(songs,isArtist) {
        songs.groupBy { it.album }.entries.sortedBy { it.key.lowercase(Locale.ROOT) }
    }
    var artworkRetry by remember(label, isArtist) { mutableIntStateOf(0) }
    var refreshMessage by remember(label) { mutableStateOf("") }
    val hero by produceState<Bitmap?>(null,label,isArtist,revision,artworkRetry,GlideMetroStyle.artworkRevision) {
        value = if (isArtist) loadArtistBackdropPatiently(context, label)
            else withContext(Dispatchers.IO) { songs.firstOrNull()?.let { loadAlbumArt(context,it) } }
    }
    LaunchedEffect(hero, artworkRetry) {
        if (artworkRetry > 0 && OnlineArt.enabled(context) && isArtist) {
            if (hero == null) delay(16000L) // the patient lookup may still be waiting its turn
            refreshMessage = if (hero != null) "" else "no photo found · ${OnlineArt.diagnostic()}"
        }
    }
    var entered by remember(label,isArtist) { mutableStateOf(false) }
    LaunchedEffect(label,isArtist) { entered=false; delay(20L); entered=true }
    val enter by animateFloatAsState(if(entered) 1f else 0f,
        animationSpec=tween(300,easing=FastOutSlowInEasing),label="artistContinuum")
    // v1.10e 0.4: GlideInkWaves is opaque full-screen; the extra Ink fill was overdraw.
    // v1.11f Zune: black page; the artist photo is the whole background under a black scrim.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val heroPhoto = if (isArtist) hero?.takeUnless { it.isRecycled } else null
        if (heroPhoto != null) {
            val img = remember(heroPhoto) { heroPhoto.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color(0x8C000000), .35f to Color(0xB3000000), .7f to Color(0xDB000000), 1f to Color(0xF0000000))))
        }
        Column(Modifier.fillMaxSize().padding(horizontal=23.dp).graphicsLayer {
            alpha=enter; translationY=(1f-enter)*28f*density
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick=onBack), contentAlignment=Alignment.CenterStart) {
                    ZuneIcon("back", Zune.white, Modifier.size(24.dp))
                }
                Text(if(isArtist) "artist" else "album", style=Zune.style(15.sp, FontWeight.Normal, Zune.grey),
                    modifier=Modifier.weight(1f))
                if (isArtist) Box(Modifier.size(44.dp).clickable(onClick=onPortrait), contentAlignment=Alignment.Center) {
                    ZuneIcon("picture", Zune.white, Modifier.size(22.dp))
                }
                Box(Modifier.size(44.dp).clickable {
                        // Retry provider misses, not just the Compose image state.
                        OnlineArt.clearFailure(context, label, songs.map { it.album }.distinct())
                        if (isArtist) GlideArtistEditorial.invalidate(context, label)
                        // v1.10e 0.5: invalidate only this artist/album, not every cover in the app.
                        songs.forEach { missingAlbumArt.remove(it.albumId); artCache.remove(it.albumId) }
                        if (isArtist) { invalidateArtistBackdrop(label); invalidateArtistPortraits(label) }
                        GlideMetroStyle.artworkRevision++
                        artworkRetry++
                        refreshMessage = if (OnlineArt.enabled(context))
                            "looking for photo and biography…" else
                            "Enable verified online artwork in Settings first"
                    }, contentAlignment=Alignment.Center) { ZuneIcon("refresh", Zune.white, Modifier.size(22.dp)) }
            }
            Spacer(Modifier.height(18.dp))
            // Zune: one huge Light line that runs off the edge instead of wrapping.
            // laptop test: album pages show just the album name (the artist is in the line below)
            Text((if (isArtist) label else label.substringBefore(" · ")).lowercase(Locale.getDefault()), style=Zune.style(58.sp, FontWeight.Light),
                lineHeight=62.sp, maxLines=1, softWrap=false, overflow=TextOverflow.Clip,
                // F4a (Zune HD manual): tapping the big title goes back
                modifier=Modifier.metroHeroEntrance().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
                    .wrapContentWidth(Alignment.Start, unbounded=true))
            Spacer(Modifier.height(4.dp))
            val songWord = if (songs.size == 1) "song" else "songs"
            Text(if(isArtist) "${albums.size} ${if (albums.size == 1) "album" else "albums"} · ${songs.size} $songWord"
                else "${songs.firstOrNull()?.artist?.lowercase(Locale.getDefault()) ?: ""} · ${songs.size} $songWord",
                style=Zune.style(14.sp, FontWeight.Normal, Zune.grey))
            if (refreshMessage.isNotBlank()) Text(refreshMessage.lowercase(Locale.getDefault()),
                style=Zune.style(12.sp, FontWeight.Normal, Zune.grey))
            // F1: play / shuffle for this artist or album (opens in your player; its own queue continues after)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.motionTap(onTap = { playFrom(context, songs, 0) }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ZuneIcon("play", Zune.white, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("play", style = Zune.style(20.sp, FontWeight.Light))
                }
                Row(Modifier.motionTap(onTap = { if (songs.isNotEmpty()) shuffleSongs(context, songs) }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ZuneIcon("shuffle", Zune.white, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("shuffle", style = Zune.style(20.sp, FontWeight.Light))
                }
            }
            Spacer(Modifier.height(14.dp))
            if(isArtist) {
                // Zune pivots: selected white, others dim, no underline.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(22.dp)) {
                    listOf("albums","songs","about","related").forEachIndexed { index,name ->
                        Text(name, style=Zune.style(28.sp, FontWeight.Light, if(selectedTab==index) Zune.white else Zune.dim),
                            modifier=Modifier.clickable { onTab(index) }.padding(vertical=4.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (isArtist) {
                // Directional Metro page motion. The artist hero/header stay anchored,
                // only the selected section moves. No per-row animations while scrolling.
                val artistAlbumsScroll = rememberLazyListState()
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(label, selectedTab) {
                        var swipePx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { swipePx = 0f },
                            onHorizontalDrag = { change, delta ->
                                swipePx += delta
                                change.consume() // Prevent the outer panorama taking this gesture
                            },
                            onDragEnd = {
                                val minimum = 64f * density
                                if (swipePx < -minimum && selectedTab < 3) onTab(selectedTab + 1)
                                else if (swipePx > minimum && selectedTab > 0) onTab(selectedTab - 1)
                                swipePx = 0f
                            },
                            onDragCancel = { swipePx = 0f }
                        )
                    },
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(
                            animationSpec = tween(285, easing = FastOutSlowInEasing),
                            initialOffsetX = { width -> (width * 0.22f).roundToInt() * direction }
                        ) + fadeIn(animationSpec = tween(230))) togetherWith
                        (slideOutHorizontally(
                            animationSpec = tween(245, easing = FastOutSlowInEasing),
                            targetOffsetX = { width -> -(width * 0.16f).roundToInt() * direction }
                        ) + fadeOut(animationSpec = tween(195)))
                    },
                    label = "GlideArtistTabMetroMotion"
                ) { tab ->
                when {
                    tab==3 -> GlideRelatedArtists(context, label, onArtist)
                    tab==2 -> Column(Modifier.fillMaxWidth().metroElastic(true)
                        .verticalScroll(rememberScrollState())) {
                        Text("biography", style=Zune.style(26.sp, FontWeight.Light))
                        Spacer(Modifier.height(10.dp))
                        GlideVerifiedArtistBio(context, label, enabled = OnlineArt.enabled(context), revision = artworkRetry)
                    }
                    tab==0 -> LazyColumn(state = artistAlbumsScroll, modifier = Modifier.fillMaxSize().metroLibraryElastic(artistAlbumsScroll),
                        verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                        itemsIndexed(albums,key={_, it -> it.key}) { i, group ->
                            Row(Modifier.metroStagger(i).fillMaxWidth().motionTap(onTap = {onAlbum(group.key,group.value)})
                                .padding(vertical=6.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                AlbumCover(context,group.value.firstOrNull(),64.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(group.key.lowercase(Locale.getDefault()), style=Zune.style(22.sp, FontWeight.Light),
                                        maxLines=1, softWrap=false, overflow=TextOverflow.Ellipsis)
                                    Text("${group.value.size} ${if (group.value.size == 1) "song" else "songs"}",
                                        style=Zune.style(12.sp, FontWeight.Normal, Zune.grey))
                                }
                            }
                        }
                    }
                    else -> SongsList(context,songs,onSongLong)
                }
                }
                // Bottom strip intentionally belongs to Glide's outer panorama.
                Spacer(Modifier.height(24.dp))
            } else {
                SongsList(context, songs, onSongLong)
            }
        }
    }
}

@Composable
private fun LibraryPage(context: Context, onDetailChanged: (Boolean) -> Unit) {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) }
    val requester = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var index by remember { mutableStateOf(cachedMusicIndex ?: MusicIndex(emptyList(), emptyList())) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var artistCoverTarget by remember { mutableStateOf<String?>(null) }
    val artworkScope = rememberCoroutineScope()
    var artistCoverRevision by remember { mutableIntStateOf(0) }
    val artistCoverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri ->
        val artist = artistCoverTarget
        if (uri != null && artist != null) {
            val target = artistCoverFile(context, artist)
            artworkScope.launch {
              try {
                withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.parentFile?.mkdirs()
                    // A 5 MB cap prevents enormous imported artwork from
                    // crowding device storage. Re-read as reduced bitmap at display.
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > 5 * 1024 * 1024) throw IllegalArgumentException("Artist artwork exceeds 5 MB")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                }
                invalidateArtistBackdrop(artist)
                invalidateArtistPortraits(artist)
                artistCoverRevision++
              } catch (_: Exception) {
                target.delete()
                GlideToast.show("Unable to import that artwork")
              }
            }
        }
        artistCoverTarget = null
    }
    var focus by remember { mutableStateOf<Pair<String, List<Song>>?>(null) }
    val currentDetailCallback by rememberUpdatedState(onDetailChanged)
    LaunchedEffect(focus != null) { currentDetailCallback(focus != null) }
    DisposableEffect(Unit) { onDispose { currentDetailCallback(false) } }
    var focusIsArtist by remember { mutableStateOf(false) }
    var collectionMode by remember { mutableStateOf<String?>(null) }
    var collectionRevision by remember { mutableIntStateOf(0) }
    var artistTab by remember { mutableIntStateOf(0) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var artistMenu by remember { mutableStateOf<String?>(null) }
    var albumWall by remember { mutableStateOf(false) }
    // F4a: open an artist / album by name (related pivot, home pins, "new")
    fun songsOfArtist(name: String) = index.artistGroups.entries.firstOrNull { it.key.equals(name, true) }?.value
        ?: index.songs.filter { it.artist == name || OnlineArt.primaryArtist(it.artist).equals(name, true) }
    fun openArtistByName(name: String) {
        val songs = songsOfArtist(name)
        if (songs.isEmpty()) { GlideToast.show("not in your collection"); return }
        GlideMotionState.drillAt = android.os.SystemClock.elapsedRealtime()
        focus = name to songs; focusIsArtist = true; artistTab = 0
    }
    val playlistRequest = GlideLocalLists.openPlaylist.value
    LaunchedEffect(playlistRequest) {
        playlistRequest?.let { name ->
            focus = null
            collectionMode = "playlist:" + name
            GlideLocalLists.openPlaylist.value = null
        }
    }
    val openRequest = GlideOpen.request.value
    LaunchedEffect(openRequest, index) {
        val r = openRequest ?: return@LaunchedEffect
        if (index.songs.isEmpty()) return@LaunchedEffect
        GlideOpen.request.value = null
        if (r.isArtist) openArtistByName(r.name) else {
            val songs = index.songs.filter { it.album == r.name && (r.artist.isBlank() || it.artist == r.artist) }
            if (songs.isEmpty()) GlideToast.show("not in your collection")
            else { GlideMotionState.drillAt = android.os.SystemClock.elapsedRealtime(); focus = r.name to songs; focusIsArtist = false }
        }
    }
    var songDetails by remember { mutableStateOf(false) }
    var editSong by remember { mutableStateOf<Song?>(null) }
    var techDetails by remember { mutableStateOf("Reading metadata…") }
    LaunchedEffect(menuSong, songDetails) {
        if (songDetails && menuSong != null) {
            techDetails = withContext(Dispatchers.IO) { readTechnicalDetails(context, menuSong!!) }
        } else techDetails = "Reading metadata…"
    }
    val sections = remember { listOf("artists", "albums", "songs", "genres") }
    // Artist and album grouping is built once with the MediaStore query on IO,
    // rather than grouping all tracks during a layout or page transition.
    val artistGroups = index.artistGroups
    val albumGroups = index.albumGroups
    val genreGroups = remember(index.genres) { index.genres.associate { it.name to it.songs } }
    val matchingSongs by produceState(initialValue = index.songs, index.songs, query) {
        if (query.isBlank()) { value = index.songs }
        else {
            delay(90L) // Avoid filtering all tracks on every keypress.
            value = withContext(Dispatchers.Default) {
                index.songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            }
        }
    }
    val tabs = rememberPagerState(initialPage = 0, pageCount = { sections.size })
    LaunchedEffect(focus) { if (focus != null) GlideMotionState.drillAt = SystemClock.elapsedRealtime() }
    var lastTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(tabs.settledPage) { if (tabs.settledPage != lastTab) { lastTab = tabs.settledPage; GlideFeedback.tick(context) } }
    // F2: leaving Library (swiping to another page) returns it to its default: collection, artists tab, no search
    LaunchedEffect(GlideNav.libraryReset.intValue) {
        if (GlideNav.libraryReset.intValue == 0) return@LaunchedEffect
        focus = null; collectionMode = null; focusIsArtist = false; artistTab = 0; query = ""
        tabs.scrollToPage(0)
    }
    // The slow genre/member MediaStore joins are needed only when Genres is visible.
    LaunchedEffect(granted, tabs.currentPage, index.songs) {
        if (granted && tabs.currentPage == 3 && index.songs.isNotEmpty() && !cachedGenresLoaded) {
            val loaded = withContext(Dispatchers.IO) { readGenres(context, index.songs) }
            index = index.copy(genres = loaded)
            cachedMusicIndex = index
            cachedGenresLoaded = true
        }
    }
    val scope = rememberCoroutineScope()
    BackHandler(focus != null || collectionMode != null) { focus = null; collectionMode = null; focusIsArtist = false }
    val currentIndex by rememberUpdatedState(index)
    DisposableEffect(context, granted) {
        if (!granted) return@DisposableEffect onDispose {}
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pending = mutableSetOf<Long>()
        var full = false
        var scan: kotlinx.coroutines.Job? = null
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val id = uri?.lastPathSegment?.toLongOrNull()
                if (id == null) full = true else pending.add(id)
                scan?.cancel()
                scan = scope.launch {
                    delay(350L)
                    if (full || currentIndex.songs.isEmpty()) {
                        full = false
                        pending.clear()
                        cachedMusicIndex = null
                        cachedGenresLoaded = false
                        reloadTick++
                    } else {
                        val ids = pending.toSet()
                        val previous = currentIndex.songs
                        val updated = withContext(Dispatchers.IO) {
                            val changed = ids.flatMap { readSongs(context, it) }
                            buildMusicIndex((previous.filterNot { it.id in ids } + changed)
                                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }))
                        }
                        pending.removeAll(ids)
                        cachedMusicIndex = updated
                        cachedGenresLoaded = false
                        index = updated
                    }
                }
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        onDispose {
            scan?.cancel()
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    LaunchedEffect(granted, reloadTick) {
        if (granted) {
            loading = true
            try {
                index = withContext(Dispatchers.IO) { getOrLoadMusicIndex(context) }
                error = null
            }
            catch (_: Exception) { error = "Media database could not be read" }
            loading = false
        }
    }
    // E: drill-down like Zune/WP — the list slides left and fades, the artist/album page comes in from the
    // right; back is the exact reverse.
    AnimatedContent(targetState = focus, transitionSpec = {
        val dir = if (targetState != null) 1 else -1
        (slideInHorizontally(tween(260, delayMillis = 60, easing = MetroEaseOut)) { (it * 0.17f).roundToInt() * dir } + fadeIn(tween(260, delayMillis = 60, easing = MetroEaseOut)))
            .togetherWith(slideOutHorizontally(tween(150, easing = MetroEaseIn)) { -(it * 0.12f).roundToInt() * dir } + fadeOut(tween(150, easing = MetroEaseIn)))
    }, label = "drill") { shown ->
    if (shown != null) {
        MetroArtistAlbumPage(context, shown.first, shown.second, focusIsArtist, artistTab,
            onTab = { artistTab = it },
            onBack = { focus = null; focusIsArtist = false },
            onAlbum = { label, songs -> focus = label to songs; focusIsArtist = false },
            onSongLong = { menuSong = it },
            onPortrait = { artistCoverTarget = shown.first; artistCoverPicker.launch("image/*") },
            revision = artistCoverRevision,
            onArtist = { name -> openArtistByName(name) })
    } else Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (focus != null || collectionMode != null) {
                Box(Modifier.size(44.dp).motionTap(onTap = { focus = null; collectionMode = null; focusIsArtist = false }),
                    contentAlignment = Alignment.CenterStart) { ZuneIcon("back", Zune.white, Modifier.size(24.dp)) }
            }
            Text(focus?.first ?: collectionMode ?: "collection", style = Zune.style(34.sp, FontWeight.Light),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable {
                cachedMusicIndex = null
                cachedGenresLoaded = false
                missingAlbumArt.clear()
                artCache.evictAll()
                reloadTick++
            }, contentAlignment = Alignment.Center) { ZuneIcon("refresh", Zune.grey, Modifier.size(20.dp)) }
        }
        if (!granted) {
            Spacer(Modifier.height(15.dp))
            Text("Allow local audio access to browse songs, artists, albums and genres. No files are uploaded.", color = Subtle)
            Spacer(Modifier.height(18.dp))
            Text("allow music access", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.clickable { requester.launch(permission) })
            return@Column
        }
        if (loading) { MetroFlyingDots(Modifier.fillMaxWidth()); return@Column }
        if (error != null) { Text(error!!, color = Subtle); return@Column }
        var showHealth by remember { mutableStateOf(false) }
        if (showHealth) {
            val health by produceState<List<String>?>(null, index.songs) {
                value = withContext(Dispatchers.IO) {
                    val songs = index.songs
                    val possibleDuplicates = songs.groupingBy {
                        Triple(it.artist.trim().lowercase(Locale.ROOT), it.album.trim().lowercase(Locale.ROOT),
                            it.title.trim().lowercase(Locale.ROOT))
                    }.eachCount().count { it.value > 1 }
                    val unreadable = songs.count { song ->
                        runCatching {
                            context.contentResolver.openFileDescriptor(song.uri, "r")?.use { true } ?: false
                        }.getOrDefault(false).not()
                    }
                    listOf(
                        "${songs.size} indexed tracks",
                        "$possibleDuplicates possible duplicate groups by artist, album and title",
                        "$unreadable tracks could not be opened",
                        "${songs.count { it.albumId <= 0L }} tracks without an album artwork reference"
                    )
                }
            }
            AlertDialog(
                onDismissRequest = { showHealth = false },
                title = { Text("Library health") },
                text = { Column {
                    health?.forEach { Text(it, modifier = Modifier.padding(vertical = 4.dp)) }
                        ?: Text("Checking local tracks…")
                } },
                confirmButton = { TextButton(onClick = { showHealth = false }) { Text("Done") } }
            )
        }
        var showFixer by remember { mutableStateOf(false) }
        if (showFixer) GlideLibraryFixerSheet(context, onClose = { showFixer = false }, onChanged = {
            cachedMusicIndex = null; cachedGenresLoaded = false; reloadTick++
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index.songs.size} songs on this phone", style = Zune.style(13.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.weight(1f))
            Text("health", style = Zune.style(13.sp, FontWeight.Normal, Zune.magenta),
                modifier = Modifier.clickable { showHealth = true }.padding(vertical = 6.dp))
            // B3: library fixer (inside Glide only)
            Text("fix library", style = Zune.style(13.sp, FontWeight.Normal, Zune.magenta),
                modifier = Modifier.clickable { showFixer = true }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
            // v1.11k: one tap = get every missing cover and artist photo now (tap again to stop)
            Text(if (GlideArtBatch.running) "artwork ${GlideArtBatch.done} / ${GlideArtBatch.total} · stop" else "get all artwork",
                style = Zune.style(13.sp, FontWeight.Normal, Zune.magenta),
                modifier = Modifier.clickable {
                    if (GlideArtBatch.running) GlideArtBatch.cancel()
                    else if (OnlineArt.enabled(context)) GlideArtBatch.start(context)
                    else GlideToast.show("Turn on online artwork in Settings first")
                }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 12.dp))
        }
        Spacer(Modifier.height(12.dp))
        BasicTextField(query, onValueChange = { query = it }, singleLine = true,
            cursorBrush = SolidColor(Blue), textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
                .drawBehind { drawLine(Zune.dim, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .padding(vertical = 11.dp),
            decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically) {
                ZuneIcon("search", Zune.grey, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                Box { if (query.isEmpty()) Text("search songs, artists, albums", style = Zune.style(15.sp, FontWeight.Normal, Zune.grey)); inner() }
            } })
        Spacer(Modifier.height(12.dp))
        if (focus == null && collectionMode == null) {
            Text("your lists", style = Zune.style(22.sp, FontWeight.Light, Zune.grey))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlideLocalLists.playlistNames(context).forEach { name ->
                    Text(name, style = Zune.style(17.sp, FontWeight.Light),
                        modifier = Modifier.clickable { collectionMode = "playlist:" + name }.padding(vertical = 8.dp))
                }
                listOf("quick list", "saved queue", "favorites", "most opened", "recent opens").forEach { name ->
                    Text(name, style = Zune.style(17.sp, FontWeight.Light),
                        modifier = Modifier.clickable { collectionMode = name }.padding(vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            // Zune sub-pivots: big Light words that run off the right edge.
            Row(Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                sections.forEachIndexed { n, label ->
                    Text(label, style = Zune.style(30.sp, FontWeight.Light, if (tabs.currentPage == n) Zune.white else Zune.dim),
                        maxLines = 1, softWrap = false,
                        // F4b: tap "albums" again (or hold it) for the album wall
                        modifier = Modifier.motionTap(onTap = {
                            if (n == 1 && tabs.currentPage == 1) albumWall = true else scope.launch { tabs.animateScrollToPage(n) }
                        }, onLongPress = if (n == 1) ({ albumWall = true }) else null))
                }
            }
            Spacer(Modifier.height(14.dp))
            // v1.10e 0.9: neighbouring Library tab stays composed (artists <-> albums <-> songs <-> genres).
            HorizontalPager(tabs, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                when (page) {
                    0 -> LibraryGroups(context, artistGroups, query, true,
                        onSelect = { name, list -> focus = name to list; focusIsArtist = true; artistTab = 0 }, onSongLong = { menuSong = it },
                        onArtistCover = { artist -> artistMenu = artist },
                        artistRevision = artistCoverRevision)
                    1 -> LibraryGroups(context, albumGroups, query, false,
                        onSelect = { name, list -> focus = name to list; focusIsArtist = false }, onSongLong = { menuSong = it })
                    2 -> SongsList(context, matchingSongs, onSongLong = { menuSong = it })
                    else -> LibraryGroups(context, genreGroups, query, false,
                        onSelect = { name, list -> focus = name to list; focusIsArtist = false }, onSongLong = { menuSong = it })
                }
            }
        } else if (collectionMode != null) {
            val ids = remember(collectionMode, collectionRevision, index.songs) {
                val mode = collectionMode.orEmpty()
                if (mode.startsWith("playlist:")) GlideLocalLists.playlistIds(context, mode.removePrefix("playlist:"))
                else GlideLocalLists.idsFor(context, mode)
            }
            val byId = remember(index.songs) { index.songs.associateBy { it.id } }
            val tracks = ids.mapNotNull { byId[it] }
            Text("${tracks.size} ${if (tracks.size == 1) "song" else "songs"}", style = Zune.style(13.sp, FontWeight.Normal, Zune.grey))
            Spacer(Modifier.height(12.dp))
            if (collectionMode == "quick list") {
                Text("clear quick list", style = Zune.style(17.sp, FontWeight.Light, Zune.grey),
                    modifier = Modifier.clickable { GlideLocalLists.clearQuick(context); collectionRevision++ }
                        .padding(vertical = 9.dp))
            }
            collectionMode?.takeIf { it.startsWith("playlist:") }?.removePrefix("playlist:")?.let { name ->
                GlideLocalLists.pinRevision.intValue
                Text(if (GlideLocalLists.pinnedPlaylist(context) == name) "unpin from home" else "pin playlist to home",
                    style = Zune.style(17.sp, FontWeight.Light, Zune.magenta),
                    modifier = Modifier.clickable { GlideLocalLists.togglePlaylistPin(context, name) }.padding(vertical = 8.dp))
            }
            SongsList(context, tracks, onSongLong = { menuSong = it },
                onReorder = if (collectionMode == "quick list") { from, to ->
                    GlideLocalLists.moveQuick(context, from, to)
                    collectionRevision++
                } else null)
        }
    }
    }
    if (albumWall) {
        val wallAlbums = remember(albumGroups) { albumGroups.entries.sortedBy { it.key.lowercase(Locale.ROOT) }.map { it.key to it.value } }
        GlideAlbumWall(context, wallAlbums, onOpen = { name, list ->
            GlideMotionState.drillAt = SystemClock.elapsedRealtime(); focus = name to list; focusIsArtist = false
        }, onClose = { albumWall = false })
    }
    artistMenu?.let { artist ->
        // v1.11k: artist long-press menu (replaces the picture icon on every row)
        MetroMenu(onDismissRequest = { artistMenu = null },
            title = { Text(artist.lowercase(Locale.getDefault()), style = Zune.style(30.sp, FontWeight.Light), maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis) },
            text = { Column {
                val pinned = GlidePins.isPinned(context, GlidePin(true, artist, ""))
                listOf("open", "play", "shuffle", if (pinned) "unpin from home" else "pin to home", "add artist to quick list",
                    "choose artist photo", "find photo and bio again").forEach { item ->
                    Text(item, style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.fillMaxWidth().clickable {
                        artistMenu = null
                        val songs = index.artistGroups[artist] ?: index.songs.filter { it.artist == artist }
                        when (item) {
                            "open" -> { focus = artist to songs; focusIsArtist = true; artistTab = 0 }
                            "play" -> playFrom(context, songs, 0)
                            "shuffle" -> { if (songs.isNotEmpty()) shuffleSongs(context, songs) }
                            "pin to home", "unpin from home" -> GlidePins.toggle(context, GlidePin(true, artist, ""))
                            "add artist to quick list" -> { GlideLocalLists.addQuick(context, songs.map { it.id }); collectionRevision++ }
                            "choose artist photo" -> { artistCoverTarget = artist; artistCoverPicker.launch("image/*") }
                            else -> {
                                OnlineArt.clearFailure(context, artist, songs.map { it.album }.distinct())
                                GlideArtistEditorial.invalidate(context, artist)
                                invalidateArtistBackdrop(artist); invalidateArtistPortraits(artist)
                                GlideMetroStyle.artworkRevision++; artistCoverRevision++
                            }
                        }
                    }.padding(vertical = 9.dp))
                }
            } },
            confirmButton = { TextButton(onClick = { artistMenu = null }) { Text("close", style = Zune.style(17.sp, FontWeight.Normal)) } }
        )
    }
    menuSong?.let { song ->
        MetroMenu(onDismissRequest = { menuSong = null },
            title = { Text(song.title.lowercase(Locale.getDefault()), style = Zune.style(30.sp, FontWeight.Light), maxLines = 2) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).metroElastic(true).verticalScroll(rememberScrollState())) {
                    Text("${song.artist} · ${song.album}".lowercase(Locale.getDefault()), style = Zune.style(14.sp, FontWeight.Normal, Zune.grey))
                    Spacer(Modifier.height(12.dp))
                    Text("play", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.fillMaxWidth().clickable {
                        menuSong = null; playSong(context, song)
                    }.padding(vertical = 9.dp))
                    if (GlideEngine.enabled(context)) {
                        Text("play next", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.fillMaxWidth().clickable {
                            menuSong = null; GlideEngine.playNext(context, song)
                        }.padding(vertical = 9.dp))
                        Text("add to now playing", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.fillMaxWidth().clickable {
                            menuSong = null; GlideEngine.addToQueue(context, song)
                        }.padding(vertical = 9.dp))
                    }
                    Text("add to quick list", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            GlideLocalLists.addQuick(context, listOf(song.id))
                            collectionRevision++; menuSong = null
                            GlideToast.show("Added without interrupting music")
                        }.padding(vertical = 9.dp))
                    Text("add album to quick list", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            GlideLocalLists.addQuick(context, index.songs
                                .filter { it.album == song.album && it.artist == song.artist }.map { it.id })
                            collectionRevision++; menuSong = null
                        }.padding(vertical = 9.dp))
                    Text("add artist to quick list", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            GlideLocalLists.addQuick(context, index.songs.filter { it.artist == song.artist }.map { it.id })
                            collectionRevision++; menuSong = null
                        }.padding(vertical = 9.dp))
                    Text(if (GlideLocalLists.isFavorite(context, song.id)) "remove favorite" else "add favorite", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            GlideLocalLists.toggleFavorite(context, song.id)
                            collectionRevision++; menuSong = null
                        }.padding(vertical = 9.dp))
                    Text("mix heart: " + when (GlideMixData.heart(context,song.id)) {
                        GlideMixData.Heart.LOVE -> "love"
                        GlideMixData.Heart.NEVER -> "never in shuffle"
                        GlideMixData.Heart.NEUTRAL -> "neutral"
                    }, style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            val next = GlideMixData.cycle(context,song.id)
                            collectionRevision++; menuSong = null
                            GlideToast.show("mix: " + next.name.lowercase(Locale.getDefault()))
                        }.padding(vertical = 9.dp))
                    Text("open with poweramp", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            menuSong = null; playSongWith(context, song, "com.maxmpz.audioplayer")
                        }.padding(vertical = 9.dp))
                    Text("go to album", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            focus = song.album to index.songs.filter { it.album == song.album && it.artist == song.artist }
                            focusIsArtist = false; collectionMode = null
                            menuSong = null
                        }.padding(vertical = 9.dp))
                    Text(if (GlidePins.isPinned(context, GlidePin(false, song.album, song.artist))) "unpin album from home" else "pin album to home",
                        style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            GlidePins.toggle(context, GlidePin(false, song.album, song.artist)); menuSong = null
                        }.padding(vertical = 9.dp))
                    Text("go to artist", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            focus = song.artist to index.songs.filter { it.artist == song.artist }
                            focusIsArtist = true; artistTab = 0; collectionMode = null
                            menuSong = null
                        }.padding(vertical = 9.dp))
                    Text("edit display tags", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            editSong = song; menuSong = null
                        }.padding(vertical = 9.dp))
                    Text("details", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable { songDetails = !songDetails }
                            .padding(vertical = 9.dp))
                    if (songDetails) {
                        Text("Title: ${song.title}\nArtist: ${song.artist}\nAlbum: ${song.album}\n" +
                            "Duration: ${elapsedLabel(song.duration)}\nFile: ${song.uri}\n" + techDetails,
                            color = Subtle, fontSize = 12.sp)
                    }
                    Text("share file", style = Zune.style(22.sp, FontWeight.Light),
                        modifier = Modifier.fillMaxWidth().clickable {
                            menuSong = null; shareSong(context, song)
                        }.padding(vertical = 9.dp))
                    Text("${TimeUnit.MILLISECONDS.toMinutes(song.duration)} min · Local audio", color = Subtle, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { menuSong = null }) { Text("close", style = Zune.style(17.sp, FontWeight.Normal)) } }
        )
    }
    editSong?.let { song ->
        var title by remember(song.id) { mutableStateOf(song.title) }
        var artist by remember(song.id) { mutableStateOf(song.artist) }
        var album by remember(song.id) { mutableStateOf(song.album) }
        AlertDialog(onDismissRequest = { editSong = null }, containerColor = GlideMetroStyle.ink,
            title = { Text("display metadata", color = White, fontSize = 24.sp, fontWeight = FontWeight.Light) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Changes are local to Glide. Your audio files remain untouched.", color = Subtle, fontSize = 12.sp)
                    OutlinedTextField(title, { title = it.take(160) }, label = { Text("Track") }, singleLine = true)
                    OutlinedTextField(artist, { artist = it.take(160) }, label = { Text("Artist") }, singleLine = true)
                    OutlinedTextField(album, { album = it.take(160) }, label = { Text("Album") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = {
                context.getSharedPreferences("glide_sidecar_tags", Context.MODE_PRIVATE).edit()
                    .putString(sidecarKey(song.id, "title"), title.trim().ifBlank { song.title })
                    .putString(sidecarKey(song.id, "artist"), artist.trim().ifBlank { song.artist })
                    .putString(sidecarKey(song.id, "album"), album.trim().ifBlank { song.album })
                    .apply()
                cachedMusicIndex = null
                cachedGenresLoaded = false
                index = MusicIndex(emptyList(), emptyList())
                reloadTick++
                editSong = null
            }) { Text("save", style = Zune.style(17.sp, FontWeight.Normal)) } },
            dismissButton = { TextButton(onClick = { editSong = null }) { Text("cancel", style = Zune.style(17.sp, FontWeight.Normal, Zune.grey)) } }
        )
    }
}

// Windows Phone alphabet jump: a drag-sensitive A-Z rail plus a tappable
// full alphabet jump grid.  Only visible list rows are composed.
private val JumpLetters = listOf("#") + ('A'..'Z').map(Char::toString)
private fun firstLetter(label: String): String {
    val c = label.trimStart().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else "#"
}

@Composable
private fun LetterRail(onJump: (String) -> Unit, onOpenGrid: () -> Unit) {
    val railContext = LocalContext.current
    var last by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    LaunchedEffect(last) {
        if (last.isNotEmpty()) {
            preview = true
            delay(550L)
            preview = false
        }
    }
    Box(Modifier.width(34.dp).fillMaxHeight()) {
    Column(
        modifier = Modifier.width(34.dp).fillMaxHeight()
            .pointerInput(onJump) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val i = ((offset.y / size.height.toFloat()) * JumpLetters.size)
                            .toInt().coerceIn(0, JumpLetters.lastIndex)
                        last = JumpLetters[i]; onJump(last); GlideFeedback.tick(railContext)
                    },
                    onVerticalDrag = { change, _ ->
                        val i = ((change.position.y / size.height.toFloat()) * JumpLetters.size)
                            .toInt().coerceIn(0, JumpLetters.lastIndex)
                        val letter = JumpLetters[i]
                        if (letter != last) { last = letter; onJump(letter); GlideFeedback.tick(railContext) }
                        change.consume()
                    }
                )
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        JumpLetters.forEach { letter ->
            Text(letter, color = if (last == letter) Zune.white else Zune.grey,
                fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Normal,
                modifier = Modifier.clickable { last = letter; onJump(letter) })
        }
        Box(Modifier.size(24.dp).clickable(onClick = onOpenGrid), contentAlignment = Alignment.Center) {
            ZuneIcon("apps", Zune.grey, Modifier.size(12.dp))
        }
    }
    // Large typographic target directly beside the rail, without a blocking dialog.
    if (preview && last.isNotEmpty()) {
        Box(
            Modifier.align(Alignment.CenterEnd).offset(x = (-38).dp)
                .size(72.dp).background(Zune.magenta),
            contentAlignment = Alignment.Center
        ) {
            Text(last, color = White, fontSize = 39.sp, fontWeight = FontWeight.Light)
        }
    }
    }
}

@Composable
private fun AlphabetGrid(onSelect: (String) -> Unit, onClose: () -> Unit, available: Set<String> = JumpLetters.toSet()) {
    // E: Windows Phone jump list — zooms in, accent tiles for letters you have, empty letters greyed and inactive.
    val zoom = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { zoom.animateTo(1f, tween(220, easing = MetroEaseOut)) }
    val gridScope = rememberCoroutineScope()
    val gridContext = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(GlideMetroStyle.ink.copy(alpha = .94f)).clickable { onClose() }) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 18.dp).graphicsLayer {
                    val s = 1.15f - .15f * zoom.value; scaleX = s; scaleY = s; alpha = zoom.value },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JumpLetters.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { letter ->
                            val on = letter in available
                            Box(Modifier.size(64.dp).background(if (on) Zune.magenta else Zune.dim.copy(alpha = .12f))
                                .then(if (on) Modifier.clickable {
                                    GlideFeedback.tick(gridContext)
                                    gridScope.launch { zoom.animateTo(0f, tween(200, easing = MetroEaseIn)); onSelect(letter) } // E2: zoom back in
                                } else Modifier),
                                contentAlignment = Alignment.BottomStart) {
                                Text(letter.lowercase(Locale.ROOT), style = Zune.style(30.sp, FontWeight.Light,
                                    if (on) androidx.compose.ui.graphics.Color.White else Zune.grey),
                                    modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryGroups(
    context: Context, groups: Map<String, List<Song>>, query: String, artists: Boolean,
    onSelect: (String, List<Song>) -> Unit, onSongLong: (Song) -> Unit,
    onArtistCover: ((String) -> Unit)? = null, artistRevision: Int = 0
) {
    val entries by produceState<List<Map.Entry<String, List<Song>>>>(emptyList(), groups, query) {
        if(query.isNotBlank()) delay(90L)
        value = withContext(Dispatchers.Default) {
            groups.entries.filter { query.isBlank() || it.key.contains(query,true) ||
                it.value.any { song -> song.title.contains(query,true) } }
                .sortedBy { it.key.lowercase(Locale.ROOT) }
        }
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showGrid by remember { mutableStateOf(false) }
    val jumpTargets = remember(entries) { entries.mapIndexedNotNull { index, entry ->
        val letter = firstLetter(entry.key)
        if (index == 0 || letter != firstLetter(entries[index - 1].key)) letter to index else null
    }.toMap() }
    val jump: (String) -> Unit = { letter ->
        val target = jumpTargets[letter] ?: entries.indexOfFirst { firstLetter(it.key) >= letter }
            .takeIf { it >= 0 } ?: entries.lastIndex
        if (target >= 0) scope.launch { state.scrollToItem(target) }
    }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(state = state, modifier = Modifier.weight(1f).metroLibraryElastic(state), flingBehavior = if (GlideDiag.has(256)) androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior() else rememberMetroFling(),
                contentPadding = PaddingValues(end = 5.dp, bottom = 30.dp)) {
                itemsIndexed(entries, key = { _, it -> it.key }) { i, group ->
                    Row(Modifier.metroStagger(i).fillMaxWidth().motionTap(onTap = { onSelect(group.key, group.value) },
                        onLongPress = { if (artists && onArtistCover != null) onArtistCover(group.key) else group.value.firstOrNull()?.let(onSongLong) })
                        .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (artists) ArtistPortrait(context, group.key, artistRevision)
                        else AlbumCover(context, group.value.firstOrNull(), 65.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text((if (artists) group.key else group.key.substringBefore(" · ")).lowercase(Locale.getDefault()), style = Zune.style(22.sp, FontWeight.Light),
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            val by = if (artists) "" else group.key.substringAfter(" · ", "").let { if (it.isBlank()) "" else "${it.lowercase(Locale.getDefault())} · " }
                            Text("$by${group.value.size} ${if (group.value.size == 1) "song" else "songs"}",
                                style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), maxLines = 1)
                        }

                    }
                }
            }
            if (entries.size >= 30) LetterRail(onJump = jump, onOpenGrid = { showGrid = true }) // short lists: no A-Z rail
        }
    }
    if (showGrid) AlphabetGrid(onSelect = { jump(it); showGrid = false }, onClose = { showGrid = false }, available = jumpTargets.keys)
}

@Composable
private fun SongsList(context: Context, songs: List<Song>, onSongLong: (Song) -> Unit,
    onReorder: ((Long, Long) -> Unit)? = null) {
    if (songs.isEmpty()) {
        Text("no songs", style = Zune.style(22.sp, FontWeight.Light, Zune.grey), modifier = Modifier.padding(top = 20.dp))
        return
    }
    // MediaStore song index and search subsets already preserve title ordering.
    val ordered = songs
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showGrid by remember { mutableStateOf(false) }
    val jumpTargets by produceState<Map<String,Int>>(emptyMap(),ordered) {
        value = withContext(Dispatchers.Default) {
            ordered.mapIndexedNotNull { index, song ->
                val letter=firstLetter(song.title)
                if(index==0 || letter!=firstLetter(ordered[index-1].title)) letter to index else null
            }.toMap()
        }
    }
    val jump: (String) -> Unit = { letter ->
        val target = jumpTargets[letter] ?: ordered.indexOfFirst { firstLetter(it.title) >= letter }
            .takeIf { it >= 0 } ?: ordered.lastIndex
        if (target >= 0) scope.launch { state.scrollToItem(target) }
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.weight(1f).metroLibraryElastic(state), flingBehavior = if (GlideDiag.has(256)) androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior() else rememberMetroFling(),
            contentPadding = PaddingValues(end = 5.dp, bottom = 32.dp)) {
            itemsIndexed(ordered) { i, song ->
                // P1 (Zune): tapping a song plays the whole list from that song
                Row(Modifier.metroStagger(i).fillMaxWidth().motionTap(onTap = { playFrom(context, ordered, i) },
                    onLongPress = { onSongLong(song) }).padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (onReorder != null) {
                        Text("≡", style = Zune.style(24.sp, FontWeight.Normal, Zune.light),
                            modifier = Modifier.pointerInput(song.id, i, ordered) {
                                var distance = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { distance = 0f },
                                    onDragEnd = {
                                        val visible = state.layoutInfo.visibleItemsInfo
                                        val source = visible.firstOrNull { it.index == i }
                                        if (source != null) {
                                            val destination = source.offset + source.size / 2f + distance
                                            val target = visible.minByOrNull {
                                                kotlin.math.abs(it.offset + it.size / 2f - destination)
                                            }
                                            ordered.getOrNull(target?.index ?: -1)?.let { onReorder(song.id, it.id) }
                                        }
                                    }
                                ) { change, amount -> change.consume(); distance += amount }
                            }.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                    AlbumCover(context, song, 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title.lowercase(Locale.getDefault()), style = Zune.style(20.sp, FontWeight.Light),
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text("${song.artist} · ${song.album}".lowercase(Locale.getDefault()),
                            style = Zune.style(12.sp, FontWeight.Normal, Zune.grey),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(40.dp).clickable { onSongLong(song) }, contentAlignment = Alignment.Center) {
                        ZuneIcon("more", Zune.grey, Modifier.size(18.dp))
                    }
                }
            }
        }
        if (songs.size >= 30) LetterRail(onJump = jump, onOpenGrid = { showGrid = true })
    }
    if (showGrid) AlphabetGrid(onSelect = { jump(it); showGrid = false }, onClose = { showGrid = false }, available = jumpTargets.keys)
}


/** Lightweight platform trace sections: readable in Android Studio/System Trace. */
private inline fun <T> tracedGlide(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try { block() } finally { Trace.endSection() }
}

/** Load the only data needed for Artists, Albums and Songs first. */
private fun readMusicIndex(context: Context): MusicIndex = tracedGlide("Glide.MediaStoreSongs") {
    buildMusicIndex(readSongs(context))
}
private fun buildMusicIndex(songs: List<Song>): MusicIndex {
    // R (laptop test): "a.r. rahman, javed ali, …" and "a. r. rahman / alka yagnik" join their lead artist, like the Zune
    val dots = Regex("\\.\\s+")
    val leadNames = HashMap<String, String>()
    return MusicIndex(songs, emptyList(),
        artistGroups = songs.groupBy { s ->
            val lead = OnlineArt.primaryArtist(s.artist).trim().ifBlank { s.artist }
            leadNames.getOrPut(lead.replace(dots, ".").lowercase(Locale.ROOT)) { lead.replace(dots, ".") }
        },
        albumGroups = songs.groupBy { "${it.album} · ${it.artist}" })
}

/** Genre membership is a separate, potentially expensive set of provider queries. */
private fun readGenres(context: Context, songs: List<Song>): List<GenreGroup> {
    val byId = songs.associateBy { it.id }
    val genres = mutableListOf<GenreGroup>()
    try {
        context.contentResolver.query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Unknown genre"
                val members = mutableListOf<Song>()
                try {
                    context.contentResolver.query(MediaStore.Audio.Genres.Members.getContentUri("external", id),
                        arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)?.use { m ->
                        while (m.moveToNext()) byId[m.getLong(0)]?.let(members::add)
                    }
                } catch (_: Exception) { /* Some providers omit genre membership. */ }
                if (members.isNotEmpty()) genres.add(GenreGroup(name, members))
            }
        }
    } catch (_: Exception) { /* Genre metadata is optional. */ }
    return genres
}

private fun sidecarKey(id: Long, key: String): String = "tag_${id}_$key"
private fun readTag(context: Context, id: Long, key: String, original: String): String =
    context.getSharedPreferences("glide_sidecar_tags", Context.MODE_PRIVATE)
        .getString(sidecarKey(id, key), null)?.takeIf { it.isNotBlank() } ?: original

private fun readSongs(context: Context, onlyId: Long? = null): List<Song> {
    val result = ArrayList<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID)
    val music = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val selection = if (onlyId == null) music else "$music AND ${MediaStore.Audio.Media._ID} = ?"
    context.contentResolver.query(collection, columns, selection, onlyId?.let { arrayOf(it.toString()) },
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) {
            val songId = c.getLong(id)
            result.add(Song(songId, readTag(context, songId, "title", c.getString(title) ?: "Unknown track"),
                readTag(context, songId, "artist", c.getString(artist) ?: "Unknown artist"), readTag(context, songId, "album", c.getString(album) ?: "Unknown album"),
                c.getLong(duration), ContentUris.withAppendedId(collection, songId), c.getLong(albumId)))
        }
    }
    return result
}

private fun playSongWith(context: Context, song: Song, pkg: String) {
    try {
        launchWithFade(context, Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(song.uri, "audio/*")
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        GlideLocalLists.recordLaunch(context, song.id)
    } catch (_: Exception) { GlideToast.show("Player could not open track") }
}

private fun shareSong(context: Context, song: Song) {
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, song.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share audio file"))
    } catch (_: Exception) { GlideToast.show("Sharing unavailable") }
}

/** F1: start a random song of this list and ask the player to shuffle (LG Music / Poweramp honour it when they can). */
private fun shuffleSongs(context: Context, songs: List<Song>) {
    val eligible = songs.filter { GlideMixData.heart(context,it.id) != GlideMixData.Heart.NEVER }
    if (eligible.isEmpty()) { GlideToast.show("No songs eligible for shuffle"); return }
    if (GlideEngine.enabled(context)) { GlideEngine.playList(context, eligible, eligible.indices.random(), shuffle = true); return }
    playSong(context, eligible.random())
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(ComponentName(context, PlaybackListenerService::class.java)).firstOrNull()?.let { c ->
                MediaSessionCompat.Token.fromToken(c.sessionToken)?.let { token ->
                    MediaControllerCompat(context, token).transportControls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
                }
            }
        }
    }, 1500L)
}

/** P1: play [songs] from [index] — Glide's own player when it's on, otherwise the old one-song hand-off. */
internal fun playFrom(context: Context, songs: List<Song>, index: Int) {
    if (songs.isEmpty()) return
    if (GlideEngine.enabled(context)) GlideEngine.playList(context, songs, index) else playSong(context, songs[index.coerceIn(0, songs.lastIndex)])
}

private fun playSong(context: Context, song: Song) {
    if (GlideEngine.enabled(context)) { GlideEngine.playList(context, listOf(song), 0); return }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(song.uri, "audio/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        launchWithFade(context, intent)
        GlideLocalLists.recordLaunch(context, song.id)
    } catch (_: Exception) { GlideToast.show("No player accepted this track; try LG Music") }
}

/** E: Windows Phone turnstile. Opening an app swings Glide's pages away on a hinge at the left edge,
 *  then the app opens; coming back to Glide swings them back in. */
internal object GlideIntroOnce { @Volatile var shown = false }

/** F2: when you swipe away from a page, its inner screens (album, artist, Now Playing details) close. */
internal object GlideNav {
    val nowPlayingReset = androidx.compose.runtime.mutableIntStateOf(0)
    val libraryReset = androidx.compose.runtime.mutableIntStateOf(0)
}

internal object GlideMotion {
    val turn = androidx.compose.animation.core.Animatable(0f)
    // AndroidUiDispatcher.Main carries a frame clock, which Animatable needs outside composition
    private val main = kotlinx.coroutines.CoroutineScope(androidx.compose.ui.platform.AndroidUiDispatcher.Main + kotlinx.coroutines.SupervisorJob())
    fun enabled(context: Context) = context.getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getBoolean("motion", true)
    fun out(context: Context, then: () -> Unit) {
        if (!enabled(context) || context !is Activity) { then(); return }
        main.launch {
            turn.animateTo(1f, tween(180, easing = MetroEaseIn))
            then()
            delay(1500L) // the app did not open (or opened over us and came straight back): swing back in
            if ((context as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) back()
        }
    }
    /** F4a 7.6: after the Zune curtain lifts, the panorama swings in on its hinge */
    fun swingIn(context: Context) {
        if (!enabled(context)) return
        main.launch { turn.snapTo(1f); delay(60L); turn.animateTo(0f, tween(300, easing = MetroEaseOut)) }
    }
    fun back() {
        if (turn.value == 0f) return
        main.launch { turn.animateTo(0f, tween(260, easing = MetroEaseOut)) }
    }
}

internal fun launchApp(context: Context, packageName: String) {
    val intent = if (packageName == "glide:camera") Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        else if (packageName == "com.android.settings") Intent(Settings.ACTION_SETTINGS)
        else context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) { GlideToast.show("App not available"); return }
    GlideMotion.out(context) {
        try { launchWithFade(context, intent) }
        catch (_: Exception) { GlideToast.show("Unable to open app"); GlideMotion.back() }
    }
}

private fun launchWithFade(context: Context, intent: Intent) {
    if (context is Activity) {
        val options = ActivityOptions.makeCustomAnimation(
            context, R.anim.glide_push_in, R.anim.glide_push_out
        )
        context.startActivity(intent, options.toBundle())
    } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun openAppInfo(context: Context, pkg: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$pkg"))
    try { launchWithFade(context, intent) }
    catch (_: Exception) { GlideToast.show("App info unavailable") }
}

/** Animated press feedback and haptic long press without extra dependencies. */
internal fun Modifier.motionTap(
    enabled: Boolean = true,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null
): Modifier = composed {
    // E: Metro tilt — the item tips toward where the finger landed (3D), 60 ms in, 180 ms back.
    var pressed by remember { mutableStateOf(false) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    val k by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 60 else 180), label = "tilt"
    )
    val view = androidx.compose.ui.platform.LocalView.current
    this.graphicsLayer {
        val s = 1f - .015f * k
        scaleX = s; scaleY = s
        rotationY = tx * 6f * k
        rotationX = -ty * 4f * k
        cameraDistance = 12f * density
    }
        .pointerInput(enabled, onTap, onLongPress) {
            if (enabled) detectTapGestures(
                onPress = { pos ->
                    tx = ((pos.x / size.width.coerceAtLeast(1)) * 2f - 1f).coerceIn(-1f, 1f)
                    ty = ((pos.y / size.height.coerceAtLeast(1)) * 2f - 1f).coerceIn(-1f, 1f)
                    pressed = true; GlideFeedback.tap(view); tryAwaitRelease(); pressed = false },
                onTap = { onTap() },
                onLongPress = onLongPress?.let { callback ->
                    { GlideFeedback.hold(view); callback() }
                }
            )
        }
}

@Composable
internal fun rememberPlayerInfo(context: Context, resumeTick: Int, positionVisible: State<Boolean>? = null): PlayerInfo {
    var info by remember { mutableStateOf(PlayerInfo()) }
    val listenerComponent = remember { ComponentName(context, PlaybackListenerService::class.java) }
    DisposableEffect(context, resumeTick) {
        val artworkScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val handler = Handler(Looper.getMainLooper())
        val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
        var lastController: MediaController? = null
        var lastArtwork: Bitmap? = null
        var artworkGeneration = 0
        var lastTitle = "Nothing playing"
        var lastArtist = "Start playback in your music app"
        var lastAlbum = ""
        var lastDuration = 0L
        val playerPrefs = context.getSharedPreferences("glide_player_selection", 0)
        val knownPlaybackStates = mutableMapOf<MediaController, Int>()
        var stickyController: MediaController? = null

        fun updateState(
            controllers: List<MediaController>,
            metadataChanged: Boolean = false,
            controllerHint: MediaController? = null,
            stateHint: PlaybackState? = null
        ) {
            // A callback from a stale LG Music session must not steal Poweramp's UI.
            // Switch automatically on a transition INTO PLAYING, not on every position tick.
            val preferredPackage = playerPrefs.getString("preferred", "AUTO") ?: "AUTO"
            val playing = controllers.filter { controller ->
                (if (controller === controllerHint && stateHint != null) stateHint else controller.playbackState)
                    ?.state == PlaybackState.STATE_PLAYING
            }
            val hintedStarted = if (controllerHint != null && stateHint != null) {
                val before = knownPlaybackStates[controllerHint]
                val transitioned = stateHint.state == PlaybackState.STATE_PLAYING &&
                    before != PlaybackState.STATE_PLAYING
                knownPlaybackStates[controllerHint] = stateHint.state
                transitioned
            } else false
            if (preferredPackage == "AUTO" && hintedStarted && controllerHint in playing) {
                stickyController = controllerHint
            }
            if (stickyController !in controllers) stickyController = null
            val manual = controllers.firstOrNull { it.packageName == preferredPackage }
            // P1: when Glide's own player is playing, Glide shows it
            val engine = playing.firstOrNull { it.packageName == GlideEngine.PKG }
            val selected = if (engine != null) engine
                else if (manual != null) manual
                else if (stickyController in playing) stickyController
                // K1 fix: when nothing is playing (Poweramp paused/stopped), stay on the player you used last
                // instead of jumping to LG Music's idle session.
                else if (playing.isEmpty() && stickyController in controllers) stickyController
                else if (playing.isEmpty() && lastController in controllers) lastController
                else playing.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
                    ?: controllers.firstOrNull { it.metadata != null }
                    ?: controllers.firstOrNull()
            if (preferredPackage == "AUTO" && selected in playing) stickyController = selected
            val state = if (selected === controllerHint && stateHint != null) stateHint else selected?.playbackState
            val controllerChanged = selected !== lastController
            // Playback-state notifications are frequent. Never pull a large Bitmap
            // from MediaMetadata again unless the selected session or tags changed.
            if (selected !== lastController || metadataChanged) {
                lastController = selected
                val metadata = selected?.metadata
                lastTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing"
                lastArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: "Start playback in your music app"
                lastAlbum = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                lastDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                lastArtwork = null
                // Clear old cover immediately; never associate previous art with the new track.
                if (info.artwork != null) info = info.copy(artwork = null)
                val generation = ++artworkGeneration
                artworkScope.launch {
                    val bitmap = (metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART))?.let(::boundedSessionArt)
                    handler.post {
                        if (generation == artworkGeneration && lastController === selected) {
                            lastArtwork = bitmap
                            if (bitmap != null) info = info.copy(artwork = bitmap)
                        }
                    }
                }
            }
            // Most external players dispatch position-only updates. Those are useful
            // on Now Playing but force unrelated Home/Library composition to revisit
            // player UI. The visible player still gets every event.
            if (positionVisible?.value == false && !controllerChanged && !metadataChanged &&
                info.controller === selected &&
                info.playing == (state?.state == PlaybackState.STATE_PLAYING) &&
                info.actions == (state?.actions ?: 0L) &&
                info.speed == (state?.playbackSpeed ?: 1f) &&
                (info.playing || info.position == (state?.position?.coerceAtLeast(0L) ?: 0L))) return
            val updated = PlayerInfo(
                controller = selected,
                title = lastTitle,
                artist = lastArtist,
                album = lastAlbum,
                artwork = lastArtwork,
                playing = state?.state == PlaybackState.STATE_PLAYING,
                source = when (selected?.packageName) {
                    "com.lge.music" -> "LG MUSIC"
                    "com.maxmpz.audioplayer" -> "POWERAMP"
                    null -> ""
                    else -> selected.packageName
                },
                actions = state?.actions ?: 0L,
                duration = lastDuration,
                position = state?.position?.coerceAtLeast(0L) ?: 0L,
                speed = state?.playbackSpeed ?: 1f,
                updatedAt = state?.lastPositionUpdateTime ?: 0L
            )
            if (updated != info) info = updated
        }

        fun refresh() {
            val active = try { manager.getActiveSessions(listenerComponent) }
                catch (_: SecurityException) { emptyList() }
            callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
            callbacks.clear()
            knownPlaybackStates.keys.retainAll(active.toSet())
            active.forEach { controller ->
                if (controller !in knownPlaybackStates) {
                    knownPlaybackStates[controller] = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                }
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        updateState(active, controllerHint = controller, stateHint = state)
                    }
                    override fun onMetadataChanged(metadata: MediaMetadata?) { updateState(active, true) }
                    override fun onSessionDestroyed() { handler.post { refresh() } }
                }
                controller.registerCallback(callback, handler)
                callbacks[controller] = callback
            }
            updateState(active, true)
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { handler.post { refresh() } }
        try { manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler) }
        catch (_: SecurityException) { /* User can grant access through Settings. */ }
        val playerSelectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) { refresh() }
        }
        ContextCompat.registerReceiver(context, playerSelectionReceiver,
            IntentFilter("com.gharaudio.launcher.SELECT_PLAYER"), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        onDispose {
            context.unregisterReceiver(playerSelectionReceiver)
            artworkGeneration++
            artworkScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
            callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        }
    }
    return info
}


/** Copy picked images privately (max 8 MB each); returns how many were usable. Blocking. */
internal fun importImagesInto(context: Context, uris: List<Uri>, dir: java.io.File): Int {
    var n = 0
    uris.forEachIndexed { i, uri ->
        val target = java.io.File(dir, "g_${System.currentTimeMillis()}_$i.img")
        val tmp = java.io.File(target.path + ".partial")
        try {
            var length = 0
            context.contentResolver.openInputStream(uri)?.use { src ->
                tmp.outputStream().use { out ->
                    val block = ByteArray(16384)
                    while (true) {
                        val r = src.read(block); if (r < 0) break
                        length += r; if (length > 8 * 1024 * 1024) throw IllegalArgumentException()
                        out.write(block, 0, r)
                    }
                }
            }
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, opt)
            if (opt.outWidth >= 64 && opt.outHeight >= 64 && tmp.renameTo(target)) n++ else tmp.delete()
        } catch (_: Exception) { tmp.delete() }
    }
    return n
}


/** Metro motion curves (his spec): arrivals = exponential ease-out, departures = exponential ease-in. */
internal val MetroEaseOut = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
internal val MetroEaseIn = androidx.compose.animation.core.CubicBezierEasing(0.7f, 0f, 0.84f, 0f)
internal val MetroSnap = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)


/** Glide Engine settings (P1–P5). Everything applies at once except "hi-res (pure)", which applies when the player next starts. */
@Composable
private fun GlidePlayerSettings(context: Context) {
    val prefs = remember { GlideFx.prefs(context) }
    fun save(block: android.content.SharedPreferences.Editor.() -> Unit) { prefs.edit().apply(block).apply(); GlideFx.apply(context) }
    ZuneSettingsGroup("glide player")
    var engineOn by remember { mutableStateOf(prefs.getBoolean("glide_engine", true)) }
    ZuneToggle("glide player", "glide plays your songs itself: tap a song for the whole list, play next, gapless. off = hand songs to lg music / poweramp", engineOn) {
        engineOn = it; save { putBoolean("glide_engine", it) }
    }
    if (!engineOn) return
    val fxRev = GlideFx.revision.intValue
    var report by remember(fxRev) { mutableStateOf(GlideFx.outputReport(context)) }
    Text(report, style = Zune.style(13.sp, FontWeight.Normal, Zune.light), modifier = Modifier.padding(vertical = 6.dp))
    Text("check again", style = Zune.style(16.sp, FontWeight.Light, Zune.magenta),
        modifier = Modifier.clickable { report = GlideFx.outputReport(context) }.padding(vertical = 4.dp))
    var pure by remember { mutableStateOf(prefs.getBoolean("engine_pure", false)) }
    ZuneToggle("hi-res (pure)", "float output for 24-bit / hi-res files, no equalizer, levelling or sing. applies when the player next starts", pure) {
        pure = it; save { putBoolean("engine_pure", it) }; GlideToast.show("applies when the player next starts")
    }
    var stemMode by remember { mutableStateOf(prefs.getBoolean("engine_stems", false)) }
    ZuneToggle("sing v2 stem packs", "requires laptop-separated vocal/instrumental pack for each track; vocals change, accompaniment stays at unity. pure mode uses the original file. select the track again after switching", stemMode) {
        stemMode = it; save { putBoolean("engine_stems", it) }
        GlideToast.show("reopen a track to apply stem playback")
    }
    val activeStem = GlideStems.isStemPlaying(GlideFx.player)
    var stemCount by remember { mutableIntStateOf(GlideStems.availableCount(context)) }
    Text(if (activeStem) "sing v2 stems active · accompaniment stays at unity" else "sing: standard mix; centre reduction is NOT true vocal isolation", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
    Text("$stemCount valid laptop stem packs on device", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
    Text("refresh stem packs", style = Zune.style(16.sp, FontWeight.Light, Zune.magenta),
        modifier = Modifier.clickable { stemCount = GlideStems.availableCount(context) }.padding(vertical = 4.dp))
    if (stemMode && stemCount == 0) {
        Text("no valid four-channel packs: Glide keeps original playback; instruments cannot be isolated with the fallback", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey))
    }
    ZuneSettingsAction("equalizer", "headphone presets and AutoEq in Wavelet") {
        launchApp(context, "com.pittvandewitt.wavelet")
    }
    var rg by remember { mutableStateOf(prefs.getBoolean("engine_rg", false)) }
    ZuneToggle("volume levelling", "uses the replaygain tags in your files so loud and quiet songs play at a similar level", rg) {
        rg = it; save { putBoolean("engine_rg", it) }
    }
    var fade by remember { mutableStateOf(prefs.getBoolean("engine_fade", false)) }
    ZuneToggle("fade between songs", "3 s fade out and in (not zune; turns gapless into a short fade)", fade) {
        fade = it; save { putBoolean("engine_fade", it) }
    }
    var overlap by remember { mutableStateOf(prefs.getBoolean("engine_overlap", false)) }
    ZuneToggle("overlapping crossfade", "experimental · local playback at normal speed with fade and audio effects off", overlap) {
        overlap = it
        if (it) fade = false
        save { putBoolean("engine_overlap", it); if (it) putBoolean("engine_fade", false) }
    }
    Text("speed", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.padding(top = 10.dp))
    var speed by remember { mutableStateOf(prefs.getFloat("engine_speed", 1f)) }
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        GlideFx.speeds.forEach { v ->
            Text("${v}x", style = Zune.style(18.sp, if (speed == v) FontWeight.Bold else FontWeight.Light, if (speed == v) Zune.magenta else Zune.white),
                modifier = Modifier.clickable { speed = v; save { putFloat("engine_speed", v) } })
        }
    }
    Text("sleep timer", style = Zune.style(22.sp, FontWeight.Light), modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlideFx.sleepChoices.forEach { m ->
            Text(when (m) { 0 -> "off"; -1 -> "end of song"; -2 -> "end of album"; else -> "$m min" }, style = Zune.style(18.sp, FontWeight.Light),
                modifier = Modifier.clickable { GlideFx.setSleep(context, m) })
        }
    }
    Text("sing microphone is on now playing; true vocal control needs a laptop stem pack", style = Zune.style(12.sp, FontWeight.Normal, Zune.grey), modifier = Modifier.padding(top = 10.dp))
    Text("open audio files from other apps with glide", style = Zune.style(16.sp, FontWeight.Light, Zune.magenta),
        modifier = Modifier.padding(vertical = 8.dp).clickable {
            GlideToast.show("open any audio file and choose glide › always")
        })
}
