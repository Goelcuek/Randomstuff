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
    /** Reminder time (epoch millis), or null for no reminder. For repeating jobs: the next occurrence. */
    val remindAt: Long? = null,
    /** Repeat rule for recurring jobs (weekly, monthly…), or null for a one-off note. */
    val repeat: Repeat? = null,
    /** A repeating job whose time has come and that hasn't been ticked off yet. */
    val dueNow: Boolean = false,
    /** When a repeating job was last ticked off. */
    val lastDoneAt: Long? = null,
) {
    val isRecurring: Boolean get() = repeat != null

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
        .apply { repeat?.let { put("repeat", it.toJson()) } }
        .put("dueNow", dueNow)
        .apply { lastDoneAt?.let { put("lastDoneAt", it) } }

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
                repeat = Repeat.fromJson(o.optJSONObject("repeat")),
                dueNow = o.optBoolean("dueNow"),
                lastDoneAt = if (o.has("lastDoneAt")) o.getLong("lastDoneAt") else null,
            )
        }
    }
}

data class NotesState(
    val notes: List<Note> = emptyList(),
    /** Last deleted note, kept (and saved to disk) so Undo works even after the app was killed. */
    val recentlyDeleted: Note? = null,
)
