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

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Waking up the way one asks for it - "weck mich um 7 mit Ö3": at its time the station comes
 * up softly, at the alarm's volume, with a large window over the lock screen to snooze or
 * end it (or by voice). Without the station (no network, gone from the directory) the
 * tablet's alarm sound rings instead after 20 seconds: an alarm must never stay silent.
 */
public class Wakeup extends Service {

    private static final String TAG = "ONFireAI";
    private static final String KEY = "wakeups";
    private static final String CHANNEL = "wakeup";
    private static final String EXTRA_ID = "id";
    static final String ACTION_RING = "org.onfirelos.ai.wakeup.RING";
    static final String ACTION_STOP = "org.onfirelos.ai.wakeup.STOP";
    static final String ACTION_SNOOZE = "org.onfirelos.ai.wakeup.SNOOZE";
    private static final int SNOOZE_MINUTES = 9;
    private static final int SNOOZE_CODE = 7999;
    private static final int MAX = 10;

    /** The wake-up ringing now, for the window and the tools; null when none. */
    static volatile JSONObject sRinging;

    private MediaPlayer mPlayer;
    private PowerManager.WakeLock mLock;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private int mVolumeBefore = -1;

    // ---------------------------------------------------------------- the tools

    static Tools.Result set(Context context, JSONObject args) {
        final String time = args.optString("time").trim();
        if (!time.matches("\\d{1,2}:\\d{2}")) {
            return new Tools.Result("error: time as HH:MM", null);
        }
        final String[] hm = time.split(":");
        if (Integer.parseInt(hm[0]) > 23 || Integer.parseInt(hm[1]) > 59) {
            return new Tools.Result("error: time as HH:MM, 00:00 to 23:59", null);
        }
        final JSONArray days = args.optJSONArray("days") != null ? args.optJSONArray("days")
                : new JSONArray();
        for (int i = 0; i < days.length(); i++) {
            if (days.optInt(i) < 1 || days.optInt(i) > 7) {
                return new Tools.Result("error: days are 1 (Sunday) to 7 (Saturday)", null);
            }
        }
        final JSONArray all = all(context);
        if (all.length() >= MAX) {
            return new Tools.Result("error: at most " + MAX + " wake-ups; delete one first",
                    null);
        }
        try {
            int id = 1;
            for (int i = 0; i < all.length(); i++) {
                id = Math.max(id, all.getJSONObject(i).optInt("id") + 1);
            }
            final JSONObject w = new JSONObject().put("id", id).put("time", time)
                    .put("days", days).put("station", args.optString("station").trim())
                    .put("label", args.optString("label").trim());
            all.put(w);
            save(context, all);
            scheduleAll(context);
            final long at = next(w, System.currentTimeMillis());
            final String with = w.optString("station").isEmpty()
                    ? context.getString(R.string.wakeup_sound) : w.optString("station");
            return new Tools.Result("wake-up " + id + " set for " + Reminders.describe(at,
                    System.currentTimeMillis()) + " with " + with + (days.length() > 0
                    ? ", repeating" : ""), context.getString(R.string.chip_wakeup, time, with));
        } catch (JSONException e) {
            return new Tools.Result("error: " + e.getMessage(), null);
        }
    }

    static Tools.Result list(Context context) {
        final JSONArray all = all(context);
        if (all.length() == 0) {
            return new Tools.Result("no wake-ups", null);
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject w = all.optJSONObject(i);
            out.append('[').append(w.optInt("id")).append("] ").append(w.optString("time"))
                    .append(w.optJSONArray("days") != null && w.optJSONArray("days").length() > 0
                            ? " days " + w.optJSONArray("days") : " once")
                    .append(", ").append(w.optString("station").isEmpty() ? "alarm sound"
                            : w.optString("station")).append('\n');
        }
        return new Tools.Result(out.toString(), context.getString(R.string.chip_wakeups));
    }

