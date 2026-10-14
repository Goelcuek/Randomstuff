package com.example.widgetnotes.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.widgetnotes.EditNoteActivity
import com.example.widgetnotes.R
import com.example.widgetnotes.data.Note
import com.example.widgetnotes.data.NoteRepository
import com.example.widgetnotes.data.NotesState

const val FILTER_ALL = "all"
const val FILTER_OPEN = "open"
const val FILTER_DONE = "done"

/** Per-widget UI state (which tab is selected). */
val FilterKey = stringPreferencesKey("filter")

class NotesWidget : GlanceAppWidget() {

    // Re-lay out for the exact size, so it looks right from small up to full screen.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notesFlow = NoteRepository.state(context)
        provideContent {
            val state by notesFlow.collectAsState()
            val filter = currentState(FilterKey) ?: FILTER_ALL
            GlanceTheme {
                WidgetContent(state, filter)
            }
        }
    }
}

@Composable
private fun WidgetContent(state: NotesState, filter: String) {
    val context = LocalContext.current
    val openCount = state.notes.count { !it.done }
    val doneCount = state.notes.size - openCount
    val visible = when (filter) {
        FILTER_OPEN -> state.notes.filter { !it.done }
        FILTER_DONE -> state.notes.filter { it.done }
        else -> state.notes
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(28.dp)
            .padding(14.dp)
    ) {
        // Header
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    "Notes",
                    style = TextStyle(GlanceTheme.colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    "$openCount open · $doneCount done",
                    style = TextStyle(GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_add),
                contentDescription = "New note",
                onClick = actionStartActivity(EditNoteActivity.intent(context, null)),
                backgroundColor = GlanceTheme.colors.primary,
                contentColor = GlanceTheme.colors.onPrimary,
            )
        }

        Spacer(GlanceModifier.height(12.dp))

        // Filter tabs
        Row(GlanceModifier.fillMaxWidth()) {
            FilterChip("All", FILTER_ALL, filter)
            Spacer(GlanceModifier.width(6.dp))
            FilterChip("Open", FILTER_OPEN, filter)
            Spacer(GlanceModifier.width(6.dp))
            FilterChip("Done", FILTER_DONE, filter)
        }

        Spacer(GlanceModifier.height(12.dp))

        state.recentlyDeleted?.let { deleted ->
            UndoBanner(deleted)
            Spacer(GlanceModifier.height(10.dp))
        }

        // Scrollable list (or empty state)
        if (visible.isEmpty()) {
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (filter) {
                        FILTER_DONE -> "Nothing finished yet"
                        FILTER_OPEN -> "All clear ✨"
                        else -> "No notes yet — tap + to write one"
                    },
                    style = TextStyle(GlanceTheme.colors.onSurfaceVariant, fontSize = 15.sp),
                )
            }
        } else {
            LazyColumn(GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(visible, itemId = { it.id }) { note ->
                    Column {
                        NoteRow(note)
                        Spacer(GlanceModifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(GlanceModifier.height(10.dp))

        // Quick-write bar
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(52.dp)
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(26.dp)
                .padding(horizontal = 18.dp)
                .clickable(actionStartActivity(EditNoteActivity.intent(context, null))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_edit),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
            )
            Spacer(GlanceModifier.width(12.dp))
            Text(
                "Write a note…",
                style = TextStyle(GlanceTheme.colors.onSecondaryContainer, fontSize = 15.sp),
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, value: String, selected: String) {
    val isSelected = value == selected
    Box(
        modifier = GlanceModifier
            .height(34.dp)
            .background(if (isSelected) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant)
            .cornerRadius(17.dp)
            .padding(horizontal = 16.dp)
            .clickable(actionRunCallback<SetFilterAction>(actionParametersOf(FilterParam to value))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                color = if (isSelected) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun NoteRow(note: Note) {
    val context = LocalContext.current
    val params = actionParametersOf(NoteIdKey to note.id)
    val container = if (note.pinned) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant
    val onContainer = if (note.pinned) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface
    val muted = if (note.pinned) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(container)
            .cornerRadius(18.dp)
            .padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CheckBox(
            checked = note.done,
            onCheckedChange = actionRunCallback<ToggleDoneAction>(params),
        )

        // Tapping the text opens the floating editor
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(top = 8.dp, bottom = 6.dp, end = 4.dp)
                .clickable(actionStartActivity(EditNoteActivity.intent(context, note.id))),
        ) {
            val strike = if (note.done) TextDecoration.LineThrough else TextDecoration.None
            if (note.title.isNotBlank()) {
                Text(
                    note.title,
                    maxLines = 2,
                    style = TextStyle(onContainer, fontSize = 16.sp, fontWeight = FontWeight.Bold, textDecoration = strike),
                )
            }
            if (note.body.isNotBlank()) {
                Text(
                    note.body,
                    maxLines = 4,
                    style = TextStyle(muted, fontSize = 14.sp, textDecoration = strike),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SmallIconButton(
                res = R.drawable.ic_pin,
                description = if (note.pinned) "Unpin" else "Pin",
                tint = if (note.pinned) GlanceTheme.colors.primary else muted,
                action = actionRunCallback<TogglePinAction>(params),
            )
            SmallIconButton(
                res = R.drawable.ic_delete,
                description = "Delete",
                tint = muted,
                action = actionRunCallback<DeleteNoteAction>(params),
            )
        }
    }
}

@Composable
private fun UndoBanner(deleted: Note) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.inverseSurface)
            .cornerRadius(16.dp)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Deleted “${deleted.label}”",
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(GlanceTheme.colors.inverseOnSurface, fontSize = 14.sp),
        )
        Box(
            modifier = GlanceModifier
                .height(36.dp)
                .padding(horizontal = 12.dp)
                .clickable(actionRunCallback<UndoDeleteAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "UNDO",
                style = TextStyle(GlanceTheme.colors.inversePrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
        SmallIconButton(
            res = R.drawable.ic_close,
            description = "Dismiss",
            tint = GlanceTheme.colors.inverseOnSurface,
            action = actionRunCallback<DismissUndoAction>(),
        )
    }
}

@Composable
private fun SmallIconButton(res: Int, description: String, tint: ColorProvider, action: Action) {
    Box(
        modifier = GlanceModifier.size(40.dp).cornerRadius(20.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(res),
            contentDescription = description,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}
