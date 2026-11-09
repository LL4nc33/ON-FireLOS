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

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * The tablet on its charger, as a screen to glance at from across the room: the time large,
 * the weather, the next appointment, running timers counting down and what is playing.
 * "Hey Fire" works on top of it; a tap ends it. It drifts a little every minute so nothing
 * burns in, and dims at night.
 */
public class Ambient extends DreamService {

    private static final long WEATHER_EVERY = 30 * 60_000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();
    private LinearLayout mColumn;
    private TextClock mTime;
    private TextView mDate;
    private ImageView mWeatherIcon;
    private TextView mWeather;
    private LinearLayout mWeatherRow;
    private TextView mEvent;
    private TextView mTimers;
    private TextView mPlaying;
    private long mWeatherAt;
    private long mTimersAt;
    /** The timers as last asked: label, state, and when each ends (or its time if paused). */
    private volatile List<long[]> mTimerTimes = new ArrayList<>();
    private volatile List<String> mTimerNames = new ArrayList<>();
    private int mMinute = -1;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            tick();
            mMain.postDelayed(this, 1000);
        }
    };

    /** Once: ON-FireAI's screen becomes the one shown while charging. */
    static void becomeDefault(Context context) {
        if (Prefs.get(context).getBoolean(Prefs.AMBIENT_SET, false)) {
            return;
        }
        try {
            final String me = new ComponentName(context, Ambient.class).flattenToString();
            Settings.Secure.putString(context.getContentResolver(), "screensaver_components", me);
            Settings.Secure.putString(context.getContentResolver(),
                    "screensaver_default_component", me);
            Settings.Secure.putInt(context.getContentResolver(), "screensaver_enabled", 1);
            Settings.Secure.putInt(context.getContentResolver(),
                    "screensaver_activate_on_sleep", 1);
            Settings.Secure.putInt(context.getContentResolver(),
                    "screensaver_activate_on_dock", 1);
            Prefs.put(context, Prefs.AMBIENT_SET, true);
        } catch (SecurityException e) {
            android.util.Log.w("ONFireAI", "screensaver: " + e);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        final boolean night = night();
        setScreenBright(!night);
        final int strong = night ? Color.rgb(150, 150, 140) : Color.rgb(236, 238, 230);
        final int quiet = night ? Color.rgb(90, 90, 84) : Color.rgb(160, 166, 150);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        mColumn = new LinearLayout(this);
        mColumn.setOrientation(LinearLayout.VERTICAL);
        mColumn.setGravity(Gravity.CENTER_HORIZONTAL);

        mTime = new TextClock(this);
        mTime.setFormat24Hour("HH:mm");
        mTime.setFormat12Hour("h:mm");
        mTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 150);
        mTime.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        mTime.setTextColor(strong);
        mTime.setIncludeFontPadding(false);
        mColumn.addView(mTime);

        mDate = new TextClock(this);
        ((TextClock) mDate).setFormat24Hour("EEEE, d. MMMM");
        ((TextClock) mDate).setFormat12Hour("EEEE, MMMM d");
        mDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        mDate.setTextColor(quiet);
        mColumn.addView(mDate);

        mWeatherRow = new LinearLayout(this);
        mWeatherRow.setGravity(Gravity.CENTER_VERTICAL);
        mWeatherRow.setPadding(0, dp(36), 0, 0);
        mWeatherIcon = new ImageView(this);
        mWeatherRow.addView(mWeatherIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mWeather = text(30, strong);
        mWeather.setPadding(dp(14), 0, 0, 0);
        mWeatherRow.addView(mWeather);
        mWeatherRow.setVisibility(View.GONE);
        mColumn.addView(mWeatherRow);

        mEvent = text(24, quiet);
        mEvent.setPadding(0, dp(24), 0, 0);
        mColumn.addView(mEvent);
        mTimers = text(30, Color.rgb(196, 214, 160));
        mTimers.setPadding(0, dp(24), 0, 0);
        mColumn.addView(mTimers);
        mPlaying = text(24, quiet);
        mPlaying.setPadding(0, dp(24), 0, 0);
        mColumn.addView(mPlaying);

        root.addView(mColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mWeatherAt = 0;
        mMain.post(mTick);
    }

    @Override
    public void onDreamingStopped() {
        mMain.removeCallbacks(mTick);
        super.onDreamingStopped();
    }

    private TextView text(int sp, int color) {
        final TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static boolean night() {
        final int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 6;
    }

    private void tick() {
        final long now = System.currentTimeMillis();
        final int minute = Calendar.getInstance().get(Calendar.MINUTE);
        if (minute != mMinute) {
            mMinute = minute;
            // A little elsewhere every minute: nothing stays on the same pixels.
            mColumn.animate().translationX(dp(mRandom.nextInt(41) - 20))
                    .translationY(dp(mRandom.nextInt(41) - 20)).setDuration(2000);
            new Thread(this::slowParts, "ONFireAI-ambient").start();
        }
        if (now - mWeatherAt > WEATHER_EVERY) {
            mWeatherAt = now;
            new Thread(this::weather, "ONFireAI-ambient-weather").start();
        }
        // The clock app is asked every 5 s, in the background; in between it counts here.
        if (now - mTimersAt > 5000) {
            mTimersAt = now;
            new Thread(this::fetchTimers, "ONFireAI-ambient-timers").start();
        }
        showTimers(now);
        if (now / 1000 % 2 == 0) {
            showPlaying();
        }
    }

    /** Once a minute: the next appointment (not at the lock screen: it is personal). */
    private void slowParts() {
        final String event = Tools.locked(this) ? "" : Calendars.next(this, 12);
        mMain.post(() -> {
            mEvent.setText(event.isEmpty() ? "" : event.replaceFirst("^\\w+ \\d{4}-\\d{2}-\\d{2} ",
                    ""));
            mEvent.setVisibility(event.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void weather() {
        final Tools.Result r = Weather.forTool(this, "", 1);
        final JSONObject card = r.card;
        mMain.post(() -> {
            if (card == null || !card.has("now")) {
                mWeatherRow.setVisibility(View.GONE);
                return;
            }
            mWeatherIcon.setImageResource(Cards.symbol(card.optInt("code"),
                    card.optBoolean("day", true)));
            final JSONObject today = card.optJSONArray("days") == null ? null
                    : card.optJSONArray("days").optJSONObject(0);
            mWeather.setText(card.optLong("now") + "°  " + card.optString("place")
                    + (today == null ? "" : "   " + today.optLong("max") + "° / "
                            + today.optLong("min") + "°"));
            mWeatherRow.setVisibility(View.VISIBLE);
        });
    }

    private void fetchTimers() {
        final long now = System.currentTimeMillis();
        final List<long[]> times = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (Bundle t : Clock.timerList(this)) {
            final String state = String.valueOf(t.getString("state"));
            if (!state.equals("running") && !state.equals("paused") && !state.equals("expired")) {
                continue;
            }
            final boolean paused = state.equals("paused");
            times.add(new long[] { paused ? 1 : 0, paused ? t.getLong("remaining")
                    : now + t.getLong("remaining") });
            final String label = t.getString("label", "");
            names.add(label == null || label.isEmpty() ? getString(R.string.ambient_timer)
                    : label);
        }
        mTimerTimes = times;
        mTimerNames = names;
    }

    private void showTimers(long now) {
        final List<long[]> times = mTimerTimes;
        final List<String> names = mTimerNames;
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < times.size() && i < names.size(); i++) {
            final long[] t = times.get(i);
            out.append(out.length() == 0 ? "" : "     ").append(names.get(i)).append("  ")
                    .append(Clock.clockText(t[0] == 1 ? t[1] : t[1] - now));
        }
        mTimers.setText(out);
        mTimers.setVisibility(out.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void showPlaying() {
        String shown = "";
        try {
            final List<MediaController> sessions = getSystemService(MediaSessionManager.class)
                    .getActiveSessions(null);
            for (MediaController c : sessions) {
                final PlaybackState state = c.getPlaybackState();
                final MediaMetadata meta = c.getMetadata();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING
                        && meta != null) {
                    final String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
                    final String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    shown = "♪  " + (title == null ? "" : title)
                            + (TextUtils.isEmpty(artist) ? "" : " · " + artist);
                    break;
                }
            }
        } catch (SecurityException ignored) {
        }
        mPlaying.setText(shown);
        mPlaying.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