    static Tools.Result delete(Context context, int id) {
        final JSONArray all = all(context);
        final JSONArray kept = new JSONArray();
        boolean found = false;
        for (int i = 0; i < all.length(); i++) {
            final JSONObject w = all.optJSONObject(i);
            if (w != null && (id == -1 || w.optInt("id") != id)) {
                kept.put(w);
            } else {
                found = true;
            }
        }
        if (!found) {
            return new Tools.Result("error: no wake-up " + id, null);
        }
        save(context, kept);
        scheduleAll(context);
        return new Tools.Result("deleted", context.getString(R.string.chip_wakeup_deleted));
    }

    /** "Schlummern" / "aus" while it rings. */
    static Tools.Result control(Context context, String action) {
        if (sRinging == null) {
            return new Tools.Result("no wake-up is ringing", null);
        }
        context.startService(new Intent(context, Wakeup.class).setAction(
                "snooze".equals(action) ? ACTION_SNOOZE : ACTION_STOP));
        return new Tools.Result("snooze".equals(action) ? "snoozing " + SNOOZE_MINUTES
                + " minutes" : "wake-up off", context.getString("snooze".equals(action)
                ? R.string.chip_snooze : R.string.chip_wakeup_off));
    }

    // ---------------------------------------------------------------- the times

