package com.example.widgetnotes

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.widgetnotes.data.ImageStore
import com.example.widgetnotes.data.Note
import com.example.widgetnotes.data.NoteRepository
import com.example.widgetnotes.reminders.Reminders
import com.example.widgetnotes.ui.NotesTheme
import com.example.widgetnotes.ui.LocalGlass
import com.example.widgetnotes.ui.blurController
import com.example.widgetnotes.ui.glassSurface
import com.example.widgetnotes.ui.PhotoViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private data class EditorResult(val title: String, val body: String, val images: List<String>, val remindAt: Long?)

/**
 * Floating editor. Opened from the widget it appears over the home screen.
 * Tapping outside or pressing Back saves; Cancel discards.
 */
class EditNoteActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "note_id"
        private const val EXTRA_CAMERA = "camera"
        private const val EXTRA_VIEW = "view_photos"

        /** [viewPhotos] opens straight into the full-screen photo viewer (used by widget thumbnails). */
        fun intent(context: Context, id: Long?, camera: Boolean = false, viewPhotos: Boolean = false): Intent =
            Intent(context, EditNoteActivity::class.java).apply {
                // Unique data URI so each note / mode gets its own PendingIntent
                val mode = when {
                    camera -> "/camera"
                    viewPhotos -> "/view"
                    else -> ""
                }
                data = Uri.parse("widgetnotes://note/${id ?: "new"}$mode")
                putExtra(EXTRA_ID, id ?: -1L)
                putExtra(EXTRA_CAMERA, camera)
                putExtra(EXTRA_VIEW, viewPhotos)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
    }

    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val id = intent.getLongExtra(EXTRA_ID, -1L).takeIf { it >= 0 }
        val existing = id?.let { NoteRepository.get(this, it) }
        val startCamera = intent.getBooleanExtra(EXTRA_CAMERA, false)

        // Photo-only mode: just the zoomable viewer, closing it goes straight back home
        if (intent.getBooleanExtra(EXTRA_VIEW, false) && existing != null && existing.images.isNotEmpty()) {
            setContent {
                NotesTheme {
                    PhotoViewer(
                        files = existing.images.map { ImageStore.file(this, it) },
                        startIndex = 0,
                        onDismiss = { finish() },
                    )
                }
            }
            return
        }

        // Frosted blur of the home screen behind the editor (Android 12+)
        val blur = blurController()
        setContent {
            NotesTheme(blur) {
                EditorSheet(
                    initial = existing,
                    startWithCamera = startCamera,
                    onSave = { result -> saveAndClose(existing, result) },
                    onCancel = { added -> close(added) },
                    onDelete = existing?.let { note -> { added: List<String> -> deleteAndClose(note, added) } },
                )
            }
        }
    }

    private fun saveAndClose(existing: Note?, r: EditorResult) {
        if (finishing) return
        finishing = true
        lifecycleScope.launch {
            NoteRepository.save(applicationContext, existing?.id, r.title, r.body, r.images, r.remindAt)
            // Photos removed in this edit are gone for good
            ImageStore.delete(applicationContext, existing?.images.orEmpty() - r.images.toSet())
            finish()
        }
    }

    private fun close(addedImages: List<String>) {
        if (finishing) return
        finishing = true
        ImageStore.delete(applicationContext, addedImages)
        finish()
    }

    private fun deleteAndClose(note: Note, addedImages: List<String>) {
        if (finishing) return
        finishing = true
        lifecycleScope.launch {
            ImageStore.delete(applicationContext, addedImages)
            NoteRepository.delete(applicationContext, note.id)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(
    initial: Note?,
    startWithCamera: Boolean,
    onSave: (EditorResult) -> Unit,
    onCancel: (List<String>) -> Unit,
    onDelete: ((List<String>) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialImages = remember { initial?.images.orEmpty() }

    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var body by rememberSaveable { mutableStateOf(initial?.body.orEmpty()) }
    var images by rememberSaveable { mutableStateOf(initialImages) }
    var remindAt by rememberSaveable { mutableStateOf(initial?.remindAt) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraAutoLaunched by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showReminderMenu by remember { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var pickedDateUtc by rememberSaveable { mutableStateOf<Long?>(null) }
    val focus = remember { FocusRequester() }

    fun result() = EditorResult(title, body, images, remindAt)
    fun added() = images - initialImages.toSet()
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // ---- Camera ----
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val name = pendingPhoto ?: return@rememberLauncherForActivityResult
        pendingPhoto = null
        if (ok) {
            scope.launch {
                withContext(Dispatchers.IO) { ImageStore.makeThumb(context, name) }
                images = images + name
            }
        } else {
            ImageStore.file(context, name).delete()
        }
    }

    fun launchCamera() {
        val f = ImageStore.newPhotoFile(context)
        pendingPhoto = f.name
        try {
            takePicture.launch(ImageStore.uriFor(context, f))
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            toast("No camera app found")
        }
    }

    // ---- Gallery ----
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val names = uris.mapNotNull { ImageStore.importFromUri(context, it) }
                images = images + names
            }
        }
    }

    // ---- Reminders ----
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Notifications are off, so the reminder can't pop up")
    }

    fun setReminder(at: Long) {
        if (at <= System.currentTimeMillis()) {
            toast("That time has already passed")
            return
        }
        remindAt = at
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun atLocal(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    LaunchedEffect(Unit) {
        if (startWithCamera && !cameraAutoLaunched) {
            cameraAutoLaunched = true
            launchCamera()
        } else if (!startWithCamera) {
            focus.requestFocus()
        }
    }

    BackHandler(enabled = viewing == null) { onSave(result()) }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )

    Box(Modifier.fillMaxSize()) {
    // Dimmed scrim — tap outside the card to save and close
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalGlass.current.let { if (it.isGlass) it.screenTint else Color.Black.copy(alpha = 0.6f) })
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSave(result()) }
            .systemBarsPadding()
            .imePadding()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val glass = LocalGlass.current
        val sheetShape = RoundedCornerShape(28.dp)
        Surface(
            shape = sheetShape,
            color = if (glass.isGlass) Color.Transparent else Color(0xFF0E0E0E),
            border = if (glass.isGlass) null else BorderStroke(1.dp, Color(0xFF262626)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .then(if (glass.isGlass) Modifier.glassSurface(sheetShape, strong = true) else Modifier),
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("Title", style = MaterialTheme.typography.titleLarge) },
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    if (onDelete != null) {
                        IconButton(onClick = { onDelete(added()) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete note")
                        }
                    }
                }
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("Write something…") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = fieldColors,
                    minLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .focusRequester(focus),
                )

                if (images.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(images, key = { _, name -> name }) { index, name ->
                            Box(Modifier.size(88.dp)) {
                                AsyncImage(
                                    model = ImageStore.file(context, name),
                                    contentDescription = "Photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            focusManager.clearFocus()
                                            keyboard?.hide()
                                            viewing = index
                                        },
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .clickable {
                                            images = images - name
                                            // Newly added photos can go right away; existing ones on save
                                            if (name !in initialImages) ImageStore.delete(context, listOf(name))
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                remindAt?.let { at ->
                    AssistChip(
                        onClick = { showReminderMenu = true },
                        label = { Text(Reminders.format(at)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_bell), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove reminder",
                                modifier = Modifier.size(AssistChipDefaults.IconSize).clickable { remindAt = null },
                            )
                        },
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }

                // Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { launchCamera() }) {
                        Icon(painterResource(R.drawable.ic_camera), contentDescription = "Take photo")
                    }
                    IconButton(onClick = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(painterResource(R.drawable.ic_image), contentDescription = "Attach photo")
                    }
                    Box {
                        IconButton(onClick = { showReminderMenu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_bell),
                                contentDescription = "Reminder",
                                tint = if (remindAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(expanded = showReminderMenu, onDismissRequest = { showReminderMenu = false }) {
                            val now = LocalDateTime.now()
                            DropdownMenuItem(
                                text = { Text("In 1 hour") },
                                onClick = { showReminderMenu = false; setReminder(System.currentTimeMillis() + 3_600_000L) },
                            )
                            if (now.hour < 17) {
                                DropdownMenuItem(
                                    text = { Text("This evening  18:00") },
                                    onClick = { showReminderMenu = false; setReminder(atLocal(now.toLocalDate(), 18, 0)) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Tomorrow morning  09:00") },
                                onClick = { showReminderMenu = false; setReminder(atLocal(now.toLocalDate().plusDays(1), 9, 0)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Pick date & time…") },
                                onClick = { showReminderMenu = false; showDatePicker = true },
                            )
                            if (remindAt != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove reminder") },
                                    onClick = { showReminderMenu = false; remindAt = null },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onCancel(added()) }) { Text("Cancel") }
                    Button(onClick = { onSave(result()) }) { Text("Save") }
                }
            }
        }
    }

    // ---- Date → time pickers ----
    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    pickedDateUtc = dateState.selectedDateMillis
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    }

    pickedDateUtc?.let { dateUtc ->
        val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDateUtc = null },
            confirmButton = {
                TextButton(onClick = {
                    val date = Instant.ofEpochMilli(dateUtc).atZone(ZoneOffset.UTC).toLocalDate()
                    pickedDateUtc = null
                    setReminder(atLocal(date, timeState.hour, timeState.minute))
                }) { Text("Set reminder") }
            },
            dismissButton = { TextButton(onClick = { pickedDateUtc = null }) { Text("Cancel") } },
            text = { TimePicker(state = timeState) },
        )
    }

    // ---- Full-screen zoomable photo viewer (drawn on top of the editor) ----
    viewing?.let { index ->
        PhotoViewer(
            files = images.map { ImageStore.file(context, it) },
            startIndex = index,
            onDismiss = { viewing = null },
        )
    }
    }
}
