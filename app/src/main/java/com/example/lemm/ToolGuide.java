package com.example.lemm;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Animated, in-app tool instructions. A {@link Tutorial} pairs a {@link ToolAnimationView} clip with a
 * short title + description. {@link #showSingle} pops a one-tool coach-mark (e.g. long-press a tool);
 * {@link #showTour} plays every tool of an editor in sequence with Back / Next. The registries below
 * (e.g. {@link #drawing2d()}) describe each editor's tools in display order.
 */
public final class ToolGuide {
    private ToolGuide() {}

    public static final class Tutorial {
        final int anim, titleRes, descRes;
        public Tutorial(int anim, int titleRes, int descRes) { this.anim = anim; this.titleRes = titleRes; this.descRes = descRes; }
    }

    /** The 2D drawing editor, in toolbar order. */
    public static List<Tutorial> drawing2d() {
        return Arrays.asList(
                new Tutorial(ToolAnimationView.TYPE_PAN,     R.string.tg2_pan_t,     R.string.tg2_pan_d),
                new Tutorial(ToolAnimationView.TYPE_LINE,    R.string.tg2_line_t,    R.string.tg2_line_d),
                new Tutorial(ToolAnimationView.TYPE_RECT,    R.string.tg2_rect_t,    R.string.tg2_rect_d),
                new Tutorial(ToolAnimationView.TYPE_CIRCLE,  R.string.tg2_circle_t,  R.string.tg2_circle_d),
                new Tutorial(ToolAnimationView.TYPE_SELECT,  R.string.tg2_select_t,  R.string.tg2_select_d),
                new Tutorial(ToolAnimationView.TYPE_ORTHO,   R.string.tg2_ortho_t,   R.string.tg2_ortho_d),
                new Tutorial(ToolAnimationView.TYPE_ANGLE,   R.string.tg2_angle_t,   R.string.tg2_angle_d),
                new Tutorial(ToolAnimationView.TYPE_EXTRUDE, R.string.tg2_extrude_t, R.string.tg2_extrude_d),
                new Tutorial(ToolAnimationView.TYPE_UNDO,    R.string.tg2_undo_t,    R.string.tg2_undo_d),
                new Tutorial(ToolAnimationView.TYPE_REDO,    R.string.tg2_redo_t,    R.string.tg2_redo_d),
                new Tutorial(ToolAnimationView.TYPE_DELETE,  R.string.tg2_delete_t,  R.string.tg2_delete_d),
                new Tutorial(ToolAnimationView.TYPE_CLEAR,   R.string.tg2_clear_t,   R.string.tg2_clear_d),
                new Tutorial(ToolAnimationView.TYPE_EXTRUDE, R.string.tg2_open3d_t,  R.string.tg2_open3d_d),
                new Tutorial(ToolAnimationView.TYPE_SAVE,    R.string.tg2_save_t,    R.string.tg2_save_d)
        );
    }

    public static void showSingle(Activity a, Tutorial t) {
        show(a, Collections.singletonList(t));
    }

    public static void showTour(Activity a, List<Tutorial> tutorials) {
        if (tutorials != null && !tutorials.isEmpty()) show(a, tutorials);
    }

    private static void show(Activity a, List<Tutorial> list) {
        View root = LayoutInflater.from(a).inflate(R.layout.dialog_tool_guide, null);
        ToolAnimationView anim = root.findViewById(R.id.animView);
        TextView title = root.findViewById(R.id.tvTitle);
        TextView desc = root.findViewById(R.id.tvDesc);
        Button back = root.findViewById(R.id.btnBack);
        MaterialButton next = root.findViewById(R.id.btnNext);
        LinearLayout dots = root.findViewById(R.id.dots);

        AlertDialog dialog = new AlertDialog.Builder(a).setView(root).create();

        final int[] idx = {0};
        final int n = list.size();
        float density = a.getResources().getDisplayMetrics().density;

        // progress dots (only meaningful for a multi-step tour)
        if (n > 1) {
            int sz = (int) (8 * density), gap = (int) (4 * density);
            for (int i = 0; i < n; i++) {
                View dot = new View(a);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                lp.setMargins(gap, 0, gap, 0);
                dot.setLayoutParams(lp);
                dot.setBackgroundResource(R.drawable.bg_dot);
                dots.addView(dot);
            }
        }

        Runnable render = () -> {
            Tutorial t = list.get(idx[0]);
            anim.setType(t.anim);
            title.setText(t.titleRes);
            desc.setText(t.descRes);
            back.setVisibility(idx[0] > 0 ? View.VISIBLE : View.INVISIBLE);
            boolean last = idx[0] == n - 1;
            next.setText(last ? R.string.tg_got_it : R.string.onb_next);
            for (int i = 0; i < dots.getChildCount(); i++)
                dots.getChildAt(i).setAlpha(i == idx[0] ? 1f : 0.4f);
        };

        back.setOnClickListener(v -> { if (idx[0] > 0) { idx[0]--; render.run(); } });
        next.setOnClickListener(v -> {
            if (idx[0] < n - 1) { idx[0]++; render.run(); }
            else dialog.dismiss();
        });

        render.run();
        dialog.show();
    }
}
