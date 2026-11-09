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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Listening through a Whisper server: the tablet records, notices when the speaker pauses,
 * and sends the recording to an OpenAI-compatible /v1/audio/transcriptions (faster-whisper,
 * whisper.cpp, LocalAI, OpenAI). Works without any of Google's software.
 */
final class WhisperInput implements Listening {

    private static final String TAG = "ONFireAI";
    private static final int RATE = 16000;
    private static final int FRAME = 320;            // 20 ms
    private static final int SPEECH_FRAMES = 4;      // 80 ms above the noise to count as speech
    private static final int END_SILENCE = 40;       // 800 ms of quiet ends it
    private static final int MAX_FRAMES = 15 * 50;  // 15 s at most
    private static final int PARTIAL_EVERY = 60;     // a live transcript every 1.2 s
    private static final long PARTIAL_TOO_SLOW = 2500;
    /** The chime after the wake word: 0.35 s, a little more for its echo. */
    private static final int CHIME_FRAMES = 35;
    /** Less than this much voice (0.3 s) before a pause is a click or an echo, not a question. */
    private static final int MIN_VOICED = 15;
    /** On the tablet: a pause this long (300 ms) closes a stretch to be read at once... */
    private static final int SEGMENT_PAUSE = 15;
    /** ...when the stretch has at least 1.5 s (enough to read it well), any quiet moment
     *  after 4 s. */
    private static final int SEGMENT_MIN = RATE * 2 * 3 / 2;
    private static final int SEGMENT_MAX = RATE * 2 * 4;
    /** Small words the model writes capitalised when a stretch begins with them. */
    private static final java.util.Set<String> SMALL = new java.util.HashSet<>(
            java.util.Arrays.asList("und", "oder", "aber", "wenn", "weil", "dass", "ob",
                    "dann", "denn", "als", "wie", "was", "wo", "wann", "mit", "in", "im",
                    "auf", "an", "am", "zu", "zum", "zur", "für", "von", "vom", "bei", "nach",
                    "aus", "um", "über", "unter", "vor", "der", "die", "das", "den", "dem",
                    "des", "ein", "eine", "einen", "einem", "ich", "du", "er", "es", "wir",
                    "ihr", "mir", "mich", "dir", "dich", "uns", "noch", "auch", "nicht",
                    "bitte", "so", "and", "or", "but", "the", "to", "of", "with"));
    /** Whole lines Whisper learned from subtitles, said into silence. */
    private static final String[] HALLUCINATIONS = {
        "untertitel im auftrag des zdf", "untertitelung im auftrag des zdf",
        "untertitel der amara.org-community", "untertitel von stephanie geiges", "amara.org",
        "copyright wdr", "vielen dank für ihre aufmerksamkeit", "thanks for watching",
        "thank you for watching", "swr 2020", "swr 2021"
    };

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mActive;
    private volatile boolean mStop;
    /** Counts the recordings; one that was cancelled finds a newer number and keeps quiet. */
    private volatile int mGeneration;
    /** The recording whose live transcript may still show; -1 once it is being finished. */
    private volatile int mLiveFor = -1;
    private volatile boolean mPartialBusy;
    /** A server too slow for a live transcript is spared it until the app restarts. */
    private static volatile boolean sPartialSlow;
    private Thread mThread;

