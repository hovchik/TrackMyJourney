package com.trackjourney.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.trackjourney.ui.theme.Primary
import com.trackjourney.ui.theme.PrimaryLight
import com.trackjourney.ui.theme.Secondary
import com.trackjourney.ui.theme.Accent
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 5

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> HowToTrackPage()
                    2 -> ActivityDetectionPage()
                    3 -> InsightsAndMapsPage()
                    4 -> PermissionsPage(onOnboardingComplete = onOnboardingComplete)
                }
            }

            // Bottom bar: page indicators + navigation
            BottomNavBar(
                currentPage = pagerState.currentPage,
                pageCount = PAGE_COUNT,
                onNext = {
                    if (pagerState.currentPage < PAGE_COUNT - 1) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                onBack = {
                    if (pagerState.currentPage > 0) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }
                },
                onSkipToPermissions = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(PAGE_COUNT - 1)
                    }
                }
            )
        }
    }
}

// ─── Page 1: Welcome ───────────────────────────────────

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Primary,
            modifier = Modifier.size(100.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "Welcome to\nPathwise",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 34.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Your intelligent journey companion.\nTrack trips, analyze routes, and get\nAI-powered insights — all in one app.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            FeaturePill(Icons.Filled.Route, "Track")
            FeaturePill(Icons.Filled.Analytics, "Analyze")
            FeaturePill(Icons.Filled.AutoAwesome, "AI Insights")
        }
    }
}

