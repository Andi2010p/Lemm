package com.example.lemm;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.BlockThreshold;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.ai.client.generativeai.type.GenerationConfig;
import com.google.ai.client.generativeai.type.HarmCategory;
import com.google.ai.client.generativeai.type.SafetySetting;
import com.google.common.util.concurrent.ListenableFuture;
import android.graphics.Bitmap;
import java.util.Arrays;

public class GeminiAI {
    /**
     * Solve models in priority order. We lead with Gemini 3 (newest, best reasoning + diagrams) and
     * fall back to the production-stable 2.5 model. The solver ({@code GeometryInputActivity}) walks
     * this list automatically when a model is persistently overloaded (503), so we get Gemini 3's
     * quality without regressing reliability when its preview pool is busy.
     */
    public static final String[] SOLVE_MODELS = { "gemini-3-flash-preview", "gemini-2.5-flash" };

    private GenerativeModelFutures textModel;
    private GenerativeModelFutures visionModel;

    public GeminiAI(String apiKey) { this(apiKey, SOLVE_MODELS[0]); }

    public GeminiAI(String apiKey, String modelName) {
        // 1. Lower safety thresholds so math words (like "cut", "strike") don't trigger false errors
        SafetySetting harass = new SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.ONLY_HIGH);
        SafetySetting hate = new SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.ONLY_HIGH);
        SafetySetting sex = new SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.ONLY_HIGH);
        SafetySetting danger = new SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.ONLY_HIGH);

        // 2. Low temperature for deterministic, accurate math; large output budget for long proofs.
        GenerationConfig.Builder cfg = new GenerationConfig.Builder();
        cfg.temperature = 0.2f;
        cfg.topP = 0.95f;
        cfg.maxOutputTokens = 8192;

        if (modelName == null || modelName.isEmpty()) modelName = SOLVE_MODELS[0];
        GenerativeModel gm = new GenerativeModel(
                modelName,
                apiKey,
                cfg.build(),
                Arrays.asList(harass, hate, sex, danger)
        );

        this.textModel = GenerativeModelFutures.from(gm);
        this.visionModel = GenerativeModelFutures.from(gm);
    }

    public ListenableFuture<GenerateContentResponse> getSolution(String prompt) {
        Content content = new Content.Builder().addText(prompt).build();
        return textModel.generateContent(content);
    }

    /** Multimodal solve: one or more images (problem text and/or figures) plus the prompt. */
    public ListenableFuture<GenerateContentResponse> getSolutionWithImages(java.util.List<Bitmap> images, String prompt) {
        Content.Builder builder = new Content.Builder();
        if (images != null) {
            for (Bitmap bmp : images) {
                if (bmp != null) builder.addImage(bmp);
            }
        }
        builder.addText(prompt);
        return visionModel.generateContent(builder.build());
    }

    public ListenableFuture<GenerateContentResponse> extractTextFromImage(Bitmap bitmap, String prompt) {
        Content content = new Content.Builder()
                .addImage(bitmap)
                .addText(prompt)
                .build();
        return visionModel.generateContent(content);
    }
}