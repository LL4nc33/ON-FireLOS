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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;

import java.io.IOException;

/**
 * Speech to text on the tablet itself, without any server: NVIDIA's German FastConformer
 * (stt_de_fastconformer_hybrid_large_pc, CC-BY-4.0, 115M parameters, int8) through
 * sherpa-onnx. Measured on my device: a 4s sentence in about 1s, with punctuation and
 * capital letters. The model (about 130MB) is loaded when listening starts and let go
 * a few minutes after the last use.
 */
final class LocalSpeech {

    private static final String TAG = "ONFireAI";
    private static final String DIR = "asr/";
    private static final long KEEP = 5 * 60_000;

    private static final Object LOCK = new Object();
    private static volatile OfflineRecognizer sRecognizer;
    private static Boolean sAvailable;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable RELEASE = () -> new Thread(() -> {
        synchronized (LOCK) {
            if (sRecognizer != null) {
                sRecognizer.release();
                sRecognizer = null;
                Log.i(TAG, "speech model let go");
            }
        }
    }, "ONFireAI-asr-release").start();

    private LocalSpeech() {
    }

    /** The model is in this build, and the library to run it. */
    static boolean available(Context context) {
        if (sAvailable == null) {
            boolean model;
            try {
                context.getAssets().openFd(DIR + "encoder.onnx").close();
                model = true;
            } catch (IOException e) {
                model = false;
            }
            sAvailable = model && WakeWord.available();
        }
        return sAvailable;
    }

    /** Loaded and free to use now. */
    static boolean ready() {
        return sRecognizer != null;
    }

    /** Loads the model in the background, so it is ready when the speaker pauses. */
    static void warmUp(Context context) {
        final Context app = context.getApplicationContext();
        MAIN.removeCallbacks(RELEASE);
        new Thread(() -> {
            try {
                recognizer(app);
            } catch (RuntimeException e) {
                Log.w(TAG, "speech model", e);
            }
        }, "ONFireAI-asr-load").start();
    }

    private static OfflineRecognizer recognizer(Context context) {
        synchronized (LOCK) {
            if (sRecognizer == null) {
                final long start = SystemClock.elapsedRealtime();
                final OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                        .setOfflineModelConfig(OfflineModelConfig.builder()
                                .setTransducer(OfflineTransducerModelConfig.builder()
                                        .setEncoder(DIR + "encoder.onnx")
                                        .setDecoder(DIR + "decoder.onnx")
                                        .setJoiner(DIR + "joiner.onnx")
                                        .build())
                                .setTokens(DIR + "tokens.txt")
                                .setModelType("nemo_transducer")
                                .setNumThreads(4)
                                .build())
                        .setDecodingMethod("greedy_search")
                        .build();
                sRecognizer = new OfflineRecognizer(context.getAssets(), config);
                Log.i(TAG, "speech model loaded in "
                        + (SystemClock.elapsedRealtime() - start) + " ms");
            }
            return sRecognizer;
        }
    }

    /** 16-bit mono PCM at 16kHz to text; one at a time (a live transcript waits its turn). */
    static String transcribe(Context context, byte[] pcm, int offset) throws IOException {
        final int n = (pcm.length - offset) / 2;
        final float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            samples[i] = (short) ((pcm[offset + 2 * i] & 0xff)
                    | (pcm[offset + 2 * i + 1] << 8)) / 32768f;
        }
        MAIN.removeCallbacks(RELEASE);
        try {
            synchronized (LOCK) {
                final OfflineRecognizer recognizer = recognizer(context.getApplicationContext());
                final OfflineStream stream = recognizer.createStream();
                try {
                    stream.acceptWaveform(samples, 16000);
                    recognizer.decode(stream);
                    return recognizer.getResult(stream).getText().trim();
                } finally {
                    stream.release();
                }
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            throw new IOException("speech on the tablet: " + e.getMessage());
        } finally {
            MAIN.postDelayed(RELEASE, KEEP);
        }
    }
}
