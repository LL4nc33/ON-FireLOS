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
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;

/**
 * The system voice, a sentence at a time: the first sentence plays while the model still
 * writes the next. Tells when everything queued has been said.
 */
final class Speaker {

    interface Callback {
        /** Everything of the answer has been said (or there was nothing to say). */
        void onSpoken();
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Callback mCallback;
    private final List<String> mWaiting = new ArrayList<>();
    private TextToSpeech mTts;
    private boolean mReady;
    private boolean mFailed;
    private int mOutstanding;
    private int mCount;
    private boolean mAnswerDone;

    Speaker(Context context, Callback callback) {
        mCallback = callback;
        mTts = new TextToSpeech(context.getApplicationContext(), status -> mMain.post(() -> {
            mReady = status == TextToSpeech.SUCCESS;
            mFailed = !mReady;
            if (mReady) {
                mTts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        mMain.post(Speaker.this::finishedOne);
                    }

                    @Override
                    public void onError(String id) {
                        mMain.post(Speaker.this::finishedOne);
                    }

                    @Override
                    public void onStop(String id, boolean interrupted) {
                        mMain.post(Speaker.this::finishedOne);
                    }
                });
            }
            final List<String> waiting = new ArrayList<>(mWaiting);
            mWaiting.clear();
            mOutstanding -= waiting.size();
            for (String text : waiting) {
                say(text);
            }
            checkDone();
        }));
    }

    /** Main thread. A new answer begins. */
    void begin() {
        mAnswerDone = false;
    }

    /** Main thread. */
    void say(String text) {
        if (mFailed || text == null || text.trim().isEmpty()) {
            return;
        }
        mOutstanding++;
        if (!mReady) {
            mWaiting.add(text);
            return;
        }
        if (mTts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "s" + (mCount++))
                != TextToSpeech.SUCCESS) {
            mOutstanding--;
        }
    }

    /** Main thread. Nothing more will come for this answer. */
    void end() {
        mAnswerDone = true;
        checkDone();
    }

    boolean speaking() {
        return mOutstanding > 0;
    }

    /** Main thread. Silence now; does not report the answer as spoken. */
    void stop() {
        mAnswerDone = false;
        mWaiting.clear();
        mOutstanding = 0;
        if (mReady) {
            mTts.stop();
        }
    }

    void shutdown() {
        stop();
        mTts.shutdown();
    }

    private void finishedOne() {
        if (mOutstanding > 0) {
            mOutstanding--;
        }
        checkDone();
    }

    private void checkDone() {
        if (mAnswerDone && mOutstanding == 0 && (mReady || mFailed)) {
            mAnswerDone = false;
            mCallback.onSpoken();
        }
    }
}
