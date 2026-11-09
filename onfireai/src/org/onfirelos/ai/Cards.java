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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Answers that are more than words: the weather with its symbols and the days ahead, a timer
 * that counts down, reminders, appointments and alarms as a neat list, and what is playing
 * with its cover and buttons. A tool hands its card over as JSON; the sheet draws it here.
 */
final class Cards {

    private final Context mContext;

    private Cards(Context context) {
        mContext = context;
    }

    /** The card's view, or null when the JSON is not one. */
    static View build(Context context, String json) {
        try {
            final JSONObject card = new JSONObject(json);
            final Cards c = new Cards(context);
            switch (card.optString("type")) {
                case "weather": return c.weather(card);
                case "timers": return c.timers(card);
                case "alarms": return c.alarms(card);
                case "list": return c.list(card);
                case "checklist": return c.checklist(card);
                case "media": return c.media();
                case "videos": return c.videos(card);
                default: return null;
            }
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- pieces

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                mContext.getResources().getDisplayMetrics()));
    }

    private LinearLayout panel() {
        final LinearLayout p = new LinearLayout(mContext);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundResource(R.drawable.card_tonal);
        p.setPadding(dp(20), dp(16), dp(20), dp(16));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        p.setLayoutParams(lp);
        return p;
    }

    private TextView text(CharSequence s, float sp, int color) {
        final TextView t = new TextView(mContext);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(mContext.getColor(color));
        return t;
    }

    private ImageView icon(int res, int size) {
        final ImageView i = new ImageView(mContext);
        i.setImageResource(res);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return i;
    }

    private LinearLayout row() {
        final LinearLayout r = new LinearLayout(mContext);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    // ---------------------------------------------------------------- weather

    /** WMO weather codes as a symbol; clear and partly clear have a night face. */
    static int symbol(int code, boolean day) {
        if (code <= 1) {
            return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        }
        if (code == 2) {
            return day ? R.drawable.ic_w_partly : R.drawable.ic_w_partly_night;
        }
        if (code == 3) {
            return R.drawable.ic_w_cloud;
        }
        if (code == 45 || code == 48) {
            return R.drawable.ic_w_fog;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return R.drawable.ic_w_snow;
        }
        if (code >= 95) {
            return R.drawable.ic_w_storm;
        }
        return R.drawable.ic_w_rain;
    }

    private View weather(JSONObject card) throws JSONException {
        final LinearLayout p = panel();
        final LinearLayout top = row();
        top.addView(icon(symbol(card.optInt("code"), card.optBoolean("day", true)), 56));
        final LinearLayout now = new LinearLayout(mContext);
        now.setOrientation(LinearLayout.VERTICAL);
        final TextView temp = text(card.optLong("now") + "°", 44, R.color.on_background);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temp.setIncludeFontPadding(false);
        now.addView(temp);
        now.addView(text(mContext.getString(R.string.card_feels, card.optLong("feels")) + " · "
                + card.optString("place"), 14, R.color.on_background_quiet));
        final LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowLp.setMarginStart(dp(16));
        top.addView(now, nowLp);
        p.addView(top);

        final JSONArray days = card.optJSONArray("days");
        if (days != null && days.length() > 1) {
            final LinearLayout strip = row();
            // Columns of different height (a rain line or not) start at the same top.
            strip.setGravity(Gravity.TOP);
            strip.setWeightSum(days.length());
            final SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            final SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 0; i < days.length(); i++) {
                final JSONObject d = days.getJSONObject(i);
                final LinearLayout col = new LinearLayout(mContext);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                String name;
                try {
                    name = i == 0 ? mContext.getString(R.string.card_today)
                            : out.format(in.parse(d.optString("date"))).replace(".", "");
                } catch (java.text.ParseException e) {
                    name = d.optString("date");
                }
                final TextView day = text(name, 13, R.color.on_background_quiet);
                day.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(day);
                final ImageView sym = icon(symbol(d.optInt("code"), true), 28);
                ((LinearLayout.LayoutParams) sym.getLayoutParams()).topMargin = dp(6);
                col.addView(sym);
                final TextView range = text(d.optLong("max") + "° " , 14, R.color.on_background);
                range.append(colored(d.optLong("min") + "°"));
                final LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rLp.topMargin = dp(6);
                col.addView(range, rLp);
                if (d.optInt("rain") >= 30) {
                    final TextView rain = text(d.optInt("rain") + "%", 12, R.color.weather_rain);
                    rain.setGravity(Gravity.CENTER_HORIZONTAL);
                    col.addView(rain);
                }
                strip.addView(col, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            final LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(16);
            p.addView(strip, sLp);
        }
        return p;
    }

    /** The low of a day, quieter than the high. */
    private CharSequence colored(String s) {
        final android.text.SpannableString span = new android.text.SpannableString(s);
        span.setSpan(new android.text.style.ForegroundColorSpan(
                mContext.getColor(R.color.on_background_quiet)), 0, s.length(), 0);
        return span;
    }

    // ---------------------------------------------------------------- timers

    private View timers(JSONObject card) throws JSONException {
        final JSONArray list = card.optJSONArray("timers");
        if (list == null || list.length() == 0) {
            return null;
        }
        final LinearLayout p = panel();
        for (int i = 0; i < list.length(); i++) {
            final JSONObject t = list.getJSONObject(i);
            final LinearLayout r = row();
            r.addView(icon(R.drawable.ic_timer, 24));
            final LinearLayout col = new LinearLayout(mContext);
            col.setOrientation(LinearLayout.VERTICAL);
            final String label = t.optString("label");
            col.addView(text(label.isEmpty() ? mContext.getString(R.string.card_timer) : label,
                    14, R.color.on_background_quiet));
            final String state = t.optString("state");
            final long end = t.optLong("end");
            if ("running".equals(state) && end > 0) {
                // It goes on counting down while the sheet is open.
                final Chronometer clock = new Chronometer(mContext);
                clock.setCountDown(true);
                clock.setBase(SystemClock.elapsedRealtime() + Math.max(0,
                        end - System.currentTimeMillis()));
                clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
                clock.setTextColor(mContext.getColor(R.color.on_background));
                clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                clock.setOnChronometerTickListener(c -> {
                    if (c.getBase() <= SystemClock.elapsedRealtime()) {
                        c.stop();
                        c.setText(R.string.card_timer_done);
                    }
                });
                clock.start();
                col.addView(clock);
            } else {
                final TextView still = text("expired".equals(state)
                        ? mContext.getString(R.string.card_timer_done)
                        : clockText(t.optLong("remaining")), 32, R.color.on_background);
                still.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                col.addView(still);
                if ("paused".equals(state)) {
                    col.addView(text(mContext.getString(R.string.card_timer_paused), 13,
                            R.color.accent));
                }
            }
            final LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            colLp.setMarginStart(dp(16));
            r.addView(col, colLp);
            if (i > 0) {
                ((LinearLayout.LayoutParams) generate(r)).topMargin = dp(12);
            }
            p.addView(r);
        }
        return p;
    }

    private static ViewGroup.LayoutParams generate(View v) {
        if (v.getLayoutParams() == null) {
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return v.getLayoutParams();
    }

    private static String clockText(long ms) {
        final long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- alarms

    private View alarms(JSONObject card) throws JSONException {
        final JSONArray list = card.optJSONArray("alarms");
        if (list == null || list.length() == 0) {
            return null;
        }
        final LinearLayout p = panel();
        final String[] names = new java.text.DateFormatSymbols(Locale.getDefault())
                .getShortWeekdays();
        for (int i = 0; i < list.length(); i++) {
            final JSONObject a = list.getJSONObject(i);
            final boolean on = a.optBoolean("on");
            final LinearLayout r = row();
            final TextView time = text(String.format(Locale.ROOT, "%d:%02d", a.optInt("hour"),
                    a.optInt("minute")), 28, on ? R.color.on_background
                    : R.color.on_background_quiet);
            time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            r.addView(time, new LinearLayout.LayoutParams(dp(96),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            final StringBuilder days = new StringBuilder();
            final JSONArray d = a.optJSONArray("days");
            // The week from Monday, Sunday last, as it is written here.
            final java.util.List<Integer> order = new java.util.ArrayList<>();
            for (int k = 0; d != null && k < d.length(); k++) {
                order.add(d.optInt(k));
            }
            order.sort((x, y) -> Integer.compare((x + 5) % 7, (y + 5) % 7));
            for (int k = 0; k < order.size(); k++) {
                days.append(k > 0 ? " " : "").append(names[order.get(k)].replace(".", ""));
            }
            final String label = a.optString("label");
            final TextView what = text((days.length() == 0
                    ? mContext.getString(R.string.card_alarm_once) : days)
                    + (label.isEmpty() ? "" : " · " + label), 14, R.color.on_background_quiet);
            r.addView(what, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(text(mContext.getString(on ? R.string.card_on : R.string.card_off), 14,
                    on ? R.color.accent : R.color.on_background_quiet));
            generate(r);
            if (i > 0) {
                ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = dp(8);
            }
            p.addView(r);
        }
        return p;
    }

    // ---------------------------------------------------------------- reminders, appointments

    private View list(JSONObject card) throws JSONException {
        final JSONArray rows = card.optJSONArray("rows");
        if (rows == null || rows.length() == 0) {
            return null;
        }
        final LinearLayout p = panel();
        final int glyph = "event".equals(card.optString("icon")) ? R.drawable.ic_event
                : R.drawable.ic_bell;
        for (int i = 0; i < rows.length(); i++) {
            final JSONObject e = rows.getJSONObject(i);
            final LinearLayout r = row();
            r.setGravity(Gravity.TOP);
            final ImageView g = icon(glyph, 20);
            ((LinearLayout.LayoutParams) g.getLayoutParams()).topMargin = dp(2);
            r.addView(g);
            final LinearLayout col = new LinearLayout(mContext);
            col.setOrientation(LinearLayout.VERTICAL);
            final TextView when = text(e.optString("time"), 14, R.color.accent);
            when.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            col.addView(when);
            col.addView(text(e.optString("text"), 17, R.color.on_background));
            final LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            colLp.setMarginStart(dp(14));
            r.addView(col, colLp);
            generate(r);
            if (i > 0) {
                ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = dp(12);
            }
            p.addView(r);
        }
        return p;
    }

    // ---------------------------------------------------------------- videos

    /**
     * Videos side by side to swipe through: the picture with its length, the title, the
     * channel and views. A tap plays it in the browser, with sound.
     */
    private View videos(JSONObject card) throws JSONException {
        final JSONArray videos = card.optJSONArray("videos");
        if (videos == null || videos.length() == 0) {
            return null;
        }
        final android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(mContext);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        final LinearLayout strip = new LinearLayout(mContext);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        final int width = dp(236);
        for (int i = 0; i < videos.length(); i++) {
            final JSONObject v = videos.getJSONObject(i);
            final LinearLayout tile = new LinearLayout(mContext);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackgroundResource(R.drawable.card_tonal);
            tile.setClipToOutline(true);
            final android.widget.FrameLayout picture = new android.widget.FrameLayout(mContext);
            final ImageView thumb = new ImageView(mContext);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF20241E);
            picture.addView(thumb, new android.widget.FrameLayout.LayoutParams(width,
                    width * 9 / 16));
            final String length = v.optString("length");
            if (!length.isEmpty()) {
                final TextView badge = text(length, 12, android.R.color.white);
                badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                badge.setBackgroundColor(0xCC000000);
                badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                final android.widget.FrameLayout.LayoutParams blp =
                        new android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM | Gravity.END);
                blp.setMargins(0, 0, dp(8), dp(8));
                picture.addView(badge, blp);
            }
            tile.addView(picture);
            final TextView title = text(v.optString("title"), 15, R.color.on_background);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(dp(12), dp(10), dp(12), 0);
            tile.addView(title, new LinearLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            final String views = v.optString("views");
            final TextView meta = text(v.optString("channel") + (views.isEmpty() ? ""
                    : " · " + views), 13, R.color.on_background_quiet);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            meta.setPadding(dp(12), dp(4), dp(12), dp(12));
            tile.addView(meta, new LinearLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            final String url = v.optString("url");
            tile.setOnClickListener(x -> Tools.openVideo(mContext, url));
            tile.setForeground(mContext.getDrawable(android.R.drawable.list_selector_background));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.setMarginStart(dp(12));
            }
            strip.addView(tile, lp);
            loadPicture(thumb, "https://i.ytimg.com/vi/" + v.optString("id")
                    + "/mqdefault.jpg");
        }
        scroll.addView(strip);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        scroll.setLayoutParams(lp);
        return scroll;
    }

    /** A picture from the web, in the background; kept for the next look. */
    private static final android.util.LruCache<String, Bitmap> PICTURES =
            new android.util.LruCache<>(24);

    private void loadPicture(ImageView view, String address) {
        final Bitmap known = PICTURES.get(address);
        if (known != null) {
            view.setImageBitmap(known);
            return;
        }
        view.setTag(address);
        new Thread(() -> {
            try {
                final java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(address).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                final Bitmap b;
                try (java.io.InputStream in = c.getInputStream()) {
                    b = android.graphics.BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
                if (b != null) {
                    PICTURES.put(address, b);
                    view.post(() -> {
                        if (address.equals(view.getTag())) {
                            view.setImageBitmap(b);
                        }
                    });
                }
            } catch (Exception ignored) {
                // No picture: the tile keeps its dark ground and still plays.
            }
        }, "ONFireAI-picture").start();
    }

    // ---------------------------------------------------------------- lists

    /** A list with its title; open items with a ring, done ones ticked, struck and quiet. */
    private View checklist(JSONObject card) throws JSONException {
        final LinearLayout p = panel();
        final TextView title = text(card.optString("title"), 14, R.color.accent);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.addView(title);
        final JSONArray rows = card.optJSONArray("rows");
        if (rows == null || rows.length() == 0) {
            p.addView(text(mContext.getString(R.string.card_list_empty), 17,
                    R.color.on_background_quiet));
            return p;
        }
        for (int i = 0; i < rows.length() && i < 30; i++) {
            final JSONObject e = rows.getJSONObject(i);
            final boolean done = e.optBoolean("done");
            final LinearLayout r = row();
            r.addView(icon(done ? R.drawable.ic_check : R.drawable.ic_ring, 20));
            final TextView t = text(e.optString("text"), 17, done ? R.color.on_background_quiet
                    : R.color.on_background);
            if (done) {
                t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            final LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tLp.setMarginStart(dp(14));
            r.addView(t, tLp);
            generate(r);
            ((LinearLayout.LayoutParams) r.getLayoutParams()).topMargin = dp(10);
            p.addView(r);
        }
        return p;
    }

    // ---------------------------------------------------------------- what is playing

    /** Drawn from the player itself, so it is current whenever the sheet is rebuilt. */
    private View media() {
        final MediaController c = active(mContext);
        if (c == null || c.getMetadata() == null) {
            return null;
        }
        final MediaMetadata m = c.getMetadata();
        final LinearLayout p = panel();
        final LinearLayout r = row();
        final ImageView art = new ImageView(mContext);
        Bitmap cover = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) {
            cover = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        if (cover != null) {
            art.setImageBitmap(cover);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            art.setImageResource(R.drawable.ic_music);
            art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            art.setBackgroundResource(R.drawable.round_button);
        }
        art.setClipToOutline(true);
        art.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        r.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));
        final LinearLayout col = new LinearLayout(mContext);
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(nonEmpty(m.getString(MediaMetadata.METADATA_KEY_TITLE)), 17,
                R.color.on_background);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);
        final TextView artist = text(nonEmpty(m.getString(MediaMetadata.METADATA_KEY_ARTIST)),
                14, R.color.on_background_quiet);
        artist.setMaxLines(1);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(artist);
        final LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        colLp.setMarginStart(dp(14));
        r.addView(col, colLp);
        final MediaController.TransportControls t = c.getTransportControls();
        // Skipping only where the player can (a radio station cannot).
        final long actions = c.getPlaybackState() == null ? 0
                : c.getPlaybackState().getActions();
        if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
            r.addView(button(R.drawable.ic_previous, v -> t.skipToPrevious()));
        }
        final boolean playing = c.getPlaybackState() != null
                && (c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING
                || c.getPlaybackState().getState() == PlaybackState.STATE_BUFFERING);
        final ImageButton play = button(playing ? R.drawable.ic_pause : R.drawable.ic_play, null);
        play.setOnClickListener(v -> {
            final PlaybackState s = c.getPlaybackState();
            final boolean now = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            if (now) {
                t.pause();
            } else {
                t.play();
            }
            play.setImageResource(now ? R.drawable.ic_play : R.drawable.ic_pause);
        });
        r.addView(play);
        if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
            r.addView(button(R.drawable.ic_next, v -> t.skipToNext()));
        }
        p.addView(r);
        return p;
    }

    private ImageButton button(int res, View.OnClickListener click) {
        final ImageButton b = new ImageButton(mContext);
        b.setImageResource(res);
        b.setBackgroundResource(R.drawable.round_button);
        b.setOnClickListener(click);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMarginStart(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private static String nonEmpty(String s) {
        return s == null ? "" : s;
    }

    private static MediaController active(Context context) {
        try {
            final List<MediaController> list = context.getSystemService(
                    MediaSessionManager.class).getActiveSessions(null);
            for (MediaController c : list) {
                final PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    return c;
                }
            }
            return list.isEmpty() ? null : list.get(0);
        } catch (SecurityException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- for the tools

    /** A list card of reminders or appointments: rows of a time and a text. */
    static JSONObject listCard(String icon, List<String[]> rows) {
        try {
            final JSONArray a = new JSONArray();
            for (String[] row : rows) {
                a.put(new JSONObject().put("time", row[0]).put("text", row[1]));
            }
            return new JSONObject().put("type", "list").put("icon", icon).put("rows", a);
        } catch (JSONException e) {
            return null;
        }
    }

    static JSONObject mediaCard() {
        try {
            return new JSONObject().put("type", "media");
        } catch (JSONException e) {
            return null;
        }
    }

    /** "Tue 15:00" or, today, "15:00", in the user's way of writing times. */
    static String when(Context context, long at) {
        return Reminders.shortTime(context, at, System.currentTimeMillis());
    }
}
