package com.gharaudio.launcher

import android.content.Context

/** Private, bounded, non-destructive local collections. Never alters tags or player queues. */
internal object GlideLocalLists {
    private const val PREF = "glide_local_collections_v1"
    private const val CAP = 5000
    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun ids(raw: String?): List<Long> = raw.orEmpty().split(',')
        .mapNotNull { it.toLongOrNull()?.takeIf { n -> n > 0L } }.distinct().take(CAP)
    private fun read(context: Context, key: String): List<Long> = ids(prefs(context).getString(key, ""))
    private fun save(context: Context, key: String, values: List<Long>) {
        prefs(context).edit().putString(key, values.distinct().take(CAP).joinToString(",")).apply()
    }
    fun addQuick(context: Context, values: List<Long>) {
        save(context, "quick", read(context, "quick") + values.filter { it > 0L })
    }
    fun saveQueue(context: Context, values: List<Long>) {
        val ids = values.filter { it > 0L }.take(CAP)
        prefs(context).edit().putString("saved_queue", ids.joinToString(",")).apply()
    }
    fun moveQuick(context: Context, id: Long, target: Long) {
        val queue = read(context, "quick").toMutableList()
        val from = queue.indexOf(id)
        val to = queue.indexOf(target)
        if (from < 0 || to < 0 || from == to) return
        queue.add(to, queue.removeAt(from))
        save(context, "quick", queue)
    }

    private const val PLAYLIST = "playlist:"
    fun playlistNames(context: Context): List<String> = prefs(context).all.keys
        .filter { it.startsWith(PLAYLIST) }.map { it.removePrefix(PLAYLIST) }.sorted()

    fun savePlaylist(context: Context, name: String, values: List<Long>): Boolean {
        val title = name.trim()
        if (title.isEmpty() || title.length > 60 || values.isEmpty()) return false
        if (playlistNames(context).any { it.equals(title, ignoreCase = true) }) return false
        prefs(context).edit().putString(PLAYLIST + title, values.filter { it > 0L }.take(CAP).joinToString(",")).apply()
        return true
    }

    val pinRevision = androidx.compose.runtime.mutableIntStateOf(0)
    fun pinnedPlaylist(context: Context): String? = prefs(context).getString("pinned_playlist", null)
    fun togglePlaylistPin(context: Context, name: String) {
        prefs(context).edit().putString("pinned_playlist", name.takeUnless { it == pinnedPlaylist(context) }).apply()
        pinRevision.intValue++
    }
    val openPlaylist = androidx.compose.runtime.mutableStateOf<String?>(null)

    fun playlistIds(context: Context, name: String): List<Long> =
        prefs(context).getString(PLAYLIST + name, "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }.take(CAP)

    fun clearQuick(context: Context) = save(context, "quick", emptyList())
    fun isFavorite(context: Context, id: Long): Boolean = id in read(context, "favorite")
    fun toggleFavorite(context: Context, id: Long) {
        val existing = read(context, "favorite")
        save(context, "favorite", if (id in existing) existing.filterNot { it == id } else existing + id)
    }
    /** Counts successful player-launch intents initiated inside Glide, not verified listens. */
    fun recordLaunch(context: Context, id: Long) {
        if (id <= 0L) return
        val p = prefs(context)
        val old = p.getInt("plays_$id", 0)
        p.edit().putInt("plays_$id", (old + 1).coerceAtMost(1000000)).apply()
        save(context, "recent", listOf(id) + read(context, "recent").filterNot { it == id })
    }
    fun idsFor(context: Context, kind: String): List<Long> = when (kind) {
        "quick list" -> read(context, "quick")
        "saved queue" -> prefs(context).getString("saved_queue", "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }.take(CAP)
        "favorites" -> read(context, "favorite")
        "recent opens" -> read(context, "recent")
        "most opened" -> read(context, "recent").sortedByDescending { prefs(context).getInt("plays_$it", 0) }
        else -> emptyList()
    }
}
