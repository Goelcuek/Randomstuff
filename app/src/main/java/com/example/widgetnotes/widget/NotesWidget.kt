package com.example.widgetnotes.widget

import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import com.example.widgetnotes.data.AppStyle
import com.example.widgetnotes.data.ImageStore
import com.example.widgetnotes.data.Note
import com.example.widgetnotes.data.NoteRepository
import com.example.widgetnotes.data.NotesState
import com.example.widgetnotes.data.Settings
import com.example.widgetnotes.reminders.Reminders

const val FILTER_ALL = "all"
const val FILTER_OPEN = "open"
const val FILTER_DONE = "done"

/** Per-widget UI state (which tab is selected). */
val FilterKey = stringPreferencesKey("filter")

/** A surface is either a glass drawable (translucent + rim) or a flat colour. */
data class Fill(@DrawableRes val res: Int? = null, val color: ColorProvider? = null, val radius: Dp)

private fun GlanceModifier.fill(f: Fill): GlanceModifier {
    val withBg = if (f.res != null) background(ImageProvider(f.res)) else background(f.color!!)
    return withBg.cornerRadius(f.radius)
}

data class WidgetPalette(
    val panel: Fill,
    val card: Fill,
    val pinnedCard: Fill,
    val chip: Fill,
    val chipSelected: Fill,
    val pill: Fill,
    val banner: Fill,
    val action: Fill,
    val onChipSelected: ColorProvider,
    val onAction: ColorProvider,
    val text: ColorProvider,
    val muted: ColorProvider,
    val accent: ColorProvider,
    val warn: ColorProvider,
)

private val LocalPalette = staticCompositionLocalOf<WidgetPalette> { error("No palette") }

private fun buildPalette(context: Context, style: AppStyle): WidgetPalette {
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else null
    val accent = scheme?.primary ?: Color(0xFF8AB4F8)
    val onAccent = scheme?.onPrimary ?: Color(0xFF00213F)
    fun c(color: Color) = ColorProvider(color)

    return when (style) {
        AppStyle.GLASS, AppStyle.GLASS_DARK -> WidgetPalette(
            panel = Fill(res = if (style == AppStyle.GLASS) R.drawable.glass_panel else R.drawable.glass_dark_panel, radius = 28.dp),
            card = Fill(res = R.drawable.glass_card, radius = 18.dp),
            pinnedCard = Fill(res = R.drawable.glass_card_pinned, radius = 18.dp),
            chip = Fill(res = R.drawable.glass_chip, radius = 16.dp),
            chipSelected = Fill(color = c(Color(0xF2FFFFFF)), radius = 16.dp),
            pill = Fill(res = R.drawable.glass_pill, radius = 26.dp),
            banner = Fill(res = R.drawable.glass_banner, radius = 14.dp),
            action = Fill(color = c(Color(0xF2FFFFFF)), radius = 26.dp),
            onChipSelected = c(Color(0xFF151515)),
            onAction = c(Color(0xFF151515)),
            text = c(Color.White),
            muted = c(Color(0xD9FFFFFF)),
            accent = c(lerp(accent, Color.White, 0.35f)),
            warn = c(Color(0xFFFFB4AB)),
        )
        AppStyle.AMOLED -> {
            val card = Color(0xFF111111)
            WidgetPalette(
                panel = Fill(color = c(Color.Black), radius = 28.dp),
                card = Fill(color = c(card), radius = 18.dp),
                pinnedCard = Fill(color = c(lerp(card, accent, 0.14f)), radius = 18.dp),
                chip = Fill(color = c(Color(0xFF1A1A1A)), radius = 16.dp),
                chipSelected = Fill(color = c(accent), radius = 16.dp),
                pill = Fill(color = c(Color(0xFF1A1A1A)), radius = 26.dp),
                banner = Fill(color = c(Color(0xFF222222)), radius = 14.dp),
                action = Fill(color = c(accent), radius = 26.dp),
                onChipSelected = c(onAccent),
                onAction = c(onAccent),
                text = c(Color(0xFFF2F2F2)),
                muted = c(Color(0xFF8E8E8E)),
                accent = c(accent),
                warn = c(Color(0xFFFF8A80)),
            )
        }
    }
}

