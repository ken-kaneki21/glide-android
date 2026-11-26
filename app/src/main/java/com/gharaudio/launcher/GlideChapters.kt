package com.gharaudio.launcher

import android.content.Context
import android.provider.MediaStore
import java.io.File

internal data class GlideChapter(val title: String, val position: Long)

internal fun loadCueChapters(context: Context, song: Song): List<GlideChapter> = runCatching {
    val path = context.contentResolver.query(song.uri, arrayOf(MediaStore.Audio.Media.DATA),
        null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: return emptyList()
    val media = File(path)
    val cue = File(media.parentFile, media.nameWithoutExtension + ".cue")
    if (!cue.isFile || cue.length() !in 1..256_000) return emptyList()
    parseCueChapters(cue.readLines(), song.duration)
}.getOrDefault(emptyList())

internal fun parseCueChapters(lines: List<String>, duration: Long): List<GlideChapter> {
    val files = lines.count { it.trimStart().startsWith("FILE ", ignoreCase = true) }
    if (files > 1) return emptyList()
    val titlePattern = Regex("""^TITLE\s+"(.+)"$""", RegexOption.IGNORE_CASE)
    val indexPattern = Regex("""^INDEX\s+01\s+(\d+):(\d{2}):(\d{2})$""", RegexOption.IGNORE_CASE)
    var track = 0
    var title = ""
    val chapters = mutableListOf<GlideChapter>()
    for (line in lines.map(String::trim)) {
        if (line.startsWith("TRACK ", ignoreCase = true)) {
            track++
            title = ""
        }
        if (track == 0) continue
        titlePattern.matchEntire(line)?.let { title = it.groupValues[1] }
        val match = indexPattern.matchEntire(line) ?: continue
        val minutes = match.groupValues[1].toLongOrNull()?.takeIf { it <= 100000 } ?: continue
        val seconds = match.groupValues[2].toInt()
        val frames = match.groupValues[3].toInt()
        if (seconds >= 60 || frames >= 75) continue
        val position = minutes * 60000 + seconds * 1000 + frames * 1000 / 75
        if (duration > 0 && position >= duration) continue
        chapters.add(GlideChapter(title.ifBlank { "Chapter $track" }, position))
        if (chapters.size >= 500) break
    }
    return chapters.distinctBy { it.position }.sortedBy { it.position }
}
