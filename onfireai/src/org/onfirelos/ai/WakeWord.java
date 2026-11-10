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

    /**
     * What the microphone heard from just before the word on, handed to the sheet: who says
     * "Hey Fire, wie spät ist es" in one breath is heard whole, without the gap it takes the
     * sheet to come up and open the microphone itself.
     */
    static final class Handoff {
        final java.util.concurrent.LinkedBlockingQueue<short[]> chunks =
                new java.util.concurrent.LinkedBlockingQueue<>();
        /** The room's noise before the word, as the sheet measures loudness. */
        final float ambientDb;
        /** How much at the start is from before the word was recognised (frames of 20 ms). */
        final int prerollFrames;
        final long since = android.os.SystemClock.elapsedRealtime();
        /** Heard while ON-FireAI spoke: until its voice is gone, the chunks hold it too. */
        final boolean overVoice = Session.speaking();
        volatile boolean claimed;
        volatile boolean done;

        Handoff(float ambientDb, int prerollFrames) {
            this.ambientDb = ambientDb;
            this.prerollFrames = prerollFrames;
        }
    }

    private static volatile Handoff sHandoff;
    private static final int PREROLL_CHUNKS = 6;     // 0.6 s before the word was recognised
    private static final int AMBIENT_CHUNKS = 30;    // the last 3 s...
    private static final int WORD_CHUNKS = 12;       // ...without the word itself

    /** The sheet takes what was heard since the word; null when there is nothing to take. */
    static Handoff takeHandoff() {
        final Handoff h = sHandoff;
        if (h == null || h.claimed || h.done) {
            return null;
        }
        h.claimed = true;
        return h;
    }

    /** Listening goes another way (Android's recognition): nothing is handed over. */
    static void dropHandoff() {
        final Handoff h = sHandoff;
        if (h != null) {
            h.done = true;
        }
    }

    /** The sheet has what it needs: the microphone goes back to listening for the word. */
    static void endHandoff(Handoff h) {
        h.done = true;
    }

    private static float db(short[] pcm, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (double) pcm[i] * pcm[i];
        }
        return (float) (20 * Math.log10(Math.sqrt(sum / Math.max(1, n)) + 1));
    }

    private java.io.InputStream openTest() {
        final java.io.File file = new java.io.File(getFilesDir(), "wake-test.wav");
        if (!file.exists()) {
            return null;
        }
        try {
            final java.io.InputStream in = new java.io.FileInputStream(file);
            in.skip(44);
            return in;
        } catch (IOException e) {
            return null;
        } finally {
            file.delete();
        }
    }

    /** 100 ms of the test recording; 0 at its end. */
    private static int readTest(java.io.InputStream in, short[] pcm) throws IOException {
        final byte[] bytes = new byte[pcm.length * 2];
        int got = 0;
        while (got < bytes.length) {
            final int r = in.read(bytes, got, bytes.length - got);
            if (r < 0) {
                break;
            }
            got += r;
        }
        for (int i = 0; i < got / 2; i++) {
            pcm[i] = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
        }
        return got / 2;
    }

    private boolean mayListen() {
        // Paused only while ON-FireAI itself listens: while it speaks, the word interrupts it.
        return !sPaused && !Session.listening() && (mCharging
                || !Prefs.get(this).getBoolean(Prefs.WAKE_CHARGING_ONLY, true)
                // Off the charger too while ON-FireAI answers: talking into it is heard.
                || (Session.working() && Prefs.bargeIn(this)));
    }

    private void run() {
        KeywordSpotter spotter = null;
        OnlineStream stream = null;
        OnlineStream late = null;
        try {
            spotter = new KeywordSpotter(getAssets(), config());
            final String word = Prefs.get(this).getString(Prefs.WAKE_WORD, PRESETS[0][0]);
            final String tokens = tokens(this, word);
            final String keyword = tokens.isEmpty() ? "" : tokens + " @"
                    + word.replace(' ', '_');
            stream = spotter.createStream(keyword);
            late = spotter.createStream(keyword);
            Log.i(TAG, "wake word '" + word + "' as " + tokens);
            listen(spotter, stream, late);
        } catch (Throwable t) {
            Log.e(TAG, "wake word", t);
        } finally {
            if (stream != null) {
                stream.release();
            }
            if (late != null) {
                late.release();
            }
            if (spotter != null) {
                spotter.release();
            }
            mThread = null;
        }
    }

    /** The word, when the stream heard it in what it has so far (it starts over then). */
    private static String decode(KeywordSpotter spotter, OnlineStream stream) {
        String heard = null;
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
            final KeywordSpotterResult result = spotter.getResult(stream);
            if (result != null && result.keyword != null && !result.keyword.isEmpty()) {
                spotter.reset(stream);
                heard = result.keyword;
            }
        }
        return heard;
    }

    /**
     * Two listeners on the same sound, the second 130 ms later: the model hears a word or not
     * depending on where it falls in its processing steps, and what one cuts badly the other
     * catches (measured offline: 63 instead of 56 of 75 words, over music 49 instead of 36).
     */
    private static final int LATE = RATE * 13 / 100;
    /** One word heard by both listeners counts once. */
    private static final long AGAIN_AFTER = 2000;

    private void listen(KeywordSpotter spotter, OnlineStream stream, OnlineStream late) {
        final short[] pcm = new short[RATE / 10];
        final float[] samples = new float[pcm.length];
        final float[] delayed = new float[LATE + pcm.length];
        long heardAt = 0;
        long speakingSince = 0;
        final short[][] recent = new short[PREROLL_CHUNKS][];
        final float[] levels = new float[AMBIENT_CHUNKS];
        int count = 0;
        java.io.InputStream test = null;
        AudioRecord record = null;
        // The tablet's own music out of the microphone, where the ROM hands a copy of it.
        final EchoCanceller echo = EchoCanceller.available(this) ? new EchoCanceller(this)
                : null;
        try {
            while (mRunning) {
                final Handoff handoff = sHandoff;
                if (handoff != null && !handoff.done) {
                    final long age = android.os.SystemClock.elapsedRealtime() - handoff.since;
                    // Not taken up (the sheet listens another way), or long enough.
                    if ((!handoff.claimed && age > 4000) || age > 20000) {
                        handoff.done = true;
                    }
                }
                final boolean capturing = handoff != null && !handoff.done;
                if (!capturing && !mayListen()) {
                    if (record != null) {
                        record.stop();
                        record.release();
                        record = null;
                    }
                    if (echo != null) {
                        echo.stop();
                    }
                    Thread.sleep(500);
                    continue;
                }
                // A test recording put in place by hand (root only) is heard instead of the
                // microphone, once, the whole way: word, hand-over, question.
                if (test == null) {
                    test = openTest();
                }
                final int n;
                if (test != null) {
                    n = readTest(test, pcm);
                    if (n <= 0) {
                        test.close();
                        test = null;
                        continue;
                    }
                    Thread.sleep(100);
                } else {
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
                    spotter.reset(late);
                    java.util.Arrays.fill(delayed, 0f);
                }
                n = record.read(pcm, 0, pcm.length);
                if (n <= 0) {
                    // The microphone did not start (busy elsewhere): try again in a while,
                    // a read that fails at once would keep a core busy.
                    record.release();
                    record = null;
                    Thread.sleep(2000);
                    continue;
                }
                }
                short[] heard = pcm;
                if (echo != null && test == null) {
                    final boolean was = echo.cleaning();
                    heard = echo.process(pcm, n);
                    // Cleaned audio is from a moment earlier: the word starts over there.
                    if (echo.cleaning() != was) {
                        spotter.reset(stream);
                        spotter.reset(late);
                    }
                }
                if (capturing) {
                    handoff.chunks.offer(java.util.Arrays.copyOf(heard, n));
                    continue;
                }
                recent[count % PREROLL_CHUNKS] = java.util.Arrays.copyOf(heard, n);
                levels[count % AMBIENT_CHUNKS] = db(heard, n);
                count++;
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    samples[i] = heard[i] / 32768f;
                    peak = Math.max(peak, Math.abs(heard[i]));
                }
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "wake word level " + peak);
                }
                final float[] chunk = n == samples.length ? samples
                        : java.util.Arrays.copyOf(samples, n);
                stream.acceptWaveform(chunk, RATE);
                // The late listener: what came LATE samples ago, then the new chunk waits.
                System.arraycopy(chunk, 0, delayed, LATE, n);
                late.acceptWaveform(java.util.Arrays.copyOf(delayed, n), RATE);
                System.arraycopy(delayed, n, delayed, 0, LATE);
                final String first = decode(spotter, stream);
                final String second = decode(spotter, late);
                final long now = android.os.SystemClock.elapsedRealtime();
                // Talking into ON-FireAI's answer, without the word: 600 ms of a voice over
                // its own, once the canceller has had more than a second with it.
                final boolean speaking = Session.speaking();
                speakingSince = speaking ? (speakingSince == 0 ? now : speakingSince) : 0;
                final boolean bargeIn = speaking && echo != null && test == null
                        && now - speakingSince > 1500 && echo.voice() >= 6
                        && Prefs.bargeIn(this);
                final String keyword = first != null ? first : second != null ? second
                        : bargeIn ? "VOICE" : null;
                if (keyword != null && now - heardAt > AGAIN_AFTER) {
                    heardAt = now;
                    spotter.reset(stream);
                    spotter.reset(late);
                    Log.i(TAG, bargeIn && first == null && second == null
                            ? "talked into the answer"
                            : "wake word heard: " + keyword + (first == null ? " (late listener)" : ""));
                    // The room before the word: the quietest of the seconds before it.
                    float ambient = Float.MAX_VALUE;
                    for (int k = WORD_CHUNKS; k < Math.min(count, AMBIENT_CHUNKS); k++) {
                        ambient = Math.min(ambient,
                                levels[(count - 1 - k + AMBIENT_CHUNKS) % AMBIENT_CHUNKS]);
                    }
                    final int pre = Math.min(count, PREROLL_CHUNKS);
                    final Handoff h = new Handoff(ambient == Float.MAX_VALUE ? -1 : ambient,
                            pre * 5);
                    for (int k = pre; k >= 1; k--) {
                        h.chunks.offer(recent[(count - k) % PREROLL_CHUNKS]);
                    }
                    sHandoff = h;
                    sPaused = true;
                    mMain.post(this::wake);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.w(TAG, "wake word test", e);
        } finally {
            if (record != null) {
                record.stop();
                record.release();
            }
            if (echo != null) {
                echo.stop();
            }
        }
    }

    /** Whether the hand-over waiting to be taken up began over ON-FireAI's own voice. */
    static boolean overVoice() {
        final Handoff h = sHandoff;
        return h != null && !h.done && h.overVoice;
    }

    /** The word was heard: a short sound, and the sheet comes up listening. */
    private void wake() {
        // Its own voice would be heard as the question: it stops now.
        Session.hush();
        // The pause set on hearing it ends once the sheet listens (which pauses anyway).
        mMain.postDelayed(() -> sPaused = false, 3000);
        // Down at once, before the sheet is even there; the sheet keeps it so.
        Duck.set(this, true, 10_000);
        Chime.play();
        if (WhisperInput.local(this)) {
            // The model loads while the sheet comes up.
            LocalSpeech.warmUp(this);
        }
        startActivity(new Intent(this, AssistActivity.class)
                .setAction(Intent.ACTION_VOICE_COMMAND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
