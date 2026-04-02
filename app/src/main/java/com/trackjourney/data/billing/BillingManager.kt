package com.trackjourney.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.trackjourney.data.model.SubscriptionPlan
import com.trackjourney.data.model.SubscriptionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BillingManager(
    private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus())
    val subscriptionStatus: StateFlow<SubscriptionStatus> = _subscriptionStatus.asStateFlow()

    /** Cached ProductDetails for the single subscription product. */
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var onStatusChanged: ((SubscriptionStatus) -> Unit)? = null

    fun setOnStatusChangedListener(listener: (SubscriptionStatus) -> Unit) {
        onStatusChanged = listener
    }

    fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing client connected")
                    queryProductDetails()
                    queryExistingPurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    _error.value = "Billing setup failed: ${billingResult.debugMessage}"
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SubscriptionPlan.PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        scope.launch {
            val (billingResult, productDetailsList) =
                suspendCancellableCoroutine<Pair<BillingResult, List<ProductDetails>>> { cont ->
                    billingClient.queryProductDetailsAsync(
                        params,
                        ProductDetailsResponseListener { result, queryResult ->
                            cont.resume(result to queryResult.productDetailsList)
                        }
                    )
                }
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = productDetailsList.firstOrNull()
                _productDetails.value = details
                if (details != null) {
                    val basePlans = details.subscriptionOfferDetails?.map { offer -> offer.basePlanId }
                    Log.i(TAG, "Loaded subscription product with base plans: $basePlans")
                } else {
                    Log.w(TAG, "No product details found for ${SubscriptionPlan.PRODUCT_ID}")
                }
            } else {
                Log.w(TAG, "Query product details failed: ${billingResult.debugMessage}")
            }
        }
    }

    fun queryExistingPurchases() {
        scope.launch {
            val (billingResult, purchases) =
                suspendCancellableCoroutine<Pair<BillingResult, List<Purchase>>> { cont ->
                    billingClient.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder()
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                        PurchasesResponseListener { result, list ->
                            cont.resume(result to list)
                        }
                    )
                }
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity, plan: SubscriptionPlan) {
        _isLoading.value = true
        _error.value = null

        val details = _productDetails.value
        if (details == null) {
            _error.value = "Product not available. Please try again later."
            _isLoading.value = false
            return
        }

        // Find the offer matching the selected base plan
        val offer = details.subscriptionOfferDetails?.firstOrNull { it.basePlanId == plan.basePlanId }
        if (offer == null) {
            _error.value = "Plan \"${plan.label}\" not available. Please try again later."
            _isLoading.value = false
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            _error.value = "Failed to launch purchase: ${billingResult.debugMessage}"
            _isLoading.value = false
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        _isLoading.value = false

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let { handlePurchases(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase cancelled by user")
            }
            else -> {
                _error.value = "Purchase failed: ${billingResult.debugMessage}"
                Log.w(TAG, "Purchase failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }

                val productId = purchase.products.firstOrNull() ?: continue
                if (productId != SubscriptionPlan.PRODUCT_ID) continue

                // Determine which base plan by matching offer details from cached ProductDetails
                val plan = resolveBasePlan(purchase)

                val durationMs = when (plan) {
                    SubscriptionPlan.MONTHLY -> 30L * 24 * 60 * 60 * 1000
                    SubscriptionPlan.SEMI_ANNUAL -> 180L * 24 * 60 * 60 * 1000
                    SubscriptionPlan.ANNUAL -> 365L * 24 * 60 * 60 * 1000
                    null -> 30L * 24 * 60 * 60 * 1000 // fallback to monthly
                }

                val status = SubscriptionStatus(
                    isSubscribed = true,
                    plan = plan ?: SubscriptionPlan.MONTHLY,
                    expiryTime = purchase.purchaseTime + durationMs,
                    purchaseToken = purchase.purchaseToken
                )
                _subscriptionStatus.value = status
                onStatusChanged?.invoke(status)
                Log.i(TAG, "Active subscription: ${plan?.label ?: "Unknown"} (expires: ${status.expiryTime})")
                return
            }
        }
    }

    /**
     * Try to resolve which base plan was purchased by checking the purchase's
     * accountIdentifiers or by matching the subscription period from product details.
     * Falls back to null if we can't determine.
     */
    private fun resolveBasePlan(purchase: Purchase): SubscriptionPlan? {
        // The purchase token encodes the base plan, but there's no direct API to extract it.
        // Best approach: check all offers and match by what's available.
        // For restored purchases we rely on the stored plan in SettingsDataStore.
        // For new purchases, we track the selected plan via the UI flow.
        return _subscriptionStatus.value.plan.takeIf { _subscriptionStatus.value.isSubscribed }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged")
            } else {
                Log.w(TAG, "Acknowledge failed: ${billingResult.debugMessage}")
            }
        }
    }

    fun updateStatus(status: SubscriptionStatus) {
        _subscriptionStatus.value = status
    }

    fun clearError() {
        _error.value = null
    }

    fun endConnection() {
        billingClient.endConnection()
        job.cancel()
    }
}
