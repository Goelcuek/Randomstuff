package com.example.widgetnotes.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.widgetnotes.widget.NotesWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppStyle(val label: String) {
    GLASS("Liquid glass"),
    GLASS_DARK("Dark glass"),
    AMOLED("AMOLED black"),
}

/** Small app-wide settings, shared by the app and the widget. */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_STYLE = "style"

    private var flow: MutableStateFlow<AppStyle>? = null

    @Synchronized
    fun style(context: Context): StateFlow<AppStyle> {
        flow?.let { return it.asStateFlow() }
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STYLE, null)
        val initial = AppStyle.entries.firstOrNull { it.name == saved } ?: AppStyle.GLASS
        return MutableStateFlow(initial).also { flow = it }.asStateFlow()
    }

    suspend fun setStyle(context: Context, style: AppStyle) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, style.name).apply()
        style(app)
        flow?.value = style
        NotesWidget().updateAll(app)
    }
}
