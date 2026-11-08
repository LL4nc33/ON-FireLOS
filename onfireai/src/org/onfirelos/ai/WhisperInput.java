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
    private static final String[] HALLUCINATIONS = {
        "untertitel", "copyright", "vielen dank für ihre aufmerksamkeit",
        "thanks for watching", "thank you for watching", "amara.org", "swr 20", "zdf"
    };

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mActive;
    private volatile boolean mStop;
    private volatile boolean mCancel;
    private Thread mThread;

    WhisperInput(Context context, Callback callback) {
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    static boolean configured(Context context) {
        return !Prefs.string(context, Prefs.STT_URL).isEmpty();
    }

    @Override
    public boolean start(boolean followUp) {
        if (mActive) {
            return true;
        }
        mActive = true;
        mStop = false;
        mCancel = false;
        final int quietLimit = (followUp ? 5 : 8) * 50;
        mThread = new Thread(() -> listen(quietLimit), "ONFireAI-whisper");
        mThread.start();
        return true;
    }

    @Override
    public void stop() {
        mStop = true;
    }

    @Override
    public void cancel() {
        mCancel = true;
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

    private void post(Runnable r) {
        mMain.post(() -> {
            if (!mCancel) {
                r.run();
            }
        });
    }

    private void fail(int error) {
        mActive = false;
        post(() -> mCallback.onError(error));
    }

    private void listen(int quietLimit) {
        final int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, RATE));
        } catch (RuntimeException e) {
            fail(SpeechRecognizer.ERROR_AUDIO);
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            fail(SpeechRecognizer.ERROR_AUDIO);
            return;
        }
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream(RATE * 2 * 6);
        final short[] frame = new short[FRAME];
        final byte[] bytes = new byte[FRAME * 2];
        float floor = Float.MAX_VALUE;
        int frames = 0;
        int loud = 0;
        int quiet = 0;
        boolean speech = false;
        try {
            record.startRecording();
            post(mCallback::onReadyForSpeech);
            while (!mCancel) {
                final int n = record.read(frame, 0, FRAME);
                if (n <= 0) {
                    fail(SpeechRecognizer.ERROR_AUDIO);
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
                if (frames <= 15 || db < floor) {
                    floor = Math.min(floor, db);
                }
                final float threshold = Math.max(floor + 12f, 38f);
                final float level = (db - floor) / 3f - 2f;
                post(() -> mCallback.onLevel(level));
                if (db > threshold) {
                    loud++;
                    quiet = 0;
                    if (loud >= SPEECH_FRAMES) {
                        speech = true;
                    }
                } else {
                    loud = Math.max(0, loud - 1);
                    if (speech) {
                        quiet++;
                    }
                }
                if (speech && quiet >= END_SILENCE) {
                    break;
                }
                if (mStop) {
                    if (!speech) {
                        mActive = false;
                        post(() -> mCallback.onFinal(""));
                        return;
                    }
                    break;
                }
                if (!speech && frames >= quietLimit) {
                    fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    return;
                }
                if (frames >= MAX_FRAMES) {
                    break;
                }
            }
        } finally {
            try {
                record.stop();
            } catch (RuntimeException ignored) {
            }
            record.release();
        }
        if (mCancel) {
            return;
        }
        post(() -> mCallback.onPartial("…"));
        try {
            final String text = clean(transcribe(wav(pcm.toByteArray())));
            mActive = false;
            post(() -> mCallback.onFinal(text));
        } catch (IOException e) {
            Log.w(TAG, "whisper", e);
            fail(e instanceof LlmClient.ServerException ? SpeechRecognizer.ERROR_SERVER
                    : SpeechRecognizer.ERROR_NETWORK);
        }
    }

    /** Whisper fills silence with lines it learned from subtitles; those are not speech. */
    private static String clean(String text) {
        final String t = text.trim();
        final String lower = t.toLowerCase(Locale.ROOT);
        for (String h : HALLUCINATIONS) {
            if (lower.contains(h)) {
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
