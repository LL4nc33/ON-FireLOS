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

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Process;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Listening through the speech recognition installed on the tablet - Google's speech
 * services where present, which work without Google's other services.
 */
final class SpeechInput implements RecognitionListener, Listening {

    private static final String TAG = "ONFireAI";
    private static final String GOOGLE = "com.google.android.tts";


    private final Context mContext;
    private final Callback mCallback;
    private SpeechRecognizer mRecognizer;
    private boolean mActive;

    SpeechInput(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
    }

    /** The recognition service to use: Google's first, then any other. */
    static ComponentName service(Context context) {
        final PackageManager pm = context.getPackageManager();
        // Google's recognition comes switched off and only switches itself on once it is
        // set up as the default; as the system's assistant, ON-FireAI switches it on.
        final List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE),
                PackageManager.MATCH_DISABLED_COMPONENTS);
        ComponentName any = null;
        for (ResolveInfo info : services) {
            final ComponentName name = new ComponentName(info.serviceInfo.packageName,
                    info.serviceInfo.name);
            if (GOOGLE.equals(name.getPackageName())) {
                enable(pm, name);
                return name;
            }
            if (any == null && info.serviceInfo.isEnabled()) {
                any = name;
            }
        }
        return any;
    }

    private static void enable(PackageManager pm, ComponentName name) {
        try {
            if (pm.getComponentEnabledSetting(name)
                    != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "enable " + name + ": " + e);
        }
    }

    /** False without a recognition service. Main thread. */
    @Override
    public boolean start(boolean followUp) {
        final ComponentName service = service(mContext);
        if (service == null) {
            return false;
        }
        allowMicrophone(service.getPackageName());
        if (mRecognizer == null) {
            mRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext, service);
            mRecognizer.setRecognitionListener(this);
        }
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Without Google's services the network recognition cannot connect; the
                // downloaded language works on the tablet itself.
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, mContext.getPackageName())
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        700);
        mActive = true;
        mRecognizer.startListening(intent);
        return true;
    }

    /**
     * The recognition service records the voice itself, so it needs the microphone too. The
     * user chose it as the tablet's recognition by installing it for ON-FireAI; as the system's
     * assistant, ON-FireAI hands it the microphone the way Android does for a default app.
     */
    private void allowMicrophone(String pkg) {
        final PackageManager pm = mContext.getPackageManager();
        if (pm.checkPermission(Manifest.permission.RECORD_AUDIO, pkg)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            pm.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO,
                    Process.myUserHandle());
        } catch (RuntimeException e) {
            Log.w(TAG, "microphone for " + pkg + ": " + e);
        }
    }

    @Override
    public boolean active() {
        return mActive;
    }

    @Override
    public void stop() {
        if (mRecognizer != null && mActive) {
            mRecognizer.stopListening();
        }
    }

    @Override
    public void cancel() {
        mActive = false;
        if (mRecognizer != null) {
            mRecognizer.cancel();
        }
    }

    @Override
    public void destroy() {
        cancel();
        if (mRecognizer != null) {
            mRecognizer.destroy();
            mRecognizer = null;
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        mCallback.onReadyForSpeech();
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        mCallback.onLevel(rmsdB);
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onError(int error) {
        Log.i(TAG, "recognition error " + error);
        if (!mActive) {
            return;
        }
        mActive = false;
        mCallback.onError(error);
    }

    @Override
    public void onResults(Bundle results) {
        if (!mActive) {
            return;
        }
        mActive = false;
        mCallback.onFinal(first(results));
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (mActive) {
            final String text = first(partialResults);
            if (!text.isEmpty()) {
                mCallback.onPartial(text);
            }
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    private static String first(Bundle bundle) {
        if (bundle == null) {
            return "";
        }
        final ArrayList<String> list = bundle.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
    }
}
