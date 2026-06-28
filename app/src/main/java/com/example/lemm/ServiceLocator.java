package com.example.lemm;

import android.content.Context;

/**
 * Lightweight dependency container (manual DI). Centralizes how core dependencies are created so the
 * rest of the app doesn't do {@code new DatabaseHelper(this)} ad-hoc — which makes wiring explicit
 * and lets tests swap implementations in one place.
 *
 * Note: a full DI framework (Hilt/Dagger) is the longer-term goal; this container is the offline,
 * zero-dependency stepping stone with the same call sites (`ServiceLocator.db(ctx)` etc.). To migrate
 * to Hilt later, replace these accessors with {@code @Inject} constructors and {@code @Module} provides.
 */
public final class ServiceLocator {
    private static volatile DatabaseHelper db;

    private ServiceLocator() {}

    /** App-wide single SQLite helper instance. */
    public static DatabaseHelper db(Context c) {
        if (db == null) {
            synchronized (ServiceLocator.class) {
                if (db == null) db = new DatabaseHelper(c.getApplicationContext());
            }
        }
        return db;
    }

    /** Repository for the History feature, backed by the shared DB helper. */
    public static HistoryRepository historyRepository(Context c) {
        return new HistoryRepository(db(c));
    }

    /** For tests: inject a fake/mock DB helper. */
    public static void setDatabaseHelperForTest(DatabaseHelper helper) {
        db = helper;
    }
}
