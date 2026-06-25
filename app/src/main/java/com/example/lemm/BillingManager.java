package com.example.lemm;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
   //comment for comment
public class BillingManager {
    private static final String TAG = "BillingManager";
    public static final String PRODUCT_PRO_UNLOCK = "lemma_pro_unlock";

    private BillingClient billingClient;
    private Activity activity;
    private ProductDetails proProductDetails;
    private BillingListener listener;

    public interface BillingListener {
        void onBillingReady();
        void onPriceFetched(String price);
        void onPurchaseSuccess();
        void onBillingError(); // ADDED: To handle missing console setup
    }

    public BillingManager(Activity activity, BillingListener listener) {
        this.activity = activity;
        this.listener = listener;

        PurchasesUpdatedListener purchasesUpdatedListener = (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (Purchase purchase : purchases) {
                    handlePurchase(purchase);
                }
            } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
                Toast.makeText(activity, "Purchase Cancelled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(activity, "Purchase Error: " + billingResult.getDebugMessage(), Toast.LENGTH_SHORT).show();
            }
        };

        billingClient = BillingClient.newBuilder(activity)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases()
                .build();
    }

    public void startConnection() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing Setup OK");
                    checkPreviousPurchases();
                    fetchProductDetails();
                    if (listener != null) listener.onBillingReady();
                } else {
                    if (listener != null) listener.onBillingError();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.d(TAG, "Billing Disconnected");
                if (listener != null) listener.onBillingError();
            }
        });
    }

    private void fetchProductDetails() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(PRODUCT_PRO_UNLOCK)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()
                )).build();

        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsList) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && productDetailsList != null && !productDetailsList.isEmpty()) {
                proProductDetails = productDetailsList.get(0);
                String price = proProductDetails.getOneTimePurchaseOfferDetails().getFormattedPrice();
                if (listener != null) {
                    activity.runOnUiThread(() -> listener.onPriceFetched(price));
                }
            } else {
                // ADDED: If product isn't found (because console isn't set up yet)
                if (listener != null) activity.runOnUiThread(() -> listener.onBillingError());
            }
        });
    }

    public void initiatePurchase() {
        if (proProductDetails == null) {
            Toast.makeText(activity, "Product not available yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(proProductDetails)
                                .build()
                )).build();

        billingClient.launchBillingFlow(activity, billingFlowParams);
    }

    private void checkPreviousPurchases() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                        boolean isPro = false;
                        for (Purchase purchase : purchases) {
                            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                isPro = true;
                                if (!purchase.isAcknowledged()) handlePurchase(purchase);
                            }
                        }
                        saveProStatus(isPro);
                    }
                }
        );
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build();

                billingClient.acknowledgePurchase(ackParams, billingResult -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        saveProStatus(true);
                        activity.runOnUiThread(() -> {
                            if (listener != null) listener.onPurchaseSuccess();
                            Toast.makeText(activity, "Pro Unlocked Successfully!", Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } else {
                saveProStatus(true);
            }
        }
    }

    private void saveProStatus(boolean isPro) {
        if (isPro) {
            // Purchase confirmed/restored on this device: grant locally AND mirror to the cloud so
            // every device this account logs into sees Pro.
            ProStatusManager.grant(activity, false);
            return;
        }

        // isPro == false: Google Play sees no purchase on THIS device. Never revoke a Pro grant that
        // came from the 5-tap unlock (pro_bypass) or from the account in the cloud (pro_cloud).
        if (ProStatusManager.isProtected(activity)) {
            Log.d(TAG, "Pro protected (bypass/cloud). Keeping Pro unlocked.");
            return;
        }

        SharedPreferences userPrefs = activity.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE);
        userPrefs.edit().putBoolean("is_pro_user", false).apply();
    }
}