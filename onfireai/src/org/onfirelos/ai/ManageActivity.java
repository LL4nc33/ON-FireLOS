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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * What ON-FireAI keeps doing at a time: reminders, radio wake-ups and routines, to look
 * through and delete by hand, as one can by voice.
 */
public class ManageActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        final Page page = new Page(this, getString(R.string.manage_title));
        final long now = System.currentTimeMillis();

        page.section(R.string.manage_reminders);
        final List<Store.Reminder> reminders = Store.get(this).reminders(true);
        if (reminders.isEmpty()) {
            page.row(getString(R.string.manage_none), "", false, null);
        }
        for (Store.Reminder r : reminders) {
            page.row(r.text, Reminders.shortTime(this, r.at, now), false, v -> confirm(r.text,
                    () -> Reminders.cancel(this, r.id)));
        }

        page.section(R.string.manage_wakeups);
        final JSONArray wakeups = Wakeup.all(this);
        if (wakeups.length() == 0) {
            page.row(getString(R.string.manage_none), "", false, null);
        }
        for (int i = 0; i < wakeups.length(); i++) {
            final JSONObject w = wakeups.optJSONObject(i);
            if (w == null) {
                continue;
            }
            final String station = w.optString("station");
            final String title = w.optString("time") + (station.isEmpty() ? ""
                    : " · " + station);
            final JSONArray days = w.optJSONArray("days");
            page.row(title, getString(days != null && days.length() > 0
                    ? R.string.manage_repeating : R.string.manage_once), false,
                    v -> confirm(title, () -> Wakeup.delete(this, w.optInt("id"))));
        }

        page.section(R.string.manage_routines);
        final JSONArray routines = Routines.all(this);
        if (routines.length() == 0) {
            page.row(getString(R.string.manage_none), "", false, null);
        }
        for (int i = 0; i < routines.length(); i++) {
            final JSONObject r = routines.optJSONObject(i);
            if (r == null) {
                continue;
            }
            final String name = r.optString("name");
            page.row(name, r.optString("instruction"), false,
                    v -> confirm(name, () -> Routines.delete(this, name)));
        }
    }

    /** Shown in full, deleted only after a yes. */
    private void confirm(String what, Runnable delete) {
        new AlertDialog.Builder(this)
                .setMessage(what)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    delete.run();
                    build();
                })
                .show();
    }
}
