package com.example.widgetnotes.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.widgetnotes.data.AppStyle
import java.util.function.Consumer

/** What the current screen looks like: style chosen by the user + whether real blur is active. */
data class Glass(val style: AppStyle, val blur: Boolean) {
    val isGlass: Boolean get() = style != AppStyle.AMOLED

    /** Tint laid over the (blurred) home screen behind our window. */
    val screenTint: Color
        get() = when (style) {
            AppStyle.GLASS -> Color.Black.copy(alpha = if (blur) 0.28f else 0.62f)
            AppStyle.GLASS_DARK -> Color.Black.copy(alpha = if (blur) 0.52f else 0.80f)
            AppStyle.AMOLED -> Color.Black
        }
}

val LocalGlass = staticCompositionLocalOf { Glass(AppStyle.AMOLED, blur = false) }

/**
 * Glass surface: translucent white fill with a light sheen at the top and a
 * bright hairline rim that fades towards the bottom (catches the "light").
 * In AMOLED style it falls back to flat dark grey.
 */
@Composable
fun Modifier.glassSurface(shape: Shape, strong: Boolean = false, accentTint: Boolean = false): Modifier {
    val g = LocalGlass.current
    val accent = MaterialTheme.colorScheme.primary
    return if (g.isGlass) {
        val top = if (strong) 0.26f else 0.16f
        val bottom = if (strong) 0.14f else 0.07f
        val tint = if (accentTint) 0.18f else 0f
        this
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(Color.White, accent, tint).copy(alpha = top),
                        lerp(Color.White, accent, tint).copy(alpha = bottom),
                    )
                ),
                shape,
            )
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.10f))),
                shape,
            )
    } else {
        val base = if (strong) Color(0xFF181818) else Color(0xFF111111)
        this
            .background(if (accentTint) lerp(base, accent, 0.14f) else base, shape)
            .border(1.dp, Color(0xFF1F1F1F), shape)
    }
}

/**
 * Real "frosted" blur of whatever is behind our (translucent) window — the home screen.
 * Needs Android 12+ and a device/GPU mode that allows cross-window blur; otherwise
 * [available] stays false and the UI uses a darker tint instead.
 */
class BlurController(private val activity: ComponentActivity, private val radiusDp: Int = 36) {
    val available = mutableStateOf(false)
    private var wanted = true

    private val listener = Consumer<Boolean> { enabled ->
        available.value = enabled
        apply()
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.windowManager.addCrossWindowBlurEnabledListener(listener)
            activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    activity.windowManager.removeCrossWindowBlurEnabledListener(listener)
                }
            })
        }
    }

    fun setWanted(want: Boolean) {
        if (wanted == want) return
        wanted = want
        apply()
    }

    private fun apply() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val window = activity.window
        if (wanted && available.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also {
                it.blurBehindRadius = (radiusDp * activity.resources.displayMetrics.density).toInt()
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }
}
