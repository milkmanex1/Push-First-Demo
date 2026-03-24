package com.pushfirst.demo

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*

private const val TAG = "BILLING_DEBUG"
const val PRODUCT_ID = "pushfirst_pro"
const val BASE_PLAN_MONTHLY = "monthly"
const val BASE_PLAN_YEARLY = "yearly"

class BillingManager(
    private val context: Context,
    private val onPurchaseSuccess: () -> Unit,
    private val onPurchaseCancelled: () -> Unit,
    private val onPurchaseError: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cached after queryProductDetails()
    private var productDetailsList: List<ProductDetails>? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(TAG, "onPurchasesUpdated — code=${billingResult.responseCode} msg=${billingResult.debugMessage}")
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                Log.d(TAG, "Purchase OK — ${purchases?.size ?: 0} purchase(s)")
                purchases?.forEach { purchase ->
                    scope.launch { handlePurchase(purchase) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User cancelled the purchase flow")
                onPurchaseCancelled()
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned — verifying via queryPurchasesAsync")
                scope.launch {
                    val isActive = checkSubscriptionStatus()
                    Log.d(TAG, "ITEM_ALREADY_OWNED verification — isActive=$isActive")
                    if (isActive) onPurchaseSuccess()
                    else onPurchaseError("Could not verify existing subscription.")
                }
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                Log.e(TAG, "Billing unavailable: ${billingResult.debugMessage}")
                onPurchaseError("Billing unavailable. Please check your Play Store account.")
            }
            BillingClient.BillingResponseCode.ERROR -> {
                Log.e(TAG, "Billing error: ${billingResult.debugMessage}")
                onPurchaseError("Purchase failed. Please try again.")
            }
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
                Log.e(TAG, "Developer error: ${billingResult.debugMessage}")
                onPurchaseError("Configuration error. Please contact support.")
            }
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                Log.e(TAG, "Service unavailable — check network")
                onPurchaseError("No internet connection. Please try again.")
            }
            else -> {
                Log.w(TAG, "Unhandled response code ${billingResult.responseCode}: ${billingResult.debugMessage}")
                onPurchaseError("Unexpected error (${billingResult.responseCode}).")
            }
        }
    }

    val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases()
        .build()

    // ── Connect ──────────────────────────────────────────────────────────────

    fun connect(onReady: () -> Unit = {}) {
        Log.d(TAG, "Starting billing connection…")
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(TAG, "onBillingSetupFinished — code=${billingResult.responseCode}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { queryProductDetails() }
                    onReady()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected — will retry on next operation")
            }
        })
    }

    // ── Product details ──────────────────────────────────────────────────────

    private suspend fun queryProductDetails() {
        Log.d(TAG, "Querying product details for $PRODUCT_ID…")
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        val result = billingClient.queryProductDetails(params)
        Log.d(TAG, "queryProductDetails — code=${result.billingResult.responseCode} count=${result.productDetailsList?.size}")

        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            productDetailsList = result.productDetailsList
            result.productDetailsList?.forEach { pd ->
                Log.d(TAG, "  Product: ${pd.productId}")
                pd.subscriptionOfferDetails?.forEach { offer ->
                    Log.d(TAG, "    BasePlan=${offer.basePlanId} offerToken=${offer.offerToken.take(20)}…")
                }
            }
        } else {
            Log.e(TAG, "queryProductDetails failed: ${result.billingResult.debugMessage}")
        }
    }

    // ── Purchase flow ─────────────────────────────────────────────────────────

    fun launchPurchaseFlow(activity: Activity, basePlanId: String) {
        Log.d(TAG, "launchPurchaseFlow — basePlan=$basePlanId")

        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient not ready — reconnecting…")
            connect(onReady = { launchPurchaseFlow(activity, basePlanId) })
            return
        }

        val productDetails = productDetailsList?.firstOrNull { it.productId == PRODUCT_ID }
        if (productDetails == null) {
            Log.e(TAG, "Product details not loaded yet for $PRODUCT_ID")
            onPurchaseError("Products not loaded yet. Please wait a moment and try again.")
            return
        }

        val offerToken = productDetails.subscriptionOfferDetails
            ?.firstOrNull { it.basePlanId == basePlanId }
            ?.offerToken

        if (offerToken == null) {
            Log.e(TAG, "No offer token found for base plan: $basePlanId")
            onPurchaseError("This plan is not available right now.")
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        Log.d(TAG, "launchBillingFlow — code=${result.responseCode} msg=${result.debugMessage}")
    }

    // ── Subscription status ───────────────────────────────────────────────────

    suspend fun checkSubscriptionStatus(): Boolean {
        if (!billingClient.isReady) {
            Log.w(TAG, "checkSubscriptionStatus — client not ready, returning false")
            return false
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        Log.d(TAG, "checkSubscriptionStatus — code=${result.billingResult.responseCode} purchases=${result.purchasesList.size}")

        return result.purchasesList.any { purchase ->
            val active = purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.contains(PRODUCT_ID)
            Log.d(TAG, "  Purchase: ${purchase.products} state=${purchase.purchaseState} active=$active")
            active
        }
    }

    // ── Restore purchases ─────────────────────────────────────────────────────

    fun restorePurchases() {
        Log.d(TAG, "restorePurchases triggered")
        scope.launch {
            val hasActive = checkSubscriptionStatus()
            Log.d(TAG, "restorePurchases — hasActiveSub=$hasActive")
            if (hasActive) onPurchaseSuccess()
            else onPurchaseError("No active subscription found to restore.")
        }
    }

    // ── Acknowledge ───────────────────────────────────────────────────────────

    private suspend fun handlePurchase(purchase: Purchase) {
        Log.d(TAG, "handlePurchase — state=${purchase.purchaseState} acknowledged=${purchase.isAcknowledged}")
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                val result = billingClient.acknowledgePurchase(params)
                Log.d(TAG, "acknowledgePurchase — code=${result.responseCode} msg=${result.debugMessage}")
            }
            onPurchaseSuccess()
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.d(TAG, "Purchase is PENDING — waiting for completion")
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun destroy() {
        Log.d(TAG, "BillingManager.destroy()")
        scope.cancel()
        if (billingClient.isReady) billingClient.endConnection()
    }
}
