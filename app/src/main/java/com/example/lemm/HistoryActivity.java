package com.example.lemm;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory;
    private TextView tvTestModeWarning;
    private HistoryAdapter adapter;
    private DatabaseHelper dbHelper;
    private List<HistoryItem> historyList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        rvHistory = findViewById(R.id.rvHistory);
        tvTestModeWarning = findViewById(R.id.tvTestModeWarning);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        
        dbHelper = new DatabaseHelper(this);

        checkTestMode();
        loadHistory();
    }

    private void checkTestMode() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "User");
        if ("GuestUser".equals(username)) {
            tvTestModeWarning.setVisibility(View.VISIBLE);
        } else {
            tvTestModeWarning.setVisibility(View.GONE);
        }
    }

    private void loadHistory() {
        SharedPreferences pref = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        String username = pref.getString("username", "User");

        Cursor cursor = dbHelper.getHistory(username);
        if (cursor != null && cursor.moveToFirst()) {
            do {
                String problem = cursor.getString(cursor.getColumnIndexOrThrow("problem"));
                String solution = cursor.getString(cursor.getColumnIndexOrThrow("solution"));
                String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                historyList.add(new HistoryItem(problem, solution, date));
            } while (cursor.moveToNext());
            cursor.close();
        }

        adapter = new HistoryAdapter(historyList);
        rvHistory.setAdapter(adapter);
    }

    private static class HistoryItem {
        String problem, solution, date;
        HistoryItem(String p, String s, String d) {
            this.problem = p;
            this.solution = s;
            this.date = d;
        }
    }

    private static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {
        private List<HistoryItem> items;

        HistoryAdapter(List<HistoryItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            HistoryItem item = items.get(position);
            holder.text1.setText(item.problem);
            holder.text2.setText(item.date + "\n" + item.solution);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView text1, text2;
            ViewHolder(View itemView) {
                super(itemView);
                text1 = itemView.findViewById(android.R.id.text1);
                text2 = itemView.findViewById(android.R.id.text2);
                text1.setTextColor(0xFF0C3D6A);
                text1.setTextSize(18f);
                text1.setTypeface(null, Typeface.BOLD);
            }
        }
    }
}
