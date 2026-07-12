package com.example.lemm;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class TheoremsActivity extends AppCompatActivity {
    private boolean styleGlass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StyleManager.apply(this);
        setContentView(R.layout.activity_theorems);
        styleGlass = StyleManager.isGlass(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnHelpTheorems).setOnClickListener(v -> HelpDialog.show(this, R.string.help_title, R.string.help_theorems_body));

        // Now these open the Theorem List instead of the Canvas directly
        findViewById(R.id.btnGrade7).setOnClickListener(v -> openGrade(7));
        findViewById(R.id.btnGrade8).setOnClickListener(v -> openGrade(8));
        findViewById(R.id.btnGrade9).setOnClickListener(v -> openGrade(9));
        findViewById(R.id.btnGrade10).setOnClickListener(v -> openGrade(10));
        findViewById(R.id.btnGrade11).setOnClickListener(v -> openGrade(11));
        findViewById(R.id.btnGrade12).setOnClickListener(v -> openGrade(12));
    }

    @Override
    protected void onResume() {
        super.onResume();
        StyleManager.recreateIfChanged(this, styleGlass);
    }

    private void openGrade(int grade) {
        Intent intent = new Intent(this, TheoremListActivity.class);
        intent.putExtra("GRADE", grade);
        startActivity(intent);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }
}