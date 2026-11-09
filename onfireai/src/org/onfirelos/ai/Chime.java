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

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * The sound when the wake word was heard: two soft rising notes, made on the spot (a sine
 * with a little warmth, a quick attack and a gentle fade) instead of a beep.
 */
final class Chime {

    private static final int RATE = 44100;

    private Chime() {
    }

    static void play() {
        new Thread(Chime::render, "ONFireAI-chime").start();
    }

    private static void render() {
        final float[][] notes = { { 1046.5f, 0.11f }, { 1568.0f, 0.24f } };
        int total = 0;
        for (float[] n : notes) {
            total += Math.round(n[1] * RATE);
        }
        final short[] pcm = new short[total];
        int at = 0;
        for (float[] n : notes) {
            final int length = Math.round(n[1] * RATE);
            for (int i = 0; i < length; i++) {
                final double t = (double) i / RATE;
                final double attack = Math.min(1.0, t / 0.006);
                final double fade = Math.exp(-t * 9.0);
                final double wave = Math.sin(2 * Math.PI * n[0] * t)
                        + 0.18 * Math.sin(4 * Math.PI * n[0] * t);
                pcm[at + i] = (short) (wave * attack * fade * 0.22 * Short.MAX_VALUE);
            }
            at += length;
        }
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            Thread.sleep(pcm.length * 1000L / RATE + 120);
        } catch (RuntimeException | InterruptedException e) {
            Log.w("ONFireAI", "chime: " + e);
        } finally {
            if (track != null) {
                track.release();
            }
        }
    }
}
