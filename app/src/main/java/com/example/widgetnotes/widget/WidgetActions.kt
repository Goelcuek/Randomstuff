package com.example.widgetnotes.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.example.widgetnotes.data.NoteRepository

val NoteIdKey = ActionParameters.Key<Long>("note_id")
val FilterParam = ActionParameters.Key<String>("filter")

class NotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotesWidget()
}

class ToggleDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[NoteIdKey]?.let { NoteRepository.toggleDone(context, it) }
    }
}

class TogglePinAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[NoteIdKey]?.let { NoteRepository.togglePinned(context, it) }
    }
}

class DeleteNoteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[NoteIdKey]?.let { NoteRepository.delete(context, it) }
    }
}

class UndoDeleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NoteRepository.undoDelete(context)
    }
}

class DismissUndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NoteRepository.dismissUndo(context)
    }
}

class SetFilterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val filter = parameters[FilterParam] ?: return
        updateAppWidgetState(context, glanceId) { prefs -> prefs[FilterKey] = filter }
        NotesWidget().update(context, glanceId)
    }
}
