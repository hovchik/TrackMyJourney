package com.trackjourney.ui.screens.subscription

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackjourney.data.model.SubscriptionPlan
import com.trackjourney.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val subscriptionStatus by viewModel.subscriptionStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var selectedPlan by remember { mutableStateOf(SubscriptionPlan.ANNUAL) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Premium", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Active subscription / trial badge
            if (subscriptionStatus.isActive) {
                val isTrial = subscriptionStatus.isTrialActive && !subscriptionStatus.isSubscribed
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isTrial) Accent.copy(alpha = 0.12f) else PrimaryLight.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isTrial) Icons.Filled.Timer else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (isTrial) Accent else PrimaryLight,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                if (isTrial) "Free Trial Active" else "Premium Active",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = if (isTrial) Accent else PrimaryLight
                            )
                            Text(
                                if (isTrial) "${subscriptionStatus.trialDaysRemaining} days remaining"
                                else "Plan: ${subscriptionStatus.plan?.label ?: "Unknown"}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Header
            Icon(
                Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Unlock All Features",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Get the most out of Pathwise",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Premium features list
            PremiumFeatureItem(
                icon = Icons.Filled.DirectionsCar,
                title = "My Cars",
                description = "Add car profiles and track ride costs"
            )
            PremiumFeatureItem(
                icon = Icons.Filled.CurrencyExchange,
                title = "Currency Selection",
                description = "Choose your preferred currency for ride costs"
            )
            PremiumFeatureItem(
                icon = Icons.Filled.DirectionsRun,
                title = "Activity Types",
                description = "Edit and add custom activity types with speed ranges"
            )
            PremiumFeatureItem(
                icon = Icons.Filled.Cloud,
                title = "Webhooks",
                description = "Send real-time location data to external services"
            )
            PremiumFeatureItem(
                icon = Icons.Filled.PlayCircle,
                title = "Track Playback",
                description = "Replay and animate saved GPS tracks on the map"
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Pricing plans
            Text(
                "Choose Your Plan",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            SubscriptionPlan.entries.forEach { plan ->
                PlanCard(
                    plan = plan,
                    isSelected = selectedPlan == plan,
                    isCurrentPlan = subscriptionStatus.plan == plan && subscriptionStatus.isActive,
                    onClick = { selectedPlan = plan }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Free trial button
            if (!subscriptionStatus.hasUsedTrial && !subscriptionStatus.isActive) {
                OutlinedButton(
                    onClick = {
                        viewModel.startFreeTrial()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(2.dp, Accent)
                ) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Accent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Start 7-Day Free Trial",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Accent
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "No payment required. Full access for 7 days.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Subscribe button
            Button(
                onClick = {
                    activity?.let { viewModel.purchase(it, selectedPlan) }
                },
                enabled = !isLoading && !subscriptionStatus.isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Processing...", fontSize = 16.sp)
                } else if (subscriptionStatus.isActive) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Already Subscribed", fontSize = 16.sp)
                } else {
                    Text("Subscribe ${selectedPlan.price}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Restore purchases
            TextButton(onClick = { viewModel.restorePurchases() }) {
                Icon(
                    Icons.Filled.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Restore Purchases", fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Legal text
            Text(
                "Subscriptions auto-renew unless cancelled at least 24 hours before the end of the current period. " +
                        "Payment will be charged to your Google Play account. " +
                        "Manage subscriptions in Google Play Store settings.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PremiumFeatureItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Primary.copy(alpha = 0.1f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PlanCard(
    plan: SubscriptionPlan,
    isSelected: Boolean,
    isCurrentPlan: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        isCurrentPlan -> PrimaryLight
        isSelected -> Primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = when {
        isCurrentPlan -> PrimaryLight.copy(alpha = 0.08f)
        isSelected -> Primary.copy(alpha = 0.06f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (isSelected || isCurrentPlan) 2.dp else 1.dp,
            color = borderColor
        ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isCurrentPlan) { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = { if (!isCurrentPlan) onClick() },
                colors = RadioButtonDefaults.colors(
                    selectedColor = Primary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plan.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (plan.savings.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = Accent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                plan.savings,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isCurrentPlan) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = PrimaryLight.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "Current",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryLight,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    plan.monthlyEquivalent,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    plan.price,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = if (isSelected) Primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    plan.periodLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
