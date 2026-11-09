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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Internet radio, the way one asks for it ("play Ö3"): the station is found in the free
 * radio-browser.info directory and played here, with a notification and a media session, so
 * pause, play and the music card work as for any player.
 */
public class Radio extends Service {

    private static final String TAG = "ONFireAI";
    private static final String CHANNEL = "radio";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_NAME = "name";
    static final String ACTION_STOP = "org.onfirelos.ai.radio.STOP";

    private MediaPlayer mPlayer;
    private MediaSession mSession;
    private AudioFocusRequest mFocus;
    private String mName = "";
    private String mUrl;
    /** Paused for a moment (a call, a timer): it comes back by itself. */
    private boolean mResumeOnGain;
    private android.net.wifi.WifiManager.WifiLock mWifi;

    // ---------------------------------------------------------------- the tool

    /** Worker thread: the station of that name in the directory (its stream in url_resolved). */
    static JSONObject find(String query) throws Exception {
        final String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name());
        final java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://de1.api.radio-browser.info/json/stations/search?name=" + q
                        + "&limit=8&order=clickcount&reverse=true&hidebroken=true")
                .openConnection();
        final JSONArray found;
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "ON-FireAI");
            found = new JSONArray(LlmClient.readAll(c.getInputStream()));
        } finally {
            c.disconnect();
        }
        for (int i = 0; i < found.length(); i++) {
            final JSONObject s = found.getJSONObject(i);
            if (!s.optString("url_resolved").isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /** Worker thread: finds the station and starts it. */
    static Tools.Result play(Context context, String query) {
        if (query == null || query.trim().isEmpty()) {
            return new Tools.Result("error: which station?", null);
        }
        try {
            final JSONObject best = find(query);
            if (best == null) {
                return new Tools.Result("error: no station " + query + " found", null);
            }
            final String name = best.optString("name").trim();
            final Intent start = new Intent(context, Radio.class)
                    .putExtra(EXTRA_URL, best.optString("url_resolved"))
                    .putExtra(EXTRA_NAME, name);
            // On air once the answer is said, not over it.
            Session.afterAnswer(() -> context.startForegroundService(start));
            return new Tools.Result("starting station " + name + " after your answer; say"
                    + " only a few words (" + best.optString("country") + ")",
                    context.getString(R.string.chip_radio,
                    name), Cards.mediaCard());
        } catch (Exception e) {
            return new Tools.Result("error: radio directory: " + LlmClient.describe(context,
                    e), null);
        }
    }

    // ---------------------------------------------------------------- the player

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSession = new MediaSession(this, "ON-FireAI radio");
        mSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (mUrl != null) {
                    start(mUrl, mName);
                }
            }

            @Override
            public void onPause() {
                stopPlaying(PlaybackState.STATE_PAUSED);
            }

            @Override
            public void onStop() {
                stopPlaying(PlaybackState.STATE_STOPPED);
                stopSelf();
            }
        });
        mSession.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                | MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        mSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopPlaying(PlaybackState.STATE_STOPPED);
            stopSelf();
            return START_NOT_STICKY;
        }
        final String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        if (url == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        start(url, intent.getStringExtra(EXTRA_NAME));
        return START_NOT_STICKY;
    }

    private void start(String url, String name) {
        mUrl = url;
        mName = name == null ? "" : name;
        startForeground(9, notification(true));
        release();
        final AudioManager audio = getSystemService(AudioManager.class);
        final AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        // One request for the whole service, so a second station does not leave a first
        // one behind with Android.
        if (mFocus == null) {
            mFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this::focusChanged).build();
        }
        audio.requestAudioFocus(mFocus);
        mResumeOnGain = false;
        if (mWifi == null) {
            mWifi = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "ONFireAI:radio");
            mWifi.setReferenceCounted(false);
        }
        mWifi.acquire();
        mPlayer = new MediaPlayer();
        mPlayer.setAudioAttributes(attrs);
        // The stream goes on with the screen off.
        mPlayer.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
        try {
            mPlayer.setDataSource(this, Uri.parse(url));
            mPlayer.setOnPreparedListener(p -> {
                p.start();
                state(PlaybackState.STATE_PLAYING);
            });
            mPlayer.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "radio: " + what + "/" + extra);
                stopPlaying(PlaybackState.STATE_ERROR);
                return true;
            });
            mPlayer.prepareAsync();
            state(PlaybackState.STATE_BUFFERING);
        } catch (Exception e) {
            Log.w(TAG, "radio", e);
            stopPlaying(PlaybackState.STATE_ERROR);
        }
        mSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, mName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.radio_live))
                .build());
    }

    private void focusChanged(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                stopPlaying(PlaybackState.STATE_PAUSED);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (mPlayer != null) {
                    stopPlaying(PlaybackState.STATE_PAUSED);
                    mResumeOnGain = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mPlayer != null) {
                    mPlayer.setVolume(0.2f, 0.2f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mPlayer != null) {
                    mPlayer.setVolume(1f, 1f);
                } else if (mResumeOnGain && mUrl != null) {
                    start(mUrl, mName);
                }
                break;
            default:
                break;
        }
    }

    private void state(int state) {
        mSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        getSystemService(NotificationManager.class).notify(9,
                notification(state == PlaybackState.STATE_PLAYING
                        || state == PlaybackState.STATE_BUFFERING));
    }

    private void stopPlaying(int state) {
        release();
        mResumeOnGain = false;
        if (mWifi != null) {
            mWifi.release();
        }
        // A short pause keeps its place with Android, to be handed back.
        if (mFocus != null && state != PlaybackState.STATE_PAUSED) {
            getSystemService(AudioManager.class).abandonAudioFocusRequest(mFocus);
        }
        state(state);
        stopForeground(state == PlaybackState.STATE_STOPPED);
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

    private Notification notification(boolean playing) {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.radio_channel), NotificationManager.IMPORTANCE_LOW));
        final PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, Radio.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(mName)
                .setContentText(getString(playing ? R.string.radio_live : R.string.radio_paused))
                .setColor(getColor(R.color.accent))
                .setStyle(new Notification.MediaStyle().setMediaSession(
                        mSession.getSessionToken()).setShowActionsInCompactView(0))
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,
                                R.drawable.ic_stop), getString(R.string.radio_stop), stop)
                        .build())
                .setOngoing(playing)
                .build();
    }

    @Override
    public void onDestroy() {
        release();
        if (mWifi != null) {
            mWifi.release();
        }
        if (mFocus != null) {
            getSystemService(AudioManager.class).abandonAudioFocusRequest(mFocus);
        }
        mSession.release();
        super.onDestroy();
    }
}
