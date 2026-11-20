package com.bassplayer

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class FilenameMetadata(
    val artist: String,
    val albumArtist: String
)

/** Extracts artist names while treating labels such as Remix/Reload as modifiers. */
object FilenameMetadataParser {
    private val artistSeparators = Regex("\\s*(?:&|,|\\bx\\b|\\bfeat\\.?|\\bft\\.?)\\s*", RegexOption.IGNORE_CASE)
    private val labels = setOf(
        "remix", "remixes", "reload", "reloaded", "vip", "flip", "edit", "bootleg",
        "mix", "original", "extended", "radio", "instrumental", "remaster",
        "remastered", "version", "clean", "dirty", "final", "official", "audio", "video"
    )

    fun parse(fileName: String): FilenameMetadata? {
        val base = fileName.substringBeforeLast('.', fileName)
            .replace(Regex("\\s+FINAL\\s*$", RegexOption.IGNORE_CASE), "")
        val dash = base.indexOf(" - ")
        if (dash <= 0) return null
        val lead = base.substring(0, dash).trim()
        val primaryArtists = splitArtists(lead)
        if (primaryArtists.isEmpty()) return null

        val contributors = linkedSetOf<String>().apply { addAll(primaryArtists) }
        val annotations = Regex("\\(([^()]*)\\)|\\[([^\\[\\]]*)\\]")
        for (match in annotations.findAll(base.substring(dash + 3))) {
            val annotation = (match.groups[1]?.value ?: match.groups[2]?.value ?: "").trim()
            val candidates = artistsFromAnnotation(annotation)
            candidates.forEach { candidate ->
                if (contributors.none { it.equals(candidate, ignoreCase = true) }) contributors.add(candidate)
            }
        }
        return FilenameMetadata(
            artist = contributors.joinToString("; "),
            albumArtist = primaryArtists.first()
        )
    }

    private fun artistsFromAnnotation(text: String): List<String> {
        var value = text.trim()
        if (value.isBlank() || value.matches(Regex("\\d{4}"))) return emptyList()
        val normalized = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (normalized in setOf("original mix", "extended mix", "radio edit", "instrumental", "official audio", "official video")) return emptyList()
        val hadFeat = Regex("\\b(?:feat\\.?|ft\\.?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
        value = value.replace(Regex("^.*?\\b(?:feat\\.?|ft\\.?)\\s+", RegexOption.IGNORE_CASE), "")
        val words = value.split(Regex("\\s+"))
        val modifierIndex = words.indexOfFirst { it.trim('.', ',', '-', '_').lowercase(Locale.ROOT) in labels }
        if (modifierIndex >= 0) value = words.take(modifierIndex).joinToString(" ")
        else if (!hadFeat && text.contains(Regex("\\b(?:remix|reload|vip|flip|edit|bootleg|mix|instrumental|version)\\b", RegexOption.IGNORE_CASE))) {
            return emptyList()
        }
        value = value.trim(' ', '-', ',', '_')
        if (value.isBlank()) return emptyList()
        return splitArtists(value).filterNot { it.lowercase(Locale.ROOT) in labels }
    }

    private fun splitArtists(value: String): List<String> = value
        .split(artistSeparators)
        .map { it.trim().trim('(', ')', '[', ']', ' ', '\'', '"') }
        .filter { it.isNotBlank() && !it.equals("unknown artist", ignoreCase = true) }
}

data class LocalPlaylist(val name: String, val songIds: List<Long>)

/** Stores playlist names and MediaStore IDs only; it has no file deletion path. */
class PlaylistStore(private val preferences: SharedPreferences) {
    fun all(): List<LocalPlaylist> {
        val json = preferences.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").trim()
                if (name.isEmpty()) return@mapNotNull null
                val ids = item.optJSONArray("ids") ?: JSONArray()
                LocalPlaylist(name, (0 until ids.length()).mapNotNull { ids.optLong(it).takeIf { id -> id > 0L } })
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(playlists: List<LocalPlaylist>) {
        val json = JSONArray()
        playlists.forEach { playlist ->
            val ids = JSONArray()
            playlist.songIds.distinct().forEach(ids::put)
            json.put(JSONObject().put("name", playlist.name).put("ids", ids))
        }
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    private companion object { const val KEY = "user_playlists_v1" }
}
