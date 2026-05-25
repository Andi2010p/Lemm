package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.card.MaterialCardView;

public class TheoremListActivity extends AppCompatActivity {

    private LinearLayout theoremListContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theorem_list);

        try {
            findViewById(R.id.btnBack).setOnClickListener(v -> finish());
            theoremListContainer = findViewById(R.id.theoremListContainer);
            TextView tvListTitle = findViewById(R.id.tvListTitle);

            // Get the grade number that was clicked
            int grade = getIntent().getIntExtra("GRADE", 7);

            // FIXED: Now uses the translated string (e.g. "Программа 7 класса" or "7-րդ դասարան")
            if (tvListTitle != null) {
                tvListTitle.setText(getString(R.string.grade_title, grade));
            }

            // Dynamically find up to 20 theorems for this grade in strings.xml
            boolean foundAny = false;
            for (int topic = 1; topic <= 20; topic++) {
                int titleId = getResources().getIdentifier("th_title_" + grade + "_" + topic, "string", getPackageName());
                if (titleId != 0) {
                    addTheoremCard(getString(titleId), grade, topic);
                    foundAny = true;
                }
            }

            // FIXED: Now uses the translated loading text
            if (!foundAny && theoremListContainer != null) {
                TextView emptyText = new TextView(this);
                emptyText.setText(getString(R.string.loading_advanced, grade));
                emptyText.setPadding(16, 32, 16, 32);
                emptyText.setTextColor(ContextCompat.getColor(this, R.color.text_subtitle));
                theoremListContainer.addView(emptyText);
            }
        } catch (Exception e) {
            Toast.makeText(this, "UI Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addTheoremCard(String title, int grade, int topic) {
        if (theoremListContainer == null) return;

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 160); // Fixed height
        params.setMargins(0, 0, 0, 24);
        card.setLayoutParams(params);
        card.setRadius(16f);
        card.setCardElevation(4f);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_white));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(32, 0, 32, 0);

        // Icon
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_agenda);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.main_blue));
        icon.setLayoutParams(new LinearLayout.LayoutParams(64, 64));

        // Text
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(18f);
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(32, 0, 0, 0);

        layout.addView(icon);
        layout.addView(tvTitle);
        card.addView(layout);

        // Click opens the Curriculum Canvas
        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, GradeCurriculumActivity.class);
            intent.putExtra("GRADE", grade);
            intent.putExtra("TOPIC", topic);
            intent.putExtra("THEOREM_TITLE", title); // Passes the translated title perfectly!
            startActivity(intent);
        });

        theoremListContainer.addView(card);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}