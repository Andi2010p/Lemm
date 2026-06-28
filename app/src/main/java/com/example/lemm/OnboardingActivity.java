package com.example.lemm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/**
 * A friendly, animated first-run guide (also replayable from Settings). Button-driven steps
 * (Back / Next) with a Skip that asks for confirmation, written simply enough for a 5th-grader.
 */
public class OnboardingActivity extends AppCompatActivity {

    static final String PREF_DONE = "onboarding_done";

    private final int[] types = {
            OnboardingAnimationView.TYPE_WELCOME,
            OnboardingAnimationView.TYPE_AI,
            OnboardingAnimationView.TYPE_DRAW,
            OnboardingAnimationView.TYPE_3D,
            OnboardingAnimationView.TYPE_THEOREMS,
            OnboardingAnimationView.TYPE_HISTORY
    };
    private final int[] titles = {
            R.string.onb_t_welcome, R.string.onb_t_ai, R.string.onb_t_draw,
            R.string.onb_t_3d, R.string.onb_t_theorems, R.string.onb_t_history
    };
    private final int[] descs = {
            R.string.onb_d_welcome, R.string.onb_d_ai, R.string.onb_d_draw,
            R.string.onb_d_3d, R.string.onb_d_theorems, R.string.onb_d_history
    };

    private int index = 0;
    private OnboardingAnimationView animView;
    private TextView tvTitle, tvDesc;
    private MaterialButton btnNext;
    private Button btnBack;
    private LinearLayout dots;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        animView = findViewById(R.id.animView);
        tvTitle = findViewById(R.id.tvTitle);
        tvDesc = findViewById(R.id.tvDesc);
        btnNext = findViewById(R.id.btnNext);
        btnBack = findViewById(R.id.btnBack);
        dots = findViewById(R.id.dots);

        buildDots();

        findViewById(R.id.btnSkip).setOnClickListener(v -> confirmSkip());
        btnBack.setOnClickListener(v -> { if (index > 0) showSlide(index - 1); });
        btnNext.setOnClickListener(v -> {
            if (index < types.length - 1) showSlide(index + 1);
            else finishGuide();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (index > 0) showSlide(index - 1);
                else confirmSkip();
            }
        });

        showSlide(0);
    }

    private void buildDots() {
        dots.removeAllViews();
        int sz = dp(8), gap = dp(4);
        for (int i = 0; i < types.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMargins(gap, 0, gap, 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_dot);
            dots.addView(dot);
        }
    }

    private void showSlide(int i) {
        index = i;
        animView.setType(types[i]);
        tvTitle.setText(titles[i]);
        tvDesc.setText(descs[i]);

        boolean last = i == types.length - 1;
        btnNext.setText(last ? R.string.onb_start : R.string.onb_next);
        btnBack.setVisibility(i == 0 ? View.INVISIBLE : View.VISIBLE);

        for (int d = 0; d < dots.getChildCount(); d++) {
            View dot = dots.getChildAt(d);
            dot.setAlpha(d == i ? 1f : 0.4f);
            dot.setScaleX(d == i ? 1.4f : 1f);
            dot.setScaleY(d == i ? 1.4f : 1f);
        }

        // Gentle fade + slide-in transition on the changing content.
        for (View v : new View[]{animView, tvTitle, tvDesc}) {
            v.setAlpha(0f);
            v.setTranslationX(dp(24));
            v.animate().alpha(1f).translationX(0f).setDuration(280).start();
        }
    }

    private void confirmSkip() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.onb_leave_title)
                .setMessage(R.string.onb_leave_msg)
                .setPositiveButton(R.string.onb_leave_yes, (d, w) -> finishGuide())
                .setNegativeButton(R.string.onb_leave_no, null)
                .show();
    }

    private void finishGuide() {
        getSharedPreferences("UserPrefs", MODE_PRIVATE).edit().putBoolean(PREF_DONE, true).apply();
        finish();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}
