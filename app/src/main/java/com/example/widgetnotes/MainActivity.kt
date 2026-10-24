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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
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
import com.example.widgetnotes.ui.LocalGlass
import com.example.widgetnotes.ui.blurController
import com.example.widgetnotes.ui.glassSurface
import com.example.widgetnotes.data.AppStyle
import com.example.widgetnotes.data.Settings
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import com.example.widgetnotes.ui.PhotoViewer
import com.example.widgetnotes.widget.NotesWidgetReceiver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val blur = blurController()
        setContent {
            NotesTheme(blur) {
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
    var viewing by remember { mutableStateOf<Note?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val glass = LocalGlass.current
    val style by remember { Settings.style(context) }.collectAsStateWithLifecycle()

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

    // The window is translucent: in glass styles the (blurred) home screen shows through this tint
    Box(Modifier.fillMaxSize().background(glass.screenTint)) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text("Notes") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Add widget to home screen") },
                                onClick = { menuOpen = false; onAddWidget() },
                            )
                            HorizontalDivider()
                            Text(
                                "Style",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                            )
                            AppStyle.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    trailingIcon = {
                                        if (option == style) Icon(Icons.Filled.Check, contentDescription = "Selected")
                                    },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { Settings.setStyle(context, option) }
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = if (glass.isGlass) Color.Black.copy(alpha = 0.35f) else Color.Black,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            val fabColor = if (glass.isGlass) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.primaryContainer
            val fabContent = if (glass.isGlass) Color(0xFF151515) else MaterialTheme.colorScheme.onPrimaryContainer
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = { onOpen(null, true) },
                    containerColor = fabColor,
                    contentColor = fabContent,
                ) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = "Photo note")
                }
                ExtendedFloatingActionButton(
                    onClick = { onOpen(null, false) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New note") },
                    containerColor = fabColor,
                    contentColor = fabContent,
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
                        onPhotoClick = { viewing = note },
                    )
                }
            }
        }
    }

    viewing?.let { note ->
        PhotoViewer(
            files = note.images.map { ImageStore.file(context, it) },
            startIndex = 0,
            onDismiss = { viewing = null },
        )
    }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    onTogglePin: () -> Unit,
    onPhotoClick: () -> Unit,
) {
    val context = LocalContext.current
    val decoration = if (note.done) TextDecoration.LineThrough else TextDecoration.None
    val shape = RoundedCornerShape(18.dp)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = Color.White),
        shape = shape,
        modifier = Modifier.glassSurface(shape, strong = note.pinned, accentTint = note.pinned),
    ) {
        note.images.firstOrNull()?.let { name ->
            AsyncImage(
                model = ImageStore.file(context, name),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .clickable { onPhotoClick() },
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
                        else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (note.body.isNotBlank()) {
                Text(
                    text = note.body,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = decoration,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            Reminders.describe(note)?.let { (text, due) ->
                val tint = if (due) Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary
                Row(Modifier.padding(top = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (note.isRecurring) R.drawable.ic_repeat else R.drawable.ic_bell),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = note.done, onCheckedChange = { onToggleDone() })
                Text(if (note.isRecurring) "Done this time" else "Done", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
