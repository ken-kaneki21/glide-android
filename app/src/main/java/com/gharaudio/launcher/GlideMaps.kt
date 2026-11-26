package com.gharaudio.launcher

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.text.TextWatcher
import android.text.Editable
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.EditText
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** App-owned saved places. Never writes or changes music metadata. */
internal object GlidePlaces {
    private const val PREF = "glide_places_v1"
    private const val KEY = "places"
    fun load(c: Context): List<String> = runCatching {
        val json = JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        (0 until minOf(json.length(), 40)).mapNotNull { i -> json.optString(i).takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())
    fun save(c: Context, names: List<String>) {
        val json = JSONArray()
        names.distinct().take(40).forEach { json.put(it) }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}

internal data class GlideMapPlace(val name: String, val lat: Double, val lon: Double)
internal data class GlideTurn(val lat: Double, val lon: Double, val instruction: String, val meters: Double)
internal data class GlideRoute(val points: List<GeoPoint>, val summary: String, val turns: List<GlideTurn>)

/** Explicit searches only. Nominatim usage: max one request/s, no autocomplete, valid UA. */
internal object GlideMapNetwork {
    private const val UA = "GlideDeviceEdition/1.41 (com.gharaudio.launcher; personal Android client)"
    @Volatile private var lastSearch = 0L
    @Volatile private var lastRoute = 0L
    private fun request(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    @Synchronized fun search(text: String): List<GlideMapPlace> {
        val now = android.os.SystemClock.elapsedRealtime()
        val remaining = 1100L - (now - lastSearch)
        if (remaining > 0) Thread.sleep(remaining)
        lastSearch = android.os.SystemClock.elapsedRealtime()
        val q = java.net.URLEncoder.encode(text.trim().take(160), "UTF-8")
        val json = JSONArray(request("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=$q"))
        return (0 until json.length()).mapNotNull { i ->
            val obj = json.optJSONObject(i) ?: return@mapNotNull null
            val lat = obj.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = obj.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            GlideMapPlace(obj.optString("display_name"), lat, lon)
        }
    }
    /** Public OSRM demo service: best-effort directions for two selected points, not offline navigation. */
    @Synchronized fun route(start: GlideMapPlace, end: GlideMapPlace): GlideRoute {
        val now = android.os.SystemClock.elapsedRealtime()
        val pause = 1100L - (now - lastRoute)
        if (pause > 0) Thread.sleep(pause)
        lastRoute = android.os.SystemClock.elapsedRealtime()
        val url = "https://router.project-osrm.org/route/v1/driving/${start.lon},${start.lat};${end.lon},${end.lat}?overview=full&geometries=geojson&steps=true"
        val root = JSONObject(request(url))
        val route = root.optJSONArray("routes")?.optJSONObject(0) ?: throw IllegalStateException("No route found")
        val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until geometry.length()).map { i ->
            val point = geometry.getJSONArray(i)
            GeoPoint(point.getDouble(1), point.getDouble(0))
        }
        val km = route.optDouble("distance") / 1000.0
        val min = route.optDouble("duration") / 60.0
        val legs = route.optJSONArray("legs")
        val turns = mutableListOf<GlideTurn>()
        if (legs != null) for (j in 0 until legs.length()) {
            val steps = legs.optJSONObject(j)?.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val maneuver = step.optJSONObject("maneuver") ?: continue
                val ll = maneuver.optJSONArray("location") ?: continue
                val kind = maneuver.optString("type", "continue")
                val side = maneuver.optString("modifier").replace(' ', '-')
                val road = step.optString("name").ifBlank { "the road" }
                val instruction = when (kind) {
                    "depart" -> "Head ${side.ifBlank { "out" }} on $road"
                    "arrive" -> "Arrive at your destination"
                    "turn", "fork", "merge", "roundabout", "on ramp", "off ramp" ->
                        "${kind.replaceFirstChar { it.uppercaseChar() }} ${side.replace('-', ' ')} onto $road"
                    else -> "Continue on $road"
                }
                turns.add(GlideTurn(ll.getDouble(1), ll.getDouble(0), instruction, step.optDouble("distance", 0.0)))
            }
        }
        return GlideRoute(points, String.format(Locale.US, "%.1f km · %.0f min (estimated)", km, min), turns)
    }
}

/** Native MapView embedded in Glide. No Google SDK, Google app, or Google map URLs. */
@Composable
internal fun GlideMapsScreen(context: Context, onClose: () -> Unit) {
    val dark = !GlideMetroStyle.lightTheme
    val bg = if (dark) Color(0xFF101014) else Color(0xFFF4F4F4)
    val foreground = if (dark) Color.White else Color(0xFF151515)
    val muted = if (dark) Color(0xFFAAAAAA) else Color(0xFF555555)
    val accent = GlideMetroStyle.accent
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GlideMapPlace>>(emptyList()) }
    var selected by remember { mutableStateOf<GlideMapPlace?>(null) }
    var origin by remember { mutableStateOf<GlideMapPlace?>(null) }
    var route by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var turns by remember { mutableStateOf<List<GlideTurn>>(emptyList()) }
    var routeSummary by remember { mutableStateOf("") }
    var navigationActive by remember { mutableStateOf(false) }
    var followPosition by remember { mutableStateOf(true) }
    var lastPosition by remember { mutableStateOf<GlideMapPlace?>(null) }
    var turnInstruction by remember { mutableStateOf("") }
    var turnIndex by remember { mutableIntStateOf(0) }
    var pickingOrigin by remember { mutableStateOf(false) }
    val locationManager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun currentPosition(): GlideMapPlace? {
        if (!hasLocationPermission()) return null
        return runCatching {
            val fixes = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { locationManager.isProviderEnabled(it) }
                .mapNotNull { locationManager.getLastKnownLocation(it) }
            fixes.maxByOrNull { it.time }?.let { GlideMapPlace("my location", it.latitude, it.longitude) }
        }.getOrNull()
    }
    var message by remember { mutableStateOf("search a place to drop a pin") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "location permitted · tap my location again" else "location denied · select a starting pin instead"
    }
    fun requestGps() {
        if (!hasLocationPermission()) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            val fix = currentPosition()
            if (fix == null) message = "waiting for GPS · turn on Location and try again"
            else { origin = fix; message = "start: my location · select your destination" }
        }
    }
    var saved by remember { mutableStateOf(GlidePlaces.load(context)) }
    DisposableEffect(navigationActive, context) {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastPosition = GlideMapPlace("my location", location.latitude, location.longitude)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { message = "GPS disabled · guidance paused" }
        }
        if (navigationActive && hasLocationPermission()) {
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) runCatching { locationManager.requestLocationUpdates(provider, 1800L, 4f, listener) }
        }
        onDispose { runCatching { locationManager.removeUpdates(listener) } }
    }
    LaunchedEffect(lastPosition, navigationActive, turns) {
        val here = lastPosition ?: return@LaunchedEffect
        if (!navigationActive || turns.isEmpty()) return@LaunchedEffect
        val distance = FloatArray(1)
        var index = turnIndex.coerceIn(0, turns.lastIndex)
        Location.distanceBetween(here.lat, here.lon, turns[index].lat, turns[index].lon, distance)
        if (distance[0] < 35f && index < turns.lastIndex) {
            index++
            turnIndex = index
        }
        val next = turns[index]
        Location.distanceBetween(here.lat, here.lon, next.lat, next.lon, distance)
        turnInstruction = "${distance[0].toInt()} m · ${next.instruction}"
    }
    // GPU-friendly grayscale/night palette inspired by Windows Maps; full-resolution OSM tiles preserved.
    val darkTiles = remember {
        ColorMatrixColorFilter(floatArrayOf(
            -0.07f, -0.23f, -0.03f, 0f, 97f,
            -0.07f, -0.23f, -0.03f, 0f, 105f,
            -0.07f, -0.23f, -0.03f, 0f, 119f,
            0f, 0f, 0f, 1f, 0f
        ))
    }
    val tiles = remember { XYTileSource("GlideOSM", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")) }
    val map = remember(context) {
        Configuration.getInstance().userAgentValue = "GlideDeviceEdition/1.41 (com.gharaudio.launcher)"
        MapView(context).apply {
            setTileSource(tiles)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            controller.setZoom(5.2)
            controller.setCenter(GeoPoint(22.9734, 78.6569))
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
            // OSM's 256px tiles were previously expanded by this device's high DPI,
            // making the map appear oversized and blurry. Keep them at native resolution.
            setTilesScaledToDpi(false)
            setTilesScaleFactor(1.0f)
            setUseDataConnection(true)
            // Map gestures must never take keyboard focus from the destination input.
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }
    // Direct map selection does not depend on geocoding, GPS, or Google services.
    DisposableEffect(map) {
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean =
                pickingOrigin && longPressHelper(p)
            override fun longPressHelper(p: GeoPoint): Boolean {
                val name = String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude)
                val chosen = GlideMapPlace(name, p.latitude, p.longitude)
                if (pickingOrigin) { origin = chosen; pickingOrigin = false; message = "starting point set · choose destination" }
                else { selected = chosen; message = "destination pin dropped · $name" }
                route = emptyList(); turns = emptyList(); navigationActive = false
                return true
            }
        })
        map.overlays.add(0, events)
        onDispose { map.overlays.remove(events) }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    LaunchedEffect(map, origin, selected, route) {
        map.overlays.removeAll { it is Marker || it is Polyline }
        if (route.isNotEmpty()) {
            map.overlays.add(Polyline().apply {
                setPoints(route)
                outlinePaint.color = AndroidColor.rgb(0, 184, 224)
                outlinePaint.strokeWidth = 9f
            })
        }
        listOf("start" to origin, "destination" to selected).forEach { (label, place) ->
            if (place != null) map.overlays.add(Marker(map).apply {
                position = GeoPoint(place.lat, place.lon)
                title = "$label: ${place.name}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        map.invalidate()
    }
    fun showPlace(p: GlideMapPlace) {
        if (pickingOrigin) { origin = p; pickingOrigin = false; message = "starting point set · choose destination" }
        else { selected = p; message = "destination: ${p.name}" }
        route = emptyList(); turns = emptyList(); navigationActive = false
        map.controller.animateTo(GeoPoint(p.lat, p.lon), 14.0, 450L)
        map.invalidate()
    }
    fun runSearch(text: String) {
        if (text.isBlank()) { message = "type a destination"; return }
        // Coordinates work offline: "12.9716, 77.5946" (latitude, longitude).
        val coords = Regex("^\\s*([+-]?\\d{1,2}(?:\\.\\d+)?)\\s*[,;]\\s*([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*$")
            .matchEntire(text.trim())
        if (coords != null) {
            val lat = coords.groupValues[1].toDoubleOrNull()
            val lon = coords.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                results = emptyList()
                showPlace(GlideMapPlace(text.trim(), lat, lon))
                message = "coordinate pin · ${text.trim()}"
                return
            }
            message = "invalid coordinates (lat, lon)"
            return
        }
        message = "searching openstreetmap…"
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { GlideMapNetwork.search(text) } }
            found.onSuccess { places ->
                results = places
                if (places.isEmpty()) message = "no places found" else { showPlace(places.first()); message = "select a result or move the map" }
            }.onFailure { message = "place search unavailable: ${it.message ?: "network error"}" }
        }
    }
    fun previewRoute() {
        val from = origin; val to = selected
        if (from == null) { message = "choose my location or set a start pin"; return }
        if (to == null) { message = "search or drop a destination pin"; return }
        if (from.lat == to.lat && from.lon == to.lon) { message = "start and destination are the same"; return }
        scope.launch {
            navigationActive = false
            message = "calculating route inside Glide…"
            val response = withContext(Dispatchers.IO) { runCatching { GlideMapNetwork.route(from, to) } }
            if (origin != from || selected != to) return@launch
            response.onSuccess { data ->
                route = data.points
                turns = data.turns
                turnIndex = 0
                routeSummary = data.summary
                if (data.points.size > 1) {
                    val north = data.points.maxOf { it.latitude }
                    val south = data.points.minOf { it.latitude }
                    val east = data.points.maxOf { it.longitude }
                    val west = data.points.minOf { it.longitude }
                    map.post { map.zoomToBoundingBox(BoundingBox(north, east, south, west), true, 80) }
                }
                map.invalidate()
                message = "$routeSummary · tap navigate to start GPS guidance"
            }.onFailure {
                message = if (it is javax.net.ssl.SSLException) {
                    "could not secure the routing connection · try another network"
                } else "route unavailable: ${it.message ?: "network problem"}"
            }
        }
    }
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("glide   /   maps", style = Zune.style(27.sp, FontWeight.Light, foreground))
            Text("close ×", style = Zune.style(16.sp, FontWeight.Normal, foreground), modifier = Modifier.clickable(onClick = onClose))
        }
        Text("explore your world", style = Zune.style(14.sp, FontWeight.Normal, muted), modifier = Modifier.padding(start = 18.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            // Native EditText avoids Compose/MapView focus clashes on LG Android 9.
            // It also supports the system IME even when Glide Keyboard is not selected.
            AndroidView(
                factory = { c ->
                    EditText(c).apply {
                        setSingleLine(true)
                        setTextColor(AndroidColor.WHITE)
                        setHintTextColor(AndroidColor.LTGRAY)
                        setTextSize(17f)
                        hint = "place or latitude, longitude"
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        imeOptions = EditorInfo.IME_ACTION_SEARCH
                        setPadding(14, 16, 14, 16)
                        background = GradientDrawable().apply {
                            setColor(if (dark) AndroidColor.rgb(31, 31, 38) else AndroidColor.WHITE)
                            setStroke(2, AndroidColor.rgb(0, 184, 224))
                            cornerRadius = 4f
                        }
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                query = s?.toString()?.take(160).orEmpty()
                            }
                            override fun afterTextChanged(s: Editable?) {}
                        })
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                                runSearch(text.toString())
                                val keyboard = c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                keyboard.hideSoftInputFromWindow(windowToken, 0)
                                clearFocus()
                                true
                            } else false
                        }
                        setOnClickListener {
                            requestFocus()
                            val keyboard = c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            keyboard.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                },
                update = { field ->
                    val wanted = if (dark) AndroidColor.WHITE else AndroidColor.rgb(24, 24, 24)
                    field.setTextColor(wanted)
                    field.setHintTextColor(if (dark) AndroidColor.LTGRAY else AndroidColor.DKGRAY)
                    if (field.text.toString() != query) {
                        val cursor = field.selectionStart.coerceAtLeast(0)
                        field.setText(query)
                        field.setSelection(cursor.coerceAtMost(field.length()))
                    }
                },
                modifier = Modifier.weight(1f).height(54.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text("go", style = Zune.style(23.sp, FontWeight.Normal, accent), modifier = Modifier.clickable { runSearch(query) })
        }
        Text(message, color = muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp))
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp).clipToBounds()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { view ->
                view.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTiles else null)
            })
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("+", color = Color.White, fontSize = 30.sp,
                    modifier = Modifier.background(Color(0xDD202026)).clickable { map.controller.zoomIn() }.padding(horizontal = 14.dp, vertical = 4.dp))
                Text("−", color = Color.White, fontSize = 30.sp,
                    modifier = Modifier.background(Color(0xDD202026)).clickable { map.controller.zoomOut() }.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            Text("hold map to drop a pin", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA171717)).padding(5.dp))
            Text("© OpenStreetMap contributors", fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xCC171717)).padding(5.dp))
        }
        if (results.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 115.dp).padding(horizontal = 18.dp)) {
                items(results) { place ->
                    Text(place.name, color = foreground, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { showPlace(place) }.padding(vertical = 5.dp))
                }
            }
        }
        Text(
            "start: ${origin?.name ?: "not set"}   •   destination: ${selected?.name ?: "not set"}",
            color = foreground, fontSize = 12.sp, maxLines = 2,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("my location", style = Zune.style(16.sp, FontWeight.Normal, accent), modifier = Modifier.clickable {
                requestGps()
            })
            Text(if (pickingOrigin) "tap map…" else "set start pin", style = Zune.style(16.sp, FontWeight.Normal, accent), modifier = Modifier.clickable {
                pickingOrigin = true; message = "tap the map or search to set the starting point"
            })
            Text("route", style = Zune.style(18.sp, FontWeight.Normal, accent), modifier = Modifier.clickable { previewRoute() })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (navigationActive) "stop navigation" else "navigate ▶", style = Zune.style(19.sp, FontWeight.Normal, accent), modifier = Modifier.clickable {
                if (navigationActive) { navigationActive = false; message = "navigation stopped · route retained" }
                else if (route.isEmpty()) { message = "preview a route first" }
                else if (!hasLocationPermission()) { message = "GPS permission required for live navigation"; permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    message = "enable phone Location to start navigation"
                } else {
                    navigationActive = true
                    lastPosition = currentPosition()
                    followPosition = true
                    message = "navigation active · GPS guidance in Glide"
                }
            })
            Text("save", style = Zune.style(17.sp, FontWeight.Normal, accent), modifier = Modifier.clickable {
                val p = selected
                if (p != null) { saved = (listOf(p.name) + saved.filterNot { it == p.name }).take(40); GlidePlaces.save(context, saved); message = "saved locally" }
            })
            if (navigationActive) Text(if (followPosition) "unfollow" else "follow", style = Zune.style(16.sp, FontWeight.Normal, accent), modifier = Modifier.clickable {
                followPosition = !followPosition
            })
        }
        if (route.isNotEmpty()) {
            Text(if (navigationActive) turnInstruction.ifBlank { "Waiting for GPS position…" } else "$routeSummary · ${turns.firstOrNull()?.instruction ?: "route preview"}",
                color = foreground, fontSize = 13.sp, maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp))
        }
        LaunchedEffect(lastPosition, navigationActive, followPosition) {
            val position = lastPosition
            if (navigationActive && followPosition && position != null) {
                map.controller.animateTo(GeoPoint(position.lat, position.lon))
            }
        }
        if (saved.isNotEmpty()) {
            Text("saved", color = muted, fontSize = 12.sp, modifier = Modifier.padding(start = 18.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 60.dp).padding(horizontal = 18.dp)) {
                items(saved) { name ->
                    Text(name, color = foreground, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { query = name; runSearch(name) }.padding(4.dp))
                }
            }
        }
    }
}