@Composable
private fun FeaturePill(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = Primary.copy(alpha = 0.12f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Primary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Page 2: How to Track ──────────────────────────────

@Composable
private fun HowToTrackPage() {
    HowToPage(
        icon = Icons.Filled.PlayCircle,
        iconColor = PrimaryLight,
        title = "Track Your Journeys",
        subtitle = "Recording your trips is simple and automatic.",
        steps = listOf(
            HowToStep(
                Icons.Filled.ToggleOn,
                "Start Tracking",
                "Tap the toggle at the top of any screen to begin recording your journey."
            ),
            HowToStep(
                Icons.Filled.GpsFixed,
                "GPS Records Your Route",
                "Your phone's GPS captures your path, speed, altitude, and distance in real-time."
            ),
            HowToStep(
                Icons.Filled.Lock,
                "Works in Background",
                "Tracking continues even when your phone is locked or you switch apps."
            ),
            HowToStep(
                Icons.Filled.Stop,
                "Stop & Save",
                "Toggle off to stop. Your journey is saved automatically and available in Tracks."
            )
        )
    )
}

// ─── Page 3: Smart Activity Detection ──────────────────

@Composable
private fun ActivityDetectionPage() {
    HowToPage(
        icon = Icons.Filled.DirectionsRun,
        iconColor = Accent,
        title = "Smart Activity Detection",
        subtitle = "AI automatically recognizes how you're moving.",
        steps = listOf(
            HowToStep(
                Icons.Filled.DirectionsWalk,
                "Walking & Hiking",
                "Slow, steady movement patterns are detected as walking or hiking based on terrain."
            ),
            HowToStep(
                Icons.Filled.DirectionsBike,
                "Cycling",
                "Medium speeds with consistent movement are recognized as cycling."
            ),
            HowToStep(
                Icons.Filled.DirectionsCar,
                "Driving & Transport",
                "Higher speeds on roads are classified as driving, with ride cost tracking."
            ),
            HowToStep(
                Icons.Filled.Watch,
                "Wearable Integration",
                "Connect a Bluetooth smartwatch for cadence, step count, and more precise activity data."
            )
        )
    )
}

// ─── Page 4: Insights & Maps ───────────────────────────

@Composable
private fun InsightsAndMapsPage() {
    HowToPage(
        icon = Icons.Filled.AutoAwesome,
        iconColor = Secondary,
        title = "AI Insights & Maps",
        subtitle = "Understand your journeys with smart analysis.",
        steps = listOf(
            HowToStep(
                Icons.Filled.Map,
                "Interactive Maps",
                "View your recorded routes on a detailed map with speed coloring and waypoints."
            ),
            HowToStep(
                Icons.Filled.Psychology,
                "AI Analysis",
                "Get daily and weekly behavior summaries powered by local or cloud AI models."
            ),
            HowToStep(
                Icons.Filled.BarChart,
                "Statistics & Trends",
                "Track distance, speed trends, elevation, and activity statistics over time."
            ),
            HowToStep(
                Icons.Filled.Share,
                "Export & Webhooks",
                "Export tracks as JSON/GPX or send live data to external services via webhooks."
            )
        )
    )
}

// ─── Page 5: Permissions ───────────────────────────────

@Composable
private fun PermissionsPage(onOnboardingComplete: () -> Unit) {
    val context = LocalContext.current

    // ── Permission states ──

    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var backgroundLocationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    var activityRecognitionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    var bluetoothGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val internetGranted = true

    // ── Launchers ──

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> backgroundLocationGranted = granted }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationGranted = granted }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> activityRecognitionGranted = granted }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        bluetoothGranted = permissions.values.all { it }
    }

    val allGranted = locationGranted && backgroundLocationGranted &&
            notificationGranted && activityRecognitionGranted && bluetoothGranted

    // ── Grant All helper ──
    val grantAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityRecognitionGranted = permissions[Manifest.permission.ACTIVITY_RECOGNITION] == true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            shape = CircleShape,
            color = Primary,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Security, null, modifier = Modifier.size(32.dp), tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Enable Permissions",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            "Pathwise needs these permissions to work properly.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Grant All button
        if (!allGranted) {
            FilledTonalButton(
                onClick = {
                    val perms = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                        perms.add(Manifest.permission.BLUETOOTH_SCAN)
                    }
                    grantAllLauncher.launch(perms.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.DoneAll, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Grant All Permissions", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Individual permission cards
        PermissionCard(
            icon = Icons.Filled.LocationOn,
            title = "Location Access",
            description = "Required to track your trips and record GPS data.",
            isGranted = locationGranted,
            onRequest = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Filled.ShareLocation,
            title = "Background Location",
            description = "Allows tracking to continue when the app is in the background.",
            isGranted = backgroundLocationGranted,
            enabled = locationGranted,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Filled.DirectionsWalk,
            title = "Activity Recognition",
            description = "Detects walking, running, cycling, and driving automatically.",
            isGranted = activityRecognitionGranted,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Filled.Notifications,
            title = "Notifications",
            description = "Shows tracking status while recording in the background.",
            isGranted = notificationGranted,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Filled.Bluetooth,
            title = "Bluetooth",
            description = "Connect to smartwatches and fitness trackers for health data.",
            isGranted = bluetoothGranted,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                        )
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PermissionCard(
            icon = Icons.Filled.Wifi,
            title = "Internet",
            description = "Used for map tiles, webhooks, and cloud AI insights.",
            isGranted = internetGranted,
            onRequest = {}
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Get Started button
        Button(
            onClick = onOnboardingComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted) Primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (allGranted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            enabled = locationGranted
        ) {
            Text(
                text = if (allGranted) "Get Started"
                else if (locationGranted) "Continue"
                else "Grant Location to Continue",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (!locationGranted) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Location permission is required to use the app.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ─── Shared How-To Page Layout ─────────────────────────

private data class HowToStep(
    val icon: ImageVector,
    val title: String,
    val description: String
)

@Composable
private fun HowToPage(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    steps: List<HowToStep>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            shape = CircleShape,
            color = iconColor.copy(alpha = 0.12f),
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(36.dp), tint = iconColor)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            subtitle,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        steps.forEachIndexed { index, step ->
            HowToStepCard(
                stepNumber = index + 1,
                icon = step.icon,
                title = step.title,
                description = step.description
            )
            if (index < steps.lastIndex) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HowToStepCard(
    stepNumber: Int,
    icon: ImageVector,
    title: String,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = Primary.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Primary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ─── Bottom Navigation Bar ─────────────────────────────

@Composable
private fun BottomNavBar(
    currentPage: Int,
    pageCount: Int,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkipToPermissions: () -> Unit
) {
    val isLastPage = currentPage == pageCount - 1

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Page indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pageCount) { index ->
                    val isActive = index == currentPage
                    val width by animateDpAsState(
                        targetValue = if (isActive) 24.dp else 8.dp,
                        label = "indicator_width"
                    )
                    val color by animateColorAsState(
                        targetValue = if (isActive) Primary else MaterialTheme.colorScheme.outlineVariant,
                        label = "indicator_color"
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation buttons
            if (!isLastPage) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentPage > 0) {
                        TextButton(onClick = onBack) {
                            Text("Back")
                        }
                    } else {
                        TextButton(onClick = onSkipToPermissions) {
                            Text("Skip")
                        }
                    }

                    Button(
                        onClick = onNext,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Next")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ─── Permission Card ───────────────────────────────────

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    enabled: Boolean = true,
    onRequest: () -> Unit
) {
    val cardAlpha = if (enabled) 1f else 0.5f

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isGranted)
            PrimaryLight.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isGranted) PrimaryLight.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        icon, null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isGranted) PrimaryLight
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = cardAlpha)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = cardAlpha)
                )
                Text(
                    description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = cardAlpha),
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGranted) {
                Surface(
                    shape = CircleShape,
                    color = PrimaryLight,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Filled.Check, "Granted", modifier = Modifier.size(16.dp), tint = Color.White)
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = onRequest,
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Grant", fontSize = 13.sp)
                }
            }
        }
    }
}
