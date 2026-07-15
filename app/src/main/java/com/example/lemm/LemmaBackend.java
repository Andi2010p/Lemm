package com.example.lemm;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app's only door to the Lemma backend (Cloud Functions, europe-west1).
 *
 * <p>Everything here is a thing the client is <b>not allowed to do by itself</b>, because the
 * database rules forbid it:
 * <ul>
 *   <li><b>askAI</b> — the Gemini key lives on the server. The app never holds it, so extracting the
 *       APK yields nothing. The server also meters the real token cost and charges credits inside a
 *       transaction, so two devices on one account cannot double-spend.</li>
 *   <li><b>claimUsername / friend + group writes</b> — these need <em>consent</em> ("did this person
 *       agree?") and <em>counting</em> ("are there already 40 members?"), neither of which Realtime
 *       Database rules can express.</li>
 *   <li><b>verifyPurchase</b> — Google, not the phone, decides what somebody paid for.</li>
 * </ul>
 *
 * <p>Every call is App Check attested (see {@link LemmApp#initAppCheck}), so a script wielding a
 * copy of {@code google-services.json} is turned away before it reaches any of this.
 */
public final class LemmaBackend {

    private static final String REGION = "europe-west1";

    private LemmaBackend() {}

    private static FirebaseFunctions fn() {
        return FirebaseFunctions.getInstance(REGION);
    }

    /** One AI request. {@code kind} is "solve" | "chat" | "scan" — it decides the credit price. */
    public static final class AiRequest {
        public final String kind;
        public final String prompt;
        public final List<String> imagesB64 = new ArrayList<>();
        public String model; // ignored by the server unless the plan unlocks model choice

        public AiRequest(String kind, String prompt) { this.kind = kind; this.prompt = prompt; }
    }

    /** What the server sends back. {@code creditsLeft} is authoritative — never compute it locally. */
    public static final class AiReply {
        public final String text;
        public final String plan;
        public final int creditsLeft;
        AiReply(String text, String plan, int creditsLeft) {
            this.text = text; this.plan = plan; this.creditsLeft = creditsLeft;
        }
    }

    public interface Callback<T> {
        void onSuccess(T value);
        /** @param code a callable error code, e.g. "resource-exhausted" when out of credits. */
        void onError(String code, String message);
    }

    // ---------- AI ----------

    public static void askAI(AiRequest req, Callback<AiReply> cb) {
        Map<String, Object> data = new HashMap<>();
        data.put("kind", req.kind);
        data.put("prompt", req.prompt);
        if (req.model != null) data.put("model", req.model);

        if (!req.imagesB64.isEmpty()) {
            List<Map<String, Object>> imgs = new ArrayList<>();
            for (String b64 : req.imagesB64) {
                Map<String, Object> img = new HashMap<>();
                img.put("mimeType", "image/jpeg");
                img.put("dataB64", b64);
                imgs.add(img);
            }
            data.put("images", imgs);
        }

        call("askAI", data, new Callback<Map<String, Object>>() {
            @Override public void onSuccess(Map<String, Object> m) {
                cb.onSuccess(new AiReply(
                        str(m.get("text")),
                        str(m.get("plan")),
                        (int) num(m.get("creditsLeft"))));
            }
            @Override public void onError(String code, String message) { cb.onError(code, message); }
        });
    }

    /** Plan + remaining credits, straight from the server. Drives the paywall and the balance line. */
    public static void getMyStatus(Callback<Map<String, Object>> cb) {
        call("getMyStatus", new HashMap<>(), cb);
    }

    // ---------- identity & social (consent lives on the server) ----------

    public static void claimUsername(String username, Callback<Map<String, Object>> cb) {
        cb = nonNull(cb);
        Map<String, Object> d = new HashMap<>();
        d.put("username", username);
        call("claimUsername", d, cb);
    }

    public static void acceptFriendRequest(String fromUid, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("fromUid", fromUid);
        call("acceptFriendRequest", d, nonNull(cb));
    }

    public static void removeFriend(String peerUid, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("peerUid", peerUid);
        call("removeFriend", d, nonNull(cb));
    }

    public static void blockUser(String peerUid, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("peerUid", peerUid);
        call("blockUser", d, nonNull(cb));
    }

    public static void createGroup(String name, List<String> memberUids, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("name", name);
        d.put("memberUids", memberUids);
        call("createGroup", d, nonNull(cb));
    }

    public static void addToGroup(String groupId, String memberUid, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("groupId", groupId);
        d.put("memberUid", memberUid);
        call("addToGroup", d, nonNull(cb));
    }

    public static void leaveGroup(String groupId, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("groupId", groupId);
        call("leaveGroup", d, nonNull(cb));
    }

    // ---------- money ----------

    /** Hands Play's purchase token to the server, which asks Google what it really was. */
    public static void verifyPurchase(String purchaseToken, String kind, String productId,
                                      Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("purchaseToken", purchaseToken);
        d.put("kind", kind);            // "subscription" | "credits"
        if (productId != null) d.put("productId", productId);
        call("verifyPurchase", d, nonNull(cb));
    }

    public static void inviteToFamily(String username, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("username", username);
        call("inviteToFamily", d, nonNull(cb));
    }

    public static void acceptFamilyInvite(String familyId, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("familyId", familyId);
        call("acceptFamilyInvite", d, nonNull(cb));
    }

    /** Owner frees a seat, or a member gives up their own. Either way the entitlement dies with it. */
    public static void removeFromFamily(String familyId, String targetUid, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("familyId", familyId);
        d.put("targetUid", targetUid);
        call("removeFromFamily", d, nonNull(cb));
    }

    // ---------- email OTP (the Gmail app password stays on the server) ----------

    public static void sendOtp(String email, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("email", email);
        call("sendOtp", d, nonNull(cb));
    }

    public static void verifyOtp(String email, String code, Callback<Map<String, Object>> cb) {
        Map<String, Object> d = new HashMap<>();
        d.put("email", email);
        d.put("code", code);
        call("verifyOtp", d, nonNull(cb));
    }

    // ---------- plumbing ----------

    @SuppressWarnings("unchecked")
    private static <T> void call(String name, Map<String, Object> data, Callback<T> cb) {
        Task<com.google.firebase.functions.HttpsCallableResult> task = fn().getHttpsCallable(name).call(data);
        task.addOnSuccessListener(res -> {
            Object v = res.getData();
            try {
                cb.onSuccess((T) v);
            } catch (ClassCastException e) {
                cb.onError("internal", "Unexpected response.");
            }
        }).addOnFailureListener(e -> {
            if (e instanceof FirebaseFunctionsException) {
                FirebaseFunctionsException ffe = (FirebaseFunctionsException) e;
                cb.onError(ffe.getCode().name().toLowerCase().replace('_', '-'), ffe.getMessage());
            } else {
                cb.onError("unavailable", e.getMessage() == null ? "Network error." : e.getMessage());
            }
        });
    }

    /** Callbacks are optional for fire-and-forget social writes. */
    private static <T> Callback<T> nonNull(Callback<T> cb) {
        if (cb != null) return cb;
        return new Callback<T>() {
            @Override public void onSuccess(T v) {}
            @Override public void onError(String code, String message) {}
        };
    }

    private static String str(Object o) { return (o == null) ? "" : String.valueOf(o); }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /** True when the server said the user is out of credits (show the paywall, not an error). */
    public static boolean isOutOfCredits(@NonNull String code) {
        return "resource-exhausted".equals(code);
    }
}
