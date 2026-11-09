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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The wake word: listens on the tablet itself (sherpa-onnx keyword spotting, offline) for the
 * word the user chose; on it, the sheet opens and listens. Nothing leaves the tablet before
 * the word. Pauses while ON-FireAI itself listens or speaks, and - if wanted - while the
 * tablet is not charging.
 */
public class WakeWord extends Service {

    private static final String TAG = "ONFireAI";
    private static final int RATE = 16000;
    private static final String CHANNEL = "wake_word";

    /** The words to choose from, as the model's tokens (its own BPE vocabulary). */
    static final String[][] PRESETS = {
        { "Hey Fire", "▁HE Y ▁FI RE" },
        { "Okay Fire", "▁OKAY ▁FI RE" },
        { "Hey Computer", "▁HE Y ▁COMP U TER" },
        { "Computer", "▁COMP U TER" },
        { "Hey Jarvis", "▁HE Y ▁JA R VI S" },
    };

    private static volatile WakeWord sInstance;
    private static volatile boolean sPaused;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mRunning;
    private volatile boolean mCharging = true;
    private Thread mThread;

    private final BroadcastReceiver mPower = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
    };

    static boolean available() {
        try {
            System.loadLibrary("sherpa-onnx-jni");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean wanted(Context context) {
        return Prefs.get(context).getBoolean(Prefs.WAKE, false);
    }

    /** Starts or stops the service as the settings say. */
    static void apply(Context context) {
        final Intent intent = new Intent(context, WakeWord.class);
        if (wanted(context) && available()) {
            // Turned on in Settings before the sheet ever asked for the microphone.
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    context.getPackageManager().grantRuntimePermission(context.getPackageName(),
                            android.Manifest.permission.RECORD_AUDIO,
                            android.os.Process.myUserHandle());
                } catch (RuntimeException e) {
                    Log.w(TAG, "microphone: " + e);
                }
            }
            context.startForegroundService(intent);
        } else {
            context.stopService(intent);
        }
    }

    /** While ON-FireAI itself listens or speaks, the microphone is its. */
    static void setPaused(boolean paused) {
        sPaused = paused;
    }

    /** The chosen word as the model's tokens. */
    static String tokens(Context context, String word) {
        for (String[] p : PRESETS) {
            if (p[0].equalsIgnoreCase(word)) {
                return p[1];
            }
        }
        return greedyTokens(context, word);
    }

    /** A word of one's own: split into the vocabulary's longest pieces, left to right. */
    private static String greedyTokens(Context context, String word) {
        final List<String> vocab = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open("kws/tokens.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                final int space = line.lastIndexOf(' ');
                if (space > 0) {
                    vocab.add(line.substring(0, space));
                }
            }
        } catch (IOException e) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (String w : word.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", "").trim()
                .split("\\s+")) {
            String rest = "▁" + w;
            while (!rest.isEmpty()) {
                String best = null;
                for (String piece : vocab) {
                    if (rest.startsWith(piece) && (best == null || piece.length() > best.length())) {
                        best = piece;
                    }
                }
                if (best == null) {
                    return "";
                }
                out.append(out.length() == 0 ? "" : " ").append(best);
                rest = rest.substring(best.length());
            }
        }
        return out.toString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.wake_channel), NotificationManager.IMPORTANCE_MIN));
        final PendingIntent settings = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_settings_tile)
                .setContentTitle(getString(R.string.wake_listening,
                        Prefs.get(this).getString(Prefs.WAKE_WORD, PRESETS[0][0])))
                .setContentIntent(settings)
                .setOngoing(true)
                .build();
        startForeground(7, n);
        final Intent battery = registerReceiver(mPower,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            mCharging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // For checks over adb (the service is not exported): a 16 kHz mono WAV instead of
        // the microphone, the result in the log.
        final int source = intent == null ? -1 : intent.getIntExtra("test_source", -1);
        if (source >= 0) {
            new Thread(() -> testSource(source), "ONFireAI-mic-test").start();
            return START_NOT_STICKY;
        }
        final String test = intent == null ? null : intent.getStringExtra("test_wav");
        if (test != null) {
            new Thread(() -> testFile(test), "ONFireAI-wake-test").start();
            return START_NOT_STICKY;
        }
        if (mThread == null) {
            mRunning = true;
            mThread = new Thread(this::run, "ONFireAI-wake");
            mThread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        sInstance = null;
        unregisterReceiver(mPower);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void testFile(String path) {
        try {
            final KeywordSpotterConfig config = config();
            final KeywordSpotter spotter = new KeywordSpotter(getAssets(), config);
            final String word = Prefs.get(this).getString(Prefs.WAKE_WORD, PRESETS[0][0]);
            final OnlineStream stream = spotter.createStream(tokens(this, word) + " @"
                    + word.replace(' ', '_'));
            final byte[] all = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            final float[] samples = new float[(all.length - 44) / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((all[44 + 2 * i] & 0xff) | (all[45 + 2 * i] << 8)) / 32768f;
            }
            stream.acceptWaveform(new float[RATE / 2], RATE);
            stream.acceptWaveform(samples, RATE);
            stream.acceptWaveform(new float[RATE], RATE);
            stream.inputFinished();
            String heard = "";
            while (spotter.isReady(stream)) {
                spotter.decode(stream);
                final KeywordSpotterResult r = spotter.getResult(stream);
                if (r != null && r.keyword != null && !r.keyword.isEmpty()) {
                    heard += r.keyword + " ";
                    spotter.reset(stream);
                }
            }
            Log.i(TAG, "wake word test " + path + ": '" + heard.trim() + "'");
            // Heard: the whole way on, sound and sheet, as if it had been said.
            if (!heard.trim().isEmpty()) {
                mMain.post(this::wake);
            }
            stream.release();
            spotter.release();
        } catch (Throwable t) {
            Log.e(TAG, "wake word test", t);
        }
    }

    /** For checks over adb: how loud an audio source hears, peak per half second. */
    private void testSource(int source) {
        final AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, RATE * 2);
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.i(TAG, "mic test source " + source + ": not available");
            r.release();
            return;
        }
        final short[] pcm = new short[RATE / 2];
        final StringBuilder peaks = new StringBuilder();
        final java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        r.startRecording();
        for (int k = 0; k < 10; k++) {
            final int n = r.read(pcm, 0, pcm.length);
            int peak = 0;
            for (int i = 0; i < n; i++) {
                peak = Math.max(peak, Math.abs(pcm[i]));
                raw.write(pcm[i]);
                raw.write(pcm[i] >> 8);
            }
            peaks.append(peak).append(' ');
        }
        r.stop();
        r.release();
        Log.i(TAG, "mic test source " + source + ": " + peaks);
        // The recording too, as a WAV in the app's files, to look at elsewhere.
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                new java.io.File(getFilesDir(), "mic-" + source + ".wav"))) {
            final byte[] pcmBytes = raw.toByteArray();
            final java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36 + pcmBytes.length).put("WAVEfmt ".getBytes())
                    .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE)
                    .putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                    .put("data".getBytes()).putInt(pcmBytes.length);
            out.write(h.array());
            out.write(pcmBytes);
        } catch (IOException e) {
            Log.w(TAG, "mic test", e);
        }
    }

    private KeywordSpotterConfig config() {
        final KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.modelConfig.transducer.encoder = "kws/encoder.onnx";
        config.modelConfig.transducer.decoder = "kws/decoder.onnx";
        config.modelConfig.transducer.joiner = "kws/joiner.onnx";
        config.modelConfig.tokens = "kws/tokens.txt";
        config.modelConfig.modelType = "zipformer2";
        config.modelConfig.numThreads = 1;
        config.keywordsFile = "wakewords.txt";
        config.keywordsThreshold = Prefs.get(this).getFloat(Prefs.WAKE_THRESHOLD, 0.2f);
        return config;
    }

    private boolean mayListen() {
        return !sPaused && (mCharging
                || !Prefs.get(this).getBoolean(Prefs.WAKE_CHARGING_ONLY, true));
    }

    private void run() {
        KeywordSpotter spotter = null;
        OnlineStream stream = null;
        try {
            spotter = new KeywordSpotter(getAssets(), config());
            final String word = Prefs.get(this).getString(Prefs.WAKE_WORD, PRESETS[0][0]);
            final String tokens = tokens(this, word);
            stream = spotter.createStream(tokens.isEmpty() ? "" : tokens + " @"
                    + word.replace(' ', '_'));
            Log.i(TAG, "wake word '" + word + "' as " + tokens);
            listen(spotter, stream);
        } catch (Throwable t) {
            Log.e(TAG, "wake word", t);
        } finally {
            if (stream != null) {
                stream.release();
            }
            if (spotter != null) {
                spotter.release();
            }
            mThread = null;
        }
    }

    private void listen(KeywordSpotter spotter, OnlineStream stream) {
        final short[] pcm = new short[RATE / 10];
        final float[] samples = new float[pcm.length];
        AudioRecord record = null;
        try {
            while (mRunning) {
                if (!mayListen()) {
                    if (record != null) {
                        record.stop();
                        record.release();
                        record = null;
                    }
                    Thread.sleep(500);
                    continue;
                }
                if (record == null) {
                    record = new AudioRecord(WhisperInput.micSource(this), RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(AudioRecord.getMinBufferSize(RATE,
                                    AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT), RATE));
                    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                        record.release();
                        record = null;
                        Thread.sleep(2000);
                        continue;
                    }
                    record.startRecording();
                    spotter.reset(stream);
                }
                final int n = record.read(pcm, 0, pcm.length);
                if (n <= 0) {
                    // The microphone did not start (busy elsewhere): try again in a while,
                    // a read that fails at once would keep a core busy.
                    record.release();
                    record = null;
                    Thread.sleep(2000);
                    continue;
                }
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    samples[i] = pcm[i] / 32768f;
                    peak = Math.max(peak, Math.abs(pcm[i]));
                }
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "wake word level " + peak);
                }
                stream.acceptWaveform(n == samples.length ? samples
                        : java.util.Arrays.copyOf(samples, n), RATE);
                while (spotter.isReady(stream)) {
                    spotter.decode(stream);
                    final KeywordSpotterResult result = spotter.getResult(stream);
                    if (result != null && result.keyword != null && !result.keyword.isEmpty()) {
                        spotter.reset(stream);
                        Log.i(TAG, "wake word heard: " + result.keyword);
                        sPaused = true;
                        mMain.post(this::wake);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (record != null) {
                record.stop();
                record.release();
            }
        }
    }

    /** The word was heard: a short sound, and the sheet comes up listening. */
    private void wake() {
        Chime.play();
        startActivity(new Intent(this, AssistActivity.class)
                .setAction(Intent.ACTION_VOICE_COMMAND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
