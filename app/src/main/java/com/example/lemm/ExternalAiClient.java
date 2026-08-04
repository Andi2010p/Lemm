package com.example.lemm;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Calls non-Gemini AI providers — OpenAI (GPT, Chat Completions API) and Anthropic (Claude, Messages
 * API) — over raw HTTPS with the user's own API key. Deliberately dependency-free (HttpURLConnection +
 * org.json, both already used across the app) so we don't add heavy server SDKs to the APK. Runs on a
 * background thread and delivers the result on the main thread. Gemini keeps its own SDK path.
 */
public final class ExternalAiClient {

    private ExternalAiClient() {}

    public interface Callback {
        void onText(String text);
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Sends {@code prompt} (+ optional images) to the app's currently selected external provider. */
    public static void generate(Context ctx, String prompt, List<Bitmap> images, Callback cb) {
        final AiConfig.Provider p = AiConfig.provider(ctx);
        final String key = AiConfig.key(ctx, p);
        final String model = AiConfig.model(ctx, p);
        new Thread(() -> {
            try {
                String text = (p == AiConfig.Provider.OPENAI)
                        ? callOpenAi(key, model, prompt, images)
                        : callClaude(key, model, prompt, images);
                if (text == null) text = "";
                final String out = text;
                MAIN.post(() -> cb.onText(out));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Request failed" : e.getMessage();
                MAIN.post(() -> cb.onError(msg));
            }
        }).start();
    }

    // ---------------- OpenAI (Chat Completions) ----------------

    private static String callOpenAi(String key, String model, String prompt, List<Bitmap> images) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 8192);

        // One user message whose content is [text, image_url, image_url, ...].
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", prompt));
        if (images != null) {
            for (Bitmap bmp : images) {
                if (bmp == null) continue;
                JSONObject img = new JSONObject();
                img.put("type", "image_url");
                img.put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64(bmp)));
                content.put(img);
            }
        }
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));
        body.put("messages", messages);

        JSONObject resp = new JSONObject(post(
                "https://api.openai.com/v1/chat/completions",
                new String[][]{ {"Authorization", "Bearer " + key} },
                body.toString()));

        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("Empty response from OpenAI.");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String text = (msg != null) ? msg.optString("content", "") : "";
        if (text.trim().isEmpty()) throw new Exception("OpenAI returned no text.");
        return text;
    }

    // ---------------- Anthropic (Messages API) ----------------

    private static String callClaude(String key, String model, String prompt, List<Bitmap> images) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 8192);

        // Anthropic image blocks go BEFORE the text block in the content list.
        JSONArray content = new JSONArray();
        if (images != null) {
            for (Bitmap bmp : images) {
                if (bmp == null) continue;
                JSONObject src = new JSONObject();
                src.put("type", "base64");
                src.put("media_type", "image/jpeg");
                src.put("data", b64(bmp));
                content.put(new JSONObject().put("type", "image").put("source", src));
            }
        }
        content.put(new JSONObject().put("type", "text").put("text", prompt));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", content));
        body.put("messages", messages);

        JSONObject resp = new JSONObject(post(
                "https://api.anthropic.com/v1/messages",
                new String[][]{ {"x-api-key", key}, {"anthropic-version", "2023-06-01"} },
                body.toString()));

        if ("refusal".equals(resp.optString("stop_reason"))) {
            throw new Exception("Claude declined this request for safety reasons.");
        }
        JSONArray content2 = resp.optJSONArray("content");
        if (content2 == null) throw new Exception("Empty response from Claude.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content2.length(); i++) {
            JSONObject block = content2.getJSONObject(i);
            if ("text".equals(block.optString("type"))) sb.append(block.optString("text"));
        }
        String text = sb.toString();
        if (text.trim().isEmpty()) throw new Exception("Claude returned no text.");
        return text;
    }

    // ---------------- HTTP + helpers ----------------

    private static final int MAX_ATTEMPTS = 3;

    /** A transient failure (429 / 5xx / network) worth retrying, carrying any server-asked delay. */
    private static final class Transient extends Exception {
        final long retryAfterMs;
        Transient(String message, long retryAfterMs) { super(message); this.retryAfterMs = retryAfterMs; }
    }

    /**
     * POSTs with automatic retry: transient errors (rate limits, 5xx, dropped connections) are retried
     * up to {@link #MAX_ATTEMPTS} times with exponential backoff, honouring a {@code Retry-After} header
     * when the server sends one. Permanent errors (bad key, bad model) fail immediately — retrying them
     * would only waste the user's time.
     */
    private static String post(String url, String[][] headers, String jsonBody) throws Exception {
        long backoff = 900;
        for (int attempt = 1; ; attempt++) {
            try {
                return postOnce(url, headers, jsonBody);
            } catch (Transient t) {
                if (attempt >= MAX_ATTEMPTS) throw new Exception(t.getMessage());
                long wait = t.retryAfterMs > 0 ? t.retryAfterMs : backoff;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new Exception("Request interrupted");
                }
                backoff = Math.min(backoff * 2, 8000);
            }
        }
    }

    private static String postOnce(String url, String[][] headers, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            for (String[] h : headers) c.setRequestProperty(h[0], h[1]);

            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(payload);
            c.getOutputStream().flush();

            int code = c.getResponseCode();
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            String resp = readAll(is);
            if (code >= 400) {
                if (isTransient(code)) throw new Transient(friendlyError(code, resp), retryAfterMs(c));
                throw new Exception(friendlyError(code, resp));
            }
            return resp;
        } catch (java.io.IOException io) {
            // Reset connection / timeout / DNS blip — worth another attempt.
            throw new Transient("Network error: " + io.getMessage(), 0);
        } finally {
            c.disconnect();
        }
    }

    private static boolean isTransient(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    /** Reads a numeric {@code Retry-After} (seconds) header, capped so a rogue value can't hang us. */
    private static long retryAfterMs(HttpURLConnection c) {
        try {
            String h = c.getHeaderField("Retry-After");
            if (h != null) return Math.min(Long.parseLong(h.trim()) * 1000L, 15000L);
        } catch (Exception ignored) {}
        return 0;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Turns an API error body into a short, useful message (extracts error.message when present). */
    private static String friendlyError(int code, String resp) {
        String detail = resp;
        try {
            JSONObject o = new JSONObject(resp);
            JSONObject err = o.optJSONObject("error");
            if (err != null && err.has("message")) detail = err.getString("message");
            else if (o.has("message")) detail = o.getString("message");
        } catch (Exception ignored) {}
        if (code == 401) return "Invalid API key (401). Check your key in Settings.";
        if (code == 429) return "Rate limited or out of quota (429). Try again later.";
        if (code == 404) return "Model not found (404). Check the model name in Settings. " + detail;
        if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
        return "AI error " + code + ": " + detail;
    }

    private static String b64(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
