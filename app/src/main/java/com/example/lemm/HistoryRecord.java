package com.example.lemm;

/** Immutable model for one saved History row (a solution or a drawing). */
public class HistoryRecord {
    public final String id;
    public final String title;
    public final String subtext;
    public final String data;
    public final String date;

    public HistoryRecord(String id, String title, String subtext, String data, String date) {
        this.id = id;
        this.title = title;
        this.subtext = subtext;
        this.data = data;
        this.date = date;
    }
}
