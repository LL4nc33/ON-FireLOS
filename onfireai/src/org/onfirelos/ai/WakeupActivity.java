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
        // Dark and calm, for eyes just opened: the time large, a greeting, today's weather,
        // what plays; snooze big and soft, off clear.
        final int light = android.graphics.Color.rgb(236, 238, 230);
        final int soft = android.graphics.Color.rgb(160, 168, 150);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { android.graphics.Color.rgb(16, 22, 16),
                        android.graphics.Color.rgb(34, 44, 30) }));
        final int pad = dp(32);
        col.setPadding(pad, pad, pad, pad);

        final TextView hello = new TextView(this);
        final int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        hello.setText(hour < 11 ? R.string.wakeup_title : hour < 18 ? R.string.wakeup_day
                : R.string.wakeup_evening);
        hello.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        hello.setTextColor(soft);
        hello.setGravity(Gravity.CENTER);
        col.addView(hello);

        final TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 132);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setTextColor(light);
        time.setGravity(Gravity.CENTER);
        time.setIncludeFontPadding(false);
        col.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final String station = w.optString("station");
        final String label = w.optString("label");
        final TextView what = new TextView(this);
        what.setText(!label.isEmpty() ? label : station.isEmpty()
                ? getString(R.string.wakeup_sound) : station);
        what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        what.setTextColor(soft);
        what.setGravity(Gravity.CENTER);
        what.setCompoundDrawablesRelativeWithIntrinsicBounds(station.isEmpty()
                ? R.drawable.ic_alarm : R.drawable.ic_music, 0, 0, 0);
        what.setCompoundDrawablePadding(dp(10));
        what.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(soft));
        what.setPadding(0, dp(8), 0, 0);
        col.addView(what, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Today's weather, when it comes (the network may still be waking up).
        final TextView weather = new TextView(this);
        weather.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        weather.setTextColor(light);
        weather.setGravity(Gravity.CENTER);
        weather.setCompoundDrawablePadding(dp(12));
        weather.setPadding(0, dp(28), 0, 0);
        weather.setVisibility(android.view.View.INVISIBLE);
        col.addView(weather, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new Thread(() -> {
            final JSONObject card = Weather.forTool(this, "", 1).card;
            if (card == null || !card.has("now")) {
                return;
            }
            final JSONObject today = card.optJSONArray("days") == null ? null
                    : card.optJSONArray("days").optJSONObject(0);
            final String line = card.optLong("now") + "°" + (today == null ? ""
                    : "   " + today.optLong("max") + "° / " + today.optLong("min") + "°"
                    + (today.optInt("rain") > 0 ? "   ☂ " + today.optInt("rain") + "%" : ""));
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                weather.setText(line);
                final android.graphics.drawable.Drawable icon = getDrawable(Cards.symbol(
                        card.optInt("code"), card.optBoolean("day", true)));
                if (icon != null) {
                    icon.setBounds(0, 0, dp(36), dp(36));
                    weather.setCompoundDrawablesRelative(icon, null, null, null);
                }
                weather.setVisibility(android.view.View.VISIBLE);
            });
        }, "ONFireAI-wakeup-weather").start();

        final Button snooze = new Button(this, null, 0, R.style.Pill_Tonal);
        snooze.setText(R.string.wakeup_snooze);
        snooze.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        snooze.setOnClickListener(v -> act(Wakeup.ACTION_SNOOZE));
        final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(340), dp(80));
        sp.topMargin = dp(56);
        col.addView(snooze, sp);

        final Button off = new Button(this, null, 0, R.style.Pill);
        off.setText(R.string.wakeup_off);
        off.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        off.setOnClickListener(v -> act(Wakeup.ACTION_STOP));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(340), dp(80));
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
