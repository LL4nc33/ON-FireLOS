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
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/** The wake-up, over the lock screen: the time large, snooze or off with one big tap. */
public class WakeupActivity extends Activity {

    private static WeakReference<WakeupActivity> sShown = new WeakReference<>(null);

    static void closeAll() {
        final WakeupActivity shown = sShown.get();
        if (shown != null) {
            shown.runOnUiThread(shown::finish);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        sShown = new WeakReference<>(this);
        final JSONObject w = Wakeup.sRinging;
        if (w == null) {
            finish();
            return;
        }
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(getColor(R.color.background));
        final int pad = dp(32);
        col.setPadding(pad, pad, pad, pad);

        final TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 112);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setTextColor(getColor(R.color.on_background));
        time.setGravity(Gravity.CENTER);
        col.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final String station = w.optString("station");
        final String label = w.optString("label");
        final TextView what = new TextView(this);
        what.setText(!label.isEmpty() ? label : station.isEmpty()
                ? getString(R.string.wakeup_title) : station);
        what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        what.setTextColor(getColor(R.color.on_background_quiet));
        what.setGravity(Gravity.CENTER);
        what.setPadding(0, dp(4), 0, dp(48));
        col.addView(what);

        final Button snooze = new Button(this, null, 0, R.style.Pill_Tonal);
        snooze.setText(R.string.wakeup_snooze);
        snooze.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        snooze.setOnClickListener(v -> act(Wakeup.ACTION_SNOOZE));
        col.addView(snooze, new LinearLayout.LayoutParams(dp(320), dp(76)));

        final Button off = new Button(this, null, 0, R.style.Pill);
        off.setText(R.string.wakeup_off);
        off.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        off.setOnClickListener(v -> act(Wakeup.ACTION_STOP));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(320), dp(76));
        lp.topMargin = dp(16);
        col.addView(off, lp);

        setContentView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void act(String action) {
        startService(new Intent(this, Wakeup.class).setAction(action));
        finish();
    }

    @Override
    public void onBackPressed() {
        // Back is no way to end an alarm by accident: snooze it.
        act(Wakeup.ACTION_SNOOZE);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
