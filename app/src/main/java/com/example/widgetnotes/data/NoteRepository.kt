package com.example.widgetnotes.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.widgetnotes.reminders.Reminders
import com.example.widgetnotes.widget.NotesWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for notes, shared by the app, the widget and reminders.
 * Everything (including the undo slot) is saved to a JSON file, so state survives
 * the system killing the app in the background.
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

    suspend fun save(
        context: Context,
        id: Long?,
        title: String,
        body: String,
        images: List<String>,
        remindAt: Long?,
        repeat: Repeat? = null,
    ) {
        // A repeating job needs a time to repeat from
        val rule = repeat?.takeIf { remindAt != null }
        val t = title.trim()
        val b = body.trim()
        val existing = id?.let { get(context, it) }

        // Nothing left in the note → delete it (or don't create it).
        if (t.isEmpty() && b.isEmpty() && images.isEmpty()) {
            if (existing != null) delete(context, existing.id)
            return
        }

        mutate(context) { s ->
            val now = System.currentTimeMillis()
            val notes = if (existing != null) {
                s.notes.update(existing.id) {
                    val contentChanged = it.title != t || it.body != b || it.images != images
                    it.copy(
                        title = t, body = b, images = images, remindAt = remindAt,
                        repeat = rule,
                        // Rescheduling a due job (or making it one-off) clears the "due" state
                        dueNow = rule != null && it.dueNow && it.remindAt == remindAt,
                        done = if (rule != null) false else it.done,
                        updatedAt = if (contentChanged) now else it.updatedAt,
                    )
                }
            } else {
                s.notes + Note(id = now, title = t, body = b, images = images, remindAt = remindAt, repeat = rule, updatedAt = now)
            }
            s.copy(notes = notes, recentlyDeleted = null)
        }
    }

    /** Checkbox: one-off notes toggle done; repeating jobs are ticked off for this round. */
    suspend fun toggleDone(context: Context, id: Long) = mutate(context) { s ->
        val now = System.currentTimeMillis()
        s.copy(
            notes = s.notes.update(id) { if (it.isRecurring) it.completed(now) else it.copy(done = !it.done) },
            recentlyDeleted = null,
        )
    }

    /** "Mark done" from a notification. */
    suspend fun markDone(context: Context, id: Long) = mutate(context) { s ->
        val now = System.currentTimeMillis()
        s.copy(notes = s.notes.update(id) { it.completed(now) })
    }

    /**
     * A reminder went off. One-off: the reminder is used up. Repeating job: it's now due
     * (shown as "Due" until ticked) and the next occurrence is scheduled right away.
     */
    suspend fun reminderFired(context: Context, id: Long) = mutate(context) { s ->
        val now = System.currentTimeMillis()
        s.copy(notes = s.notes.update(id) { n ->
            val r = n.repeat
            if (r == null) n.copy(remindAt = null)
            else n.copy(dueNow = true, remindAt = r.nextAfter(maxOf(now, n.remindAt ?: now)))
        })
    }

    private fun Note.completed(now: Long): Note {
        val r = repeat ?: return copy(done = true)
        return if (dueNow) {
            // Did this round's job — next one is already scheduled
            copy(dueNow = false, lastDoneAt = now)
        } else {
            // Done ahead of time — skip to the occurrence after the upcoming one
            copy(remindAt = r.nextAfter(maxOf(now, remindAt ?: now)), lastDoneAt = now)
        }
    }

    suspend fun togglePinned(context: Context, id: Long) = mutate(context) { s ->
        s.copy(notes = s.notes.update(id) { it.copy(pinned = !it.pinned) }, recentlyDeleted = null)
    }

    suspend fun clearReminder(context: Context, id: Long) = mutate(context) { s ->
        s.copy(notes = s.notes.update(id) { it.copy(remindAt = null) })
    }

    suspend fun delete(context: Context, id: Long) = mutate(context) { s ->
        val note = s.notes.firstOrNull { it.id == id } ?: return@mutate s
        s.copy(notes = s.notes - note, recentlyDeleted = note)
    }

    suspend fun undoDelete(context: Context) = mutate(context) { s ->
        val note = s.recentlyDeleted ?: return@mutate s
        s.copy(notes = s.notes + note, recentlyDeleted = null)
    }

    suspend fun dismissUndo(context: Context) = mutate(context) { it.copy(recentlyDeleted = null) }

    // ---------------------------------------------------------------------

    private fun List<Note>.update(id: Long, f: (Note) -> Note) = map { if (it.id == id) f(it) else it }

    private suspend fun mutate(context: Context, block: (NotesState) -> NotesState) {
        val app = context.applicationContext
        load(app)
        val (before, after) = mutex.withLock {
            val before = _state.value
            val after = block(before).let { it.copy(notes = sort(it.notes)) }
            if (after != before) {
                _state.value = after
                withContext(Dispatchers.IO) { write(app, after) }
            }
            before to after
        }

        // A note that leaves the undo slot for good takes its photos with it.
        val trashed = before.recentlyDeleted
        if (trashed != null && trashed != after.recentlyDeleted && after.notes.none { it.id == trashed.id }) {
            ImageStore.delete(app, trashed.images)
        }

        Reminders.sync(app, before.notes, after.notes)

        // Always push to the widget, even if nothing changed, so a stale widget
        // (e.g. after the app was killed) is refreshed on any tap.
        NotesWidget().updateAll(app)
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val file = File(context.filesDir, FILE_NAME)
        _state.value = if (file.exists()) {
            runCatching {
                val text = file.readText().trim()
                if (text.startsWith("[")) {
                    NotesState(sort(parseNotes(JSONArray(text)))) // old format
                } else {
                    val o = JSONObject(text)
                    NotesState(
                        notes = sort(parseNotes(o.optJSONArray("notes") ?: JSONArray())),
                        recentlyDeleted = o.optJSONObject("trash")?.let { Note.fromJson(it) },
                    )
                }
            }.getOrDefault(NotesState())
        } else {
            NotesState(sort(welcomeNotes()))
        }
        loaded = true
    }

    private fun parseNotes(arr: JSONArray) = List(arr.length()) { Note.fromJson(arr.getJSONObject(it)) }

    private fun write(context: Context, state: NotesState) {
        val o = JSONObject()
            .put("notes", JSONArray().apply { state.notes.forEach { put(it.toJson()) } })
            .apply { state.recentlyDeleted?.let { put("trash", it.toJson()) } }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(context.filesDir, FILE_NAME)) // atomic replace
    }

    private fun sort(notes: List<Note>) = notes.sortedWith(
        compareByDescending<Note> { it.pinned }
            .thenBy { it.done }
            .thenByDescending { it.dueNow }
            .thenByDescending { it.updatedAt }
    )

    private fun welcomeNotes(): List<Note> {
        val now = System.currentTimeMillis()
        return listOf(
            Note(now, "Welcome 👋", "Long-press your home screen → Widgets → Widget Notes. Stretch it to fill the screen.", pinned = true, updatedAt = now),
            Note(now - 1, "Try the widget", "Tap 'Write a note…' to type, or the camera button to snap a photo note. Tap a note to add a reminder.", updatedAt = now - 1),
        )
    }
}
