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
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * The tablet's own music out of the wake word's microphone.
 *
 * The speaker sits a few cm from the microphone: music at half volume reaches it louder than a
 * voice across the room, and the word goes under (measured on my device: 7 of 40 heard). While
 * media plays, ON-FireLOS hands a copy of what the speaker plays (the remote submix, see the
 * frameworks/av patch), and Speex's echo canceller takes it out of the microphone: 34 of 40
 * in the same test.
 *
 * Only while media plays: starting the copy moves the playing tracks once (a gap of a tenth
 * of a second), so it starts with the music and stays on through short pauses.
 */
final class EchoCanceller {

    private static final String TAG = "ONFireAI";
    private static final int RATE = 16000;
    private static final int FRAME = 160;
    private static final int TAIL = 1024;
    /** The microphone is cleaned this much later, so the copy is surely there for it. */
    private static final int HOLD = RATE * 3 / 10;
    /** The reference goes in this much before its echo (the filter's room for the path). */
    private static final int LEAD = RATE * 3 / 100;
    /** How long the microphone and the copy are compared for the delay. */
    private static final int WINDOW = RATE * 4;
    private static final int MAX_LAG = RATE / 2;
    private static final int REF_SIZE = RATE * 12;
    private static final int MIC_SIZE = RATE * 8;
    /** The copy now and then loses a buffer (21 ms, measured on my device): measure often. */
    private static final long LAG_EVERY = 3_000;
    private static final long KEEP_AFTER_MUSIC = 10_000;
    private static final long MUSIC_FOR = 1500;

    private static boolean sLoaded;

