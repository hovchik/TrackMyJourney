package com.trackjourney.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackjourney.data.model.ActivityType
import com.trackjourney.data.model.TrackPoint
import com.trackjourney.ui.theme.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    trackPoints: List<TrackPoint> = emptyList(),
    currentLatitude: Double? = null,
    currentLongitude: Double? = null,
    centerOnUser: Boolean = true,
    zoomLevel: Double = 16.0,
    showActivityColors: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(zoomLevel)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0

            // Disable built-in zoom buttons (pinch-to-zoom is sufficient)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )

            // Performance
            isTilesScaledToDpi = true
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        }
    }

    // Track whether we've centered on initial position
    var hasCenteredOnInitial by remember { mutableStateOf(false) }
    // Track last drawn point count so we only do incremental updates
    var lastDrawnPointCount by remember { mutableIntStateOf(0) }

    // Lifecycle management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Center on current position when first available and no track points
    LaunchedEffect(currentLatitude, currentLongitude) {
        if (!hasCenteredOnInitial && currentLatitude != null && currentLongitude != null && trackPoints.isEmpty()) {
            mapView.controller.animateTo(GeoPoint(currentLatitude, currentLongitude))
            hasCenteredOnInitial = true

            mapView.overlays.removeAll { it is Marker && (it as Marker).title == "You are here" }
            val marker = Marker(mapView).apply {
                position = GeoPoint(currentLatitude, currentLongitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createCircleMarker(mapView, PrimaryLight.toArgb(), 28, 4)
                title = "You are here"
                snippet = "Current position"
            }
            mapView.overlays.add(marker)
            mapView.invalidate()
        }
    }

    // Update track overlay — full redraw only when point count resets (new track)
    // or on first draw; otherwise just update the end marker position
    LaunchedEffect(trackPoints.size) {
        val pointCount = trackPoints.size
        val needsFullRedraw = pointCount < lastDrawnPointCount || lastDrawnPointCount == 0

        if (trackPoints.isNotEmpty()) {
            if (needsFullRedraw) {
                // Full redraw — new track or first load
                mapView.overlays.clear()

                if (trackPoints.size >= 2) {
                    if (showActivityColors) {
                        drawActivityColoredTrack(mapView, trackPoints)
                    } else {
                        val borderLine = Polyline().apply {
                            outlinePaint.apply {
                                color = android.graphics.Color.argb(80, 0, 0, 0)
                                strokeWidth = 16f
                                style = Paint.Style.STROKE
                                isAntiAlias = true
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            }
                            setPoints(trackPoints.map { GeoPoint(it.latitude, it.longitude) })
                        }
                        mapView.overlays.add(borderLine)

                        val polyline = Polyline().apply {
                            outlinePaint.apply {
                                color = Primary.toArgb()
                                strokeWidth = 10f
                                style = Paint.Style.STROKE
                                isAntiAlias = true
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            }
                            setPoints(trackPoints.map { GeoPoint(it.latitude, it.longitude) })
                        }
                        mapView.overlays.add(polyline)
                    }
                }

                // Start marker
                val startPoint = trackPoints.first()
                val startMarker = Marker(mapView).apply {
                    position = GeoPoint(startPoint.latitude, startPoint.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = createCircleMarker(mapView, PrimaryLight.toArgb(), 30, 5)
                    title = startPoint.placeName ?: "Start"
                    snippet = if (startPoint.placeName != null) "Track started here" else null
                }
                mapView.overlays.add(startMarker)

                // End marker
                val lastPoint = trackPoints.last()
                val endMarker = Marker(mapView).apply {
                    position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = createCircleMarker(mapView, Secondary.toArgb(), 30, 5)
                    title = lastPoint.placeName ?: "Current"
                    snippet = "${String.format(Locale.US, "%.1f", lastPoint.speedKmh)} km/h"
                }
                mapView.overlays.add(endMarker)
            } else {
                // Incremental update — just update polyline points and move end marker
                val allGeoPoints = trackPoints.map { GeoPoint(it.latitude, it.longitude) }

                // Update existing polylines in place
                mapView.overlays.filterIsInstance<Polyline>().forEach { polyline ->
                    polyline.setPoints(allGeoPoints)
                }

                // Move end marker to latest position
                val lastPoint = trackPoints.last()
                val endMarkers = mapView.overlays.filterIsInstance<Marker>()
                    .filter { it.title != "Start" && it.title != "You are here" && !(it.title?.startsWith("Track") == true) }
                endMarkers.lastOrNull()?.let { marker ->
                    marker.position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                    marker.snippet = "${String.format(Locale.US, "%.1f", lastPoint.speedKmh)} km/h"
                }
            }

            lastDrawnPointCount = pointCount

            // Center map on latest point
            if (centerOnUser) {
                val lastPoint = trackPoints.last()
                mapView.controller.animateTo(GeoPoint(lastPoint.latitude, lastPoint.longitude))
            }
        } else if (currentLatitude != null && currentLongitude != null) {
            mapView.overlays.clear()
            lastDrawnPointCount = 0
            val marker = Marker(mapView).apply {
                position = GeoPoint(currentLatitude, currentLongitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createCircleMarker(mapView, PrimaryLight.toArgb(), 28, 4)
                title = "You are here"
                snippet = "Current position"
            }
            mapView.overlays.add(marker)
        } else {
            mapView.overlays.clear()
            lastDrawnPointCount = 0
        }

        mapView.invalidate()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

private fun createCircleMarker(
    mapView: MapView,
    fillColor: Int,
    sizeDp: Int,
    borderWidthDp: Int
): BitmapDrawable {
    val density = mapView.context.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()
    val borderPx = borderWidthDp * density

    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val radius = center - borderPx / 2f

    // White border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    // Colored fill
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius - borderPx, fillPaint)

    // Inner white dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius * 0.3f, dotPaint)

    return BitmapDrawable(mapView.context.resources, bitmap)
}

private fun drawActivityColoredTrack(mapView: MapView, points: List<TrackPoint>) {
    if (points.size < 2) return

    // First pass: draw dark border for the entire track
    val allGeoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
    val borderLine = Polyline().apply {
        outlinePaint.apply {
            color = android.graphics.Color.argb(80, 0, 0, 0)
            strokeWidth = 16f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        setPoints(allGeoPoints)
    }
    mapView.overlays.add(borderLine)

    // Second pass: draw colored segments on top
    var segmentStart = 0
    var currentActivity = points[0].activityType

    for (i in 1 until points.size) {
        if (points[i].activityType != currentActivity || i == points.size - 1) {
            val endIdx = if (i == points.size - 1) i else i - 1
            val segmentPoints = points.subList(segmentStart, endIdx + 1)

            if (segmentPoints.size >= 2) {
                val polyline = Polyline().apply {
                    outlinePaint.apply {
                        color = activityColor(currentActivity).toArgb()
                        strokeWidth = 10f
                        style = Paint.Style.STROKE
                        isAntiAlias = true
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    setPoints(segmentPoints.map { GeoPoint(it.latitude, it.longitude) })
                }
                mapView.overlays.add(polyline)
            }

            segmentStart = i
            currentActivity = points[i].activityType
        }
    }
}

private fun activityColor(activity: ActivityType): Color = when (activity) {
    ActivityType.WALKING    -> Walking
    ActivityType.RUNNING    -> Running
    ActivityType.CYCLING    -> Cycling
    ActivityType.DRIVING    -> Driving
    ActivityType.FLYING     -> Flying
    ActivityType.STATIONARY -> Stationary
    ActivityType.UNKNOWN    -> Color(0xFF757575)
}
