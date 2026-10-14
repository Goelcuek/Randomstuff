package com.example.widgetnotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.widgetnotes.data.Note
import com.example.widgetnotes.data.NoteRepository
import com.example.widgetnotes.ui.NotesTheme
import kotlinx.coroutines.launch

/**
 * Floating editor. When opened from the widget it appears over the home screen,
 * so writing a note feels like typing straight into the widget.
 * Tapping outside or pressing Back saves; Cancel discards.
 */
class EditNoteActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "note_id"

        fun intent(context: Context, id: Long?): Intent =
            Intent(context, EditNoteActivity::class.java).apply {
                // Unique data URI so each note gets its own PendingIntent
                data = Uri.parse("widgetnotes://note/${id ?: "new"}")
                putExtra(EXTRA_ID, id ?: -1L)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
    }

    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val id = intent.getLongExtra(EXTRA_ID, -1L).takeIf { it >= 0 }
        val existing = id?.let { NoteRepository.get(this, it) }

        setContent {
            NotesTheme {
                EditorSheet(
                    initial = existing,
                    onSave = { title, body -> saveAndClose(existing?.id, title, body) },
                    onCancel = { finish() },
                    onDelete = existing?.let { note -> { deleteAndClose(note.id) } },
                )
            }
        }
    }

    private fun saveAndClose(id: Long?, title: String, body: String) {
        if (finishing) return
        finishing = true
        lifecycleScope.launch {
            NoteRepository.save(applicationContext, id, title, body)
            finish()
        }
    }

    private fun deleteAndClose(id: Long) {
        if (finishing) return
        finishing = true
        lifecycleScope.launch {
            NoteRepository.delete(applicationContext, id)
            finish()
        }
    }
}

@Composable
private fun EditorSheet(
    initial: Note?,
    onSave: (String, String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var body by rememberSaveable { mutableStateOf(initial?.body.orEmpty()) }
    val focus = remember { FocusRequester() }

    BackHandler { onSave(title, body) }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )

    // Dimmed scrim — tap outside the card to save and close
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSave(title, body) }
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Title", style = MaterialTheme.typography.titleLarge) },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("Write something…") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = fieldColors,
                    minLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .focusRequester(focus),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Delete")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onSave(title, body) }) { Text("Save") }
                }
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}
