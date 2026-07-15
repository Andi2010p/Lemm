package com.example.lemm;

import android.app.Activity;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the three questions that make the rest of the app work: are you a student or a teacher, which
 * grade, and (optionally) which school.
 *
 * <p>Shown once after signing up, and reachable again from the profile page. Kept to three questions
 * on purpose — every extra field at registration loses users, so we only ask for what actually
 * changes the product:
 * <ul>
 *   <li><b>Role</b> decides whether you're offered a Classroom plan and can invite a class.</li>
 *   <li><b>Grade</b> tunes how the AI explains things, and defaults the theorem library.</li>
 *   <li><b>School</b> is what lets your classmates actually find you — the single biggest reason
 *       friend search was useless before.</li>
 * </ul>
 * School is optional and can be cleared at any time.
 */
public final class ProfileSetup {

    public interface OnDone { void done(); }

    private ProfileSetup() {}

    /** Shows the editor. {@code firstTime} makes it non-cancellable (we need a role to proceed). */
    public static void show(Activity a, boolean firstTime, OnDone onDone) {
        UserProfile me = UserProfile.mine(a);
        int dp = (int) a.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24 * dp, 8 * dp, 24 * dp, 0);

        // --- Display name (optional) ---
        root.addView(label(a, a.getString(R.string.setup_name_label)));
        final EditText etName = new EditText(a);
        etName.setHint(R.string.setup_name_hint);
        etName.setSingleLine(true);
        etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etName.setText(me.displayName);
        root.addView(etName);

        // --- Role ---
        root.addView(label(a, a.getString(R.string.setup_role_label)));
        final RadioGroup rg = new RadioGroup(a);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton rbStudent = new RadioButton(a);
        rbStudent.setId(1);
        rbStudent.setText(R.string.role_student);
        final RadioButton rbTeacher = new RadioButton(a);
        rbTeacher.setId(2);
        rbTeacher.setText(R.string.role_teacher);
        rg.addView(rbStudent);
        rg.addView(rbTeacher);
        rg.check(me.isTeacher() ? 2 : 1);
        root.addView(rg);

        // --- Grade (students only) ---
        final TextView gradeLabel = label(a, a.getString(R.string.setup_grade_label));
        root.addView(gradeLabel);
        final Spinner spGrade = new Spinner(a);
        List<String> grades = new ArrayList<>();
        grades.add(a.getString(R.string.setup_grade_none));
        for (int g = UserProfile.MIN_GRADE; g <= UserProfile.MAX_GRADE; g++) {
            grades.add(a.getString(R.string.grade_n, g));
        }
        spGrade.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, grades));
        spGrade.setSelection(me.grade >= UserProfile.MIN_GRADE ? me.grade - UserProfile.MIN_GRADE + 1 : 0);
        root.addView(spGrade);

        // A teacher has no grade — hide it rather than ask a meaningless question.
        Runnable syncRole = () -> {
            boolean teacher = rg.getCheckedRadioButtonId() == 2;
            gradeLabel.setVisibility(teacher ? TextView.GONE : TextView.VISIBLE);
            spGrade.setVisibility(teacher ? Spinner.GONE : Spinner.VISIBLE);
        };
        rg.setOnCheckedChangeListener((g, id) -> syncRole.run());
        syncRole.run();

        // --- School (optional, but this is what powers classmate discovery) ---
        root.addView(label(a, a.getString(R.string.setup_school_label)));
        final EditText etSchool = new EditText(a);
        etSchool.setHint(R.string.setup_school_hint);
        etSchool.setSingleLine(true);
        etSchool.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etSchool.setText(me.school);
        root.addView(etSchool);

        TextView why = new TextView(a);
        why.setText(R.string.setup_school_why);
        why.setTextSize(12f);
        why.setPadding(0, 6 * dp, 0, 0);
        why.setTextColor(ContextCompat.getColor(a, R.color.text_subtitle));
        root.addView(why);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(root);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(firstTime ? R.string.setup_title_first : R.string.setup_title_edit)
                .setView(scroll)
                .setCancelable(!firstTime)
                .setPositiveButton(R.string.save, (d, w) -> {
                    boolean teacher = rg.getCheckedRadioButtonId() == 2;
                    int sel = spGrade.getSelectedItemPosition();
                    int grade = (teacher || sel == 0) ? 0 : (UserProfile.MIN_GRADE + sel - 1);

                    UserProfile.save(a,
                            etName.getText().toString(),
                            teacher ? UserProfile.ROLE_TEACHER : UserProfile.ROLE_STUDENT,
                            grade,
                            etSchool.getText().toString());

                    if (onDone != null) onDone.done();
                });

        if (!firstTime) b.setNegativeButton(R.string.cancel, null);
        b.show();
    }

    private static TextView label(Activity a, String text) {
        int dp = (int) a.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setPadding(0, 14 * dp, 0, 2 * dp);
        tv.setTextColor(ContextCompat.getColor(a, R.color.text_subtitle));
        return tv;
    }
}
