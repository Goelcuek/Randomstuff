package com.example.widgetnotes.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.widgetnotes.widget.NotesWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Single source of truth for notes, shared by the app and the widget.
 * Notes live in a small JSON file in app storage; every change refreshes the widget.
 */
object NoteRepository {
    private const val FILE_NAME = "notes.json"

    private val mutex = Mutex()
    private val _state = MutableStateFlow(NotesState())

    @Volatile
    private var loaded = false

    fun state(context: Context): StateFlow<NotesState> {
        load(context.applicationContext)
        return _state.asStateFlow()
    }

    fun get(context: Context, id: Long): Note? =
        state(context).value.notes.firstOrNull { it.id == id }

    suspend fun save(context: Context, id: Long?, title: String, body: String) {
        val t = title.trim()
        val b = body.trim()
        val existing = id?.let { get(context, it) }

        // Blank new note → nothing to save. Blank existing note → delete it.
        if (t.isEmpty() && b.isEmpty()) {
            if (existing != null) delete(context, existing.id)
            return
        }
        if (existing != null && existing.title == t && existing.body == b) return

        mutate(context) { s ->
            val now = System.currentTimeMillis()
            val notes = if (existing != null) {
                s.notes.map { if (it.id == existing.id) it.copy(title = t, body = b, updatedAt = now) else it }
            } else {
                s.notes + Note(id = now, title = t, body = b, updatedAt = now)
            }
            s.copy(notes = notes, recentlyDeleted = null)
        }
    }

    suspend fun toggleDone(context: Context, id: Long) = mutate(context) { s ->
        s.copy(notes = s.notes.map { if (it.id == id) it.copy(done = !it.done) else it }, recentlyDeleted = null)
    }

    suspend fun togglePinned(context: Context, id: Long) = mutate(context) { s ->
        s.copy(notes = s.notes.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }, recentlyDeleted = null)
    }

    suspend fun delete(context: Context, id: Long) = mutate(context) { s ->
        val note = s.notes.firstOrNull { it.id == id } ?: return@mutate s
        s.copy(notes = s.notes - note, recentlyDeleted = note)
    }

    suspend fun undoDelete(context: Context) = mutate(context) { s ->
        val note = s.recentlyDeleted ?: return@mutate s
        s.copy(notes = s.notes + note, recentlyDeleted = null)
    }

    suspend fun dismissUndo(context: Context) {
        if (state(context).value.recentlyDeleted == null) return
        mutate(context) { it.copy(recentlyDeleted = null) }
    }

    // ---------------------------------------------------------------------

    private suspend fun mutate(context: Context, block: (NotesState) -> NotesState) {
        val app = context.applicationContext
        load(app)
        mutex.withLock {
            val before = _state.value
            val next = block(before)
            if (next == before) return
            val sorted = next.copy(notes = sort(next.notes))
            _state.value = sorted
            withContext(Dispatchers.IO) { write(app, sorted.notes) }
        }
        // Push the new content to every placed widget (covers the case where
        // the widget's live session has ended and it is showing a snapshot).
        NotesWidget().updateAll(app)
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val file = File(context.filesDir, FILE_NAME)
        val notes = if (file.exists()) {
            runCatching {
                val arr = JSONArray(file.readText())
                List(arr.length()) { Note.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        } else {
            welcomeNotes()
        }
        _state.value = NotesState(sort(notes))
        loaded = true
    }

    private fun write(context: Context, notes: List<Note>) {
        val arr = JSONArray().apply { notes.forEach { put(it.toJson()) } }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(context.filesDir, FILE_NAME)) // atomic replace
    }

    private fun sort(notes: List<Note>) = notes.sortedWith(
        compareByDescending<Note> { it.pinned }
            .thenBy { it.done }
            .thenByDescending { it.updatedAt }
    )

    private fun welcomeNotes(): List<Note> {
        val now = System.currentTimeMillis()
        return listOf(
            Note(now, "Welcome 👋", "Long-press your home screen → Widgets → Widget Notes. Stretch it to fill the screen.", pinned = true, updatedAt = now),
            Note(now - 1, "Try the widget", "Tick the box, tap the pin, tap the bin, or tap a note to edit it right on the home screen.", updatedAt = now - 1),
            Note(now - 2, "Done notes sink to the bottom", "Use the All / Open / Done tabs to filter.", done = true, updatedAt = now - 2),
        )
    }
}
