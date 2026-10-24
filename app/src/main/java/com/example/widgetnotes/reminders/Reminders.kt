package com.example.widgetnotes.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.widgetnotes.EditNoteActivity
import com.example.widgetnotes.R
import com.example.widgetnotes.data.ImageStore
import com.example.widgetnotes.data.Note
import com.example.widgetnotes.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Reminders {
    private const val CHANNEL = "reminders"
    const val ACTION_FIRE = "com.example.widgetnotes.REMINDER_FIRE"
    const val ACTION_DONE = "com.example.widgetnotes.REMINDER_DONE"
    const val EXTRA_ID = "note_id"

    fun requestCode(id: Long): Int = (id xor (id ushr 32)).toInt()

    /** The alarm time a note should currently have, or null if none. */
    private fun desired(n: Note?, now: Long): Long? =
        n?.takeIf { !it.done && it.remindAt != null && it.remindAt > now }?.remindAt

    /** Schedules / cancels alarms for whatever changed between two versions of the note list. */
    fun sync(context: Context, before: List<Note>, after: List<Note>) {
        val now = System.currentTimeMillis()
        val old = before.associateBy { it.id }
        val new = after.associateBy { it.id }
        for (id in old.keys + new.keys) {
            val o = desired(old[id], now)
            val n = desired(new[id], now)
            if (o == n) continue
            if (n == null) cancel(context, id) else schedule(context, id, n)
        }
    }

    fun rescheduleAll(context: Context, notes: List<Note>) {
        val now = System.currentTimeMillis()
        notes.forEach { note -> desired(note, now)?.let { schedule(context, note.id, it) } }
    }

    private fun firePendingIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(id),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("widgetnotes://reminder/$id"))
            .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun schedule(context: Context, id: Long, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePendingIntent(context, id)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java)?.cancel(firePendingIntent(context, id))
    }

    fun showNotification(context: Context, note: Note) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Note reminders"
                enableVibration(true)
            }
        )

        val open = PendingIntent.getActivity(
            context, requestCode(note.id), EditNoteActivity.intent(context, note.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            context, requestCode(note.id),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_DONE)
                .setData(Uri.parse("widgetnotes://done/${note.id}"))
                .putExtra(EXTRA_ID, note.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = note.body.ifBlank { if (note.title.isNotBlank()) "Reminder" else "" }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(note.label)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply { note.repeat?.let { setSubText(it.label) } }
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_check, "Mark done", done)

        note.images.firstOrNull()
            ?.let { ImageStore.decodeSampled(ImageStore.file(context, it), 720) }
            ?.let { builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(text)) }

        NotificationManagerCompat.from(context).notify(requestCode(note.id), builder.build())
    }

    /**
     * Line shown under a note in the widget/app, and whether it should be highlighted as due.
     * e.g. "Sat 09:00 · Weekly", "Due · Monthly", "Done today · next Mon 09:00".
     */
    fun describe(note: Note, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
        val at = note.remindAt ?: return null
        val r = note.repeat ?: return format(at) to (at < now)
        if (note.dueNow) return "Due · ${r.label}" to true
        val doneToday = note.lastDoneAt?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        } == true
        return (if (doneToday) "Done today · next ${format(at)}" else "${format(at)} · ${r.label}") to false
    }

    /** "Today 14:30", "Tomorrow 09:00", or e.g. "Fri 16 Oct, 09:00". */
    fun format(at: Long): String {
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(at).atZone(zone)
        val today = LocalDate.now(zone)
        val time = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when (dt.toLocalDate()) {
            today -> "Today $time"
            today.plusDays(1) -> "Tomorrow $time"
            else -> dt.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()))
        }
    }
}

/** Fires reminders and handles the "Mark done" button on the notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        if (id < 0) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    Reminders.ACTION_FIRE -> {
                        val note = NoteRepository.get(app, id)
                        if (note != null && !note.done) {
                            Reminders.showNotification(app, note)
                            NoteRepository.reminderFired(app, id)
                        }
                    }
                    Reminders.ACTION_DONE -> {
                        NotificationManagerCompat.from(app).cancel(Reminders.requestCode(id))
                        NoteRepository.markDone(app, id)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are wiped on reboot / app update — put them back, and show any that were missed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val now = System.currentTimeMillis()
                NoteRepository.state(app).value.notes
                    .filter { !it.done && it.remindAt != null && it.remindAt <= now }
                    .forEach {
                        Reminders.showNotification(app, it)
                        NoteRepository.reminderFired(app, it.id)
                    }
                Reminders.rescheduleAll(app, NoteRepository.state(app).value.notes)
            } finally {
                pending.finish()
            }
        }
    }
}
