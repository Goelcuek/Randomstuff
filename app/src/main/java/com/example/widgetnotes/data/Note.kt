package com.example.widgetnotes.data

import org.json.JSONArray
import org.json.JSONObject

data class Note(
    val id: Long,
    val title: String,
    val body: String,
    val done: Boolean = false,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** File names inside the app's private images folder. */
    val images: List<String> = emptyList(),
    /** Reminder time (epoch millis), or null for no reminder. */
    val remindAt: Long? = null,
) {
    /** Short label used in notifications, the undo banner, etc. */
    val label: String
        get() = title.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
            .ifBlank { if (images.isNotEmpty()) "Photo" else "Untitled" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("done", done)
        .put("pinned", pinned)
        .put("updatedAt", updatedAt)
        .put("images", JSONArray(images))
        .apply { remindAt?.let { put("remindAt", it) } }

    companion object {
        fun fromJson(o: JSONObject): Note {
            val imgs = o.optJSONArray("images")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return Note(
                id = o.getLong("id"),
                title = o.optString("title"),
                body = o.optString("body"),
                done = o.optBoolean("done"),
                pinned = o.optBoolean("pinned"),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                images = imgs,
                remindAt = if (o.has("remindAt")) o.getLong("remindAt") else null,
            )
        }
    }
}

data class NotesState(
    val notes: List<Note> = emptyList(),
    /** Last deleted note, kept (and saved to disk) so Undo works even after the app was killed. */
    val recentlyDeleted: Note? = null,
)
