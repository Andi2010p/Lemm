package com.example.lemm;

import android.graphics.Bitmap;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.ListenableFuture;

public class GeminiAI {
    private GenerativeModelFutures textModel;
    private GenerativeModelFutures visionModel;

    public GeminiAI(String apiKey) {
        // Model for normal text solving
        GenerativeModel gmText = new GenerativeModel("gemini-3-flash-preview", apiKey);
        this.textModel = GenerativeModelFutures.from(gmText);

        // Model SPECIFICALLY for images (Required for older SDK versions)
        GenerativeModel gmVision = new GenerativeModel("gemini-pro-vision", apiKey);
        this.visionModel = GenerativeModelFutures.from(gmVision);
    }

    public ListenableFuture<GenerateContentResponse> getSolution(String prompt) {
        Content content = new Content.Builder().addText(prompt).build();
        return textModel.generateContent(content);
    }

    public ListenableFuture<GenerateContentResponse> extractTextFromImage(Bitmap bitmap, String prompt) {
        Content content = new Content.Builder()
                .addImage(bitmap)
                .addText(prompt)
                .build();
        // IMPORTANT: Use the vision model here!
        return visionModel.generateContent(content);
    }
}