    static {
        try {
            System.loadLibrary("onfireai-aec");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "echo canceller: " + e.getMessage());
        }
    }

    private static native long nativeCreate(int frame, int tail, int rate);

    private static native void nativeProcess(long handle, short[] mic, short[] ref, short[] out,
                                             int n);

    private static native void nativeReset(long handle);

    private static native void nativeDestroy(long handle);

    private static native void nativeLag(short[] mic, short[] ref, int n, int maxLag,
                                         float[] result);

    /** The ROM hands the copy (its audio policy patched) and ON-FireAI may take it. */
    static boolean available(Context context) {
        return sLoaded && SystemProperties.getBoolean("persist.audio.submix_tee", false)
                && context.checkSelfPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final AudioManager mAudio;
    // The copy of what plays, as it comes in; mRefPos counts every sample read.
    private final short[] mRef = new short[REF_SIZE];
    private volatile long mRefPos;
    // The microphone as it came, for the delay and for cleaning HOLD later.
    private final short[] mMic = new short[MIC_SIZE];
    private long mMicPos;
    private AudioRecord mRecord;
    private Thread mReader;
    private volatile boolean mReading;
    private long mHandle;
    /** Microphone sample m holds the echo of copy sample m - mLag; unknown until measured. */
    private long mLag = Long.MIN_VALUE;
    private long mLagAt;
    private long mMusicSince;
    private long mMusicLast;
    private long mCheckedAt;
    private boolean mCleaning;
    // For the log: how much quieter the cleaned microphone is.
    private double mInEnergy;
    private double mOutEnergy;

    EchoCanceller(Context context) {
        mAudio = context.getSystemService(AudioManager.class);
    }

    /** Whether the last chunk came out cleaned (the wake word starts over when this changes). */
    boolean cleaning() {
        return mCleaning;
    }

    /**
     * The wake word's thread, every chunk: n samples of the microphone in, n samples out,
     * cleaned while media plays (and HOLD later), else as they came.
     */
    short[] process(short[] pcm, int n) {
        final long now = SystemClock.elapsedRealtime();
        if (now - mCheckedAt > 1000) {
            mCheckedAt = now;
            follow(now);
        }
        for (int i = 0; i < n; i++) {
            mMic[(int) ((mMicPos + i) % MIC_SIZE)] = pcm[i];
        }
        mMicPos += n;
        final boolean was = mCleaning;
        mCleaning = false;
        if (mRecord == null) {
            return pcm;
        }
        if (now - mLagAt > LAG_EVERY && mMicPos > WINDOW && mRefPos > WINDOW + MAX_LAG) {
            mLagAt = now;
            measure();
        }
        // Microphone samples [m, m + n) are due, HOLD after they came; their copy must be in.
        final long m = mMicPos - HOLD - n;
        if (mLag == Long.MIN_VALUE || m < 0 || m - mLag + LEAD + n > mRefPos
                || m - mLag + LEAD < mRefPos - REF_SIZE + RATE) {
            return pcm;
        }
        final short[] mic = new short[n];
        final short[] ref = new short[n];
        final short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            mic[i] = mMic[(int) ((m + i) % MIC_SIZE)];
            ref[i] = mRef[(int) ((m - mLag + LEAD + i) % REF_SIZE)];
        }
        if (!was) {
            nativeReset(mHandle);
        }
        nativeProcess(mHandle, mic, ref, out, n - n % FRAME);
        mCleaning = true;
        for (int i = 0; i < n; i++) {
            mInEnergy += (double) mic[i] * mic[i];
            mOutEnergy += (double) out[i] * out[i];
        }
        return out;
    }

    /** Media playing (not ON-FireAI's own voice): the copy on; quiet for a while: off. */
    private void follow(long now) {
        if (mRecord != null && Log.isLoggable(TAG, Log.DEBUG)) {
            long level = 0;
            for (int i = 1; i <= 1600 && i <= mRefPos; i++) {
                level += Math.abs(mRef[(int) ((mRefPos - i) % REF_SIZE)]);
            }
            Log.d(TAG, "echo: copy level " + level / 1600 + ", copy " + mRefPos + ", microphone "
                    + (mMicPos - mRefStartMic)
                    + ", delay " + (mLag == Long.MIN_VALUE ? "?" : mLag - mRefStartMic)
                    + (mCleaning ? ", cleaning" : ""));
        }
        if (mediaPlaying()) {
            if (mMusicSince == 0) {
                mMusicSince = now;
            }
            mMusicLast = now;
            if (mRecord == null && now - mMusicSince >= MUSIC_FOR) {
                start();
            }
        } else {
            mMusicSince = 0;
            if (mRecord != null && now - mMusicLast > KEEP_AFTER_MUSIC) {
                stop();
            }
        }
    }

    private boolean mediaPlaying() {
        if (!mAudio.isMusicActive()) {
            return false;
        }
        for (AudioPlaybackConfiguration c : mAudio.getActivePlaybackConfigurations()) {
            final int usage = c.getAudioAttributes().getUsage();
            if (c.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
                    && (usage == AudioAttributes.USAGE_MEDIA
                    || usage == AudioAttributes.USAGE_GAME
                    || usage == AudioAttributes.USAGE_UNKNOWN)) {
                return true;
            }
        }
        return false;
    }

    private void start() {
        final AudioRecord r;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, RATE);
        } catch (RuntimeException e) {
            Log.w(TAG, "echo reference: " + e);
            return;
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            Log.w(TAG, "echo reference: not available");
            return;
        }
        r.startRecording();
        mRecord = r;
        mHandle = nativeCreate(FRAME, TAIL, RATE);
        mLag = Long.MIN_VALUE;
        mLagAt = 0;
        mRefPos = 0;
        // The copy counts from now; the microphone has its own count. Their offset comes
        // from the first measurement.
        mRefStartMic = mMicPos;
        mReading = true;
        mReader = new Thread(this::read, "ONFireAI-echo-ref");
        mReader.start();
        Log.i(TAG, "echo reference on");
    }

    /** Where the microphone's count stood when the copy began. */
    private long mRefStartMic;

    private void read() {
        final short[] chunk = new short[FRAME * 4];
        while (mReading) {
            final int n = mRecord.read(chunk, 0, chunk.length);
            if (n <= 0) {
                SystemClock.sleep(20);
                continue;
            }
            final long pos = mRefPos;
            for (int i = 0; i < n; i++) {
                mRef[(int) ((pos + i) % REF_SIZE)] = chunk[i];
            }
            mRefPos = pos + n;
        }
    }

    void stop() {
        if (mRecord == null) {
            return;
        }
        mReading = false;
        try {
            mReader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mRecord.stop();
        mRecord.release();
        mRecord = null;
        nativeDestroy(mHandle);
        mHandle = 0;
        mCleaning = false;
        Log.i(TAG, "echo reference off");
    }

    /**
     * The delay, from the last WINDOW of the microphone and the copy where the last
     * measurement (or, the first time, the start of both counts) puts it.
     */
    private void measure() {
        if (mOutEnergy > 0 && Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "echo: " + Math.round(10 * Math.log10(mInEnergy / mOutEnergy))
                    + " dB taken out");
        }
        mInEnergy = 0;
        mOutEnergy = 0;
        final long guess = mLag != Long.MIN_VALUE ? mLag : mRefStartMic;
        // The newest stretch both have (the copy comes in a little late), with room for the
        // delay to lie either way.
        final long r0 = Math.min(mRefPos, mMicPos - guess) - WINDOW - MAX_LAG;
        final long m0 = r0 + guess;
        if (r0 < Math.max(0, mRefPos - REF_SIZE) || m0 < Math.max(0, mMicPos - MIC_SIZE)) {
            return;
        }
        final short[] mic = new short[WINDOW];
        final short[] ref = new short[WINDOW];
        long energy = 0;
        for (int i = 0; i < WINDOW; i++) {
            mic[i] = mMic[(int) ((m0 + i) % MIC_SIZE)];
            ref[i] = mRef[(int) ((r0 + i) % REF_SIZE)];
            energy += Math.abs(ref[i]);
        }
        // A quiet passage tells nothing.
        if (energy / WINDOW < 30) {
            return;
        }
        final float[] result = new float[2];
        nativeLag(mic, ref, WINDOW, MAX_LAG, result);
        if (result[1] < 6) {
            Log.i(TAG, "echo delay unsure (" + Math.round(result[1]) + ")");
            return;
        }
        final long lag = guess + (long) result[0];
        if (mLag != lag) {
            Log.i(TAG, "echo delay " + (lag - mRefStartMic) + " samples ("
                    + Math.round(result[1]) + ")");
        }
        mLag = lag;
    }
}
