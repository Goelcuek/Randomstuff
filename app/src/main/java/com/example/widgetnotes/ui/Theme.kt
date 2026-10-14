package com.example.widgetnotes.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Always-dark AMOLED theme: pure black surfaces, accent from the wallpaper on Android 12+. */
@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()
    val scheme = base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color(0xFF1A1A1A),
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0E0E0E),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF181818),
        surfaceContainerHighest = Color(0xFF202020),
        outlineVariant = Color(0xFF2A2A2A),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
