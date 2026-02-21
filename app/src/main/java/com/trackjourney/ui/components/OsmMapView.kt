package com.trackjourney.ui.components

import android.graphics.Paint
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

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    trackPoints: List<TrackPoint> = emptyList(),
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

            // Performance
            isTilesScaledToDpi = true
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        }
    }

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

    // Update track overlay when points change
    LaunchedEffect(trackPoints) {
        mapView.overlays.clear()

        if (trackPoints.isNotEmpty()) {
            if (showActivityColors) {
                // Draw colored segments by activity type
                drawActivityColoredTrack(mapView, trackPoints)
            } else {
                // Single-color track line
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

            // Start marker
            val startPoint = trackPoints.first()
            val startMarker = Marker(mapView).apply {
                position = GeoPoint(startPoint.latitude, startPoint.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Start"
                snippet = "Track started here"
            }
            mapView.overlays.add(startMarker)

            // Current position / end marker
            val lastPoint = trackPoints.last()
            val endMarker = Marker(mapView).apply {
                position = GeoPoint(lastPoint.latitude, lastPoint.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Current"
                snippet = "${String.format("%.1f", lastPoint.speedKmh)} km/h"
            }
            mapView.overlays.add(endMarker)

            // Center map on latest point
            if (centerOnUser) {
                mapView.controller.animateTo(GeoPoint(lastPoint.latitude, lastPoint.longitude))
            }
        }

        mapView.invalidate()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}

private fun drawActivityColoredTrack(mapView: MapView, points: List<TrackPoint>) {
    if (points.size < 2) return

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
