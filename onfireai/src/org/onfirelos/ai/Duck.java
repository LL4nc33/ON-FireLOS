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
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * While ON-FireAI listens and answers, whatever else plays goes quiet, and comes back as
 * loud as before once it is done.
 *
 * Android does the turning down for every player that holds the audio focus (the browser's
 * videos, music apps); ON-FireAI's own radio and sleep sounds turn themselves down, since
 * Android leaves an app's own players alone.
 */
final class Duck {

    /** Back up only after a short moment: a follow-up question right after keeps it down. */
    private static final long BACK_UP_AFTER = 1200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable UP = () -> apply(false);
    private static Context sContext;
    private static AudioFocusRequest sFocus;
    private static boolean sDown;

    private Duck() {
    }

    /**
     * Any thread. Down for at most {@code hold} ms (should whoever asked never say it is
     * done), or up again shortly.
     */
    static void set(Context context, boolean down, long hold) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            sContext = app;
            MAIN.removeCallbacks(UP);
            if (down) {
                apply(true);
                MAIN.postDelayed(UP, hold);
            } else if (sDown) {
                MAIN.postDelayed(UP, BACK_UP_AFTER);
            }
        });
    }

    /** Main thread. Up at once: something of ON-FireAI's own starts to play. */
    static void upNow() {
        MAIN.removeCallbacks(UP);
        apply(false);
    }

    private static void apply(boolean down) {
        if (down == sDown || sContext == null) {
            return;
        }
        sDown = down;
        final AudioManager audio = sContext.getSystemService(AudioManager.class);
        if (down) {
            if (sFocus == null) {
                sFocus = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .build();
            }
            audio.requestAudioFocus(sFocus);
        } else if (sFocus != null) {
            audio.abandonAudioFocusRequest(sFocus);
        }
    }
}
