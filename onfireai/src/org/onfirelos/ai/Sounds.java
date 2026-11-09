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
import android.media.AudioFormat;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.Random;

/**
 * Sounds to fall asleep to, made here on the tablet (no download, no internet): rain, the sea,
 * a soft rushing and a deep hum, endless until stopped or until their time is up, fading in
 * and out. And the sleep timer: whatever plays (these, the radio, any player) stops after
 * so many minutes, quietly.
 */
public class Sounds extends Service {

    private static final String TAG = "ONFireAI";
    private static final String CHANNEL = "sounds";
    private static final String EXTRA_KIND = "kind";
    private static final String EXTRA_MINUTES = "minutes";
    static final String ACTION_STOP = "org.onfirelos.ai.sounds.STOP";
    /** The sleep timer's moment: everything that plays fades out and stops. */
    static final String ACTION_SLEEP = "org.onfirelos.ai.sounds.SLEEP";
    private static final int RATE = 22050;
    static final String[] KINDS = { "rain", "sea", "noise", "hum" };

    private volatile boolean mPlaying;
    private volatile long mStopAt;
    private Thread mThread;
    private AudioFocusRequest mFocus;
    private String mKind = "rain";

    // ---------------------------------------------------------------- the tools

    static Tools.Result play(Context context, String kind, int minutes) {
        String k = kind == null ? "" : kind.toLowerCase(java.util.Locale.ROOT);
        if (k.contains("meer") || k.contains("wave") || k.contains("welle") || k.contains("sea")
                || k.contains("ocean")) {
            k = "sea";
        } else if (k.contains("brumm") || k.contains("hum") || k.contains("brown")
                || k.contains("braun") || k.contains("tief")) {
            k = "hum";
        } else if (k.contains("rausch") || k.contains("white") || k.contains("weiß")
                || k.contains("noise")) {
            k = "noise";
        } else {
            k = "rain";
        }
        context.startForegroundService(new Intent(context, Sounds.class)
                .putExtra(EXTRA_KIND, k).putExtra(EXTRA_MINUTES, Math.max(0, minutes)));
        return new Tools.Result("playing " + k + (minutes > 0 ? " for " + minutes + " min" : "")
                + "; stop with media stop or stop_sounds",
                context.getString(R.string.chip_sounds, name(context, k)));
    }

    static Tools.Result stop(Context context) {
        context.startService(new Intent(context, Sounds.class).setAction(ACTION_STOP));
        return new Tools.Result("sounds stopped", context.getString(R.string.chip_sounds_off));
    }

    /** Stops whatever plays in so many minutes; 0 cancels the timer. */
    static Tools.Result sleepTimer(Context context, int minutes) {
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        // A foreground start: at that hour the app is in the background.
        final PendingIntent pending = PendingIntent.getForegroundService(context, 41,
                new Intent(context, Sounds.class).setAction(ACTION_SLEEP),
                PendingIntent.FLAG_UPDATE_CURRENT);
        if (minutes <= 0) {
            alarms.cancel(pending);
            Prefs.get(context).edit().remove(Prefs.SLEEP_AT).apply();
            return new Tools.Result("sleep timer off", context.getString(R.string.chip_sleep_off));
        }
        if (minutes > 12 * 60) {
            return new Tools.Result("error: at most 12 hours", null);
        }
        final long at = System.currentTimeMillis() + minutes * 60_000L;
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        Prefs.get(context).edit().putLong(Prefs.SLEEP_AT, at).apply();
        return new Tools.Result("everything playing stops in " + minutes + " min",
                context.getString(R.string.chip_sleep, minutes));
    }

    static String name(Context context, String kind) {
        switch (kind) {
            case "sea": return context.getString(R.string.sound_sea);
            case "noise": return context.getString(R.string.sound_noise);
            case "hum": return context.getString(R.string.sound_hum);
            default: return context.getString(R.string.sound_rain);
        }
    }

