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
import android.os.ConditionVariable;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * "ON-FireAI voice": a text-to-speech engine for the whole tablet that speaks with a voice
 * from a server of the user's - any OpenAI-compatible /v1/audio/speech (Kokoro, Chatterbox,
 * Piper, LocalAI, OpenAI) or ElevenLabs. When the server cannot be reached it speaks with
 * the tablet's other voice instead, so nothing falls silent.
 */
public class ServerVoice extends TextToSpeechService {

    private static final String TAG = "ONFireAI";
    private volatile boolean mStopped;
    private volatile HttpURLConnection mConnection;
    private TextToSpeech mFallback;
    private final ConditionVariable mFallbackReady = new ConditionVariable();
    private volatile boolean mFallbackOk;
    /** Until when the server is left alone after it did not answer (one wait, not one per
     *  sentence). */
    private static volatile long sServerDownUntil;
    /** Which server that was: another address set meanwhile is tried at once. */
    private static volatile String sServerDown = "";
    private static final long SERVER_REST = 60_000;
    private static final int MAX_AUDIO = 32 << 20;

    static boolean configured(Context context) {
        return "elevenlabs".equals(Prefs.get(context).getString(Prefs.TTS_PROVIDER, ""))
                ? !Prefs.string(context, Prefs.TTS_KEY).isEmpty()
                        && !Prefs.string(context, Prefs.TTS_VOICE).isEmpty()
                : !Prefs.string(context, Prefs.TTS_URL).isEmpty();
    }

    // ---------------------------------------------------------------- languages: whatever the server speaks

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        final Locale l = Locale.getDefault();
        return new String[] { l.getISO3Language(), l.getISO3Country(), "" };
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected void onStop() {
        mStopped = true;
        final HttpURLConnection c = mConnection;
        if (c != null) {
            c.disconnect();
        }
        if (mFallback != null) {
            mFallback.stop();
        }
    }

    @Override
    public void onDestroy() {
        if (mFallback != null) {
            mFallback.shutdown();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- speaking

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        mStopped = false;
        final String text = request.getCharSequenceText() == null ? request.getText()
                : request.getCharSequenceText().toString();
        if (text == null || text.trim().isEmpty()) {
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1);
            callback.done();
            return;
        }
        boolean started = false;
        try {
            if (!configured(this)) {
                throw new IOException("no voice server set");
            }
            final String server = Prefs.get(this).getString(Prefs.TTS_PROVIDER, "") + " "
                    + Prefs.string(this, Prefs.TTS_URL);
            if (server.equals(sServerDown)
                    && android.os.SystemClock.elapsedRealtime() < sServerDownUntil) {
                throw new IOException("the voice server did not answer a moment ago");
            }
            final boolean eleven = "elevenlabs".equals(
                    Prefs.get(this).getString(Prefs.TTS_PROVIDER, ""));
            final byte[] audio;
            try {
                audio = eleven ? elevenLabs(text) : openAi(text);
            } catch (java.net.SocketTimeoutException | java.net.ConnectException
                    | java.net.NoRouteToHostException | java.net.UnknownHostException e) {
                sServerDown = server;
                sServerDownUntil = android.os.SystemClock.elapsedRealtime() + SERVER_REST;
                throw e;
            }
            if (mStopped) {
                callback.error(TextToSpeech.STOPPED);
                return;
            }
            started = true;
            play(audio, eleven ? 22050 : 0, callback);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // Whatever goes wrong here must not take the app (and the wake word) down.
            Log.w(TAG, "voice server: " + e + "; the tablet's other voice speaks");
            if (started) {
                callback.error(TextToSpeech.ERROR_OUTPUT);
            } else if (!mStopped) {
                fallback(text, request, callback);
            }
        }
    }

    /** OpenAI-compatible: POST /v1/audio/speech, the answer a WAV file. */
    private byte[] openAi(String text) throws IOException {
        final String model = Prefs.string(this, Prefs.TTS_MODEL);
        final String voice = Prefs.string(this, Prefs.TTS_VOICE);
        try {
            final JSONObject body = new JSONObject()
                    .put("model", model.isEmpty() ? "tts-1" : model)
                    .put("voice", voice.isEmpty() ? "alloy" : voice)
                    .put("input", text)
                    .put("response_format", "wav");
            final String key = Prefs.string(this, Prefs.TTS_KEY);
            return post(LlmClient.endpoint(Prefs.string(this, Prefs.TTS_URL), "/audio/speech"),
                    body.toString(), key.isEmpty() ? null : "Bearer " + key, null);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** ElevenLabs: raw 16-bit PCM at 22050 Hz, so it can play without a header. */
    private byte[] elevenLabs(String text) throws IOException {
        final String model = Prefs.string(this, Prefs.TTS_MODEL);
        try {
            final JSONObject body = new JSONObject().put("text", text)
                    .put("model_id", model.isEmpty() ? "eleven_multilingual_v2" : model);
            return post("https://api.elevenlabs.io/v1/text-to-speech/"
                            + Prefs.string(this, Prefs.TTS_VOICE) + "?output_format=pcm_22050",
                    body.toString(), null, Prefs.string(this, Prefs.TTS_KEY));
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    private byte[] post(String address, String body, String bearer, String xiKey)
            throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        mConnection = c;
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            if (bearer != null) {
                c.setRequestProperty("Authorization", bearer);
            }
            if (xiKey != null) {
                c.setRequestProperty("xi-api-key", xiKey);
            }
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() / 100 != 2) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            return readAll(c.getInputStream());
        } finally {
            mConnection = null;
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_AUDIO) {
                throw new IOException("the answer is too large for speech");
            }
        }
        return out.toByteArray();
    }

