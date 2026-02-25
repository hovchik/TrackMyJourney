package com.trackjourney.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackjourney.data.model.ActivityType
import com.trackjourney.data.model.TrackPoint
import com.trackjourney.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sin

/**
 * Activity Timeline Graph — draws a horizontal timeline for a track,
 * showing activity-type transitions with distinct visual line patterns:
 *
 *   - - -      walking  (dashed line)
 *   *---*      driving  (dots connected by solid line)
 *   ~~~        flying   (gentle wave)
 *   ∿∿∿        running  (tight wave)
 *   ooooo      cycling  (circles)
 *   ·····      stationary (dotted)
 */
@Composable
fun ActivityTimelineGraph(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) return

    val segments = remember(points) { buildActivitySegments(points) }
    if (segments.isEmpty()) return

    val trackStartTime = points.first().timestamp
    val trackEndTime = points.last().timestamp
    val totalDuration = (trackEndTime - trackStartTime).coerceAtLeast(1L)

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("yy.MM.dd", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Activity Timeline",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Timeline canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            val canvasWidth = size.width
            val centerY = size.height / 2f
            val lineY = centerY
            val strokeWidth = 3.5f

            // Draw each segment with its activity pattern
            for (seg in segments) {
                val startFraction = (seg.startTime - trackStartTime).toFloat() / totalDuration
                val endFraction = (seg.endTime - trackStartTime).toFloat() / totalDuration
                val startX = startFraction * canvasWidth
                val endX = endFraction * canvasWidth

                if (endX - startX < 2f) continue

                val color = activityToColor(seg.activity)

                when (seg.activity) {
                    ActivityType.WALKING    -> drawDashedLine(startX, endX, lineY, color, strokeWidth)
                    ActivityType.DRIVING    -> drawDotSolidLine(startX, endX, lineY, color, strokeWidth)
                    ActivityType.FLYING     -> drawWavyLine(startX, endX, lineY, color, strokeWidth, wavelength = 24f, amplitude = 8f)
                    ActivityType.RUNNING    -> drawWavyLine(startX, endX, lineY, color, strokeWidth, wavelength = 12f, amplitude = 6f)
                    ActivityType.CYCLING    -> drawCircleLine(startX, endX, lineY, color, strokeWidth)
                    ActivityType.STATIONARY -> drawDottedLine(startX, endX, lineY, color, strokeWidth)
                    ActivityType.HIKING     -> drawDashedLine(startX, endX, lineY, color, strokeWidth)
                    ActivityType.UNKNOWN    -> drawDottedLine(startX, endX, lineY, Color.Gray, strokeWidth)
                }

                // Start marker (small circle)
                drawCircle(
                    color = color,
                    radius = 5f,
                    center = Offset(startX.coerceIn(5f, canvasWidth - 5f), lineY)
                )
            }

            // End marker
            val lastColor = activityToColor(segments.last().activity)
            drawCircle(
                color = lastColor,
                radius = 5f,
                center = Offset((canvasWidth - 5f).coerceAtLeast(5f), lineY)
            )
        }

        // Timestamp labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    dateFmt.format(Date(trackStartTime)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    timeFmt.format(Date(trackStartTime)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Show middle timestamp if track has transitions
            if (segments.size > 1) {
                val midTime = trackStartTime + totalDuration / 2
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        dateFmt.format(Date(midTime)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        timeFmt.format(Date(midTime)),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    dateFmt.format(Date(trackEndTime)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    timeFmt.format(Date(trackEndTime)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Compact legend row (FlowRow wraps to next line when space runs out)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val usedActivities = segments.map { it.activity }.distinct()
            usedActivities.forEach { activity ->
                LegendItem(activity)
            }
        }
    }
}

@Composable
private fun LegendItem(activity: ActivityType) {
    val color = activityToColor(activity)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 14.dp)) {
            val y = size.height / 2f
            val sw = 2.8f
            when (activity) {
                ActivityType.WALKING    -> drawDashedLine(0f, size.width, y, color, sw)
                ActivityType.DRIVING    -> drawDotSolidLine(0f, size.width, y, color, sw)
                ActivityType.FLYING     -> drawWavyLine(0f, size.width, y, color, sw, 10f, 4f)
                ActivityType.RUNNING    -> drawWavyLine(0f, size.width, y, color, sw, 6f, 3f)
                ActivityType.CYCLING    -> drawCircleLine(0f, size.width, y, color, sw)
                ActivityType.STATIONARY -> drawDottedLine(0f, size.width, y, color, sw)
                ActivityType.HIKING     -> drawDashedLine(0f, size.width, y, color, sw)
                ActivityType.UNKNOWN    -> drawDottedLine(0f, size.width, y, Color.Gray, sw)
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            activity.name.lowercase().replaceFirstChar { it.uppercase() },
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─── Drawing helpers ───────────────────────────────

/** - - - dashed line (walking) */
private fun DrawScope.drawDashedLine(
    startX: Float, endX: Float, y: Float, color: Color, strokeWidth: Float
) {
    val dashLen = 10f
    val gapLen = 6f
    var x = startX
    while (x < endX) {
        val dashEnd = (x + dashLen).coerceAtMost(endX)
        drawLine(color, Offset(x, y), Offset(dashEnd, y), strokeWidth)
        x = dashEnd + gapLen
    }
}

/** *---* dots connected by solid line (driving) */
private fun DrawScope.drawDotSolidLine(
    startX: Float, endX: Float, y: Float, color: Color, strokeWidth: Float
) {
    // Solid line
    drawLine(color, Offset(startX, y), Offset(endX, y), strokeWidth)
    // Dots at intervals
    val interval = 16f
    var x = startX
    while (x <= endX) {
        drawCircle(color, radius = strokeWidth + 1f, center = Offset(x, y))
        x += interval
    }
}

/** ~~~ gentle wave (flying) or tight wave (running) */
private fun DrawScope.drawWavyLine(
    startX: Float, endX: Float, y: Float, color: Color, strokeWidth: Float,
    wavelength: Float, amplitude: Float
) {
    val path = Path()
    path.moveTo(startX, y)
    var x = startX
    while (x <= endX) {
        val wavY = y + amplitude * sin((x - startX) * (2 * Math.PI / wavelength)).toFloat()
        path.lineTo(x, wavY)
        x += 1f
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

/** ooooo circles (cycling) */
private fun DrawScope.drawCircleLine(
    startX: Float, endX: Float, y: Float, color: Color, strokeWidth: Float
) {
    val radius = 4.5f
    val spacing = radius * 2.5f
    var x = startX + radius
    while (x <= endX - radius) {
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(x, y),
            style = Stroke(width = strokeWidth * 0.7f)
        )
        x += spacing
    }
}

/** ..... dotted line (stationary) */
private fun DrawScope.drawDottedLine(
    startX: Float, endX: Float, y: Float, color: Color, strokeWidth: Float
) {
    val dotRadius = strokeWidth * 0.6f
    val spacing = dotRadius * 4f
    var x = startX
    while (x <= endX) {
        drawCircle(color, radius = dotRadius, center = Offset(x, y))
        x += spacing
    }
}

// ─── Segment building ──────────────────────────────

data class ActivitySegment(
    val activity: ActivityType,
    val startTime: Long,
    val endTime: Long
)

private fun buildActivitySegments(points: List<TrackPoint>): List<ActivitySegment> {
    if (points.isEmpty()) return emptyList()

    val segments = mutableListOf<ActivitySegment>()
    var currentActivity = points.first().activityType
    var segmentStart = points.first().timestamp

    for (i in 1 until points.size) {
        val pt = points[i]
        if (pt.activityType != currentActivity) {
            segments.add(ActivitySegment(currentActivity, segmentStart, points[i - 1].timestamp))
            currentActivity = pt.activityType
            segmentStart = pt.timestamp
        }
    }
    // Last segment
    segments.add(ActivitySegment(currentActivity, segmentStart, points.last().timestamp))

    // Merge very short segments (< 10 seconds) into neighbors
    return mergeShortSegments(segments)
}

private fun mergeShortSegments(segments: List<ActivitySegment>): List<ActivitySegment> {
    if (segments.size <= 1) return segments

    val merged = mutableListOf<ActivitySegment>()
    var current = segments.first()

    for (i in 1 until segments.size) {
        val next = segments[i]
        if (current.endTime - current.startTime < 10_000L) {
            // Merge short segment into the next one
            current = ActivitySegment(
                activity = next.activity,
                startTime = current.startTime,
                endTime = next.endTime
            )
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}

private fun activityToColor(type: ActivityType): Color = when (type) {
    ActivityType.WALKING    -> Walking
    ActivityType.RUNNING    -> Running
    ActivityType.CYCLING    -> Cycling
    ActivityType.DRIVING    -> Driving
    ActivityType.FLYING     -> Flying
    ActivityType.STATIONARY -> Stationary
    ActivityType.HIKING     -> Hiking
    ActivityType.UNKNOWN    -> Color.Gray
}
