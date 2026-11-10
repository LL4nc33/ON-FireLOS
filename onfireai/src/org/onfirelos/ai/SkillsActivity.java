/*
 * Copyright (C) 2026 ON-FireLOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onfirelos.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/**
 * The skills ON-FireAI knows: each one on or off, one's own also to delete. Where they come
 * from is said at the top (the built-in ones, and the folder for one's own).
 */
public class SkillsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    /** A built-in skill's title or summary in the user's language; one's own as written. */
    private String label(String name, String what, String fallback) {
        final int id = getResources().getIdentifier("skill_" + name.replace('-', '_') + "_"
                + what, "string", getPackageName());
        return id == 0 ? fallback : getString(id);
    }

    /** A skill of one's own, new or to change: its name, what it is for, its steps. */
    private void edit(Skills.Skill skill) {
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        final int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        final android.widget.EditText name = field(R.string.skills_field_name, 1);
        final android.widget.EditText what = field(R.string.skills_field_what, 2);
        final android.widget.EditText steps = field(R.string.skills_field_steps, 8);
        if (skill != null) {
            name.setText(skill.name);
            name.setEnabled(false);
            what.setText(skill.description);
            steps.setText(skill.body.trim());
        }
        box.addView(name);
        box.addView(what);
        box.addView(steps);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(skill == null ? R.string.skills_new : R.string.skills_edit_title)
                .setMessage(R.string.skills_new_help)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .show();
        // Stays open on a mistake, with what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String result = Skills.save(this, name.getText().toString(),
                    what.getText().toString(), steps.getText().toString());
            if (result.startsWith("error")) {
                android.widget.Toast.makeText(this, result.contains("built-in")
                        ? getString(R.string.skills_error_builtin)
                        : result.contains("give a name") ? getString(R.string.skills_error_missing)
                        : getString(R.string.skills_error_other, result.replaceFirst("^error: ",
                        "")), android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            build();
        });
    }

    private android.widget.EditText field(int hint, int lines) {
        final android.widget.EditText f = new android.widget.EditText(this);
        f.setHint(hint);
        f.setMinLines(lines);
        f.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        f.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (lines > 1 ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        return f;
    }

    private void build() {
        final Page page = new Page(this, getString(R.string.skills_title));
        page.section(R.string.skills_section);
        page.row(getString(R.string.skills_where), Skills.userDir().getPath(), false, null);
        page.row(getString(R.string.skills_new), getString(R.string.skills_new_summary), false,
                v -> edit(null));
        for (Skills.Skill s : Skills.all(this).values()) {
            final boolean own = s.dir != null;
            // One's own by its name as said: "gute-nacht" as "Gute nacht".
            final String said = Character.toUpperCase(s.name.charAt(0))
                    + s.name.substring(1).replace('-', ' ');
            page.toggle(label(s.name, "title", said) + (own ? " · "
                            + getString(R.string.skills_own) : ""),
                    own ? s.description : label(s.name, "summary", s.description),
                    Skills.enabled(this, s.name),
                    (b, on) -> Skills.setEnabled(this, s.name, on));
            if (own) {
                page.row(getString(R.string.skills_edit, s.name), "", false, v -> edit(s));
                page.row(getString(R.string.skills_delete, s.name), "", false,
                        v -> new AlertDialog.Builder(this)
                                .setMessage(getString(R.string.skills_delete, s.name))
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.delete, (d, w) -> {
                                    Skills.delete(s);
                                    build();
                                })
                                .show());
            }
        }
    }
}
