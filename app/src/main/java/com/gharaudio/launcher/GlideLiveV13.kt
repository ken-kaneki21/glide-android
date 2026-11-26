package com.gharaudio.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

private val liveWhite: Color get() = GlideMetroStyle.paper
private val liveBlue: Color get() = Zune.light // v1.11e2: monochrome, Zune-style
private val liveGrey: Color get() = GlideMetroStyle.secondary
private val liveSurface: Color get() = GlideMetroStyle.surface
private data class LiveRow(val title: String, val detail: String, val url: String = "")
private data class LiveBlock(val time: Long, val rows: List<LiveRow>)
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun getJson(url: String): JSONObject {
    require(url.startsWith("https://"))
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 9000
    c.readTimeout = 9000
    c.setRequestProperty("User-Agent", "Glide/1.4 (LG G6; Android 9; local music launcher)")
    c.setRequestProperty("Accept", "application/json")
    try {
        if (c.responseCode !in 200..299) error("Provider HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } finally { c.disconnect() }
}
// Coordinates are used only for an explicitly selected city and only if geocoding fails.
// This avoids mistaking GPS coordinates for the user's location and adds no location permission.
private val knownCities = mapOf(
    "bengaluru" to Pair(12.9716,77.5946), "bangalore" to Pair(12.9716,77.5946),
    "kolkata" to Pair(22.5726,88.3639), "mumbai" to Pair(19.0760,72.8777),
    "delhi" to Pair(28.6139,77.2090), "new delhi" to Pair(28.6139,77.2090),
    "chennai" to Pair(13.0827,80.2707), "hyderabad" to Pair(17.3850,78.4867),
    "pune" to Pair(18.5204,73.8567), "pilani" to Pair(28.3670,75.6041)
)
private fun skyCondition(code: Int): String = when (code) {
    0 -> "Clear"; 1,2,3 -> "Cloudy"; 45,48 -> "Fog";
    in 51..67 -> "Drizzle/rain"; in 71..77 -> "Snow";
    in 80..82 -> "Showers"; in 95..99 -> "Thunderstorm";
    else -> "Weather code $code"
}
private fun weatherRows(city: String): List<LiveRow> {
    val selected = city.trim()
    require(selected.length >= 2) { "Enter a city name (at least 2 characters)." }
    val fallback = knownCities[selected.lowercase(java.util.Locale.ROOT)]
    val geo = try {
        getJson("https://geocoding-api.open-meteo.com/v1/search?name=${enc(selected)}&count=1&format=json")
            .optJSONArray("results")?.optJSONObject(0)?.let { place ->
                val lat=place.optDouble("latitude",Double.NaN)
                val lon=place.optDouble("longitude",Double.NaN)
                if(lat.isNaN() || lon.isNaN()) null else Pair(lat,lon)
            }
    } catch (e: Exception) {
        if(fallback==null) throw IllegalStateException("City lookup: ${e.javaClass.simpleName}: ${e.message ?: "connection failed"}")
        null
    }
    val (lat,lon) = geo ?: fallback ?: throw IllegalStateException("City not found: $selected")
    val doc = getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=3&timezone=auto")
    val current=doc.optJSONObject("current") ?: error("Weather provider returned no current observations")
    val temp=current.optDouble("temperature_2m",Double.NaN)
    require(!temp.isNaN()) { "Weather provider returned no temperature" }
    val citySuffix=if(geo==null) " · city fallback" else ""
    val rows=mutableListOf(LiveRow("${temp.toInt()}°C · ${skyCondition(current.optInt("weather_code",-1))}",
        "$selected$citySuffix · Humidity ${current.optInt("relative_humidity_2m")}% · ${current.optString("time")}"))
    val daily=doc.optJSONObject("daily")
    if(daily!=null) {
        val days=daily.optJSONArray("time")
        val hi=daily.optJSONArray("temperature_2m_max")
        val lo=daily.optJSONArray("temperature_2m_min")
        val codes=daily.optJSONArray("weather_code")
        if(days!=null && hi!=null && lo!=null && codes!=null) {
            for(i in 0 until minOf(3,days.length(),hi.length(),lo.length(),codes.length())) {
                rows.add(LiveRow("${days.optString(i)} · ${skyCondition(codes.optInt(i,-1))}",
                    "High ${hi.optDouble(i).toInt()}°C · Low ${lo.optDouble(i).toInt()}°C"))
            }
        }
    }
    return rows
}
private fun fixtureRows(league: String): List<LiveRow> {
    if (!league.matches(Regex("[0-9]{3,8}"))) return emptyList()
    val arr = getJson("https://www.thesportsdb.com/api/v1/json/123/eventsnextleague.php?id=$league").optJSONArray("events") ?: return emptyList()
    return (0 until minOf(arr.length(), 12)).mapNotNull { i -> arr.optJSONObject(i)?.let { event ->
        val title = event.optString("strEvent")
        if (title.isBlank()) null else LiveRow(title, "${event.optString("dateEvent")} ${event.optString("strTime")} · scheduled fixture")
    } }
}
private fun concertRows(city: String, key: String): List<LiveRow> {
    if (key.isBlank()) return emptyList()
    val arr = getJson("https://app.ticketmaster.com/discovery/v2/events.json?classificationName=music&city=${enc(city)}&size=12&sort=date,asc&apikey=${enc(key)}")
        .optJSONObject("_embedded")?.optJSONArray("events") ?: return emptyList()
    return (0 until minOf(arr.length(), 12)).mapNotNull { i -> arr.optJSONObject(i)?.let { event ->
        val title = event.optString("name")
        if (title.isBlank()) null else {
            val date = event.optJSONObject("dates")?.optJSONObject("start")?.optString("localDate") ?: "Date unknown"
            val venue = event.optJSONObject("_embedded")?.optJSONArray("venues")?.optJSONObject(0)?.optString("name") ?: "Venue unknown"
            LiveRow(title, "$date · $venue", event.optString("url"))
        }
    } }
}
private fun saveBlock(prefs: android.content.SharedPreferences, name: String, block: LiveBlock) {
    val rows = JSONArray()
    block.rows.forEach { rows.put(JSONObject().put("title",it.title).put("detail",it.detail).put("url",it.url)) }
    prefs.edit().putString("live13_$name",JSONObject().put("time",block.time).put("rows",rows).toString()).apply()
}
private fun readBlock(prefs: android.content.SharedPreferences, name: String): LiveBlock? = try {
    val data = JSONObject(prefs.getString("live13_$name", "") ?: "")
    val arr = data.optJSONArray("rows") ?: JSONArray()
    LiveBlock(data.optLong("time"), (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let {
        LiveRow(it.optString("title"),it.optString("detail"),it.optString("url"))
    } })
} catch (_: Exception) { null }

@Composable
private fun LiveInput(label: String, value: String, update: (String) -> Unit) {
    Text(label, color=liveGrey,fontSize=12.sp)
    BasicTextField(value,update,modifier=Modifier.fillMaxWidth()
        .drawBehind { drawLine(Zune.dim, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx()) }
        .padding(vertical=10.dp),
        singleLine=true,textStyle=TextStyle(color=liveWhite,fontSize=15.sp),cursorBrush=SolidColor(liveBlue))
}
@Composable
private fun LiveAction(label: String, onClick: () -> Unit) {
    Text(label,color=liveBlue,fontSize=12.sp,modifier=Modifier.clickable(onClick=onClick).padding(vertical=8.dp))
}

@Composable
internal fun GlideLiveV13(context: Context) {
    val prefs = remember { context.getSharedPreferences("ghar_ui",Context.MODE_PRIVATE) }
    val sections = listOf("music","weather","concerts","sports","artists")
    var city by remember { mutableStateOf(prefs.getString("live_city","Bengaluru") ?: "Bengaluru") }
    var league by remember { mutableStateOf(prefs.getString("live_league","4328") ?: "4328") }
    var ticketKey by remember { mutableStateOf("") } // Never saved or logged; session-only.
    var followName by remember { mutableStateOf("") }
    var followed by remember { mutableStateOf((prefs.getString("live_followed","") ?: "").split("|").filter(String::isNotBlank)) }
    var order by remember { mutableStateOf((prefs.getString("live13_order",sections.joinToString(",")) ?: "").split(",").filter { it in sections }.distinct().let { it+sections.filterNot(it::contains) }) }
    var hidden by remember { mutableStateOf((prefs.getString("live13_hidden", "") ?: "").split(",").toSet()) }
    var edit by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(mapOf<String,String>()) }
    var music by remember { mutableStateOf<List<LiveRelease>>(emptyList()) }
    var musicTime by remember { mutableLongStateOf(prefs.getLong("live_release_updated",0)) }
    var weather by remember { mutableStateOf(readBlock(prefs,"weather")) }
    var weatherCity by remember { mutableStateOf(prefs.getString("live14_weather_city", "") ?: "") }
    var fixtures by remember { mutableStateOf(readBlock(prefs,"sports")) }
    var concerts by remember { mutableStateOf(readBlock(prefs,"concerts")) }
    var followedOnly by remember { mutableStateOf(false) }
    fun saveOrder(a: List<String>) { order=a; prefs.edit().putString("live13_order",a.joinToString(",")).apply() }
    fun saveFollow(a: List<String>) { followed=a; prefs.edit().putString("live_followed",a.joinToString("|")).apply() }
    LaunchedEffect(refresh) {
        loading=true
        val problems=mutableMapOf<String,String>()
        val now=System.currentTimeMillis()
        try {
            music=try { val arr=JSONArray(prefs.getString("live_release_cache","[]") ?: "[]"); (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { LiveRelease(it.optString("title"),it.optString("artist"),it.optString("date"),it.optString("id")) } } } catch (_: Exception) { emptyList() }
            if(refresh>0 || music.isEmpty() || now-musicTime>6*60*60*1000L) {
                try { val fresh=withContext(Dispatchers.IO) { getLatestReleases() }; if(fresh.isNotEmpty()) {
                    music=fresh;musicTime=now;val arr=JSONArray();fresh.forEach { arr.put(JSONObject().put("title",it.title).put("artist",it.artist).put("date",it.date).put("id",it.id)) }
                    prefs.edit().putString("live_release_cache",arr.toString()).putLong("live_release_updated",now).apply()
                } } catch (_: Exception) { problems["music"]="Offline · saved music data" }
            }
            suspend fun query(name:String,old:LiveBlock?,load:()->List<LiveRow>):LiveBlock? {
                if(refresh==0 && old!=null && now-old.time<30*60*1000L) return old
                return try {
                    val rows=withContext(Dispatchers.IO){load()}
                    if(name=="weather" && rows.isEmpty()) error("Weather provider returned no results")
                    LiveBlock(now,rows).also {saveBlock(prefs,name,it)}
                }
                catch (e: Exception) {
                    val reason=(e.message ?: e.javaClass.simpleName).take(110)
                    problems[name]="Could not refresh: $reason${if(old!=null) " · saved data" else ""}"
                    old
                }
            }
            val c=city.trim().ifBlank {"Bengaluru"}
            if (!weatherCity.equals(c,true)) { weather=null; prefs.edit().remove("live13_weather").apply() }
            weather=query("weather",weather){weatherRows(c)}
            if (weather!=null && problems["weather"]==null) {
                weatherCity=c; prefs.edit().putString("live14_weather_city",c).apply()
            }
            fixtures=query("sports",fixtures){fixtureRows(league)}
            if(ticketKey.isNotBlank()) concerts=query("concerts",concerts){concertRows(c,ticketKey)}
            else { concerts=null;problems["concerts"]="Ticketmaster API key required. No concert feed loaded." }
        } finally {errors=problems;loading=false}
    }
    fun open(link:String) { if(link.startsWith("https://")) try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link))) } catch (_:Exception) {} }
    fun timeLabel(t:Long)=if(t<=0L) "Never refreshed" else DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(t))
    LazyColumn(Modifier.fillMaxSize().metroElastic(true),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("live.",color=liveWhite,fontWeight=FontWeight.Light,fontSize=50.sp)
            Text("your music · your city · your world",color=liveBlue,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                LiveAction(if(loading) "UPDATING…" else "REFRESH") {if(!loading) refresh++}
                LiveAction(if(edit) "DONE" else "CUSTOMIZE") {edit=!edit}
            }
            if(edit) {
                LiveInput("City (no location permission)",city) {city=it.take(64);prefs.edit().putString("live_city",city).apply()}
                LiveInput("Sports league ID (4328 = Premier League)",league) {league=it.take(8);prefs.edit().putString("live_league",league).apply()}
                LiveInput("Ticketmaster key (session-only, not stored)",ticketKey) {ticketKey=it.take(180)}
                LiveAction("APPLY CHANGES") {if(!loading) refresh++}
            }
        }
        order.forEach { section ->
            if(edit) item(key="configure-$section") {
                val index=order.indexOf(section)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(section,color=liveWhite,fontSize=17.sp,modifier=Modifier.weight(1f))
                    LiveAction(if(section in hidden) "SHOW" else "HIDE") {hidden=if(section in hidden)hidden-section else hidden+section;prefs.edit().putString("live13_hidden",hidden.joinToString(",")).apply()}
                    LiveAction("↑") {if(index>0){val a=order.toMutableList();a.removeAt(index);a.add(index-1,section);saveOrder(a)}}
                    LiveAction("↓") {if(index<order.lastIndex){val a=order.toMutableList();a.removeAt(index);a.add(index+1,section);saveOrder(a)}}
                }
            }
            if(section !in hidden) {
                item(key="head-$section") {
                    Spacer(Modifier.height(8.dp))
                    Text(when(section){"music"->"new music";"sports"->"sports fixtures";"artists"->"followed artists";else->section},color=liveWhite,fontSize=28.sp,fontWeight=FontWeight.Light)
                    val block=when(section){"weather"->weather;"sports"->fixtures;"concerts"->concerts;else->null}
                    val ts=if(section=="music")musicTime else block?.time ?: 0L
                    Text("${if(ts>0 && System.currentTimeMillis()-ts>30*60*1000L)"SAVED · " else ""}${timeLabel(ts)}",color=liveGrey,fontSize=11.sp)
                    errors[section]?.let {Text(it,color=Color(0xFFFFB184),fontSize=11.sp)}
                    if(section=="sports")Text("Scheduled fixtures only; free API results are limited.",color=liveGrey,fontSize=11.sp)
                    if(section=="music")LiveAction(if(followedOnly)"ALL RELEASES" else "FOLLOWED ONLY") {followedOnly=!followedOnly}
                }
                if(section=="artists") {
                    item {
                        LiveInput("Artist name",followName) {followName=it.take(80)}
                        LiveAction("FOLLOW") { val a=followName.trim();if(a.isNotEmpty() && followed.none {it.equals(a,true)}){saveFollow(followed+a);followName=""} }
                    }
                    items(followed,key={"artist-$it"}) { name ->
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(name,color=liveWhite,fontSize=17.sp,modifier=Modifier.weight(1f))
                            LiveAction("UNFOLLOW") {saveFollow(followed.filterNot {it==name})}
                        }
                    }
                } else {
                    val rows=when(section){
                        "music"-> music.filter { !followedOnly || followed.any {a->it.artist.equals(a,true)} }.take(12).map { LiveRow(it.title,"${it.artist} · ${it.date}","https://musicbrainz.org/release-group/${it.id}") }
                        "weather"->weather?.rows ?: emptyList()
                        "concerts"->concerts?.rows ?: emptyList()
                        "sports"->fixtures?.rows ?: emptyList()
                        else -> emptyList()
                    }
                    if(rows.isEmpty()) item(key="empty-$section") {Text(if(loading)"Loading…" else "No current results",color=liveGrey,fontSize=13.sp)}
                    items(rows,key={section+it.title+it.detail}) { row ->
                        // v1.11e2: Zune list row, no slab and no accent bar.
                        Column(Modifier.fillMaxWidth()
                            .clickable(enabled=row.url.startsWith("https://")){open(row.url)}
                            .padding(vertical=9.dp)) {
                            Text(row.title,style=Zune.style(22.sp,FontWeight.Light),maxLines=1,softWrap=false,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Clip)
                            Text(row.detail,style=Zune.style(12.sp,FontWeight.Normal,Zune.grey),maxLines=1)
                        }
                    }
                }
            }
        }
        item {Text("Sources: MusicBrainz, Open-Meteo, TheSportsDB and optional Ticketmaster. Weather may use documented city-coordinate fallback. No background polling; data timestamps identify saved data.",color=liveGrey,fontSize=11.sp)}
    }
}
