package com.example.widgetnotes.data

import org.json.JSONObject

data class Note(
    val id: Long,
    val title: String,
    val body: String,
    val done: Boolean = false,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Short label used in places like the widget's undo banner. */
    val label: String
        get() = title.ifBlank { body.lineSequence().firstOrNull().orEmpty() }.ifBlank { "Untitled" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("done", done)
        .put("pinned", pinned)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = Note(
            id = o.getLong("id"),
            title = o.optString("title"),
            body = o.optString("body"),
            done = o.optBoolean("done"),
            pinned = o.optBoolean("pinned"),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}

data class NotesState(
    val notes: List<Note> = emptyList(),
    /** Last deleted note, kept so both the app and the widget can offer Undo. */
    val recentlyDeleted: Note? = null,
)
