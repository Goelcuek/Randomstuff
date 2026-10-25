package com.goelcuek.sehrim

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

data class MapPin(
    val lat: Double, val lon: Double, val title: String, val snippet: String = "",
    val color: Int, val sizeDp: Int = 14, val onClick: (() -> Unit)? = null,
)

data class MapLine(val points: List<Pair<Double, Double>>, val color: Int, val widthPx: Float = 10f)

private fun initOsm(ctx: Context) {
    val cfg = Configuration.getInstance()
    cfg.load(ctx, ctx.getSharedPreferences("osmdroid", 0))
    cfg.userAgentValue = "Sehrim/1.0 (${ctx.packageName})"
    val base = File(ctx.cacheDir, "osmdroid")
    cfg.osmdroidBasePath = base
    cfg.osmdroidTileCache = File(base, "tiles")
}

private fun dot(ctx: Context, color: Int, sizeDp: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
    setSize(px, px)
    setColor(color)
    setStroke((2.5f * ctx.resources.displayMetrics.density).toInt(), Color.WHITE)
}

/**
 * OpenStreetMap haritası. [fitKey] değiştiğinde görünüm pinlere/çizgilere sığdırılır;
 * aksi halde kullanıcının kaydırdığı görünüm korunur.
 */
@Composable
fun OsmMap(
    modifier: Modifier,
    centerLat: Double,
    centerLon: Double,
    pins: List<MapPin>,
    lines: List<MapLine> = emptyList(),
    me: Pair<Double, Double>? = null,
    fitKey: Any? = null,
    fitToContent: Boolean = true,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        initOsm(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 5.0
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(centerLat, centerLon))
        }
    }
    val state = remember { object { var lastFit: Any? = Any() } }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }

    AndroidView(factory = { map }, modifier = modifier, update = { mv ->
        mv.overlays.clear()
        for (l in lines) {
            if (l.points.size < 2) continue
            mv.overlays.add(Polyline(mv).apply {
                setPoints(l.points.map { GeoPoint(it.first, it.second) })
                outlinePaint.color = l.color
                outlinePaint.strokeWidth = l.widthPx
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                setOnClickListener { _, _, _ -> false }
            })
        }
        for (p in pins) {
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(p.lat, p.lon)
                title = p.title
                snippet = p.snippet
                icon = dot(ctx, p.color, p.sizeDp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                if (p.onClick != null) setOnMarkerClickListener { _, _ -> p.onClick.invoke(); true }
            })
        }
        if (me != null) {
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(me.first, me.second)
                title = "Buradasınız"
                icon = dot(ctx, Color.parseColor("#1E88E5"), 18)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }
        mv.invalidate()

        if (state.lastFit != fitKey) {
            state.lastFit = fitKey
            val pts = buildList {
                if (fitToContent) {
                    lines.forEach { l -> l.points.forEach { add(GeoPoint(it.first, it.second)) } }
                    pins.forEach { add(GeoPoint(it.lat, it.lon)) }
                }
                me?.let { add(GeoPoint(it.first, it.second)) }
            }
            mv.post {
                when {
                    pts.size >= 2 -> runCatching {
                        mv.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(pts).increaseByScale(1.15f), false)
                    }
                    pts.size == 1 -> { mv.controller.setZoom(15.0); mv.controller.setCenter(pts[0]) }
                    else -> { mv.controller.setZoom(11.0); mv.controller.setCenter(GeoPoint(centerLat, centerLon)) }
                }
            }
        }
    })
}