class NotesWidget : GlanceAppWidget() {

    // Re-lay out for the exact size, so it looks right from small up to full screen.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notesFlow = NoteRepository.state(context)
        val styleFlow = Settings.style(context)
        provideContent {
            val state by notesFlow.collectAsState()
            val style by styleFlow.collectAsState()
            val filter = currentState(FilterKey) ?: FILTER_ALL
            CompositionLocalProvider(LocalPalette provides buildPalette(context, style)) {
                WidgetContent(state, filter)
            }
        }
    }
}

@Composable
private fun WidgetContent(state: NotesState, filter: String) {
    val c = LocalPalette.current
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
            .fill(c.panel)
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        // Header: title + tabs on one line when there's room
        if (LocalSize.current.width >= 330.dp) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Header(openCount, doneCount, GlanceModifier.defaultWeight())
                FilterTabs(filter)
            }
        } else {
            Header(openCount, doneCount, GlanceModifier)
            Spacer(GlanceModifier.height(10.dp))
            FilterTabs(filter)
        }

        Spacer(GlanceModifier.height(12.dp))

        state.recentlyDeleted?.let { deleted ->
            UndoBanner(deleted)
            Spacer(GlanceModifier.height(8.dp))
        }

        if (visible.isEmpty()) {
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (filter) {
                        FILTER_DONE -> "Nothing finished yet"
                        FILTER_OPEN -> "All clear ✨"
                        else -> "No notes yet"
                    },
                    style = TextStyle(c.muted, fontSize = 15.sp),
                )
            }
        } else {
            LazyColumn(GlanceModifier.defaultWeight().fillMaxWidth()) {
                items(visible, itemId = { it.id }) { note ->
                    Column {
                        NoteRow(note)
                        Spacer(GlanceModifier.height(6.dp))
                    }
                }
            }
        }

        Spacer(GlanceModifier.height(10.dp))
        BottomBar()
    }
}

