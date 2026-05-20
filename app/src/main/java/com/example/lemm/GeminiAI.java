package com.example.lemm;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.ListenableFuture;
import android.graphics.Bitmap;

public class GeminiAI {
    private GenerativeModelFutures textModel;
    private GenerativeModelFutures visionModel;

    public GeminiAI(String apiKey) {
        GenerativeModel gm = new GenerativeModel("gemini-3-flash-preview", apiKey);
        this.textModel = GenerativeModelFutures.from(gm);
        this.visionModel = GenerativeModelFutures.from(gm);
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
        return visionModel.generateContent(content);
    }
}