    // ---------------------------------------------------------------- the service

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_SLEEP.equals(action)) {
            Prefs.get(this).edit().remove(Prefs.SLEEP_AT).apply();
            startForeground(11, notification());
            new Thread(this::fallAsleep, "ONFireAI-sleep").start();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action) || intent == null || !intent.hasExtra(EXTRA_KIND)) {
            stopPlaying();
            stopSelf();
            return START_NOT_STICKY;
        }
        mKind = intent.getStringExtra(EXTRA_KIND);
        final int minutes = intent.getIntExtra(EXTRA_MINUTES, 0);
        mStopAt = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60_000L : 0;
        startForeground(11, notification());
        if (mThread == null || !mThread.isAlive()) {
            mPlaying = true;
            mThread = new Thread(this::render, "ONFireAI-sounds");
            mThread.start();
        }
        return START_NOT_STICKY;
    }

    /**
     * The sleep timer's end: the volume goes down over 8 seconds, everything that plays
     * stops, and the volume is as it was for tomorrow.
     */
    private void fallAsleep() {
        final AudioManager audio = getSystemService(AudioManager.class);
        final int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            for (int v = volume; v > 0; v--) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                SystemClock.sleep(8000 / Math.max(1, volume));
            }
            stopPlaying();
            startService(new Intent(this, Radio.class).setAction(Radio.ACTION_STOP));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE));
            SystemClock.sleep(600);
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            stopSelf();
        }
    }

    private void render() {
        final AudioManager audio = getSystemService(AudioManager.class);
        final AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        mFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        mPlaying = false;
                    }
                }).build();
        audio.requestAudioFocus(mFocus);
        final int chunk = RATE / 10;
        final AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(chunk * 4 * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        final short[] pcm = new short[chunk * 2];
        final Random random = new Random();
        // Filter states per channel: pink (Paul Kellet), brown, and the slow swell of the sea.
        final double[][] pink = new double[2][7];
        final double[] brown = new double[2];
        double t = 0;
        float gain = 0;
        try {
            track.play();
            while (mPlaying) {
                final boolean ending = mStopAt > 0 && SystemClock.elapsedRealtime() >= mStopAt;
                for (int i = 0; i < chunk; i++) {
                    // In over 4 s, out over 6 s when the time is up.
                    gain = ending ? Math.max(0, gain - 1f / (RATE * 6))
                            : Math.min(1, gain + 1f / (RATE * 4));
                    t += 1.0 / RATE;
                    for (int c = 0; c < 2; c++) {
                        final double white = random.nextDouble() * 2 - 1;
                        final double[] p = pink[c];
                        p[0] = 0.99886 * p[0] + white * 0.0555179;
                        p[1] = 0.99332 * p[1] + white * 0.0750759;
                        p[2] = 0.96900 * p[2] + white * 0.1538520;
                        p[3] = 0.86650 * p[3] + white * 0.3104856;
                        p[4] = 0.55000 * p[4] + white * 0.5329522;
                        p[5] = -0.7616 * p[5] - white * 0.0168980;
                        final double pinkOut = (p[0] + p[1] + p[2] + p[3] + p[4] + p[5] + p[6]
                                + white * 0.5362) * 0.11;
                        p[6] = white * 0.115926;
                        brown[c] = (brown[c] + 0.02 * white) / 1.02;
                        double v;
                        switch (mKind) {
                            case "sea":
                                // Waves: brown noise swelling every 8-ish seconds.
                                final double swell = 0.35 + 0.65 * Math.pow(Math.sin(Math.PI
                                        * (t / (7.5 + c * 0.6))), 2);
                                v = brown[c] * 3.2 * swell + pinkOut * 0.25 * swell;
                                break;
                            case "noise":
                                v = pinkOut * 0.9;
                                break;
                            case "hum":
                                v = brown[c] * 3.5;
                                break;
                            default:
                                // Rain: pink noise with now and then a drop.
                                v = pinkOut * 0.7 + (random.nextDouble() < 0.0006
                                        ? (random.nextDouble() - 0.5) * 0.9 : 0);
                                break;
                        }
                        pcm[2 * i + c] = (short) Math.max(-32767, Math.min(32767,
                                v * gain * 9000));
                    }
                }
                track.write(pcm, 0, pcm.length);
                if (ending && gain <= 0) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "sounds", e);
        } finally {
            try {
                track.stop();
            } catch (RuntimeException ignored) {
            }
            track.release();
            audio.abandonAudioFocusRequest(mFocus);
            mPlaying = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopPlaying() {
        mPlaying = false;
    }

    private Notification notification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.sounds_channel), NotificationManager.IMPORTANCE_LOW));
        final PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, Sounds.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(name(this, mKind))
                .setColor(getColor(R.color.accent))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,
                                R.drawable.ic_stop), getString(R.string.radio_stop), stop)
                        .build())
                .build();
    }

    @Override
    public void onDestroy() {
        mPlaying = false;
        super.onDestroy();
    }
}
