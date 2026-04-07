package com.trackmyjourney.ui.components

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
import com.trackmyjourney.data.model.ActivityType
import com.trackmyjourney.data.model.TrackPoint
import com.trackmyjourney.ui.theme.*
import org.osmdroid.config.Configuration
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
    isPlayback: Boolean = false,
    zoomLevel: Double = 16.0,
    showActivityColors: Boolean = true,
    showSpeedColors: Boolean = false,
    showDirectionArrows: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Ensure osmdroid config is loaded before creating map
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, prefs)
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0

            // Disable built-in zoom buttons (pinch-to-zoom is sufficient)
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )

            // Use software rendering — hardware acceleration can cause blue screen on some devices
            isTilesScaledToDpi = true
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

            // Set a default world view so user doesn't see blue ocean at (0,0)
            controller.setZoom(4.0)
            controller.setCenter(GeoPoint(48.0, 10.0)) // Default to Europe
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
            mapView.controller.setZoom(zoomLevel)
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
        // During playback, point count decrease is expected (progressive draw) — don't treat as full redraw
        val needsFullRedraw = if (isPlayback) {
            lastDrawnPointCount == 0
        } else {
            pointCount < lastDrawnPointCount || lastDrawnPointCount == 0
        }

        if (trackPoints.isNotEmpty()) {
            if (needsFullRedraw) {
                // Full redraw — new track or first load
                mapView.overlays.clear()

                if (trackPoints.size >= 2) {
                    when {
                        showSpeedColors -> drawSpeedColoredTrack(mapView, trackPoints)
                        showActivityColors -> drawActivityColoredTrack(mapView, trackPoints)
                        else -> {
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
                    if (showDirectionArrows) {
                        addDirectionArrows(mapView, trackPoints)
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

            if (needsFullRedraw) {
                // On full redraw (new track or saved track load), zoom to fit the entire track
                if (trackPoints.size >= 2) {
                    val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(
                        trackPoints.map { GeoPoint(it.latitude, it.longitude) }
                    )
                    mapView.post {
                        mapView.zoomToBoundingBox(boundingBox, true, 80)
                    }
                } else {
                    val point = trackPoints.first()
                    mapView.controller.setZoom(zoomLevel)
                    mapView.controller.animateTo(GeoPoint(point.latitude, point.longitude))
                }
            } else if (centerOnUser && trackPoints.isNotEmpty()) {
                // During live tracking or playback, follow the latest point
                val lastPoint = trackPoints.last()
                val geoPoint = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                if (isPlayback) {
                    // Instant centering during playback to avoid lag
                    mapView.controller.setCenter(geoPoint)
                } else {
                    mapView.controller.animateTo(geoPoint)
                }
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
    ActivityType.HIKING     -> Hiking
    ActivityType.UNKNOWN    -> Color(0xFF757575)
}

// ─── SPEED-COLORED TRACK ─────────────────────────────────

private fun drawSpeedColoredTrack(mapView: MapView, points: List<TrackPoint>) {
    if (points.size < 2) return

    val maxSpeed = points.maxOf { it.speedKmh }.coerceAtLeast(1f)

    // Border for the entire track
    val allGeoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
    val borderLine = Polyline().apply {
        outlinePaint.apply {
            color = android.graphics.Color.argb(60, 0, 0, 0)
            strokeWidth = 16f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        setPoints(allGeoPoints)
    }
    mapView.overlays.add(borderLine)

    // Draw each segment colored by speed
    for (i in 0 until points.size - 1) {
        val avgSpeed = (points[i].speedKmh + points[i + 1].speedKmh) / 2f
        val ratio = (avgSpeed / maxSpeed).coerceIn(0f, 1f)
        val segColor = speedToColor(ratio)

        val segment = Polyline().apply {
            outlinePaint.apply {
                color = segColor
                strokeWidth = 10f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            setPoints(listOf(
                GeoPoint(points[i].latitude, points[i].longitude),
                GeoPoint(points[i + 1].latitude, points[i + 1].longitude)
            ))
        }
        mapView.overlays.add(segment)
    }
}

private fun speedToColor(ratio: Float): Int {
    val r: Int
    val g: Int
    val b: Int
    when {
        ratio < 0.25f -> { // Green (slow)
            val t = ratio / 0.25f
            r = (76 + t * (200 - 76)).toInt()
            g = (175 + t * (230 - 175)).toInt()
            b = (80 - t * 80).toInt()
        }
        ratio < 0.5f -> { // Yellow (moderate)
            val t = (ratio - 0.25f) / 0.25f
            r = (200 + t * 55).toInt()
            g = (230 - t * 50).toInt()
            b = 0
        }
        ratio < 0.75f -> { // Orange (fast)
            val t = (ratio - 0.5f) / 0.25f
            r = 255
            g = (180 - t * 100).toInt()
            b = 0
        }
        else -> { // Red (very fast)
            val t = (ratio - 0.75f) / 0.25f
            r = 255
            g = (80 - t * 80).toInt()
            b = (t * 30).toInt()
        }
    }
    return android.graphics.Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}

// ─── DIRECTION ARROWS ────────────────────────────────────

private fun addDirectionArrows(mapView: MapView, points: List<TrackPoint>) {
    if (points.size < 3) return

    // Place arrows at regular intervals (every ~12 points or at least 5 arrows)
    val interval = (points.size / 8).coerceIn(5, 25)

    for (i in interval until points.size step interval) {
        val pt = points[i]
        val prev = points[(i - 1).coerceAtLeast(0)]

        val bearing = pt.bearing ?: calculateBearing(
            prev.latitude, prev.longitude, pt.latitude, pt.longitude
        ).toFloat()

        if (bearing.isNaN() || bearing.isInfinite()) continue

        val marker = Marker(mapView).apply {
            position = GeoPoint(pt.latitude, pt.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createArrowMarker(mapView, bearing)
            setInfoWindow(null) // No popup on tap
        }
        mapView.overlays.add(marker)
    }
}

private fun createArrowMarker(
    mapView: MapView,
    bearing: Float
): BitmapDrawable {
    val density = mapView.context.resources.displayMetrics.density
    val sizePx = (20 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val r = sizePx * 0.38f

    // Rotate canvas by bearing (0 = north/up)
    canvas.save()
    canvas.rotate(bearing, cx, cy)

    // Draw arrow pointing up (north)
    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(2f * density, 0f, 1f * density, android.graphics.Color.argb(100, 0, 0, 0))
    }

    val path = android.graphics.Path().apply {
        moveTo(cx, cy - r)             // top point
        lineTo(cx - r * 0.55f, cy + r * 0.6f) // bottom left
        lineTo(cx, cy + r * 0.2f)      // notch
        lineTo(cx + r * 0.55f, cy + r * 0.6f) // bottom right
        close()
    }
    canvas.drawPath(path, arrowPaint)

    // Dark outline
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(150, 30, 30, 30)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    canvas.drawPath(path, outlinePaint)

    canvas.restore()
    return BitmapDrawable(mapView.context.resources, bitmap)
}

private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2Rad)
    val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLon)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
}