    /**
     * Hands the audio to Android in pieces. A WAV says its own rate and channels; raw PCM
     * (rate given) is taken as 16-bit mono.
     */
    private void play(byte[] audio, int rawRate, SynthesisCallback callback) throws IOException {
        int rate = rawRate;
        int channels = 1;
        int offset = 0;
        int length = audio.length;
        if (rawRate == 0) {
            if (audio.length < 44 || audio[0] != 'R' || audio[1] != 'I' || audio[8] != 'W') {
                throw new IOException("not a WAV (" + audio.length + " bytes: "
                        + new String(audio, 0, Math.min(12, audio.length),
                                StandardCharsets.ISO_8859_1) + ")");
            }
            // Walk the chunks to "fmt " and "data".
            int p = 12;
            while (p + 8 <= audio.length) {
                final String id = new String(audio, p, 4, StandardCharsets.US_ASCII);
                final int size = le32(audio, p + 4);
                if (size < 0) {
                    throw new IOException("a broken WAV");
                }
                if ("fmt ".equals(id)) {
                    if (p + 24 > audio.length) {
                        throw new IOException("a broken WAV");
                    }
                    channels = le16(audio, p + 10);
                    rate = le32(audio, p + 12);
                    if (le16(audio, p + 22) != 16) {
                        throw new IOException("only 16-bit WAV is played");
                    }
                } else if ("data".equals(id)) {
                    offset = p + 8;
                    // Streamed WAVs may say 0 or too much: take what is there.
                    length = size <= 0 || offset + size > audio.length
                            ? audio.length - offset : size;
                    break;
                }
                if (size > audio.length) {
                    break;
                }
                p += 8 + size + (size & 1);
            }
            if (offset == 0 || rate == 0) {
                throw new IOException("a WAV without sound");
            }
        }
        callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, channels);
        final int chunk = callback.getMaxBufferSize();
        for (int i = 0; i < length && !mStopped; i += chunk) {
            callback.audioAvailable(audio, offset + i, Math.min(chunk, length - i));
        }
        callback.done();
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static int le32(byte[] b, int at) {
        return le16(b, at) | le16(b, at + 2) << 16;
    }

    // ---------------------------------------------------------------- the other voice

    /** The tablet's other engine (Google's if there, else any) speaks for us. */
    private void fallback(String text, SynthesisRequest request, SynthesisCallback callback) {
        try {
            if (mFallback == null) {
                final String other = otherEngine();
                if (other == null) {
                    throw new IOException("no other voice");
                }
                mFallback = new TextToSpeech(this, status -> {
                    mFallbackOk = status == TextToSpeech.SUCCESS;
                    mFallbackReady.open();
                }, other);
            }
            if (!mFallbackReady.block(5000) || !mFallbackOk) {
                // Next time a fresh try, not this failed one for good.
                mFallback.shutdown();
                mFallback = null;
                mFallbackReady.close();
                throw new IOException("the other voice does not start");
            }
            mFallback.setLanguage(locale(request.getLanguage(), request.getCountry()));
            // The other voice speaks itself (writing it to a file gives nothing with some
            // engines); this engine waits until it is through, so the caller's timing holds.
            final ConditionVariable done = new ConditionVariable();
            final String id = "f" + System.nanoTime();
            mFallback.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    done.open();
                }

                @Override
                public void onError(String utteranceId) {
                    done.open();
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    done.open();
                }
            });
            final android.os.Bundle params = new android.os.Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    request.getParams().getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f));
            if (mFallback.speak(text, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) {
                throw new IOException("the other voice does not speak");
            }
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1);
            // Waiting, listening for a stop; an engine that never reports back is not
            // waited for longer than the text could take.
            final long until = android.os.SystemClock.elapsedRealtime() + 15_000
                    + text.length() * 150L;
            while (!mStopped && !done.block(200)
                    && android.os.SystemClock.elapsedRealtime() < until) {
                // Waiting.
            }
            callback.done();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "fallback voice: " + e);
            callback.error(TextToSpeech.ERROR_NETWORK);
        }
    }

    /** Requests name languages in three letters ("deu", "DEU"); engines want a real Locale. */
    private static Locale locale(String iso3Language, String iso3Country) {
        Locale languageOnly = null;
        for (Locale l : Locale.getAvailableLocales()) {
            try {
                if (!l.getISO3Language().equalsIgnoreCase(iso3Language)) {
                    continue;
                }
                if (l.getISO3Country().equalsIgnoreCase(iso3Country)) {
                    return l;
                }
                if (languageOnly == null && l.getCountry().isEmpty()) {
                    languageOnly = l;
                }
            } catch (java.util.MissingResourceException ignored) {
            }
        }
        return languageOnly != null ? languageOnly : Locale.getDefault();
    }

    private String otherEngine() {
        final TextToSpeech probe = new TextToSpeech(this, status -> { });
        try {
            String any = null;
            for (TextToSpeech.EngineInfo e : probe.getEngines()) {
                if (getPackageName().equals(e.name)) {
                    continue;
                }
                if ("com.google.android.tts".equals(e.name)) {
                    return e.name;
                }
                if (any == null) {
                    any = e.name;
                }
            }
            return any;
        } finally {
            probe.shutdown();
        }
    }
}