    WhisperInput(Context context, Callback callback) {
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    /**
     * The microphone as it is. This tablet's VOICE_RECOGNITION input is all but deaf (measured
     * on my device: noise 15, speech nothing; the raw input hears speech at 1000-2000), so
     * the raw one where the device has it, else the plain microphone.
     */
    static int micSource(Context context) {
        final String raw = context.getSystemService(android.media.AudioManager.class)
                .getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
        return "true".equals(raw) ? MediaRecorder.AudioSource.UNPROCESSED
                : MediaRecorder.AudioSource.MIC;
    }

    static boolean configured(Context context) {
        return !Prefs.string(context, Prefs.STT_URL).isEmpty();
    }

    /**
     * Whether what is said is read here on the tablet: when asked for, or by default when
     * no server is set.
     */
    static boolean local(Context context) {
        final String mode = Prefs.get(context).getString(Prefs.STT_MODE, "auto");
        return LocalSpeech.available(context) && ("tablet".equals(mode)
                || ("auto".equals(mode) && !configured(context)));
    }

    /** The recording (16-bit PCM) as text, from the tablet's own model or the server. */
    private String transcribePcm(byte[] pcm) throws IOException {
        return local(mContext) ? LocalSpeech.transcribe(mContext, pcm, 0)
                : transcribe(wav(pcm));
    }

    @Override
    public boolean start(boolean followUp) {
        if (mActive) {
            return true;
        }
        mActive = true;
        mStop = false;
        if (local(mContext)) {
            // Ready by the time the speaker pauses.
            LocalSpeech.warmUp(mContext);
        }
        final int generation = ++mGeneration;
        final int quietLimit = (followUp ? 5 : 8) * 50;
        mThread = new Thread(() -> listen(generation, quietLimit), "ONFireAI-whisper");
        mThread.start();
        return true;
    }

    @Override
    public void stop() {
        mStop = true;
    }

    @Override
    public void cancel() {
        mGeneration++;
        mActive = false;
    }

    @Override
    public void destroy() {
        cancel();
    }

    @Override
    public boolean active() {
        return mActive;
    }

    private void post(int generation, Runnable r) {
        mMain.post(() -> {
            if (generation == mGeneration) {
                r.run();
            }
        });
    }

    /** Still the recording asked for, not one cancelled since. */
    private boolean current(int generation) {
        return generation == mGeneration;
    }

    private void fail(int generation, int error) {
        if (current(generation)) {
            mActive = false;
        }
        post(generation, () -> mCallback.onError(error));
    }

    private void listen(int g, int quietLimit) {
        final int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        // A test recording put in place by hand (root only) stands in for the microphone,
        // once: it is deleted as it is read.
        final java.io.File testFile = new java.io.File(mContext.getFilesDir(), "test-mic.wav");
        java.io.InputStream test = null;
        if (testFile.exists()) {
            try {
                test = new java.io.FileInputStream(testFile);
                test.skip(44);
            } catch (IOException e) {
                test = null;
            }
            testFile.delete();
        }
        // Called by the wake word: what it heard since goes on here, without a gap.
        final WakeWord.Handoff handoff = test == null ? WakeWord.takeHandoff() : null;
        final HandoffReader fromWake = handoff == null ? null : new HandoffReader(handoff);
        // The wake word hands the microphone over within a tenth of a second; until then
        // it is still taken, and Android lets the second recording start in name only.
        AudioRecord record = null;
        for (int attempt = 0; test == null && fromWake == null && attempt < 12 && current(g);
                attempt++) {
            try {
                record = new AudioRecord(micSource(mContext), RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, RATE));
                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording();
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        break;
                    }
                }
                record.release();
            } catch (RuntimeException e) {
                if (record != null) {
                    record.release();
                }
            }
            record = null;
            android.os.SystemClock.sleep(120);
        }
        if (record == null && test == null && fromWake == null) {
            fail(g, SpeechRecognizer.ERROR_AUDIO);
            return;
        }
        // Before the word and while the chime sounds, loudness is no sign of a question.
        final int ignoreUntil = handoff == null ? 0 : handoff.prerollFrames + CHIME_FRAMES;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream(RATE * 2 * 6);
        final short[] frame = new short[FRAME];
        final byte[] bytes = new byte[FRAME * 2];
        // Coming from the wake word, the room was measured before it (one may be talking
        // from the first moment on); otherwise it is learned in the first moments.
        float floor = handoff != null && handoff.ambientDb > 0 ? handoff.ambientDb
                : Float.MAX_VALUE;
        final int learnFrames = handoff != null && handoff.ambientDb > 0 ? 0 : 15;
        int frames = 0;
        int loud = 0;
        int quiet = 0;
        boolean speech = false;
        int voiced = 0;
        int blips = 0;
        int nextPartial = 0;
        final Segments segments = local(mContext) ? new Segments(g) : null;
        mLiveFor = g;
        try {
            post(g, mCallback::onReadyForSpeech);
            while (current(g)) {
                final int n = test != null ? readTest(test, frame, bytes)
                        : fromWake != null ? fromWake.read(frame)
                        : record.read(frame, 0, FRAME);
                if (n <= 0) {
                    fail(g, SpeechRecognizer.ERROR_AUDIO);
                    return;
                }
                frames++;
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) frame[i] * frame[i];
                    bytes[2 * i] = (byte) frame[i];
                    bytes[2 * i + 1] = (byte) (frame[i] >> 8);
                }
                pcm.write(bytes, 0, n * 2);
                final float db = (float) (20 * Math.log10(Math.sqrt(sum / n) + 1));
                // The noise floor: the quietest stretch so far, learned in the first moments.
                if (frames <= learnFrames || db < floor) {
                    floor = Math.min(floor, db);
                }
                final float threshold = Math.max(floor + 12f, 38f);
                final float level = (db - floor) / 3f - 2f;
                post(g, () -> mCallback.onLevel(level));
                if (db > threshold && frames > ignoreUntil) {
                    loud++;
                    quiet = 0;
                    if (speech) {
                        voiced++;
                    }
                    if (loud >= SPEECH_FRAMES && !speech) {
                        speech = true;
                        nextPartial = frames + PARTIAL_EVERY;
                    }
                } else {
                    loud = Math.max(0, loud - 1);
                    if (speech) {
                        quiet++;
                    }
                }
                if (speech && quiet >= END_SILENCE) {
                    if (voiced >= MIN_VOICED) {
                        break;
                    }
                    // Only a blip (the chime's echo, a click): still waiting for the question.
                    speech = false;
                    voiced = 0;
                    quiet = 0;
                    loud = 0;
                    blips++;
                }
                if (segments != null) {
                    // The tablet reads each stretch between short pauses once, while one
                    // goes on speaking; at the end only the last bit is left to read.
                    final int length = pcm.size() - segments.cut;
                    if (speech && LocalSpeech.ready() && !segments.busy()
                            && ((quiet >= SEGMENT_PAUSE && length >= SEGMENT_MIN)
                                    || (length >= SEGMENT_MAX && db <= threshold))) {
                        segments.read(pcm.toByteArray());
                    }
                } else if (speech && frames >= nextPartial && !mPartialBusy && !sPartialSlow) {
                    nextPartial = frames + PARTIAL_EVERY;
                    partial(g, pcm.toByteArray());
                }
                if (mStop) {
                    if (!speech) {
                        mActive = false;
                        post(g, () -> mCallback.onFinal(""));
                        return;
                    }
                    break;
                }
                if (!speech && frames >= quietLimit) {
                    fail(g, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    return;
                }
                if (frames >= MAX_FRAMES) {
                    break;
                }
            }
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (RuntimeException ignored) {
                }
                record.release();
            }
            if (test != null) {
                try {
                    test.close();
                } catch (IOException ignored) {
                }
            }
            if (handoff != null) {
                WakeWord.endHandoff(handoff);
            }
        }
        if (mLiveFor == g) {
            mLiveFor = -1;
        }
        Log.i(TAG, "recording ended after " + frames * 20 + " ms: speech " + speech
                + ", voice " + voiced * 20 + " ms, blips " + blips + ", floor " + (int) floor
                + " dB" + (handoff != null ? ", from the wake word" : ""));
        if (!current(g)) {
            return;
        }
        post(g, () -> mCallback.onPartial("…"));
        try {
            final String text;
            if (segments != null && segments.await()) {
                final byte[] all = pcm.toByteArray();
                final long started = android.os.SystemClock.elapsedRealtime();
                final String tail = all.length - segments.cut >= RATE / 5
                        ? LocalSpeech.transcribe(mContext, all, segments.cut) : "";
                Log.i(TAG, "speech read on the tablet: last "
                        + (all.length - segments.cut) / 32 + " ms of speech in "
                        + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
                text = clean(segments.join(tail));
            } else {
                text = clean(transcribePcm(pcm.toByteArray()));
            }
            if (current(g)) {
                mActive = false;
            }
            Log.i(TAG, "heard " + text.length() + " characters" + (handoff != null
                    ? " after the wake word" : ""));
            mMain.post(() -> {
                if (g == mGeneration) {
                    mCallback.onFinal(text);
                } else {
                    Log.w(TAG, "heard text dropped: the listening was cancelled meanwhile");
                }
            });
        } catch (IOException e) {
            Log.w(TAG, "whisper", e);
            fail(g, e instanceof LlmClient.ServerException ? SpeechRecognizer.ERROR_SERVER
                    : SpeechRecognizer.ERROR_NETWORK);
        }
    }

    /**
     * The stretches of one recording read on the tablet, in order: one at a time in the
     * background, each shown as soon as it is read.
     */
    private final class Segments {
        private final int mFor;
        private final java.util.List<String> mTexts = new java.util.ArrayList<>();
        private Thread mThread;
        private volatile boolean mFailed;
        /** Where the part not yet handed on begins (bytes of PCM). */
        int cut;

        Segments(int generation) {
            mFor = generation;
        }

        boolean busy() {
            return mThread != null && mThread.isAlive();
        }

        void read(byte[] all) {
            final byte[] part = java.util.Arrays.copyOfRange(all, cut, all.length);
            cut = all.length;
            mThread = new Thread(() -> {
                try {
                    final String text = LocalSpeech.transcribe(mContext, part, 0).trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    synchronized (mTexts) {
                        mTexts.add(text);
                    }
                    final String shown = join("");
                    mMain.post(() -> {
                        if (mLiveFor == mFor && current(mFor)) {
                            mCallback.onPartial(shown);
                        }
                    });
                } catch (IOException e) {
                    mFailed = true;
                }
            }, "ONFireAI-asr-segment");
            mThread.start();
        }

        /** Waits for the stretch being read; false when one could not be read. */
        boolean await() {
            if (mThread != null) {
                try {
                    mThread.join();
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return !mFailed;
        }

        /** One text: a full stop where one paused for breath is no end of the sentence. */
        String join(String tail) {
            final java.util.List<String> parts = new java.util.ArrayList<>();
            synchronized (mTexts) {
                parts.addAll(mTexts);
            }
            if (!tail.trim().isEmpty()) {
                parts.add(tail.trim());
            }
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                String piece = parts.get(i);
                if (i < parts.size() - 1) {
                    piece = piece.replaceAll("[.,…]+$", "");
                }
                if (out.length() > 0 && !out.toString().matches("(?s).*[.?!]$")) {
                    // Mid-sentence, a small word is not the start of one.
                    final int space = piece.indexOf(' ');
                    final String first = space < 0 ? piece : piece.substring(0, space);
                    if (SMALL.contains(first.toLowerCase(Locale.ROOT))) {
                        piece = first.toLowerCase(Locale.ROOT)
                                + (space < 0 ? "" : piece.substring(space));
                    }
                }
                out.append(out.length() == 0 ? "" : " ").append(piece);
            }
            String text = out.toString().replaceAll("[,;:]+$", "").trim();
            if (!text.isEmpty()) {
                text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
            return text;
        }
    }

    /** The wake word's chunks (100 ms) as the frames of 20 ms read here. */
    private static final class HandoffReader {
        private final WakeWord.Handoff mHandoff;
        private short[] mChunk;
        private int mAt;

        HandoffReader(WakeWord.Handoff handoff) {
            mHandoff = handoff;
        }

        int read(short[] frame) {
            int filled = 0;
            while (filled < frame.length) {
                if (mChunk == null || mAt >= mChunk.length) {
                    try {
                        mChunk = mHandoff.chunks.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        mChunk = null;
                    }
                    mAt = 0;
                    if (mChunk == null) {
                        return -1;
                    }
                }
                final int take = Math.min(frame.length - filled, mChunk.length - mAt);
                System.arraycopy(mChunk, mAt, frame, filled, take);
                mAt += take;
                filled += take;
            }
            return filled;
        }
    }

    /** One frame of the test recording at the pace of a microphone; silence after its end. */
    private static int readTest(java.io.InputStream in, short[] frame, byte[] bytes) {
        int got = 0;
        try {
            while (got < bytes.length) {
                final int r = in.read(bytes, got, bytes.length - got);
                if (r < 0) {
                    break;
                }
                got += r;
            }
        } catch (IOException ignored) {
        }
        for (int i = 0; i < frame.length; i++) {
            frame[i] = 2 * i + 1 < got
                    ? (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8)) : 0;
        }
        android.os.SystemClock.sleep(20);
        return frame.length;
    }

    /**
     * The words so far, while one is still speaking: the recording up to now goes to the
     * server on the side. One at a time; what comes back after the end is dropped.
     */
    private void partial(int g, byte[] pcm) {
        mPartialBusy = true;
        new Thread(() -> {
            final long started = android.os.SystemClock.elapsedRealtime();
            try {
                final String text = clean(transcribePcm(pcm));
                final long took = android.os.SystemClock.elapsedRealtime() - started;
                Log.d(TAG, "live transcript: " + took + " ms for " + pcm.length / 32
                        + " ms of speech");
                if (took > PARTIAL_TOO_SLOW) {
                    sPartialSlow = true;
                }
                if (!text.isEmpty()) {
                    mMain.post(() -> {
                        if (mLiveFor == g && current(g)) {
                            mCallback.onPartial(text);
                        }
                    });
                }
            } catch (IOException e) {
                // The final transcript reports any trouble; a live one just stays away.
            } finally {
                mPartialBusy = false;
            }
        }, "ONFireAI-whisper-live").start();
    }

    /** Whisper fills silence with lines it learned from subtitles; those are not speech. */
    private static String clean(String text) {
        // The wake word itself, heard again in the run-up, is not part of the question.
        String t = text.trim().replaceFirst(
                "(?i)^((hey|hei|he|hay)[\\s,.!]*)?(fire|feuer|fayer|fair|faia)\\b[\\s,.!]*", "");
        if (!t.isEmpty()) {
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        final String lower = t.toLowerCase(Locale.ROOT);
        // Only such a line on its own: "Was läuft im ZDF?" is meant.
        for (String h : HALLUCINATIONS) {
            if (lower.contains(h) && lower.length() <= h.length() + 20) {
                return "";
            }
        }
        return t.replaceAll("^[\\s.…]+$", "");
    }

    private static byte[] wav(byte[] pcm) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        final int byteRate = RATE * 2;
        writeAscii(out, "RIFF");
        writeInt(out, 36 + pcm.length);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 1);         // PCM
        writeShort(out, 1);         // mono
        writeInt(out, RATE);
        writeInt(out, byteRate);
        writeShort(out, 2);         // block align
        writeShort(out, 16);        // bits
        writeAscii(out, "data");
        writeInt(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        final byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >> 8);
        out.write(v >> 16);
        out.write(v >> 24);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >> 8);
    }

    private String transcribe(byte[] wav) throws IOException {
        final String base = Prefs.string(mContext, Prefs.STT_URL);
        final String model = Prefs.string(mContext, Prefs.STT_MODEL);
        final String boundary = "----onfireai" + System.nanoTime();
        final HttpURLConnection c = (HttpURLConnection) new URL(
                LlmClient.endpoint(base, "/audio/transcriptions")).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            final String key = Prefs.string(mContext, Prefs.STT_KEY);
            if (!key.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + key);
            }
            try (OutputStream out = c.getOutputStream()) {
                field(out, boundary, "model", model.isEmpty() ? "whisper-1" : model);
                field(out, boundary, "language", Locale.getDefault().getLanguage());
                field(out, boundary, "response_format", "json");
                field(out, boundary, "temperature", "0");
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                        + " filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(wav);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            final int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new LlmClient.ServerException(String.valueOf(code));
            }
            final String body = LlmClient.readAll(c.getInputStream()).trim();
            if (body.startsWith("{")) {
                return new JSONObject(body).optString("text", "");
            }
            return body;
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    private static void field(OutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
