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
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The memory to look at and to delete: notes, and the conversations day by day. */
public class MemoryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    private void build() {
        final Page page = new Page(this, getString(R.string.memory_title));
        final Store store = Store.get(this);

        page.section(R.string.memory_notes);
        final List<Store.Note> notes = store.notes();
        if (notes.isEmpty()) {
            page.row(getString(R.string.memory_empty), "", false, null);
        }
        for (Store.Note note : notes) {
            page.row(note.text, DateUtils.formatDateTime(this, note.created,
                    DateUtils.FORMAT_SHOW_DATE), false, v -> new AlertDialog.Builder(this)
                            .setMessage(note.text)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.delete, (d, w) -> {
                                store.deleteNote(note.id);
                                build();
                            })
                            .show());
        }

        page.section(R.string.memory_days);
        final List<String> days = store.days();
        if (days.isEmpty()) {
            page.row(getString(R.string.memory_empty), "", false, null);
        }
        for (String day : days) {
            final List<Store.Line> lines = store.lines(day);
            final String first = lines.isEmpty() ? "" : lines.get(0).text;
            page.row(dayName(day), first, false, v -> showDay(day, lines));
        }

        final Button clear = new Button(this, null, 0, R.style.Pill_Tonal);
        clear.setText(R.string.memory_clear);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.memory_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    store.clear();
                    build();
                })
                .show());
        page.add(clear);
    }

    private String dayName(String day) {
        try {
            final Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(day);
            return DateUtils.formatDateTime(this, date.getTime(), DateUtils.FORMAT_SHOW_DATE
                    | DateUtils.FORMAT_SHOW_WEEKDAY);
        } catch (ParseException e) {
            return day;
        }
    }

    private void showDay(String day, List<Store.Line> lines) {
        final StringBuilder text = new StringBuilder();
        for (Store.Line line : lines) {
            text.append(DateUtils.formatDateTime(this, line.time, DateUtils.FORMAT_SHOW_TIME))
                    .append("  ").append("user".equals(line.role) ? "› " : "")
                    .append(line.text).append("\n\n");
        }
        final TextView view = new TextView(this);
        view.setText(text.toString().trim());
        view.setTextIsSelectable(true);
        view.setTextSize(16);
        view.setPadding(64, 24, 64, 24);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(dayName(day))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