@Composable
private fun Header(openCount: Int, doneCount: Int, modifier: GlanceModifier) {
    val c = LocalPalette.current
    Column(modifier.padding(start = 6.dp)) {
        Text("Notes", style = TextStyle(c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Text("$openCount open · $doneCount done", style = TextStyle(c.muted, fontSize = 12.sp))
    }
}

@Composable
private fun FilterTabs(selected: String) {
    Row {
        FilterChip("All", FILTER_ALL, selected)
        Spacer(GlanceModifier.width(6.dp))
        FilterChip("Open", FILTER_OPEN, selected)
        Spacer(GlanceModifier.width(6.dp))
        FilterChip("Done", FILTER_DONE, selected)
    }
}

@Composable
private fun FilterChip(label: String, value: String, selected: String) {
    val c = LocalPalette.current
    val isSelected = value == selected
    Box(
        modifier = GlanceModifier
            .height(32.dp)
            .fill(if (isSelected) c.chipSelected else c.chip)
            .padding(horizontal = 14.dp)
            .clickable(actionRunCallback<SetFilterAction>(actionParametersOf(FilterParam to value))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                color = if (isSelected) c.onChipSelected else c.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun NoteRow(note: Note) {
    val c = LocalPalette.current
    val context = LocalContext.current
    val params = actionParametersOf(NoteIdKey to note.id)
    val open = actionStartActivity(EditNoteActivity.intent(context, note.id))
    val viewPhotos = actionStartActivity(EditNoteActivity.intent(context, note.id, viewPhotos = true))
    val thumb = note.images.firstOrNull()?.let { ImageStore.loadThumb(context, it) }
    val strike = if (note.done) TextDecoration.LineThrough else TextDecoration.None
    val mainColor = if (note.done) c.muted else c.text

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .fill(if (note.pinned) c.pinnedCard else c.card)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = note.done,
            onCheckedChange = actionRunCallback<ToggleDoneAction>(params),
            colors = CheckboxDefaults.colors(checkedColor = c.text, uncheckedColor = c.muted),
        )

        // Text block — tap to open the editor
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(top = 10.dp, bottom = 10.dp, end = 6.dp)
                .clickable(open),
        ) {
            when {
                note.title.isNotBlank() -> {
                    Text(
                        note.title,
                        maxLines = 2,
                        style = TextStyle(mainColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, textDecoration = strike),
                    )
                    if (note.body.isNotBlank()) {
                        Text(note.body, maxLines = 3, style = TextStyle(c.muted, fontSize = 13.sp, textDecoration = strike))
                    }
                }
                note.body.isNotBlank() ->
                    Text(note.body, maxLines = 4, style = TextStyle(mainColor, fontSize = 15.sp, textDecoration = strike))
                else ->
                    Text("Photo", style = TextStyle(c.muted, fontSize = 15.sp))
            }

            val extraPhotos = note.images.size - 1
            val schedule = Reminders.describe(note)
            if (schedule != null || extraPhotos > 0) {
                Row(GlanceModifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    schedule?.let { (text, due) ->
                        val tint = if (due) c.warn else c.accent
                        Image(
                            provider = ImageProvider(if (note.isRecurring) R.drawable.ic_repeat else R.drawable.ic_bell),
                            contentDescription = if (note.isRecurring) "Repeats" else "Reminder",
                            modifier = GlanceModifier.size(13.dp),
                            colorFilter = ColorFilter.tint(tint),
                        )
                        Spacer(GlanceModifier.width(4.dp))
                        Text(text, maxLines = 1, style = TextStyle(tint, fontSize = 12.sp, fontWeight = if (due) FontWeight.Bold else FontWeight.Normal))
                        Spacer(GlanceModifier.width(10.dp))
                    }
                    if (extraPhotos > 0) {
                        Text("+$extraPhotos photo${if (extraPhotos > 1) "s" else ""}", style = TextStyle(c.muted, fontSize = 12.sp))
                    }
                }
            }
        }

        if (thumb != null) {
            Image(
                provider = ImageProvider(thumb),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(46.dp).cornerRadius(10.dp).clickable(viewPhotos),
            )
            Spacer(GlanceModifier.width(2.dp))
        }

        // Compact actions, side by side
        SmallIconButton(
            res = R.drawable.ic_pin,
            description = if (note.pinned) "Unpin" else "Pin",
            tint = if (note.pinned) c.accent else c.muted,
            action = actionRunCallback<TogglePinAction>(params),
        )
        SmallIconButton(
            res = R.drawable.ic_delete,
            description = "Delete",
            tint = c.muted,
            action = actionRunCallback<DeleteNoteAction>(params),
        )
    }
}

@Composable
private fun UndoBanner(deleted: Note) {
    val c = LocalPalette.current
    // Tapping anywhere on the banner (except UNDO) dismisses it.
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .fill(c.banner)
            .padding(start = 14.dp, end = 2.dp)
            .clickable(actionRunCallback<DismissUndoAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Deleted “${deleted.label}”",
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(c.text, fontSize = 14.sp),
        )
        Box(
            modifier = GlanceModifier
                .height(40.dp)
                .padding(horizontal = 12.dp)
                .clickable(actionRunCallback<UndoDeleteAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Text("UNDO", style = TextStyle(c.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
        SmallIconButton(
            res = R.drawable.ic_close,
            description = "Dismiss",
            tint = c.muted,
            action = actionRunCallback<DismissUndoAction>(),
        )
    }
}

@Composable
private fun BottomBar() {
    val c = LocalPalette.current
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .height(52.dp)
                .fill(c.pill)
                .padding(horizontal = 18.dp)
                .clickable(actionStartActivity(EditNoteActivity.intent(context, null))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_edit),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(c.muted),
            )
            Spacer(GlanceModifier.width(12.dp))
            Text("Write a note…", style = TextStyle(c.muted, fontSize = 15.sp))
        }
        Spacer(GlanceModifier.width(8.dp))
        // Photo note shortcut: opens the camera straight away
        Box(
            modifier = GlanceModifier
                .size(52.dp)
                .fill(c.action)
                .clickable(actionStartActivity(EditNoteActivity.intent(context, null, camera = true))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_camera),
                contentDescription = "Photo note",
                modifier = GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(c.onAction),
            )
        }
    }
}

@Composable
private fun SmallIconButton(res: Int, description: String, tint: ColorProvider, action: Action) {
    Box(
        modifier = GlanceModifier.size(34.dp).cornerRadius(17.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(res),
            contentDescription = description,
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}