    private static JSONArray all(Context context) {
        try {
            return new JSONArray(Prefs.get(context).getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray all) {
        Prefs.get(context).edit().putString(KEY, all.toString()).apply();
    }

    private static JSONObject byId(Context context, int id) {
        final JSONArray all = all(context);
        for (int i = 0; i < all.length(); i++) {
            if (all.optJSONObject(i).optInt("id") == id) {
                return all.optJSONObject(i);
            }
        }
        return null;
    }

    static long next(JSONObject w, long now) {
        final String[] hm = w.optString("time").split(":");
        final JSONArray days = w.optJSONArray("days");
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > now && onDay(days, c.get(Calendar.DAY_OF_WEEK))) {
                return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return -1;
    }

    private static boolean onDay(JSONArray days, int day) {
        if (days == null || days.length() == 0) {
            return true;
        }
        for (int i = 0; i < days.length(); i++) {
            if (days.optInt(i) == day) {
                return true;
            }
        }
        return false;
    }

    private static PendingIntent ringIntent(Context context, int code, int id) {
        return PendingIntent.getForegroundService(context, code, new Intent(context,
                Wakeup.class).setAction(ACTION_RING).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** As an alarm clock: shown in the status bar, and on time even when the tablet sleeps. */
    static void scheduleAll(Context context) {
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        for (int i = 0; i < MAX * 2; i++) {
            alarms.cancel(ringIntent(context, 7000 + i, 0));
        }
        final JSONArray all = all(context);
        final long now = System.currentTimeMillis();
        final PendingIntent show = PendingIntent.getActivity(context, 0,
                new Intent(context, SettingsActivity.class), 0);
        for (int i = 0; i < all.length(); i++) {
            final JSONObject w = all.optJSONObject(i);
            final long at = w == null ? -1 : next(w, now);
            if (at > 0) {
                alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show),
                        ringIntent(context, 7000 + w.optInt("id") % (MAX * 2), w.optInt("id")));
            }
        }
    }

    // ---------------------------------------------------------------- ringing

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_RING.equals(action)) {
            final int id = intent.getIntExtra(EXTRA_ID, 0);
            JSONObject w = byId(this, id);
            if (w == null) {
                // A snooze outlives a deleted wake-up; it rings with the alarm sound.
                w = new JSONObject();
            }
            ring(w);
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action) && sRinging != null) {
            final long at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L;
            getSystemService(AlarmManager.class).setAlarmClock(
                    new AlarmManager.AlarmClockInfo(at, null),
                    ringIntent(this, SNOOZE_CODE, sRinging.optInt("id")));
        }
        silence();
        stopSelf();
        return START_NOT_STICKY;
    }

    private void ring(JSONObject w) {
        sRinging = w;
        mLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ONFireAI:wakeup");
        mLock.acquire(15 * 60_000L);
        startForeground(12, notification(w));
        // A one-time wake-up is done once it rang; repeating ones get their next day.
        final JSONArray days = w.optJSONArray("days");
        if (w.has("id") && (days == null || days.length() == 0)) {
            final JSONArray all = all(this);
            final JSONArray kept = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                if (all.optJSONObject(i).optInt("id") != w.optInt("id")) {
                    kept.put(all.optJSONObject(i));
                }
            }
            save(this, kept);
        }
        scheduleAll(this);
        startActivity(new Intent(this, WakeupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final String station = w.optString("station");
        if (station.isEmpty()) {
            playSound();
            return;
        }
        new Thread(() -> {
            String url = null;
            try {
                final JSONObject found = Radio.find(station);
                url = found == null ? null : found.optString("url_resolved");
            } catch (Exception e) {
                Log.w(TAG, "wake-up station: " + e);
            }
            final String stream = url;
            mMain.post(() -> {
                if (sRinging != w) {
                    return;
                }
                if (stream == null) {
                    playSound();
                } else {
                    play(Uri.parse(stream), false);
                    // Not playing after 20 s: the alarm sound, no sleeping in.
                    mMain.postDelayed(() -> {
                        if (sRinging == w && (mPlayer == null || !mPlayer.isPlaying())) {
                            playSound();
                        }
                    }, 20_000);
                }
            });
        }, "ONFireAI-wakeup").start();
    }

    private void playSound() {
        Uri sound = RingtoneManager.getActualDefaultRingtoneUri(this,
                RingtoneManager.TYPE_ALARM);
        if (sound == null) {
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        play(sound, true);
    }

    /** At the alarm's volume, rising from low over a minute. */
    private void play(Uri uri, boolean loop) {
        release();
        final AudioManager audio = getSystemService(AudioManager.class);
        if (mVolumeBefore < 0) {
            mVolumeBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        }
        final int target = Math.max(mVolumeBefore,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 2 / 3);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
        mPlayer = new MediaPlayer();
        mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mPlayer.setLooping(loop);
        try {
            mPlayer.setDataSource(this, uri);
            mPlayer.setOnPreparedListener(MediaPlayer::start);
            mPlayer.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "wake-up sound " + what + "/" + extra);
                if (!loop) {
                    mMain.post(this::playSound);
                }
                return true;
            });
            mPlayer.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "wake-up", e);
            if (!loop) {
                playSound();
            }
            return;
        }
        for (int step = 1; step <= target; step++) {
            final int v = step;
            mMain.postDelayed(() -> {
                if (sRinging != null) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0);
                }
            }, 60_000L * step / Math.max(1, target));
        }
    }

    private void release() {
        if (mPlayer != null) {
            try {
                mPlayer.release();
            } catch (RuntimeException ignored) {
            }
            mPlayer = null;
        }
    }

    private void silence() {
        sRinging = null;
        mMain.removeCallbacksAndMessages(null);
        release();
        if (mVolumeBefore >= 0) {
            getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM,
                    mVolumeBefore, 0);
            mVolumeBefore = -1;
        }
        if (mLock != null && mLock.isHeld()) {
            mLock.release();
        }
        WakeupActivity.closeAll();
        stopForeground(true);
    }

    private Notification notification(JSONObject w) {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        final NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.wakeup_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
        final PendingIntent full = PendingIntent.getActivity(this, 3,
                new Intent(this, WakeupActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        final PendingIntent stop = PendingIntent.getService(this, 4,
                new Intent(this, Wakeup.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT);
        final PendingIntent snooze = PendingIntent.getService(this, 5,
                new Intent(this, Wakeup.class).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT);
        final String station = w.optString("station");
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(getString(R.string.wakeup_title))
                .setContentText(station.isEmpty() ? getString(R.string.wakeup_sound) : station)
                .setColor(getColor(R.color.accent))
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(full, true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.wakeup_snooze), snooze).build())
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.wakeup_off), stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        silence();
        super.onDestroy();
    }

    static String days(Context context, JSONArray days) {
        if (days == null || days.length() == 0) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        final java.text.DateFormatSymbols symbols = new java.text.DateFormatSymbols(
                Locale.getDefault());
        for (int i = 0; i < days.length(); i++) {
            out.append(i == 0 ? "" : ", ").append(symbols.getShortWeekdays()[days.optInt(i)]);
        }
        return out.toString();
    }
}
