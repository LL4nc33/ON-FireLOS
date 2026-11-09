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
    static final String WAKE = "wake";
    static final String WAKE_WORD = "wake_word";
    static final String WAKE_CHARGING_ONLY = "wake_charging_only";
    static final String WAKE_THRESHOLD = "wake_threshold";
    static final String EMBED_MODEL = "embed_model";
    /** Packages the screen agent never reads, separated by commas. */
    static final String SCREEN_BLOCKED = "screen_blocked";
    /** The voice from a server: "openai" (compatible) or "elevenlabs", and its details. */
    static final String TTS_PROVIDER = "tts_provider";
    static final String TTS_URL = "tts_url";
    static final String TTS_KEY = "tts_key";
    static final String TTS_VOICE = "tts_voice";
    static final String TTS_MODEL = "tts_model";
    /** Home Assistant: its address and a long-lived access token. */
    static final String HA_URL = "ha_url";
    static final String HA_TOKEN = "ha_token";
    /** A news feed of one's own instead of the broadcaster for the region. */
    static final String NEWS_URL = "news_url";
    /** The guided first start was offered once; after that only on request. */
    static final String SETUP_DONE = "setup_done";
    /** When the sleep timer stops what plays (epoch ms); absent when none is set. */
    static final String SLEEP_AT = "sleep_at";
    /** The music volume while the sleep timer fades it out, to restore after a restart. */
    static final String SLEEP_VOLUME = "sleep_volume";
    /** ON-FireAI's screen was made the one shown while charging (once; changeable after). */
    static final String AMBIENT_SET = "ambient_set";

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
