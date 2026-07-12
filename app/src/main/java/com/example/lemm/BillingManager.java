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
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
   //comment for comment
public class BillingManager {
    private static final String TAG = "BillingManager";
    public static final String PRODUCT_PRO_UNLOCK = "lemma_pro_unlock";

    // ---- Subscriptions. ONE product per plan, with two base plans inside it (monthly / annual) —
    // that is how Play has modelled subscriptions since Billing v5.
    public static final String SUB_PLUS = "lemma_plus";
    public static final String SUB_FAMILY = "lemma_family";
    public static final String SUB_CLASSROOM = "lemma_classroom";
    public static final String[] SUBSCRIPTIONS = { SUB_PLUS, SUB_FAMILY, SUB_CLASSROOM };

    public static final String BASE_MONTHLY = "monthly";
    public static final String BASE_ANNUAL = "annual";

    // ---- Consumable credit top-ups. These ids MUST match functions/lib/plans.js PRODUCTS, or the
    // server's verifyPurchase rejects the purchase as an unknown product and the user pays for nothing.
    public static final String CREDITS_100 = "lemma_credits_100";
    public static final String CREDITS_400 = "lemma_credits_400";
    public static final String CREDITS_1200 = "lemma_credits_1200";
    public static final String[] CREDIT_PACKS = { CREDITS_100, CREDITS_400, CREDITS_1200 };

    /** Credits a pack grants. 1 credit = 1 solved problem — the unit the user actually understands. */
    public static int creditsForPack(String productId) {
        if (CREDITS_100.equals(productId)) return 100;
        if (CREDITS_400.equals(productId)) return 400;
        if (CREDITS_1200.equals(productId)) return 1200;
        return 0;
    }

    /** Legacy offline path: the client-side TokenWallet still thinks in tokens, not credits. */
    public static long tokensForPack(String productId) {
        return (long) creditsForPack(productId) * Entitlements.APPROX_TOKENS_PER_SOLVE;
    }

    /** One purchasable base plan of a subscription (e.g. Plus / annual / "$39.99" / "P1Y"). */
    public static final class SubOffer {
        public final String productId, basePlanId, formattedPrice, billingPeriod, offerToken;
        SubOffer(String productId, String basePlanId, String formattedPrice, String billingPeriod, String offerToken) {
            this.productId = productId; this.basePlanId = basePlanId;
            this.formattedPrice = formattedPrice; this.billingPeriod = billingPeriod; this.offerToken = offerToken;
        }
        public boolean isAnnual() { return billingPeriod != null && billingPeriod.contains("Y"); }
    }

    private BillingClient billingClient;
    private Activity activity;
    private ProductDetails proProductDetails;
    private final Map<String, ProductDetails> packDetails = new HashMap<>();
    private final Map<String, ProductDetails> subDetails = new HashMap<>();
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

