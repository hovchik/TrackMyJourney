package com.trackjourney.ui.screens.subscription

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.billing.BillingManager
import com.trackjourney.data.local.SettingsDataStore
import com.trackjourney.data.model.SubscriptionPlan
import com.trackjourney.data.model.SubscriptionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val billingManager: BillingManager,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val subscriptionStatus: StateFlow<SubscriptionStatus> = settingsDataStore.subscriptionStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubscriptionStatus())

    val isLoading: StateFlow<Boolean> = billingManager.isLoading

    val error: StateFlow<String?> = billingManager.error

    init {
        billingManager.setOnStatusChangedListener { status ->
            viewModelScope.launch {
                settingsDataStore.updateSubscriptionStatus(status)
            }
        }
        billingManager.startConnection()

        // Sync persisted status into BillingManager
        viewModelScope.launch {
            settingsDataStore.subscriptionStatus.collect { status ->
                billingManager.updateStatus(status)
            }
        }
    }

    fun purchase(activity: Activity, plan: SubscriptionPlan) {
        // Pre-set the plan so handlePurchases can resolve the base plan
        viewModelScope.launch {
            val currentStatus = subscriptionStatus.value
            billingManager.updateStatus(currentStatus.copy(plan = plan))
        }
        billingManager.launchPurchaseFlow(activity, plan)
    }

    fun startFreeTrial() {
        viewModelScope.launch {
            settingsDataStore.startFreeTrial()
        }
    }

    fun restorePurchases() {
        billingManager.queryExistingPurchases()
    }

    fun clearError() {
        billingManager.clearError()
    }

    override fun onCleared() {
        super.onCleared()
        billingManager.endConnection()
    }
}
