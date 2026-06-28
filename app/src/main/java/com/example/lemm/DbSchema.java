package com.example.lemm;

/** Table and column names for the local SQLite database, shared by {@link DatabaseHelper} and the DAOs. */
final class DbSchema {
    private DbSchema() {}

    static final String DATABASE_NAME = "UserDatabase.db";
    static final int DATABASE_VERSION = 7;

    // 1 = confirmed pushed to cloud, 0 = local-only (created offline, not yet synced)
    static final String KEY_SYNCED = "synced";

    // Users
    static final String TABLE_USERS = "users";
    static final String KEY_ID = "id";
    static final String KEY_USERNAME = "username";
    static final String KEY_EMAIL = "email";
    static final String KEY_PASSWORD = "password";
    static final String KEY_GOOGLE_ID = "google_id";

    // History (Solutions)
    static final String TABLE_HISTORY = "history";
    static final String KEY_HIST_ID = "hist_id";
    static final String KEY_HIST_USERNAME = "username";
    static final String KEY_HIST_NAME = "name";
    static final String KEY_HIST_PROBLEM = "problem";
    static final String KEY_HIST_SOLUTION = "solution";
    static final String KEY_HIST_RAW_RESPONSE = "raw_response";
    static final String KEY_HIST_DATE = "date";

    // Drawings
    static final String TABLE_DRAWINGS = "drawings";
    static final String KEY_DRW_ID = "drw_id";
    static final String KEY_DRW_USERNAME = "username";
    static final String KEY_DRW_NAME = "name";
    static final String KEY_DRW_DATA = "data";
    static final String KEY_DRW_DATE = "date";
}
