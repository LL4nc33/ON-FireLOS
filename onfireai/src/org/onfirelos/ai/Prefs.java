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
import android.content.SharedPreferences;

/**
 * The assistant's settings. Server addresses and keys live only here, in the app's private
 * storage on the tablet - never in the ROM.
 */
final class Prefs {

    static final String URL = "url";
    static final String API_KEY = "api_key";
    static final String MODEL = "model";
    static final String SPEAK = "speak";
    static final String FOLLOW_UP = "follow_up";
    static final String LISTEN_NOW = "listen_now";
    static final String SEARCH_URL = "search_url";
    static final String LEARN = "learn";
    static final String KEEP_LOG = "keep_log";
    static final String ASSISTANT_SET = "assistant_set";
    /** "auto" (a Whisper server when one is set, else Android's), "google" or "server". */
    static final String STT_MODE = "stt_mode";
    static final String STT_URL = "stt_url";
    static final String STT_MODEL = "stt_model";
    static final String STT_KEY = "stt_key";
    static final String SCREEN = "screen";
    static final String NOTIFICATIONS = "notifications";
    static final String EMBED_URL = "embed_url";
    static final String EMBED_MODEL = "embed_model";
    /** Packages the screen agent never reads, separated by commas. */
    static final String SCREEN_BLOCKED = "screen_blocked";

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("onfireai", Context.MODE_PRIVATE);
    }

    static String string(Context context, String key) {
        return get(context).getString(key, "").trim();
    }

    static boolean flag(Context context, String key) {
        // Every switch is on until the user turns it off.
        return get(context).getBoolean(key, true);
    }

    static void put(Context context, String key, String value) {
        get(context).edit().putString(key, value == null ? "" : value.trim()).apply();
    }

    static void put(Context context, String key, boolean value) {
        get(context).edit().putBoolean(key, value).apply();
    }

    static boolean configured(Context context) {
        return !string(context, URL).isEmpty();
    }
}
