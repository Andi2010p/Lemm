'use strict';

/**
 * The Gemini proxy.
 *
 * The API key lives here, in a Cloud Functions secret, and never in the APK. The app sends a prompt
 * and gets text back; it never sees a key, so extracting the APK yields nothing.
 *
 * We call the REST endpoint directly rather than pulling in a client SDK: one less dependency to
 * drift, and `usageMetadata` — the thing we actually need for honest metering — is right there in
 * the response.
 */

const ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/models';

/** Models the backend is willing to run. An arbitrary client-supplied model is never used. */
const ALLOWED_MODELS = new Set([
  'gemini-2.5-flash-lite',   // the free tier — ~5x cheaper, keeps a free plan affordable
  'gemini-2.5-flash',        // paid plans
  'gemini-3-flash-preview',
]);

const DEFAULT_MODEL = 'gemini-2.5-flash-lite';

/**
 * @param {object} p
 * @param {string} p.apiKey
 * @param {string} p.prompt
 * @param {Array<{mimeType:string,dataB64:string}>} [p.images]
 * @param {string} [p.model]
 * @param {number} [p.maxOutputTokens]
 * @returns {Promise<{text:string, promptTokens:number, outputTokens:number, finishReason:string}>}
 */
async function generate({ apiKey, prompt, images = [], model, maxOutputTokens = 16384 }) {
  const useModel = ALLOWED_MODELS.has(model) ? model : DEFAULT_MODEL;

  const parts = [];
  for (const img of images) {
    parts.push({ inline_data: { mime_type: img.mimeType || 'image/jpeg', data: img.dataB64 } });
  }
  parts.push({ text: prompt });

  const body = {
    contents: [{ role: 'user', parts }],
    generationConfig: { maxOutputTokens, temperature: 0.4 },
  };

  const res = await fetch(`${ENDPOINT}/${useModel}:generateContent?key=${encodeURIComponent(apiKey)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    const err = new Error(`Gemini ${res.status}: ${detail.slice(0, 300)}`);
    err.status = res.status;
    // 429 = out of quota on OUR key. The user did nothing wrong; they must not be charged.
    err.retryable = res.status === 429 || res.status >= 500;
    throw err;
  }

  const json = await res.json();

  const candidate = (json.candidates && json.candidates[0]) || null;
  const finishReason = (candidate && candidate.finishReason) || 'UNKNOWN';

  // A safety block returns 200 with no text. That is NOT a successful answer, and the caller must
  // refund — the old Android client billed the user for exactly this case.
  const text = candidate && candidate.content && Array.isArray(candidate.content.parts)
    ? candidate.content.parts.map((p) => p.text || '').join('')
    : '';

  const usage = json.usageMetadata || {};
  return {
    text,
    finishReason,
    model: useModel, // which model actually ran — cost accounting differs ~5x between them
    // The REAL billable numbers. `promptTokenCount` includes the entire system prompt and the
    // few-shot exemplars, which the old client-side character estimate never counted.
    promptTokens: usage.promptTokenCount || 0,
    outputTokens: (usage.candidatesTokenCount || 0) + (usage.thoughtsTokenCount || 0),
  };
}

module.exports = { generate, ALLOWED_MODELS, DEFAULT_MODEL };
