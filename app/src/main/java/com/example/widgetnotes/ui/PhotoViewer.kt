package com.example.widgetnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 3f

/**
 * Full-screen photo viewer.
 * Pinch to zoom, drag to pan, double-tap to zoom in/out, swipe for the next photo,
 * single tap to hide/show the top bar.
 */
@Composable
fun PhotoViewer(files: List<File>, startIndex: Int, onDismiss: () -> Unit) {
    if (files.isEmpty()) {
        onDismiss()
        return
    }
    BackHandler { onDismiss() }

    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, files.lastIndex)) { files.size }
    var zoomed by remember { mutableStateOf(false) }
    var showBar by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            // While zoomed in, one-finger drags pan the photo instead of changing page
            userScrollEnabled = !zoomed,
            key = { files[it].path },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                file = files[page],
                onTap = { showBar = !showBar },
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
            )
        }

        AnimatedVisibility(
            visible = showBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .systemBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (files.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${files.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File, onTap: () -> Unit, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Load at high resolution so detail stays sharp when zoomed in
    val request = remember(file) {
        ImageRequest.Builder(context).data(file).size(4096).build()
    }

    fun apply(newScale: Float, newOffset: Offset) {
        val s = newScale.coerceIn(1f, MAX_ZOOM)
        // Don't let the photo be dragged off screen
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        val wasZoomed = scale > 1.01f
        scale = s
        offset = if (s <= 1.01f) Offset.Zero else Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
        val isZoomed = s > 1.01f
        if (wasZoomed != isZoomed) currentOnZoomChanged(isZoomed)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            apply(1f, Offset.Zero)
                        } else {
                            // Zoom in towards the tapped spot
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            apply(DOUBLE_TAP_ZOOM, (center - tap) * (DOUBLE_TAP_ZOOM - 1f))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // Two fingers = pinch. One finger only pans when already zoomed,
                        // otherwise the swipe goes to the pager.
                        if (fingers > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val newOffset = if (centroid.isSpecified) {
                                // Keep the spot between the fingers under the fingers
                                val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                                centroid + pan - center - (centroid - center - offset) * (newScale / scale)
                            } else {
                                offset + pan
                            }
                            apply(newScale, newOffset)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = "Photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
