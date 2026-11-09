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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * On each start and update: the wake word and the screen agent as the user set them. Once,
 * on the first start: ON-FireAI becomes the assistant (long press on home) if no other is
 * chosen; later choices of the user stay as they are.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        WakeWord.apply(context);
        // An update of the app takes the screen agent off Android's list; what the user
        // turned on stays on. Only then: turned off on Android's own page, it stays off.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                && Prefs.get(context).getBoolean(Prefs.SCREEN, false)
                && !ScreenAgent.enabled(context)) {
            ScreenAgent.setEnabled(context, true);
        }
        if (Prefs.get(context).getBoolean(Prefs.ASSISTANT_SET, false)) {
            return;
        }
        final String assistant = Settings.Secure.getString(context.getContentResolver(),
                "assistant");
        final String voice = Settings.Secure.getString(context.getContentResolver(),
                "voice_interaction_service");
        if (TextUtils.isEmpty(assistant) && TextUtils.isEmpty(voice)) {
            SettingsActivity.setAssistant(context, true);
        } else {
            Prefs.put(context, Prefs.ASSISTANT_SET, true);
        }
    }
}
