package com.example.widgetnotes.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.widgetnotes.data.Settings

/**
 * Dark theme with accent from the wallpaper (Android 12+). Provides [LocalGlass] so
 * screens can draw glass or AMOLED surfaces depending on the user's chosen style.
 * Pass the activity's [BlurController] to get real background blur in glass styles.
 */
@Composable
fun NotesTheme(blur: BlurController? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val styleFlow = remember { Settings.style(context) }
    val style by styleFlow.collectAsState()
    val blurOn = blur?.available?.value == true

    val glass = Glass(style, blur = blurOn && style != com.example.widgetnotes.data.AppStyle.AMOLED)
    LaunchedEffect(glass.isGlass) { blur?.setWanted(glass.isGlass) }

    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()
    val scheme = base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color(0xFF1A1A1A),
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF141414),
        surfaceContainer = Color(0xFF1A1A1A),
        surfaceContainerHigh = Color(0xFF202020),
        surfaceContainerHighest = Color(0xFF262626),
        outlineVariant = Color(0xFF2A2A2A),
    )
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Convenience for activities. */
fun ComponentActivity.blurController() = BlurController(this)
