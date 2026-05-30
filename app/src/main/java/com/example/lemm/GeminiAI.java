package com.example.lemm;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.BlockThreshold;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.ai.client.generativeai.type.HarmCategory;
import com.google.ai.client.generativeai.type.SafetySetting;
import com.google.common.util.concurrent.ListenableFuture;
import android.graphics.Bitmap;
import java.util.Arrays;

public class GeminiAI {
    private GenerativeModelFutures textModel;
    private GenerativeModelFutures visionModel;

    public GeminiAI(String apiKey) {
        // 1. Lower safety thresholds so math words (like "cut", "strike") don't trigger false errors
        SafetySetting harass = new SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.ONLY_HIGH);
        SafetySetting hate = new SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.ONLY_HIGH);
        SafetySetting sex = new SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.ONLY_HIGH);
        SafetySetting danger = new SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.ONLY_HIGH);

        // Production-stable flash model. We previously used "gemini-3-flash-preview", which is a
        // preview pool and returns 503 ("Service Unavailable / overloaded") for almost every call.
        GenerativeModel gm = new GenerativeModel(
                "gemini-2.5-flash",
                apiKey,
                null,
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