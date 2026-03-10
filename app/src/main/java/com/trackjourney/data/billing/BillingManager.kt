package com.trackjourney.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.trackjourney.data.model.SubscriptionPlan
import com.trackjourney.data.model.SubscriptionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BillingManager(
    private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
    }

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatus())
    val subscriptionStatus: StateFlow<SubscriptionStatus> = _subscriptionStatus.asStateFlow()

    private val _productDetails = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetails: StateFlow<Map<String, ProductDetails>> = _productDetails.asStateFlow()

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
        // Query subscriptions (monthly, semi-annual, annual)
        val subProducts = SubscriptionPlan.entries
            .filter { it != SubscriptionPlan.LIFETIME }
            .map { plan ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(plan.productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }

        // Query in-app (lifetime)
        val inAppProducts = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SubscriptionPlan.LIFETIME.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        // Query subscriptions
        if (subProducts.isNotEmpty()) {
            val subParams = QueryProductDetailsParams.newBuilder()
                .setProductList(subProducts)
                .build()
            billingClient.queryProductDetailsAsync(subParams) { billingResult, productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val currentMap = _productDetails.value.toMutableMap()
                    productDetailsList.forEach { details ->
                        currentMap[details.productId] = details
                    }
                    _productDetails.value = currentMap
                    Log.i(TAG, "Loaded ${productDetailsList.size} subscription products")
                }
            }
        }

        // Query in-app purchases
        val inAppParams = QueryProductDetailsParams.newBuilder()
            .setProductList(inAppProducts)
            .build()
        billingClient.queryProductDetailsAsync(inAppParams) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val currentMap = _productDetails.value.toMutableMap()
                productDetailsList.forEach { details ->
                    currentMap[details.productId] = details
                }
                _productDetails.value = currentMap
                Log.i(TAG, "Loaded ${productDetailsList.size} in-app products")
            }
        }
    }

    fun queryExistingPurchases() {
        // Check subscriptions
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }

        // Check in-app (lifetime)
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity, plan: SubscriptionPlan) {
        _isLoading.value = true
        _error.value = null

        val details = _productDetails.value[plan.productId]
        if (details == null) {
            _error.value = "Product not available. Please try again later."
            _isLoading.value = false
            return
        }

        val productDetailsParamsList = if (plan == SubscriptionPlan.LIFETIME) {
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .build()
            )
        } else {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                _error.value = "Subscription offer not available."
                _isLoading.value = false
                return
            }
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .setOfferToken(offerToken)
                    .build()
            )
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
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
                // Acknowledge the purchase if not yet acknowledged
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }

                // Determine which plan was purchased
                val productId = purchase.products.firstOrNull() ?: continue
                val plan = SubscriptionPlan.fromProductId(productId) ?: continue

                val expiryTime = if (plan == SubscriptionPlan.LIFETIME) {
                    Long.MAX_VALUE
                } else {
                    // For subscriptions, estimate expiry from purchase time
                    val durationMs = when (plan) {
                        SubscriptionPlan.MONTHLY -> 30L * 24 * 60 * 60 * 1000
                        SubscriptionPlan.SEMI_ANNUAL -> 180L * 24 * 60 * 60 * 1000
                        SubscriptionPlan.ANNUAL -> 365L * 24 * 60 * 60 * 1000
                        SubscriptionPlan.LIFETIME -> 0L
                    }
                    purchase.purchaseTime + durationMs
                }

                val status = SubscriptionStatus(
                    isSubscribed = true,
                    plan = plan,
                    expiryTime = expiryTime,
                    purchaseToken = purchase.purchaseToken
                )
                _subscriptionStatus.value = status
                onStatusChanged?.invoke(status)
                Log.i(TAG, "Active subscription: ${plan.label} (expires: $expiryTime)")
                return
            }
        }
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
    }
}