        // Billing 8: the no-arg enablePendingPurchases() was REMOVED — one-time products must be opted
        // in explicitly. enableAutoServiceReconnection() lets the library re-bind after a disconnect.
        billingClient = BillingClient.newBuilder(activity)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
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
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PRO_UNLOCK)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());
        for (String pack : CREDIT_PACKS) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(pack)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build());
        }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products).build();

        fetchSubscriptions();

        // Billing 8: the callback now hands back a QueryProductDetailsResult (fetched + unfetched),
        // not a bare List<ProductDetails>.
        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsResult) -> {
            List<ProductDetails> productDetailsList = (productDetailsResult == null)
                    ? null : productDetailsResult.getProductDetailsList();
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && productDetailsList != null && !productDetailsList.isEmpty()) {
                for (ProductDetails pd : productDetailsList) {
                    if (PRODUCT_PRO_UNLOCK.equals(pd.getProductId())) {
                        proProductDetails = pd;
                        if (listener != null && pd.getOneTimePurchaseOfferDetails() != null) {
                            String price = pd.getOneTimePurchaseOfferDetails().getFormattedPrice();
                            activity.runOnUiThread(() -> listener.onPriceFetched(price));
                        }
                    } else if (creditsForPack(pd.getProductId()) > 0) {
                        packDetails.put(pd.getProductId(), pd);
                    }
                }
            } else {
                // No products found (Play Console not set up yet).
                if (listener != null) activity.runOnUiThread(() -> listener.onBillingError());
            }
        });
    }

    /** Subscriptions are a SEPARATE query — Play will not mix SUBS and INAPP in one product list. */
    private void fetchSubscriptions() {
        List<QueryProductDetailsParams.Product> subs = new ArrayList<>();
        for (String id : SUBSCRIPTIONS) {
            subs.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build());
        }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(subs).build();

        billingClient.queryProductDetailsAsync(params, (result, details) -> {
            List<ProductDetails> list = (details == null) ? null : details.getProductDetailsList();
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || list == null) return;
            for (ProductDetails pd : list) subDetails.put(pd.getProductId(), pd);
            if (listener != null) activity.runOnUiThread(() -> listener.onBillingReady());
        });
    }

    /** The base plans (monthly / annual) of a subscription, with live localized prices from Play. */
    public List<SubOffer> offersFor(String productId) {
        List<SubOffer> out = new ArrayList<>();
        ProductDetails pd = subDetails.get(productId);
        if (pd == null || pd.getSubscriptionOfferDetails() == null) return out;

        for (ProductDetails.SubscriptionOfferDetails off : pd.getSubscriptionOfferDetails()) {
            List<ProductDetails.PricingPhase> phases = off.getPricingPhases().getPricingPhaseList();
            if (phases.isEmpty()) continue;
            // The LAST phase is the recurring price; earlier ones are free trials / intro offers.
            ProductDetails.PricingPhase p = phases.get(phases.size() - 1);
            out.add(new SubOffer(productId, off.getBasePlanId(),
                    p.getFormattedPrice(), p.getBillingPeriod(), off.getOfferToken()));
        }
        return out;
    }

    /**
     * Starts a subscription purchase.
     *
     * <p>{@code setObfuscatedAccountId(uid)} is what lets the SERVER prove the purchase belongs to
     * the account redeeming it: Play echoes it back through the Developer API, and
     * {@code functions/lib/play.js#applySubscription} refuses a token whose account id doesn't match
     * the caller. Without it, one person's purchase could be replayed by a different account.
     */
    public void purchaseSubscription(SubOffer offer) {
        if (offer == null || subDetails.get(offer.productId) == null) {
            Toast.makeText(activity, activity.getString(R.string.plan_unavailable), Toast.LENGTH_SHORT).show();
            return;
        }
        BillingFlowParams.Builder flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(subDetails.get(offer.productId))
                                .setOfferToken(offer.offerToken)
                                .build()));

        String uid = Social.uid();
        if (uid != null) flow.setObfuscatedAccountId(uid);

        billingClient.launchBillingFlow(activity, flow.build());
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

    /** Launches the buy flow for a consumable token pack. */
    public void initiateTokenPurchase(String productId) {
        ProductDetails pd = packDetails.get(productId);
        if (pd == null) {
            Toast.makeText(activity, "Token pack not available yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        BillingFlowParams flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(pd)
                                .build()
                )).build();
        billingClient.launchBillingFlow(activity, flow);
    }

    /** Localized price for a token pack, or null if it hasn't loaded (console not set up). */
    public String tokenPackPrice(String productId) {
        ProductDetails pd = packDetails.get(productId);
        if (pd == null || pd.getOneTimePurchaseOfferDetails() == null) return null;
        return pd.getOneTimePurchaseOfferDetails().getFormattedPrice();
    }

    private static String firstCreditPack(Purchase purchase) {
        for (String id : purchase.getProducts()) {
            if (creditsForPack(id) > 0) return id;
        }
        return null;
    }

    private static String firstSubscription(Purchase purchase) {
        for (String id : purchase.getProducts()) {
            for (String sub : SUBSCRIPTIONS) if (sub.equals(id)) return id;
        }
        return null;
    }

    /** Play auto-refunds a purchase that isn't acknowledged within 3 days. */
    private void acknowledge(Purchase purchase) {
        if (purchase.isAcknowledged()) return;
        billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build(),
                r -> Log.d(TAG, "ack: " + r.getResponseCode()));
    }

    /**
     * Re-checks the user's live subscriptions on every launch. This is what restores a plan after a
     * reinstall, and what re-syncs entitlement if an RTDN was missed while the server was down.
     */
    private void restoreSubscriptions() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || purchases == null) return;
                    for (Purchase p : purchases) handlePurchase(p);
                });
    }

    private void checkPreviousPurchases() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                        boolean isPro = false;
                        for (Purchase purchase : purchases) {
                            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                            if (purchase.getProducts().contains(PRODUCT_PRO_UNLOCK)) {
                                isPro = true;
                                if (!purchase.isAcknowledged()) handlePurchase(purchase);
                            } else if (firstCreditPack(purchase) != null) {
                                // A credit pack bought earlier but never consumed (e.g. app was killed) —
                                // consume it now and credit the user so their money isn't lost.
                                handlePurchase(purchase);
                            }
                        }
                        saveProStatus(isPro);
                    }
                }
        );
        restoreSubscriptions();
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;

        // ---- Subscriptions. The SERVER decides what this token is actually worth (it asks Google).
        // We must still ACKNOWLEDGE within 3 days or Play automatically refunds the user.
        String sub = firstSubscription(purchase);
        if (sub != null) {
            LemmaBackend.verifyPurchase(purchase.getPurchaseToken(), "subscription", sub,
                    new LemmaBackend.Callback<Map<String, Object>>() {
                        @Override public void onSuccess(Map<String, Object> v) {
                            acknowledge(purchase);
                            activity.runOnUiThread(() -> {
                                if (listener != null) listener.onPurchaseSuccess();
                            });
                        }
                        @Override public void onError(String code, String message) {
                            // Acknowledge regardless — the user really did pay, and letting Play
                            // auto-refund would be worse for everyone.
                            acknowledge(purchase);
                            // "permission-denied" = the purchase belongs to a different account. Do
                            // NOT hand out access for that. Any other error means our backend is
                            // simply unreachable, so fall back to the local flag so a paying user
                            // isn't left with nothing.
                            if (!"permission-denied".equals(code)) saveProStatus(true);
                            activity.runOnUiThread(() -> {
                                if (listener != null) listener.onPurchaseSuccess();
                            });
                        }
                    });
            return;
        }

        // ---- Credit top-ups are CONSUMABLE: consume them (so they can be re-bought) and credit the
        // user. On Cloud AI the SERVER grants the credits; otherwise the local wallet does.
        String pack = firstCreditPack(purchase);
        if (pack != null) {
            final int credits = creditsForPack(pack);
            final long tokens = tokensForPack(pack);
            final String productId = pack;

            Runnable consumeAndFinish = () -> {
                ConsumeParams cp = ConsumeParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build();
                billingClient.consumeAsync(cp, (result, token) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                    activity.runOnUiThread(() -> {
                        if (listener != null) listener.onPurchaseSuccess();
                        Toast.makeText(activity,
                                activity.getString(R.string.credits_added, credits),
                                Toast.LENGTH_LONG).show();
                    });
                });
            };

            if (AiPrefs.cloudEnabled(activity)) {
                LemmaBackend.verifyPurchase(purchase.getPurchaseToken(), "credits", productId,
                        new LemmaBackend.Callback<Map<String, Object>>() {
                            @Override public void onSuccess(Map<String, Object> v) { consumeAndFinish.run(); }
                            @Override public void onError(String code, String message) {
                                // Backend unreachable — don't lose the user's money; grant locally.
                                if (!"permission-denied".equals(code)) TokenWallet.addExtra(activity, tokens);
                                consumeAndFinish.run();
                            }
                        });
            } else {
                TokenWallet.addExtra(activity, tokens);
                consumeAndFinish.run();
            }
            return;
        }

        {
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