package com.example.widgetnotes

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.widgetnotes.data.ImageStore
import com.example.widgetnotes.data.Note
import com.example.widgetnotes.data.NoteRepository
import com.example.widgetnotes.reminders.Reminders
import com.example.widgetnotes.ui.NotesTheme
import com.example.widgetnotes.widget.NotesWidgetReceiver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotesTheme {
                NotesScreen(
                    onOpen = { id, camera -> startActivity(EditNoteActivity.intent(this, id, camera)) },
                    onAddWidget = { requestPinWidget() },
                )
            }
        }
    }

    /** Asks the launcher to place the widget on the home screen (Android 8+, most launchers). */
    private fun requestPinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, NotesWidgetReceiver::class.java), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Widget Notes", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(onOpen: (Long?, Boolean) -> Unit, onAddWidget: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stateFlow = remember { NoteRepository.state(context) }
    val state by stateFlow.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Offer Undo after a delete (from here or from the widget)
    LaunchedEffect(state.recentlyDeleted) {
        val deleted = state.recentlyDeleted ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Deleted “${deleted.label}”",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) NoteRepository.undoDelete(context)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Black,
        topBar = {
            LargeTopAppBar(
                title = { Text("Notes") },
                actions = { TextButton(onClick = onAddWidget) { Text("Add widget") } },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { onOpen(null, true) }) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = "Photo note")
                }
                ExtendedFloatingActionButton(
                    onClick = { onOpen(null, false) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New note") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No notes yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 140.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onOpen(note.id, false) },
                        onToggleDone = { scope.launch { NoteRepository.toggleDone(context, note.id) } },
                        onTogglePin = { scope.launch { NoteRepository.togglePinned(context, note.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit, onToggleDone: () -> Unit, onTogglePin: () -> Unit) {
    val context = LocalContext.current
    val base = Color(0xFF111111)
    val container = if (note.pinned) lerp(base, MaterialTheme.colorScheme.primary, 0.14f) else base
    val decoration = if (note.done) TextDecoration.LineThrough else TextDecoration.None

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, Color(0xFF1F1F1F)),
        shape = RoundedCornerShape(18.dp),
    ) {
        note.images.firstOrNull()?.let { name ->
            AsyncImage(
                model = ImageStore.file(context, name),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
            )
        }
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title.ifBlank { if (note.body.isBlank() && note.images.isNotEmpty()) "Photo" else " " },
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = decoration,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePin) {
                    Icon(
                        painterResource(R.drawable.ic_pin),
                        contentDescription = if (note.pinned) "Unpin" else "Pin",
                        tint = if (note.pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (note.body.isNotBlank()) {
                Text(
                    text = note.body,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = decoration,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            note.remindAt?.let { at ->
                val tint = if (at < System.currentTimeMillis()) Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_bell), contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(Reminders.format(at), style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = note.done, onCheckedChange = { onToggleDone() })
                Text("Done", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
